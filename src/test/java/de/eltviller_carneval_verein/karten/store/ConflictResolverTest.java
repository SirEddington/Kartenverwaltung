package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Die Auflösung verlorener eigener Strecken im Versionsspeicher: Eingaben der Strategie, Reihenfolge des Schreibens,
 * Wiederholbarkeit, Wartebedingung und Rückmeldungen.
 */
class ConflictResolverTest {

	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "A-1");
	private static final UnitKey OTHER_SEAT = UnitKey.booking("e1", "p1", "A-2");
	private static final UnitKey OTHER_SHOW_SEAT = UnitKey.booking("e1", "p2", "A-1");
	private static final UnitKey MOVED = UnitKey.unassignedBooking("e1", "p1", "X");
	private static final UnitKey SHOW = UnitKey.presentation("e1", "p1");
	private static final String FOREIGN = "0123456789abcdefghijklmnop";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Zeichnet die Eingaben auf und gibt den Inhalt von "Meine" unter {@code "seen"} zurück. */
	private static final class RecordingPolicy implements ConflictPolicy {
		final List<ConflictInput> inputs = new ArrayList<>();
		List<Change> extra = List.of();
		boolean delete;
		boolean withoutPayload;
		/** Ob die Zusammenführung einen Vermerk bekommt. */
		boolean noted;
		RuntimeException failure;

		@Override
		public Resolution resolve(ConflictInput input) {
			inputs.add(input);
			if (failure != null) {
				throw failure;
			}
			ObjectNode payload = MAPPER.createObjectNode();
			payload.set("seen", input.mine().payload().deepCopy());
			ConflictNote note = null;
			if (noted) {
				Version theirs = input.theirs();
				note = new ConflictNote(ConflictNote.Kind.UNMERGED, "test",
						new ConflictNote.Party(theirs.machine(), theirs.machineName(), theirs.operator(), theirs.id()),
						List.of(), null, null, input.mine().id());
			}
			return new Resolution(withoutPayload ? null : payload, delete, extra, note);
		}
	}

	/** Zeichnet die Rückmeldungen eines Dienstes auf. */
	private static final class Recorder implements SyncListener {
		final List<Set<UnitKey>> changed = new ArrayList<>();
		final List<String> errors = new ArrayList<>();
		final List<LostVersion> lost = new ArrayList<>();
		/** Die Reihenfolge der Meldungen: {@code "units"} und {@code "conflicts"}. */
		final List<String> sequence = new ArrayList<>();
		int conflicts;

		@Override
		public void lost(UnitKey unit, List<VersionId> mine, VersionId winner) {
			lost.add(new LostVersion(unit, mine, winner));
		}

		@Override
		public void unitsChanged(Set<UnitKey> units) {
			changed.add(units);
			sequence.add("units");
		}

		@Override
		public void error(String message) {
			errors.add(message);
		}

		@Override
		public void conflictsChanged() {
			conflicts++;
			sequence.add("conflicts");
		}

		Set<UnitKey> changedUnits() {
			Set<UnitKey> all = new HashSet<>();
			changed.forEach(all::addAll);
			return all;
		}
	}

	/** Eine verlorene Strecke: eigene Basis, fremder Gewinner darauf, eigene Version ebenfalls darauf. */
	private record Loss(Version base, Version theirs, Version mine) {
	}

	@TempDir
	Path dir;

	private final TestClock clock = new TestClock();
	private final List<LogRecord> logs = new ArrayList<>();
	private Logger logger;
	private Handler capture;
	private boolean parentHandlers;
	private int foreignCount;

	@BeforeEach
	void captureLog() {
		// Warnungen und Fehler der Auflösung werden aufgefangen: Sie belegen die Wege und halten die Ausgabe sauber
		logger = Logger.getLogger(SyncService.class.getName());
		capture = new Handler() {
			@Override
			public void publish(LogRecord record) {
				synchronized (logs) {
					logs.add(record);
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		parentHandlers = logger.getUseParentHandlers();
		logger.addHandler(capture);
		logger.setUseParentHandlers(false);
	}

	@AfterEach
	void releaseLog() {
		logger.removeHandler(capture);
		logger.setUseParentHandlers(parentHandlers);
	}

	// --- Hilfen

	private static ObjectNode name(String text) {
		return MAPPER.createObjectNode().put("name", text);
	}

	private static List<VersionId> ids(List<Version> versions) {
		return versions.stream().map(Version::id).toList();
	}

	private static Version last(List<Version> versions) {
		return versions.get(versions.size() - 1);
	}

	/** Ein gestarteter Dienst im Einzelbetrieb. */
	private SyncService single(Path appData) {
		return single(appData, new NioFileLayer());
	}

	private SyncService single(Path appData, FileLayer files) {
		SyncService service = TestSyncServices.singleMode(appData, files, clock);
		service.startup(null);
		return service;
	}

	private static Version own(SyncService service, UnitKey unit, List<VersionId> parents, ObjectNode payload)
			throws IOException {
		return service.append(UUID.randomUUID().toString(), List.of(new Change(unit, parents, payload, false))).get(0);
	}

	/** Schreibt eine Version eines anderen Rechners in den lokalen Bestand, beansprucht sie lokal und bringt sie in den Index. */
	private Version foreign(SyncService service, Path appData, UnitKey unit, List<VersionId> parents, ObjectNode payload,
			boolean deleted, Map<String, Long> seen) throws IOException {
		foreignCount++;
		Version version = new Version(StoreCodec.FORMAT, VersionId.create(1_900_000_000_000L + foreignCount, FOREIGN, foreignCount),
				parents, FOREIGN, "Kasse 2", "Bert", Instant.parse("2026-10-28T09:00:00Z"), "fremd-" + foreignCount, deleted,
				deleted ? MAPPER.createObjectNode() : payload, null, seen);
		byte[] bytes = StoreCodec.write(version);
		VersionStore store = new VersionStore(appData.resolve("data"), new NioFileLayer());
		assertTrue(store.writeVersion(unit, version.id(), bytes));
		new LocalArbiter(store, Clock.systemUTC()).claim(unit, version, bytes);
		service.unitState(unit);
		return version;
	}

	/** Eigene Basis, fremde Version darauf, dann eigene Version ebenfalls auf der Basis: Sie verliert. */
	private Loss loss(SyncService service, Path appData, UnitKey unit) throws IOException {
		Version base = own(service, unit, List.of(), name("Anna"));
		Version theirs = foreign(service, appData, unit, List.of(base.id()), name("Bert"), false, Map.of());
		Version mine = own(service, unit, List.of(base.id()), name("Carla"));
		assertEquals(List.of(List.of(mine.id())), service.unitState(unit).lost());
		return new Loss(base, theirs, mine);
	}

	/** Die Versionsdateien im Ordner der Einheit (ohne Ansprüche). */
	private static long versionFiles(Path appData, UnitKey unit) throws IOException {
		try (Stream<Path> files = Files.list(appData.resolve("data").resolve(unit.relativePath()))) {
			return files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".json")).count();
		}
	}

	private List<LogRecord> logsAtLeast(Level level) {
		synchronized (logs) {
			return logs.stream().filter(r -> r.getLevel().intValue() >= level.intValue()).toList();
		}
	}

	// --- Zurückstellen

	/**
	 * Eine Einheit, die der Dienst zurückstellt (halb abgeglichene Vorstellung), bleibt eingereiht, ohne dass die Auflösung
	 * sie liest; sobald sie nicht mehr zurückgestellt ist, kommt sie dran.
	 */
	@Test
	void deferredUnitStaysQueuedWithoutBeingRead() {
		AtomicBoolean deferred = new AtomicBoolean(true);
		List<UnitKey> read = new ArrayList<>();
		ConflictResolver.Host host = new ConflictResolver.Host() {
			@Override
			public SyncService.Resolved resolve(UnitKey unit) {
				read.add(unit);
				// "wartend": Die Auflösung gibt die Einheit ab, ohne weiter zu lesen
				return new SyncService.Resolved(null,
						new UnitState(unit, UnitState.Status.WAITING, List.of(), List.of(), List.of(), null, Set.of()));
			}

			@Override
			public boolean shared() {
				return true;
			}

			@Override
			public boolean isListed(UnitKey unit, VersionId version) {
				return true;
			}

			@Override
			public String machineId() {
				return "me";
			}

			@Override
			public Ownership ownership() {
				return new Ownership("me", List.of());
			}

			@Override
			public Map<UnitKey, ConflictInput.Sibling> siblings(UnitKey booking) {
				return Map.of();
			}

			@Override
			public Set<UnitKey> withOwnVersionsBesideChain(Collection<UnitKey> units) {
				return Set.of();
			}

			@Override
			public List<Version> append(String saveId, List<Change> changes) {
				throw new AssertionError("nichts zu schreiben");
			}

			@Override
			public String failureText(Exception failure) {
				return failure.toString();
			}

			@Override
			public boolean deferred(UnitKey unit) {
				return deferred.get();
			}
		};
		ConflictResolver resolver = new ConflictResolver(host, new SyncNotifier(Runnable::run), clock);
		resolver.enqueue(BOOKING);

		resolver.resolvePending(10);
		resolver.resolvePending(10);

		assertEquals(List.of(), read, "eine zurückgestellte Einheit wird nicht gelesen");

		deferred.set(false);
		resolver.resolvePending(10);

		assertEquals(List.of(BOOKING), read, "sie blieb eingereiht und kommt jetzt dran");
	}

	// --- Zusammenführen

	@Test
	void lostVersionIsMergedWithTheirsFirstAndMineSecond() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		RecordingPolicy policy = new RecordingPolicy();
		service.setConflictPolicy(UnitType.BOOKING, policy);
		Loss loss = loss(service, appData, BOOKING);

		service.tick();

		UnitState state = service.unitState(BOOKING);
		assertTrue(state.lost().isEmpty());
		Version merge = state.head();
		assertEquals(List.of(loss.theirs().id(), loss.mine().id()), merge.parents());
		assertEquals(name("Carla"), merge.payload().get("seen"));
		assertNull(merge.conflict());
		assertEquals(1, policy.inputs.size());
	}

	@Test
	void inputCarriesBaseMineTheirsAndChain() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		RecordingPolicy policy = new RecordingPolicy();
		service.setConflictPolicy(UnitType.BOOKING, policy);
		own(service, OTHER_SEAT, List.of(), name("Dora"));
		own(service, OTHER_SHOW_SEAT, List.of(), name("Emil"));
		Loss loss = loss(service, appData, BOOKING);

		service.tick();

		assertEquals(1, policy.inputs.size());
		ConflictInput input = policy.inputs.get(0);
		assertEquals(BOOKING, input.unit());
		assertEquals(loss.base().id(), input.base().id());
		assertEquals(loss.mine().id(), input.mine().id());
		assertEquals(loss.theirs().id(), input.theirs().id());
		assertEquals(List.of(loss.theirs().id()), ids(input.chainSinceBase()));
		assertEquals(loss.mine().machine(), input.ownMachineId());
		assertEquals(Set.of(OTHER_SEAT), input.siblings().keySet(), "nur die anderen Buchungen derselben Vorstellung");
		ConflictInput.Sibling sibling = input.siblings().get(OTHER_SEAT);
		assertEquals(name("Dora"), sibling.snapshot().payload());
		assertEquals(sibling.snapshot().version(), sibling.head().version());

		// die Inhalte der Eingabe sind Kopien: Was die Strategie ändert, erreicht weder Index noch Bestand
		input.mine().payload().put("name", "verändert");
		input.theirs().payload().put("name", "verändert");
		sibling.snapshot().payload().put("name", "verändert");
		assertEquals(name("Dora"), service.snapshots(List.of(OTHER_SEAT)).get(OTHER_SEAT).payload());
		assertEquals(name("Bert"), service.unitState(BOOKING).chain().get(1).payload());

		// eine Strecke auf der zusammengeführten eigenen Version: Die Basis ist diese Version, die Kette seit der Basis
		// beginnt mit der Zusammenführung, die sie enthält
		Version merge = service.unitState(BOOKING).head();
		Version again = own(service, BOOKING, List.of(loss.mine().id()), name("Carla 2"));
		assertEquals(List.of(List.of(again.id())), service.unitState(BOOKING).lost());

		service.tick();

		assertEquals(2, policy.inputs.size());
		ConflictInput second = policy.inputs.get(1);
		assertEquals(loss.mine().id(), second.base().id(), "Basis: die zusammengeführte eigene Version");
		assertEquals(again.id(), second.mine().id());
		assertEquals(merge.id(), second.theirs().id());
		assertEquals(List.of(merge.id()), ids(second.chainSinceBase()));
		UnitState state = service.unitState(BOOKING);
		assertTrue(state.lost().isEmpty());
		assertEquals(List.of(merge.id(), again.id()), state.head().parents());
	}

	@Test
	void siblingsWithTheBookingOfTheBaseCarryAllTheirSaveIds() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		RecordingPolicy policy = new RecordingPolicy();
		service.setConflictPolicy(UnitType.BOOKING, policy);
		ObjectNode booking = MAPPER.createObjectNode().put("id", "X").put("name", "Anna");
		// die Buchung X lebt auch auf A-2: dorthin verschoben in einem Speichervorgang, später in einem anderen geändert
		Version moved = service.append("save-move",
				List.of(new Change(OTHER_SEAT, List.of(), booking.deepCopy().put("name", "Anna auf A-2"), false))).get(0);
		service.append("save-later",
				List.of(new Change(OTHER_SEAT, List.of(moved.id()), booking.deepCopy().put("name", "Anna, Rollstuhl"), false)));
		UnitKey otherBooking = UnitKey.booking("e1", "p1", "A-3");
		service.append("save-other",
				List.of(new Change(otherBooking, List.of(), MAPPER.createObjectNode().put("id", "Y"), false)));
		Version base = own(service, BOOKING, List.of(), booking);
		foreign(service, appData, BOOKING, List.of(base.id()), name("Bert"), false, Map.of());
		own(service, BOOKING, List.of(base.id()), name("Carla"));

		service.tick();

		ConflictInput input = policy.inputs.get(0);
		assertEquals(Set.of("save-move", "save-later"), input.siblings().get(OTHER_SEAT).saveIds());
		assertEquals(Set.of(), input.siblings().get(otherBooking).saveIds(), "eine andere Buchung: kein Dateizugriff");
	}

	@Test
	void siblingWithTheLostTargetOfMyMoveCarriesIt() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		RecordingPolicy policy = new RecordingPolicy();
		service.setConflictPolicy(UnitType.BOOKING, policy);
		ObjectNode booking = MAPPER.createObjectNode().put("id", "X").put("name", "Anna");
		Version base = own(service, BOOKING, List.of(), booking);
		foreign(service, appData, BOOKING, List.of(base.id()), booking.deepCopy().put("name", "Anna bezahlt"), false,
				Map.of());
		// auf A-2 verkauft ein anderer Rechner zuerst T; danach zieht dieser Rechner X in einem Speichervorgang dorthin um
		foreign(service, appData, OTHER_SEAT, List.of(), MAPPER.createObjectNode().put("id", "T"), false, Map.of());
		List<Version> move = service.append("save-move", List.of(new Change(BOOKING, List.of(base.id()), null, true),
				new Change(OTHER_SEAT, List.of(), booking.deepCopy().put("name", "Anna am Fenster"), false)));
		UnitKey otherBooking = UnitKey.booking("e1", "p1", "A-3");
		service.append("save-move", List.of(new Change(otherBooking, List.of(), MAPPER.createObjectNode().put("id", "Y"),
				false)));
		assertEquals(1, service.unitState(BOOKING).lost().size());
		assertEquals(1, service.unitState(OTHER_SEAT).lost().size());

		service.tick();

		ConflictInput input = policy.inputs.stream().filter(i -> i.unit().equals(BOOKING)).findFirst().orElseThrow();
		ConflictInput.Sibling target = input.siblings().get(OTHER_SEAT);
		assertEquals(move.get(1).id(), target.lostMove().id(), "das verlorene Ziel des Umzugs");
		assertEquals("Anna am Fenster", target.lostMove().payload().get("name").asText());
		assertNull(input.siblings().get(otherBooking).lostMove(),
				"eine Einheit ohne eigene Versionen neben ihrer Kette: kein Dateizugriff, kein Ziel");
		// ohne Löschung als "Meine" kein Ziel
		ConflictInput seatInput = policy.inputs.stream().filter(i -> i.unit().equals(OTHER_SEAT)).findFirst().orElseThrow();
		assertNull(seatInput.siblings().get(BOOKING).lostMove());
	}

	/**
	 * R35: Die Eingabe trägt die Speichervorgänge der ganzen verlorenen Strecke, und die Einheit {@code _<ID>} der Buchung
	 * von "Meine" ihre Löschungen mit ihrem Stand: ohne Zusammenführungen und ohne die, die schon vor der Strecke dort
	 * lagen.
	 */
	@Test
	void stretchCarriesAllItsSavesAndMyUnassignedUnitItsDeletions() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		RecordingPolicy policy = new RecordingPolicy();
		service.setConflictPolicy(UnitType.BOOKING, policy);
		ObjectNode booking = MAPPER.createObjectNode().put("id", "X").put("name", "Anna");
		// X stand schon früher ohne Platz, wurde von dort gelöscht und wieder angelegt: eine ältere Löschung
		Version first = service.append("save-first", List.of(new Change(MOVED, List.of(), booking.deepCopy(), false)))
				.get(0);
		Version earlier = service.append("save-earlier", List.of(new Change(MOVED, List.of(first.id()), null, true)))
				.get(0);
		Version base = service.append("save-0",
				List.of(new Change(MOVED, List.of(earlier.id()), booking.deepCopy(), false))).get(0);
		// auf A-1 verkauft ein anderer Rechner zuerst T; dieser Rechner weist X dorthin zu und ändert X danach dort noch
		foreign(service, appData, BOOKING, List.of(), MAPPER.createObjectNode().put("id", "T"), false, Map.of());
		List<Version> assign = service.append("save-a", List.of(new Change(MOVED, List.of(base.id()), null, true),
				new Change(BOOKING, List.of(), booking.deepCopy().put("name", "Anna am Fenster"), false)));
		service.append("save-c", List.of(new Change(BOOKING, List.of(assign.get(1).id()),
				booking.deepCopy().put("name", "Anna, Rollstuhl"), false)));
		// ein anderer Rechner hat X ohne Platz ebenfalls gelöscht und dort verloren, und X nach A-2 gesetzt
		Version theirDeletion = foreign(service, appData, MOVED, List.of(base.id()), null, true, Map.of());
		Version theirCopy = foreign(service, appData, OTHER_SEAT, List.of(), booking.deepCopy(), false, Map.of());
		assertEquals(1, service.unitState(BOOKING).lost().size());
		assertEquals(assign.get(0).id(), service.unitState(MOVED).head().id(), "die eigene Löschung gilt");

		service.tick();

		ConflictInput input = policy.inputs.stream().filter(i -> i.unit().equals(BOOKING)).findFirst().orElseThrow();
		assertEquals(Set.of("save-a", "save-c"), input.mineSaveIds());
		assertEquals(Set.of(theirCopy.saveId()), input.siblings().get(OTHER_SEAT).saveIds(),
				"trägt die Buchung von Meine (die Strecke hat keine Basis)");
		assertEquals(Set.of(new ConflictInput.Deletion("save-a", ConflictInput.Standing.CHAIN),
				new ConflictInput.Deletion(theirDeletion.saveId(), ConflictInput.Standing.BESIDE)),
				Set.copyOf(input.siblings().get(MOVED).deletions()), "ohne die ältere Löschung");
	}

	/**
	 * N3: Das verlorene Ziel eines Umzugs ist nie eine Version der gültigen Kette dieser Einheit, auch wenn sie aus dem
	 * Speichervorgang der Löschung stammt und die Einheit eigene Versionen außerhalb der Kette hat.
	 */
	@Test
	void lostMoveIsNeverAVersionOfTheValidChain() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		RecordingPolicy policy = new RecordingPolicy();
		service.setConflictPolicy(UnitType.BOOKING, policy);
		ObjectNode booking = MAPPER.createObjectNode().put("id", "X").put("name", "Anna");
		Version base = own(service, MOVED, List.of(), booking);
		foreign(service, appData, MOVED, List.of(base.id()), booking.deepCopy().put("name", "Anna bezahlt"), false,
				Map.of());
		// dieser Rechner zieht X nach A-2 um; dort gilt das Ziel, ein anderer Rechner setzt danach Y darauf
		List<Version> move = service.append("save-move", List.of(new Change(MOVED, List.of(base.id()), null, true),
				new Change(OTHER_SEAT, List.of(), booking.deepCopy().put("name", "Anna am Fenster"), false)));
		foreign(service, appData, OTHER_SEAT, List.of(move.get(1).id()), MAPPER.createObjectNode().put("id", "Y"), false,
				Map.of());
		// eine eigene spätere Änderung auf A-2 verliert: A-2 hat eigene Versionen außerhalb der Kette
		own(service, OTHER_SEAT, List.of(move.get(1).id()), booking.deepCopy().put("name", "Anna, Rollstuhl"));
		assertEquals(1, service.unitState(MOVED).lost().size());
		assertEquals(1, service.unitState(OTHER_SEAT).lost().size());

		service.tick();

		ConflictInput input = policy.inputs.stream().filter(i -> i.unit().equals(MOVED)).findFirst().orElseThrow();
		assertNull(input.siblings().get(OTHER_SEAT).lostMove(), "das Ziel gilt dort, es ist kein verlorenes");
	}

	@Test
	void twoRootVersionsHaveNullBase() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		RecordingPolicy policy = new RecordingPolicy();
		service.setConflictPolicy(UnitType.BOOKING, policy);
		Version theirs = foreign(service, appData, BOOKING, List.of(), name("Bert"), false, Map.of());
		Version mine = own(service, BOOKING, List.of(), name("Carla"));
		assertEquals(List.of(List.of(mine.id())), service.unitState(BOOKING).lost());

		service.tick();

		ConflictInput input = policy.inputs.get(0);
		assertNull(input.base());
		assertEquals(mine.id(), input.mine().id());
		assertEquals(theirs.id(), input.theirs().id());
		assertEquals(List.of(theirs.id()), ids(input.chainSinceBase()));
		assertEquals(List.of(theirs.id(), mine.id()), service.unitState(BOOKING).head().parents());
	}

	@Test
	void withoutPolicyTheirsStaysWithAnUnmergedNote() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Loss loss = loss(service, appData, BOOKING);

		service.tick();

		Version merge = service.unitState(BOOKING).head();
		assertEquals(List.of(loss.theirs().id(), loss.mine().id()), merge.parents());
		assertEquals(name("Bert"), merge.payload());
		ConflictNote note = merge.conflict();
		assertEquals(ConflictNote.Kind.UNMERGED, note.kind());
		assertEquals(loss.mine().machine(), note.affected());
		assertEquals(new ConflictNote.Party(FOREIGN, "Kasse 2", "Bert", loss.theirs().id()), note.other());
		assertEquals(loss.mine().id(), note.dropped());
	}

	// --- Löschen gewinnt: seen der gewinnenden Löschung

	@Test
	void deleteWinsMergeCarriesSeenOfTheirDeletion() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		RecordingPolicy policy = new RecordingPolicy();
		policy.delete = true;
		service.setConflictPolicy(UnitType.PRESENTATION, policy);
		Version base = own(service, SHOW, List.of(), name("Sitzung"));
		Version theirs = foreign(service, appData, SHOW, List.of(base.id()), null, true, Map.of("m1", 7L));
		Version mine = own(service, SHOW, List.of(base.id()), name("Sitzung 2"));

		service.tick();

		Version merge = service.unitState(SHOW).head();
		assertEquals(List.of(theirs.id(), mine.id()), merge.parents());
		assertTrue(merge.deleted());
		assertEquals(Map.of("m1", 7L), merge.seen());
	}

	@Test
	void deleteWinsMergeCarriesSeenOfMyDeletion() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		RecordingPolicy policy = new RecordingPolicy();
		policy.delete = true;
		service.setConflictPolicy(UnitType.PRESENTATION, policy);
		Version base = own(service, SHOW, List.of(), name("Sitzung"));
		Version theirs = foreign(service, appData, SHOW, List.of(base.id()), name("Sitzung neu"), false, Map.of());
		Version mine = service.append(UUID.randomUUID().toString(),
				List.of(new Change(SHOW, List.of(base.id()), null, true, null, Map.of("m2", 4L)))).get(0);
		assertEquals(Map.of("m2", 4L), mine.seen());

		service.tick();

		Version merge = service.unitState(SHOW).head();
		assertEquals(List.of(theirs.id(), mine.id()), merge.parents());
		assertTrue(merge.deleted());
		assertEquals(Map.of("m2", 4L), merge.seen(), "nicht die (leeren) Positionen des Einzelbetriebs");
	}

	// --- Schreiben: Reihenfolge und Wiederholbarkeit

	@Test
	void extraUnitsAreWrittenBeforeTheMerge() throws IOException {
		Path appData = dir.resolve("app");
		FaultyFileLayer files = new FaultyFileLayer(new NioFileLayer());
		SyncService service = single(appData, files);
		RecordingPolicy policy = new RecordingPolicy();
		policy.extra = List.of(new Change(MOVED, List.of(), name("Carla ohne Platz"), false));
		service.setConflictPolicy(UnitType.BOOKING, policy);
		Loss loss = loss(service, appData, BOOKING);
		files.failWhen(p -> p.getParent() != null && p.getParent().endsWith(Path.of("p1", "bookings", "A-1")), "createNew",
				new IOException("Datenträger voll"));

		service.tick();

		assertEquals(name("Carla ohne Platz"), service.unitState(MOVED).head().payload());
		assertEquals(1, versionFiles(appData, MOVED));
		assertEquals(List.of(List.of(loss.mine().id())), service.unitState(BOOKING).lost());
		assertTrue(logsAtLeast(Level.WARNING).stream().anyMatch(r -> r.getMessage().contains(BOOKING.relativePath())),
				"die gescheiterte Zusammenführung steht im Log");
	}

	@Test
	void crashBetweenExtraUnitAndMergeResolvesOnceAfterRestart() throws IOException {
		Path appData = dir.resolve("app");
		FaultyFileLayer files = new FaultyFileLayer(new NioFileLayer());
		SyncService service = single(appData, files);
		RecordingPolicy policy = new RecordingPolicy();
		policy.extra = List.of(new Change(MOVED, List.of(), name("Carla ohne Platz"), false));
		service.setConflictPolicy(UnitType.BOOKING, policy);
		Loss loss = loss(service, appData, BOOKING);
		files.failWhen(p -> p.getParent() != null && p.getParent().endsWith(Path.of("p1", "bookings", "A-1")), "createNew",
				new IOException("Datenträger voll"));
		service.tick();
		assertEquals(1, versionFiles(appData, MOVED));

		// die Wiederholung liefert die neue Einheit mit anderem Inhalt (etwa anders getippte Zahlen): Sie gilt trotzdem als
		// geschrieben, sobald die Einheit eine Version hat; eine zweite Erstversion gabelte gegen die erste
		SyncService restarted = TestSyncServices.singleMode(appData, clock);
		RecordingPolicy again = new RecordingPolicy();
		again.extra = List.of(new Change(MOVED, List.of(), name("Carla ohne Platz, neu berechnet"), false));
		restarted.setConflictPolicy(UnitType.BOOKING, again);
		restarted.startup(null);

		assertEquals(1, again.inputs.size());
		assertEquals(1, versionFiles(appData, MOVED), "die Buchung ohne Platz gibt es genau einmal");
		assertEquals(name("Carla ohne Platz"), restarted.unitState(MOVED).head().payload());
		UnitState state = restarted.unitState(BOOKING);
		assertTrue(state.lost().isEmpty());
		assertEquals(List.of(loss.theirs().id(), loss.mine().id()), state.head().parents());
	}

	@Test
	void failedMergeWriteIsRetriedInTheNextTick() throws IOException {
		Path appData = dir.resolve("app");
		FaultyFileLayer files = new FaultyFileLayer(new NioFileLayer());
		SyncService service = single(appData, files);
		Recorder events = new Recorder();
		service.setListener(events);
		RecordingPolicy policy = new RecordingPolicy();
		policy.extra = List.of(new Change(MOVED, List.of(), name("Carla ohne Platz"), false));
		service.setConflictPolicy(UnitType.BOOKING, policy);
		Loss loss = loss(service, appData, BOOKING);
		AtomicBoolean full = new AtomicBoolean(true);
		files.failWhen(p -> full.get() && p.getParent() != null && p.getParent().endsWith(Path.of("p1", "bookings", "A-1")),
				"createNew", new IOException("Datenträger voll"));

		service.tick();
		service.tick();

		assertEquals(2, policy.inputs.size(), "im nächsten Takt erneut versucht");
		assertEquals(List.of(List.of(loss.mine().id())), service.unitState(BOOKING).lost());
		assertEquals(1, events.errors.size(), "SYS-006 einmal je Störung: " + events.errors);
		assertTrue(events.errors.get(0).startsWith(StoreMessages.SYS_SYNC_FAILED + ": "), events.errors.get(0));
		assertTrue(events.errors.get(0).contains("Datenträger voll"), events.errors.get(0));
		assertEquals(1, logsAtLeast(Level.WARNING).stream().filter(r -> r.getMessage().contains(BOOKING.relativePath())).count(),
				"WARNING einmal je Einheit und Störung");

		full.set(false);
		service.tick();

		UnitState state = service.unitState(BOOKING);
		assertTrue(state.lost().isEmpty());
		assertEquals(List.of(loss.theirs().id(), loss.mine().id()), state.head().parents());
		assertEquals(1, versionFiles(appData, MOVED), "die Buchung ohne Platz gibt es genau einmal");

		// eine neue Störung nach dem Erfolg wird wieder gemeldet
		own(service, BOOKING, List.of(loss.mine().id()), name("Carla 2"));
		full.set(true);
		service.tick();
		assertEquals(2, events.errors.size());
	}

	@Test
	void resolutionWithoutPayloadIsTreatedLikeAFailingPolicy() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Recorder events = new Recorder();
		service.setListener(events);
		RecordingPolicy policy = new RecordingPolicy();
		policy.withoutPayload = true;
		service.setConflictPolicy(UnitType.BOOKING, policy);
		Loss loss = loss(service, appData, BOOKING);
		ForeignVersions counter = new ForeignVersions(service, appData);
		long before = counter.files();

		service.tick();

		assertEquals(before, counter.files(), "kein leerer Inhalt als Zusammenführung");
		assertEquals(List.of(List.of(loss.mine().id())), service.unitState(BOOKING).lost());
		assertEquals(1, events.errors.size());
		assertTrue(events.errors.get(0).startsWith("Ein Konflikt an " + BOOKING.relativePath()), events.errors.get(0));
	}

	@Test
	void throwingPolicyWritesNothingAndReportsOnce() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Recorder events = new Recorder();
		service.setListener(events);
		RecordingPolicy policy = new RecordingPolicy();
		policy.failure = new IllegalStateException("kaputt");
		service.setConflictPolicy(UnitType.BOOKING, policy);
		Loss loss = loss(service, appData, BOOKING);
		ForeignVersions counter = new ForeignVersions(service, appData);
		long before = counter.files();

		service.tick();

		assertEquals(before, counter.files());
		assertEquals(List.of(List.of(loss.mine().id())), service.unitState(BOOKING).lost());
		assertEquals(List.of("Ein Konflikt an events/e1/presentations/p1/bookings/A-1 ließ sich nicht auflösen: "
				+ "IllegalStateException: kaputt"), events.errors);
		assertEquals(1, logsAtLeast(Level.SEVERE).size());
		assertEquals(0, events.conflicts);

		// nicht erneut eingereiht: Der nächste Takt versucht es nicht noch einmal
		service.tick();
		assertEquals(1, policy.inputs.size());

		// wird die Einheit erneut gemeldet, scheitert die Strategie wieder: Log ja, Fußzeile nicht noch einmal
		service.resolver().enqueue(BOOKING);
		service.tick();
		assertEquals(2, policy.inputs.size());
		assertEquals(1, events.errors.size());
		assertEquals(2, logsAtLeast(Level.SEVERE).size());
		assertEquals(before, counter.files());
	}

	// --- Wartebedingung im gemeinsamen Betrieb

	/**
	 * Beide verkaufen den Platz; A ist auf dem Server zuerst, B erkennt die Niederlage über die Anspruchsprüfung (am Ende
	 * eines Takts), und der nächste Takt versucht zum ersten Mal, sie aufzulösen.
	 */
	private Version[] lateDefeatOfB(SimulatedCloud cloud, SyncService a, SyncService b, boolean withFeeds)
			throws IOException {
		Version va = own(a, BOOKING, List.of(), name("Anna"));
		Version vb = own(b, BOOKING, List.of(), name("Bert"));
		clock.advance(Duration.ofSeconds(2));
		a.tick();
		b.tick();
		cloud.step("a");
		if (withFeeds) {
			cloud.step("b");
		} else {
			cloud.stepFilesOnly("b", rel -> !rel.startsWith("data/feeds/"));
		}
		clock.advance(Duration.ofSeconds(2));
		b.tick();
		assertEquals(List.of(List.of(vb.id())), b.unitState(BOOKING).lost());
		clock.advance(Duration.ofSeconds(2));
		b.tick();
		return new Version[] { va, vb };
	}

	@Test
	void resolutionWaitsUntilWinnersListIsProcessed() throws IOException {
		SimulatedCloud cloud = new SimulatedCloud(dir.resolve("server"));
		SyncService a = TestSyncServices.sharedMode(dir.resolve("app-a"), cloud.addClient("a"), "a", clock);
		SyncService b = TestSyncServices.sharedMode(dir.resolve("app-b"), cloud.addClient("b"), "b", clock);
		Version[] versions = lateDefeatOfB(cloud, a, b, true);
		Version va = versions[0];
		Version vb = versions[1];

		UnitState waiting = b.unitState(BOOKING);
		assertEquals(List.of(List.of(vb.id())), waiting.lost(), "die Liste von A ist noch nicht gelesen");
		assertTrue(waiting.pending().isEmpty(), "nichts geschrieben");
		assertEquals(2, b.history(BOOKING).size());

		// B findet A's Liste und liest sie (nach dem Hochladen, also nach dem Versuch in diesem Takt)
		clock.advance(Duration.ofSeconds(30));
		b.tick();
		assertTrue(b.unitState(BOOKING).pending().isEmpty());
		clock.advance(Duration.ofSeconds(2));
		b.tick();

		UnitState state = b.unitState(BOOKING);
		assertTrue(state.lost().isEmpty());
		assertEquals(List.of(va.id(), vb.id()), last(state.pending()).parents());
		assertTrue(logsAtLeast(Level.WARNING).stream().noneMatch(r -> r.getMessage().contains("5 Minuten")));
	}

	/**
	 * Eine Version der früheren ID dieses Rechners (vor SYS-011) in der Kette ist eigen: Auf ihre Änderungsliste wird nicht
	 * gewartet, auch wenn sie nie als gelesen gilt. Fiele die Prüfung auf "nur die aktuelle ID" zurück, wartete die
	 * Auflösung hier fünf Minuten.
	 */
	@Test
	void ownVersionOfAPreviousIdDoesNotWait() throws IOException {
		String previous = "frueher1aaaaaaaaaaaaaaaaaa";
		String current = "jetzt123aaaaaaaaaaaaaaaaaa";
		long untilMillis = 1_800_000_010_000L;
		Ownership own = new Ownership(current, List.of(new Ownership.Previous(previous, Instant.ofEpochMilli(untilMillis))));
		VersionStore store = new VersionStore(dir.resolve("data"), new NioFileLayer());
		LocalArbiter arbiter = new LocalArbiter(store, clock);
		Version base = new Version(StoreCodec.FORMAT, VersionId.create(untilMillis - 3000, previous, 0), List.of(), previous,
				"Kasse", null, clock.instant(), "s1", false, name("Anna"));
		// der Gewinner: noch unter der früheren ID, vor ihrer Grenze; seine Liste ist nie gelesen
		Version winner = new Version(StoreCodec.FORMAT, VersionId.create(untilMillis - 2000, previous, 1), List.of(base.id()),
				previous, "Kasse", null, clock.instant(), "s2", false, name("Bert"));
		Version mine = new Version(StoreCodec.FORMAT, VersionId.create(untilMillis + 5000, current, 0), List.of(base.id()),
				current, "Kasse", null, clock.instant(), "s3", false, name("Carla"));
		for (Version version : List.of(base, winner, mine)) {
			byte[] bytes = StoreCodec.write(version);
			assertTrue(store.writeVersion(BOOKING, version.id(), bytes));
			if (version != mine) {
				assertTrue(arbiter.claim(BOOKING, version, bytes).won());
			}
		}
		List<List<Change>> appended = new ArrayList<>();
		ConflictResolver.Host host = new ConflictResolver.Host() {
			@Override
			public SyncService.Resolved resolve(UnitKey unit) throws IOException {
				UnitFiles files = store.readUnit(unit);
				return new SyncService.Resolved(files, ChainResolver.resolve(files, own));
			}

			@Override
			public boolean shared() {
				return true;
			}

			@Override
			public boolean isListed(UnitKey unit, VersionId version) {
				return false;
			}

			@Override
			public String machineId() {
				return current;
			}

			@Override
			public Ownership ownership() {
				return own;
			}

			@Override
			public Map<UnitKey, ConflictInput.Sibling> siblings(UnitKey booking) {
				return Map.of();
			}

			@Override
			public Set<UnitKey> withOwnVersionsBesideChain(Collection<UnitKey> units) {
				return Set.of();
			}

			@Override
			public List<Version> append(String saveId, List<Change> changes) {
				appended.add(changes);
				return List.of();
			}

			@Override
			public String failureText(Exception failure) {
				return failure.toString();
			}

			@Override
			public boolean deferred(UnitKey unit) {
				return false;
			}
		};
		assertEquals(List.of(List.of(mine.id())), host.resolve(BOOKING).state().lost());
		ConflictResolver resolver = new ConflictResolver(host, new SyncNotifier(Runnable::run), clock);
		RecordingPolicy policy = new RecordingPolicy();
		resolver.setPolicy(UnitType.BOOKING, policy);
		resolver.enqueue(BOOKING);

		resolver.resolvePending(10);

		assertEquals(1, appended.size(), "zusammengeführt, ohne zu warten");
		assertEquals(List.of(winner.id(), mine.id()), appended.get(0).get(0).parents());
	}

	@Test
	void resolutionGivesUpWaitingAfterFiveMinutes() throws IOException {
		SimulatedCloud cloud = new SimulatedCloud(dir.resolve("server"));
		SyncService a = TestSyncServices.sharedMode(dir.resolve("app-a"), cloud.addClient("a"), "a", clock);
		SyncService b = TestSyncServices.sharedMode(dir.resolve("app-b"), cloud.addClient("b"), "b", clock);
		Version[] versions = lateDefeatOfB(cloud, a, b, false);
		Version va = versions[0];
		Version vb = versions[1];
		assertEquals(List.of(List.of(vb.id())), b.unitState(BOOKING).lost());

		clock.advance(Duration.ofMinutes(4));
		b.tick();
		assertEquals(List.of(List.of(vb.id())), b.unitState(BOOKING).lost(), "noch keine 5 Minuten");

		clock.advance(Duration.ofMinutes(1));
		b.tick();

		UnitState state = b.unitState(BOOKING);
		assertTrue(state.lost().isEmpty());
		assertEquals(List.of(va.id(), vb.id()), last(state.pending()).parents());
		assertTrue(logsAtLeast(Level.WARNING).stream()
				.anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("5 Minuten")
						&& r.getMessage().contains(BOOKING.relativePath())),
				"Warnung im Log");
	}

	// --- Folgefälle

	@Test
	void mergeThatLosesAgainIsResolvedAgain() throws IOException {
		Path shared = dir.resolve("shared");
		Files.createDirectories(shared);
		SyncService a = TestSyncServices.sharedMode(dir.resolve("app-a"), shared, "a", clock);
		SyncService b = TestSyncServices.sharedMode(dir.resolve("app-b"), shared, "b", clock);
		SyncService c = TestSyncServices.sharedMode(dir.resolve("app-c"), shared, "c", clock);
		Version v0 = own(a, BOOKING, List.of(), name("Anna"));
		clock.advance(Duration.ofSeconds(2));
		a.tick();
		clock.advance(Duration.ofSeconds(30));
		b.tick();
		c.tick();
		assertEquals(v0.id(), b.unitState(BOOKING).head().id());
		assertEquals(v0.id(), c.unitState(BOOKING).head().id());

		Version w = own(a, BOOKING, List.of(v0.id()), name("Anna 2"));
		a.tick();
		Version lb = own(b, BOOKING, List.of(v0.id()), name("Bert"));
		Version lc = own(c, BOOKING, List.of(v0.id()), name("Carla"));
		clock.advance(Duration.ofSeconds(2));

		// erste Runde: B und C verlieren beim Hochladen gegen W, lesen A's Liste und führen im nächsten Takt jeder für sich
		// zusammen; B lädt zuerst hoch
		b.tick();
		b.tick();
		Version mb = last(b.unitState(BOOKING).pending());
		assertEquals(List.of(w.id(), lb.id()), mb.parents());
		b.tick();
		assertEquals(mb.id(), b.unitState(BOOKING).head().id());
		c.tick();
		c.tick();
		Version mc = last(c.unitState(BOOKING).pending());
		assertEquals(List.of(w.id(), lc.id()), mc.parents());

		// zweite Runde: Die Zusammenführung von C verliert gegen die von B; C findet B's Liste erst danach
		clock.advance(Duration.ofSeconds(30));
		c.tick();
		assertEquals(List.of(List.of(mc.id())), c.unitState(BOOKING).lost());
		c.tick();

		UnitState state = c.unitState(BOOKING);
		assertTrue(state.lost().isEmpty());
		Version second = last(state.pending());
		assertEquals(List.of(mb.id(), mc.id()), second.parents());
		c.tick();
		assertEquals(second.id(), c.unitState(BOOKING).head().id());
	}

	// --- Rückmeldung, Start, Threads

	@Test
	void resolvedUnitsAreReportedAsChanged() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Recorder events = new Recorder();
		service.setListener(events);
		loss(service, appData, BOOKING);
		events.changed.clear();

		service.tick();

		assertTrue(events.changedUnits().contains(BOOKING));
		assertEquals(1, events.conflicts);
	}

	@Test
	void resolvingSeveralUnitsInOnePassReportsThemInOneUnitsChanged() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Recorder events = new Recorder();
		service.setListener(events);
		RecordingPolicy policy = new RecordingPolicy();
		policy.extra = List.of(new Change(MOVED, List.of(), name("Carla ohne Platz"), false));
		service.setConflictPolicy(UnitType.BOOKING, policy);
		loss(service, appData, BOOKING);
		loss(service, appData, OTHER_SEAT);
		events.changed.clear();
		events.sequence.clear();

		service.resolver().resolvePending(Integer.MAX_VALUE);

		assertEquals(2, policy.inputs.size());
		assertEquals(List.of(Set.of(BOOKING, OTHER_SEAT, MOVED)), events.changed);
	}

	@Test
	void resolvingSeveralNotedMergesInOnePassReportsConflictsChangedOnce() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Recorder events = new Recorder();
		service.setListener(events);
		RecordingPolicy policy = new RecordingPolicy();
		policy.noted = true;
		service.setConflictPolicy(UnitType.BOOKING, policy);
		loss(service, appData, BOOKING);
		loss(service, appData, OTHER_SEAT);
		events.changed.clear();
		events.sequence.clear();
		events.conflicts = 0;

		service.resolver().resolvePending(Integer.MAX_VALUE);

		assertEquals(2, policy.inputs.size());
		assertNotNull(service.unitState(BOOKING).head().conflict());
		assertNotNull(service.unitState(OTHER_SEAT).head().conflict());
		assertEquals(1, events.conflicts);
		assertEquals(List.of("units", "conflicts"), events.sequence);
	}

	@Test
	void aPassWithoutNoteReportsNoConflictsChanged() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Recorder events = new Recorder();
		service.setListener(events);
		service.setConflictPolicy(UnitType.BOOKING, new RecordingPolicy());
		loss(service, appData, BOOKING);
		loss(service, appData, OTHER_SEAT);
		events.changed.clear();
		events.sequence.clear();
		events.conflicts = 0;

		service.resolver().resolvePending(Integer.MAX_VALUE);

		assertEquals(1, events.changed.size());
		assertEquals(0, events.conflicts);
	}

	@Test
	void aPassWithOneFailedAndOneSuccessfulUnitReportsBothInOneUnitsChanged() throws IOException {
		Path appData = dir.resolve("app");
		FaultyFileLayer files = new FaultyFileLayer(new NioFileLayer());
		SyncService service = single(appData, files);
		Recorder events = new Recorder();
		service.setListener(events);
		service.setConflictPolicy(UnitType.BOOKING, new RecordingPolicy());
		Loss failing = loss(service, appData, BOOKING);
		loss(service, appData, OTHER_SEAT);
		AtomicBoolean full = new AtomicBoolean(true);
		files.failWhen(p -> full.get() && p.getParent() != null && p.getParent().endsWith(Path.of("p1", "bookings", "A-1")),
				"createNew", new IOException("Datenträger voll"));
		events.changed.clear();
		events.sequence.clear();

		service.resolver().resolvePending(Integer.MAX_VALUE);

		assertEquals(List.of(Set.of(BOOKING, OTHER_SEAT)), events.changed, "die gescheiterte gilt als versucht");
		assertEquals(List.of(List.of(failing.mine().id())), service.unitState(BOOKING).lost());
		assertTrue(service.unitState(OTHER_SEAT).lost().isEmpty());

		// die gescheiterte Einheit blieb eingereiht und kommt im nächsten Durchlauf allein dran
		full.set(false);
		events.changed.clear();
		service.resolver().resolvePending(Integer.MAX_VALUE);

		assertEquals(List.of(Set.of(BOOKING)), events.changed);
		assertTrue(service.unitState(BOOKING).lost().isEmpty());
	}

	@Test
	void startupResolvesBeforeReturning() throws IOException {
		Path appData = dir.resolve("app");
		SyncService first = single(appData);
		Loss loss = loss(first, appData, BOOKING);
		first.closeIndex();

		SyncService restarted = TestSyncServices.singleMode(appData, clock);
		Recorder events = new Recorder();
		restarted.setListener(events);
		restarted.startup(null);

		UnitState state = restarted.unitState(BOOKING);
		assertTrue(state.lost().isEmpty());
		assertEquals(List.of(loss.theirs().id(), loss.mine().id()), state.head().parents());
		// der Verlust, den nur der Index kannte, wird wie jeder andere gemeldet
		assertEquals(List.of(new LostVersion(BOOKING, List.of(loss.mine().id()), loss.theirs().id())), events.lost);
	}

	@Test
	void appendFromResolverAndUiConcurrentlyDoesNotDeadlock() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		for (int i = 1; i <= 50; i++) {
			loss(service, appData, UnitKey.booking("e1", "p1", "A-" + i));
		}

		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
				Thread thread = new Thread(runnable, "test-append");
				thread.setDaemon(true);
				return thread;
			});
			try {
				Future<?> ui = pool.submit(() -> {
					for (int i = 1; i <= 50; i++) {
						own(service, UnitKey.booking("e1", "p1", "B-" + i), List.of(), name("Ute " + i));
					}
					return null;
				});
				Future<?> worker = pool.submit(() -> {
					for (int i = 1; i <= 50; i++) {
						service.resolver().resolvePending(1);
					}
					return null;
				});
				ui.get();
				worker.get();
			} finally {
				pool.shutdownNow();
			}
		});

		for (int i = 1; i <= 50; i++) {
			assertTrue(service.unitState(UnitKey.booking("e1", "p1", "A-" + i)).lost().isEmpty(), "A-" + i);
			assertFalse(service.unitState(UnitKey.booking("e1", "p1", "B-" + i)).chain().isEmpty(), "B-" + i);
		}
	}
}
