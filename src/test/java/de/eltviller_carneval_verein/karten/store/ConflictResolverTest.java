package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
		RuntimeException failure;

		@Override
		public Resolution resolve(ConflictInput input) {
			inputs.add(input);
			if (failure != null) {
				throw failure;
			}
			ObjectNode payload = MAPPER.createObjectNode();
			payload.set("seen", input.mine().payload().deepCopy());
			return new Resolution(withoutPayload ? null : payload, delete, extra, null);
		}
	}

	/** Zeichnet die Rückmeldungen eines Dienstes auf. */
	private static final class Recorder implements SyncListener {
		final List<Set<UnitKey>> changed = new ArrayList<>();
		final List<String> errors = new ArrayList<>();
		final List<LostVersion> lost = new ArrayList<>();
		int conflicts;

		@Override
		public void lost(UnitKey unit, List<VersionId> mine, VersionId winner) {
			lost.add(new LostVersion(unit, mine, winner));
		}

		@Override
		public void unitsChanged(Set<UnitKey> units) {
			changed.add(units);
		}

		@Override
		public void error(String message) {
			errors.add(message);
		}

		@Override
		public void conflictsChanged() {
			conflicts++;
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
