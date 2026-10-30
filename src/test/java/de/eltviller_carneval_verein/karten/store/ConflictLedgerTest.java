package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * Die offenen Konflikte dieses Rechners aus dem Start-Index: Vermerke, die ihn betreffen, und Elternlöschungen über
 * eigenen, dem Löschenden unbekannten Verkäufen; dazu das Bestätigen.
 */
class ConflictLedgerTest {

	private static final String A_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String FOREIGN = "0123456789abcdefghijklmnop";
	/** Die frühere ID dieses Rechners (vor SYS-011); sie galt bis {@link #UNTIL}. */
	private static final String PREVIOUS_ID = "pppppppppppppppppppppppppp";
	private static final Instant UNTIL = Instant.ofEpochMilli(1_900_000_010_000L);
	/** Dieser Rechner: aktuelle ID {@link #A_ID}, frühere {@link #PREVIOUS_ID}. */
	private static final Ownership OWN = new Ownership(A_ID, List.of(new Ownership.Previous(PREVIOUS_ID, UNTIL)));
	private static final UnitKey EVENT = UnitKey.event("e1");
	private static final UnitKey SHOW = UnitKey.presentation("e1", "p1");
	private static final UnitKey SALE = UnitKey.booking("e1", "p1", "A-1");
	private static final UnitKey OTHER_SALE = UnitKey.booking("e1", "p1", "A-2");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@TempDir
	Path dir;

	private final TestClock clock = new TestClock();
	private final List<LogRecord> logs = new ArrayList<>();
	private Logger logger;
	private Handler capture;
	private boolean parentHandlers;
	private int foreignCount;
	private int publishedCount;

	@BeforeEach
	void captureLog() {
		// Warnungen des Dienstes werden aufgefangen: Sie belegen die Wege und halten die Ausgabe sauber
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

	/** Zählt die Rückmeldungen {@code conflictsChanged}. */
	private static final class Recorder implements SyncListener {
		int conflicts;

		@Override
		public void conflictsChanged() {
			conflicts++;
		}
	}

	// --- Hilfen

	private static ObjectNode name(String text) {
		return MAPPER.createObjectNode().put("name", text);
	}

	private static String idOf(Path appData) throws IOException {
		return MachineIdentity.loadOrCreate(appData, () -> "test").id();
	}

	/** Ein gestarteter Dienst im Einzelbetrieb. */
	private SyncService single(Path appData) {
		SyncService service = TestSyncServices.singleMode(appData, clock);
		service.startup(null);
		return service;
	}

	private static ConflictNote noteFor(String affected) {
		return new ConflictNote(ConflictNote.Kind.FIELDS, affected, null, List.of(), null, null, null);
	}

	private static Version own(SyncService service, UnitKey unit, List<VersionId> parents, ObjectNode payload, boolean deleted,
			ConflictNote note) throws IOException {
		return service.append(UUID.randomUUID().toString(), List.of(new Change(unit, parents, payload, deleted, note))).get(0);
	}

	private static Version own(SyncService service, UnitKey unit, List<VersionId> parents, ObjectNode payload)
			throws IOException {
		return own(service, unit, parents, payload, false, null);
	}

	/** Schreibt eine Version eines anderen Rechners in den lokalen Bestand, beansprucht sie lokal und bringt sie in den Index. */
	private Version foreign(SyncService service, Path appData, UnitKey unit, List<VersionId> parents, ObjectNode payload,
			boolean deleted, Map<String, Long> seen) throws IOException {
		return foreign(service, appData, unit, parents, payload, deleted, seen, null);
	}

	private Version foreign(SyncService service, Path appData, UnitKey unit, List<VersionId> parents, ObjectNode payload,
			boolean deleted, Map<String, Long> seen, ConflictNote note) throws IOException {
		foreignCount++;
		Version version = new Version(StoreCodec.FORMAT, VersionId.create(1_900_000_000_000L + foreignCount, FOREIGN, foreignCount),
				parents, FOREIGN, "Kasse 2", "Bert", Instant.parse("2026-10-28T09:00:00Z").plusSeconds(foreignCount),
				"fremd-" + foreignCount, deleted, deleted ? MAPPER.createObjectNode() : payload, note, seen);
		byte[] bytes = StoreCodec.write(version);
		VersionStore store = new VersionStore(appData.resolve("data"), new NioFileLayer());
		assertTrue(store.writeVersion(unit, version.id(), bytes));
		new LocalArbiter(store, Clock.systemUTC()).claim(unit, version, bytes);
		service.unitState(unit);
		return version;
	}

	private List<LogRecord> warnings() {
		synchronized (logs) {
			return logs.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue()).toList();
		}
	}

	// --- Hilfen für den gemeinsamen Betrieb

	/** Ein gestarteter Dienst im gemeinsamen Betrieb mit dem Ordner {@code shared}. */
	private SyncService sharedNode(Path appData, Path shared) {
		return TestSyncServices.sharedMode(appData, shared, "Kasse B", clock);
	}

	/** Speichert und lädt in einem Takt hoch: Die Version steht danach in der nächsten eigenen Liste. */
	private Version saveAndUpload(SyncService service, UnitKey unit) throws IOException {
		Version version = own(service, unit, List.of(), name(unit.relativePath()));
		clock.advance(Duration.ofSeconds(2));
		TestSyncServices.tick(service);
		return version;
	}

	/** Eine Version des fremden Rechners A. */
	private Version aVersion(int counter, List<VersionId> parents, boolean deleted, Map<String, Long> seen, ConflictNote note) {
		return new Version(StoreCodec.FORMAT, VersionId.create(1_900_000_000_000L + counter * 1000L, A_ID, counter), parents,
				A_ID, "Kasse A", "Anna", Instant.parse("2026-10-28T09:00:00Z").plusSeconds(counter), "a-" + counter, deleted,
				deleted ? MAPPER.createObjectNode() : name("v" + counter), note, seen);
	}

	/** Version, Liste und Anspruch des Rechners A im gemeinsamen Ordner, wie sein Uploader sie schreibt. */
	private void publish(Path shared, UnitKey unit, Version version) throws IOException {
		publishListOnly(shared, unit, version);
		publishClaim(shared, unit, version);
	}

	/** Version und Liste ohne den Anspruch: A ist mitten im Hochladen. */
	private void publishListOnly(Path shared, UnitKey unit, Version version) throws IOException {
		VersionStore store = new VersionStore(shared.resolve("data"), new NioFileLayer());
		assertTrue(store.writeVersion(unit, version.id(), StoreCodec.write(version)));
		publishedCount++;
		Feed feed = new Feed(StoreCodec.FORMAT, A_ID, publishedCount, Instant.parse("2026-10-28T09:00:00Z"),
				List.of(new FeedEntry(unit.relativePath(), version.id(), version.claimName())));
		assertTrue(store.createRelative("feeds/" + A_ID + "/" + publishedCount + ".json", StoreCodec.write(feed)));
	}

	/** Der Anspruch zu einer schon abgelegten Version. */
	private void publishClaim(Path shared, UnitKey unit, Version version) throws IOException {
		VersionStore store = new VersionStore(shared.resolve("data"), new NioFileLayer());
		byte[] bytes = store.readVersionBytes(unit, version.id()).orElseThrow();
		Claim claim = new Claim(version.id(), StoreCodec.sha256(bytes), A_ID, Instant.parse("2026-10-28T09:00:00Z"));
		assertTrue(store.createClaim(unit, version.claimName(), StoreCodec.write(claim)));
	}

	/** Version und Anspruch ohne Liste: Nur der Vollabgleich findet sie. */
	private void publishWithoutList(Path shared, UnitKey unit, Version version) throws IOException {
		VersionStore store = new VersionStore(shared.resolve("data"), new NioFileLayer());
		assertTrue(store.writeVersion(unit, version.id(), StoreCodec.write(version)));
		publishClaim(shared, unit, version);
	}

	/** A legt die Vorstellung an und löscht sie dann; {@code seen} nennt, was A von B gelesen hatte. */
	private Version aDeletesShow(Path shared, String bId, Long seenOfB) throws IOException {
		Version created = aVersion(0, List.of(), false, Map.of(), null);
		publish(shared, SHOW, created);
		Version deletion = aVersion(1, List.of(created.id()), true, seenOfB == null ? Map.of() : Map.of(bId, seenOfB), null);
		publish(shared, SHOW, deletion);
		return deletion;
	}

	private void tickAfterDiscoveryInterval(SyncService service) {
		clock.advance(Duration.ofSeconds(30));
		TestSyncServices.tick(service);
	}

	private static void deleteTree(Path root) throws IOException {
		assertTrue(Files.isDirectory(root), "der Ordner existiert: " + root);
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	// --- Vermerke

	@Test
	void noteAffectingMeIsOpenUntilAcknowledged() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Recorder recorder = new Recorder();
		service.setListener(recorder);
		String me = idOf(appData);
		Version first = own(service, SALE, List.of(), name("Anna"));
		ConflictNote note = noteFor(me);
		Version second = own(service, SALE, List.of(first.id()), name("Carla"), false, note);

		List<OpenConflict> open = service.openConflicts();

		assertEquals(1, open.size());
		OpenConflict conflict = open.get(0);
		assertEquals(second.id(), conflict.id());
		assertEquals(SALE, conflict.unit());
		assertEquals(second.at(), conflict.at());
		assertEquals(second.saveId(), conflict.saveId());
		assertEquals(note, conflict.note());
		assertNull(conflict.deletion());

		assertEquals(new ConflictKey(second.id(), ConflictKey.Kind.NOTE), conflict.key());
		service.acknowledge(List.of(conflict.key()));

		assertEquals(List.of(), service.openConflicts());
		assertEquals(1, recorder.conflicts, "das Bestätigen meldet die geänderte Menge");
		String file = Files.readString(appData.resolve("conflicts-ack.json"), StandardCharsets.UTF_8);
		assertEquals(List.of("note:" + second.id().value()),
				List.of(MAPPER.readTree(file).get("acknowledged").get(0).asText()));
		SyncService restarted = single(appData);
		assertEquals(List.of(), restarted.openConflicts(), "nach einem Neustart bleibt es bestätigt");
	}

	@Test
	void noteAffectingOtherMachineIsNotOpenHere() throws IOException {
		SyncService service = single(dir.resolve("app"));
		Version first = own(service, SALE, List.of(), name("Anna"));

		own(service, SALE, List.of(first.id()), name("Carla"), false, noteFor(FOREIGN));

		assertEquals(List.of(), service.openConflicts());
	}

	@Test
	void noteOfMergeThatWasMergedAgainStaysOpen() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		String me = idOf(appData);
		Version base = own(service, SALE, List.of(), name("Anna"));
		Version theirs = foreign(service, appData, SALE, List.of(base.id()), name("Bert"), false, Map.of());
		// verliert gegen "theirs": die erste Zusammenführung mit Vermerk, selbst nicht in der Kette
		Version lost = own(service, SALE, List.of(base.id()), name("Carla"), false, noteFor(me));
		assertEquals(List.of(List.of(lost.id())), service.unitState(SALE).lost());

		Version again = own(service, SALE, List.of(theirs.id(), lost.id()), name("Carla"));

		UnitState state = service.unitState(SALE);
		assertTrue(state.lost().isEmpty());
		assertEquals(again.id(), state.head().id());
		assertFalse(state.chain().stream().anyMatch(version -> version.id().equals(lost.id())), "nicht in der Kette");
		List<OpenConflict> open = service.openConflicts();
		assertEquals(1, open.size());
		assertEquals(lost.id(), open.get(0).id());
	}

	@Test
	void offlineResolvedConflictIsOpenBeforeUpload() throws IOException {
		Path appData = dir.resolve("app");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService service = sharedNode(appData, shared);
		String me = idOf(appData);
		Version first = own(service, SALE, List.of(), name("Anna"));
		clock.advance(Duration.ofSeconds(2));
		TestSyncServices.tick(service);

		Version merge = own(service, SALE, List.of(first.id()), name("Carla"), false, noteFor(me));

		assertEquals(List.of(merge.id()), service.unitState(SALE).pending().stream().map(Version::id).toList(),
				"noch nicht hochgeladen");
		assertEquals(List.of(merge.id()), service.openConflicts().stream().map(OpenConflict::id).toList());
		clock.advance(Duration.ofSeconds(2));
		TestSyncServices.tick(service);
		assertEquals(List.of(merge.id()), service.openConflicts().stream().map(OpenConflict::id).toList(),
				"nach dem Hochladen weiter offen");
	}

	@Test
	void foreignVersionWithNoteAffectingMeIsOpenAndReportedAfterItsListIsRead() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService b = sharedNode(appData, shared);
		Recorder recorder = new Recorder();
		b.setListener(recorder);
		Version created = aVersion(0, List.of(), false, Map.of(), null);
		publish(shared, SALE, created);
		Version merge = aVersion(1, List.of(created.id()), false, Map.of(), noteFor(idOf(appData)));
		publish(shared, SALE, merge);

		tickAfterDiscoveryInterval(b);

		assertEquals(List.of(merge.id()), b.openConflicts().stream().map(OpenConflict::id).toList());
		assertTrue(recorder.conflicts > 0);
	}

	// --- Elternlöschung

	@Test
	void parentDeletionUnknownToDeleterIsOpen() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService b = sharedNode(appData, shared);
		String bId = idOf(appData);
		for (int i = 1; i <= 4; i++) {
			saveAndUpload(b, UnitKey.booking("e1", "p1", "F-" + i));
		}
		saveAndUpload(b, SALE);
		Recorder recorder = new Recorder();
		b.setListener(recorder);
		Version deletion = aDeletesShow(shared, bId, 4L);

		tickAfterDiscoveryInterval(b);

		List<OpenConflict> open = b.openConflicts();
		assertEquals(1, open.size());
		OpenConflict conflict = open.get(0);
		assertEquals(deletion.id(), conflict.id());
		assertEquals(SHOW, conflict.unit());
		assertEquals(deletion.at(), conflict.at());
		assertEquals(deletion.saveId(), conflict.saveId());
		assertNull(conflict.note());
		assertEquals(new OpenConflict.ParentDeletion(SHOW, "Kasse A", "Anna", List.of(SALE)), conflict.deletion());
		assertTrue(recorder.conflicts > 0, "die gelistete Löschung meldet conflictsChanged");
	}

	@Test
	void parentDeletionKnownToDeleterIsNotOpen() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService b = sharedNode(appData, shared);
		String bId = idOf(appData);
		for (int i = 1; i <= 4; i++) {
			saveAndUpload(b, UnitKey.booking("e1", "p1", "F-" + i));
		}
		saveAndUpload(b, SALE);
		aDeletesShow(shared, bId, 5L);

		tickAfterDiscoveryInterval(b);

		assertEquals(UnitState.Status.OK, b.unitState(SHOW).status());
		assertTrue(b.unitState(SHOW).head().deleted(), "die Löschung ist angekommen");
		assertEquals(List.of(), b.openConflicts());
	}

	@Test
	void saleNeverListedIsUnknownToDeleter() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Version show = foreign(service, appData, SHOW, List.of(), name("Sitzung"), false, Map.of());
		own(service, SALE, List.of(), name("Anna"));
		Version deletion = foreign(service, appData, SHOW, List.of(show.id()), null, true, Map.of(FOREIGN, 7L));

		List<OpenConflict> open = service.openConflicts();

		assertEquals(1, open.size());
		assertEquals(deletion.id(), open.get(0).id());
		assertEquals(new OpenConflict.ParentDeletion(SHOW, "Kasse 2", "Bert", List.of(SALE)), open.get(0).deletion());
	}

	@Test
	void parentDeletionCanBeAcknowledgedByTheDeletionVersion() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Version show = foreign(service, appData, SHOW, List.of(), name("Sitzung"), false, Map.of());
		own(service, SALE, List.of(), name("Anna"));
		Version deletion = foreign(service, appData, SHOW, List.of(show.id()), null, true, Map.of());
		assertEquals(1, service.openConflicts().size());

		assertEquals(new ConflictKey(deletion.id(), ConflictKey.Kind.PARENT_DELETION), service.openConflicts().get(0).key());
		service.acknowledge(List.of(service.openConflicts().get(0).key()));

		assertEquals(List.of(), service.openConflicts());
		assertEquals(List.of(), single(appData).openConflicts(), "auch nach einem Neustart");
	}

	@Test
	void ownDeletionOfAParentIsNoConflict() throws IOException {
		SyncService service = single(dir.resolve("app"));
		Version show = own(service, SHOW, List.of(), name("Sitzung"));
		own(service, SALE, List.of(), name("Anna"));

		own(service, SHOW, List.of(show.id()), null, true, null);

		assertTrue(service.unitState(SHOW).head().deleted());
		assertEquals(List.of(), service.openConflicts());
	}

	@Test
	void deletedSaleUnderDeletedParentIsNoConflict() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Version show = foreign(service, appData, SHOW, List.of(), name("Sitzung"), false, Map.of());
		Version sale = own(service, SALE, List.of(), name("Anna"));
		own(service, SALE, List.of(sale.id()), null, true, null);
		foreign(service, appData, SHOW, List.of(show.id()), null, true, Map.of());

		assertEquals(List.of(), service.openConflicts());
	}

	@Test
	void unknownListNumberIsDecidedAfterOwnFeedsAreRead() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService restarted = restartedWithoutIndex(appData, shared);
		Recorder recorder = new Recorder();
		restarted.setListener(recorder);
		// der Verkauf steht in Liste 2, der Löschende kannte erst Liste 1
		aDeletesShow(shared, idOf(appData), 1L);

		tickAfterDiscoveryInterval(restarted);

		List<OpenConflict> open = restarted.openConflicts();
		assertEquals(1, open.size());
		assertEquals(List.of(SALE), open.get(0).deletion().affected());
		assertTrue(recorder.conflicts > 0, "nach dem Lesen der eigenen Listen");
	}

	@Test
	void knownListNumberAfterOwnFeedsAreReadMayBeKnownToDeleter() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService restarted = restartedWithoutIndex(appData, shared);
		aDeletesShow(shared, idOf(appData), 2L);

		tickAfterDiscoveryInterval(restarted);

		assertTrue(restarted.unitState(SHOW).head().deleted());
		assertEquals(List.of(), restarted.openConflicts());
	}

	/**
	 * B lädt zwei Verkäufe hoch (Listen 1 und 2) und startet neu ohne Index: Die Listennummern sind danach unbekannt.
	 */
	private SyncService restartedWithoutIndex(Path appData, Path shared) throws IOException {
		SyncService first = sharedNode(appData, shared);
		saveAndUpload(first, OTHER_SALE);
		saveAndUpload(first, SALE);
		deleteTree(appData.resolve("index"));
		return sharedNode(appData, shared);
	}

	@Test
	void ownSaleInNoListIsCountedAfterOwnFeedsAreRead() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> "Kasse B");
		// ein Verkauf aus der Zeit vor dem gemeinsamen Ordner: lokal beansprucht, in keiner Liste
		Version sale = new Version(StoreCodec.FORMAT, VersionId.create(1_800_000_000_000L, identity.id(), 0), List.of(),
				identity.id(), "Kasse B", null, Instant.parse("2026-10-20T09:00:00Z"), "einzel", false, name("Anna"));
		VersionStore local = new VersionStore(appData.resolve("data"), new NioFileLayer());
		byte[] bytes = StoreCodec.write(sale);
		assertTrue(local.writeVersion(SALE, sale.id(), bytes));
		new LocalArbiter(local, Clock.systemUTC()).claim(SALE, sale, bytes);
		SyncService b = new SyncService(appData, identity, new NioFileLayer(), Runnable::run, Runnable::run, path -> true, clock);
		b.startup(shared.toString());
		Recorder recorder = new Recorder();
		b.setListener(recorder);
		// A kannte alle Listen von B, die es gibt: keine
		Version deletion = aDeletesShow(shared, identity.id(), 99L);

		tickAfterDiscoveryInterval(b);

		List<OpenConflict> open = b.openConflicts();
		assertEquals(1, open.size());
		assertEquals(deletion.id(), open.get(0).id());
		assertEquals(List.of(SALE), open.get(0).deletion().affected());
		assertTrue(recorder.conflicts > 0);
	}

	// --- Bestätigen

	@Test
	void brokenAckFileShowsConflictsAgain() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Version first = own(service, SALE, List.of(), name("Anna"));
		Version second = own(service, SALE, List.of(first.id()), name("Carla"), false, noteFor(idOf(appData)));
		service.acknowledge(List.of(new ConflictKey(second.id(), ConflictKey.Kind.NOTE)));
		assertEquals(List.of(), service.openConflicts());
		Files.writeString(appData.resolve("conflicts-ack.json"), "{\"acknowledged\": [", StandardCharsets.UTF_8);

		SyncService restarted = single(appData);

		assertEquals(List.of(second.id()), restarted.openConflicts().stream().map(OpenConflict::id).toList());
		assertFalse(warnings().isEmpty(), "eine defekte Datei steht als WARNING im Log");
	}

	@Test
	void missingAckFileMeansNothingIsAcknowledged() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Version first = own(service, SALE, List.of(), name("Anna"));
		Version second = own(service, SALE, List.of(first.id()), name("Carla"), false, noteFor(idOf(appData)));

		assertFalse(Files.exists(appData.resolve("conflicts-ack.json")));
		assertEquals(List.of(second.id()), service.openConflicts().stream().map(OpenConflict::id).toList());
		assertTrue(warnings().isEmpty(), "eine fehlende Datei ist normal");
	}

	@Test
	void acknowledgeThatCannotBeWrittenKeepsTheConflictOpen() throws IOException {
		Path appData = dir.resolve("app");
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService service = TestSyncServices.singleMode(appData, layer, clock);
		service.startup(null);
		Version first = own(service, SALE, List.of(), name("Anna"));
		Version second = own(service, SALE, List.of(first.id()), name("Carla"), false, noteFor(idOf(appData)));
		layer.failWhen(p -> p.startsWith(appData) && p.toString().contains("conflicts-ack"), "createNew",
				new AccessDeniedException("schreibgeschützt"));

		assertThrows(IOException.class, () -> service.acknowledge(List.of(new ConflictKey(second.id(), ConflictKey.Kind.NOTE))));

		assertEquals(List.of(second.id()), service.openConflicts().stream().map(OpenConflict::id).toList());
	}

	// --- Meldung conflictsChanged

	@Test
	void conflictIsReportedWhenTheClaimArrivesAfterItsList() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService b = sharedNode(appData, shared);
		Recorder recorder = new Recorder();
		b.setListener(recorder);
		Version created = aVersion(0, List.of(), false, Map.of(), null);
		publish(shared, SALE, created);
		Version merge = aVersion(1, List.of(created.id()), false, Map.of(), noteFor(idOf(appData)));
		publishListOnly(shared, SALE, merge);
		tickAfterDiscoveryInterval(b);
		assertEquals(List.of(), b.openConflicts(), "ohne Anspruch gilt die Version noch nicht");
		int before = recorder.conflicts;

		// der Anspruch kommt erst in einem späteren Durchlauf; die Version steht dann in keiner neu gelesenen Liste
		publishClaim(shared, SALE, merge);
		clock.advance(Duration.ofSeconds(2));
		TestSyncServices.tick(b);

		assertEquals(List.of(merge.id()), b.openConflicts().stream().map(OpenConflict::id).toList());
		assertTrue(recorder.conflicts > before);
	}

	@Test
	void conflictIsReportedWhenAVersionArrivesOnlyThroughTheFullSync() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService b = sharedNode(appData, shared);
		Recorder recorder = new Recorder();
		b.setListener(recorder);
		Version created = aVersion(0, List.of(), false, Map.of(), null);
		publish(shared, SALE, created);
		tickAfterDiscoveryInterval(b);
		assertEquals(List.of(), b.openConflicts());
		int before = recorder.conflicts;

		Version merge = aVersion(1, List.of(created.id()), false, Map.of(), noteFor(idOf(appData)));
		publishWithoutList(shared, SALE, merge);
		b.syncNow();

		assertEquals(List.of(merge.id()), b.openConflicts().stream().map(OpenConflict::id).toList());
		assertTrue(recorder.conflicts > before);
	}

	@Test
	void conflictIsReportedWhenItDisappearsBecauseTheParentWasRevived() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService b = sharedNode(appData, shared);
		saveAndUpload(b, SALE);
		Version deletion = aDeletesShow(shared, idOf(appData), 0L);
		tickAfterDiscoveryInterval(b);
		assertEquals(1, b.openConflicts().size());
		Recorder recorder = new Recorder();
		b.setListener(recorder);

		publish(shared, SHOW, aVersion(2, List.of(deletion.id()), false, Map.of(), null));
		tickAfterDiscoveryInterval(b);

		assertEquals(List.of(), b.openConflicts());
		assertTrue(recorder.conflicts > 0);
	}

	// --- Löschen gewinnt auf der verlierenden Seite, Bestätigen je Art

	/**
	 * A löscht die Vorstellung, B ändert sie: Die Löschung gewinnt, und B schreibt die Zusammenführung als Löschung mit dem
	 * Vermerk DELETE_WINS und den gelesenen Listen der Löschung. Darunter liegt ein Verkauf von B, den A nicht kannte.
	 */
	private Version deleteWinsMergeOnLosingMachine(SyncService service, Path appData) throws IOException {
		String me = idOf(appData);
		Version show = foreign(service, appData, SHOW, List.of(), name("Sitzung"), false, Map.of());
		own(service, SALE, List.of(), name("Anna"));
		Version deletion = foreign(service, appData, SHOW, List.of(show.id()), null, true, Map.of());
		Version edit = own(service, SHOW, List.of(show.id()), name("Sitzung 2"));
		assertEquals(List.of(List.of(edit.id())), service.unitState(SHOW).lost());
		ConflictNote note = new ConflictNote(ConflictNote.Kind.DELETE_WINS, me,
				new ConflictNote.Party(FOREIGN, "Kasse 2", "Bert", deletion.id()), List.of(), null, null, null);
		return service.append(UUID.randomUUID().toString(),
				List.of(new Change(SHOW, List.of(deletion.id(), edit.id()), null, true, note, deletion.seen()))).get(0);
	}

	@Test
	void deleteWinsMergeOnTheLosingMachineCountsAsForeignParentDeletion() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Version merge = deleteWinsMergeOnLosingMachine(service, appData);
		assertTrue(service.unitState(SHOW).head().deleted());
		assertEquals(merge.id(), service.unitState(SHOW).head().id(), "die eigene Zusammenführung ist der aktuelle Stand");

		List<OpenConflict> deletions = service.openConflicts().stream().filter(c -> c.deletion() != null).toList();

		assertEquals(1, deletions.size());
		assertEquals(merge.id(), deletions.get(0).id());
		assertEquals(merge.at(), deletions.get(0).at());
		assertEquals(new OpenConflict.ParentDeletion(SHOW, "Kasse 2", "Bert", List.of(SALE)), deletions.get(0).deletion());
	}

	@Test
	void ownDeleteWinsMergeWhereTheOwnSideDeletedIsNoParentDeletion() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Version show = own(service, SHOW, List.of(), name("Sitzung"));
		own(service, SALE, List.of(), name("Anna"));
		Version deletion = own(service, SHOW, List.of(show.id()), null, true, null);
		Version edit = foreign(service, appData, SHOW, List.of(show.id()), name("Sitzung 2"), false, Map.of());
		ConflictNote note = new ConflictNote(ConflictNote.Kind.DELETE_WINS, FOREIGN,
				new ConflictNote.Party(idOf(appData), "test", null, deletion.id()), List.of(), null, null, null);

		service.append(UUID.randomUUID().toString(),
				List.of(new Change(SHOW, List.of(deletion.id(), edit.id()), null, true, note, Map.of())));

		assertEquals(List.of(), service.openConflicts(), "der Löschende ist dieser Rechner, die Änderung verlor auf dem anderen");
	}

	@Test
	void noteAndParentDeletionOnTheSameVersionAreTwoRowsAcknowledgedSeparately() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Version merge = deleteWinsMergeOnLosingMachine(service, appData);

		List<OpenConflict> open = service.openConflicts();
		assertEquals(2, open.size());
		assertEquals(List.of(merge.id(), merge.id()), open.stream().map(OpenConflict::id).toList());
		OpenConflict note = open.stream().filter(c -> c.note() != null).findFirst().orElseThrow();
		OpenConflict deletion = open.stream().filter(c -> c.deletion() != null).findFirst().orElseThrow();
		assertEquals(new ConflictKey(merge.id(), ConflictKey.Kind.NOTE), note.key());
		assertEquals(new ConflictKey(merge.id(), ConflictKey.Kind.PARENT_DELETION), deletion.key());

		service.acknowledge(List.of(note.key()));
		assertEquals(List.of(deletion.key()), service.openConflicts().stream().map(OpenConflict::key).toList());
		assertEquals(Set.of("note:" + merge.id().value()),
				Set.of(MAPPER.readTree(Files.readString(appData.resolve("conflicts-ack.json"))).get("acknowledged").get(0).asText()));

		service.acknowledge(List.of(deletion.key()));
		assertEquals(List.of(), service.openConflicts());
		String file = Files.readString(appData.resolve("conflicts-ack.json"), StandardCharsets.UTF_8);
		assertEquals(Set.of("note:" + merge.id().value(), "deletion:" + merge.id().value()),
				Set.of(MAPPER.readTree(file).get("acknowledged").get(0).asText(),
						MAPPER.readTree(file).get("acknowledged").get(1).asText()));
	}

	// --- Eigene Listen nach Verbindungsverlust

	@Test
	void offlineStartThenReconnectDecidesUnknownListNumbersWithoutChanges() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService first = sharedNode(appData, shared);
		saveAndUpload(first, SALE);
		aDeletesShow(shared, idOf(appData), 0L);
		tickAfterDiscoveryInterval(first);
		assertEquals(1, first.openConflicts().size());
		deleteTree(appData.resolve("index"));
		AtomicBoolean reachable = new AtomicBoolean(false);
		MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> "Kasse B");
		SyncService b = new SyncService(appData, identity, new NioFileLayer(), Runnable::run, Runnable::run,
				path -> reachable.get(), clock);
		Recorder recorder = new Recorder();
		b.setListener(recorder);

		assertFalse(b.startup(shared.toString()), "der gemeinsame Ordner ist nicht erreichbar");
		assertEquals(List.of(), b.openConflicts(), "die Listennummer des Verkaufs ist unbekannt");
		reachable.set(true);
		clock.advance(Duration.ofSeconds(2));
		TestSyncServices.tick(b);

		assertEquals(1, b.openConflicts().size());
		assertTrue(recorder.conflicts > 0);
	}

	@Test
	void failedReadOfOwnListsIsRetriedByTheNextTickWithoutChanges() throws IOException {
		Path appData = dir.resolve("b");
		Path shared = Files.createDirectories(dir.resolve("gemeinsam"));
		SyncService first = sharedNode(appData, shared);
		saveAndUpload(first, SALE);
		deleteTree(appData.resolve("index"));
		MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> "Kasse B");
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		AtomicBoolean broken = new AtomicBoolean(true);
		layer.failWhen(p -> broken.get() && p.toString().replace('\\', '/').endsWith("feeds/" + identity.id()), "listNames",
				new IOException("Netzlaufwerk getrennt"));
		SyncService b = new SyncService(appData, identity, layer, Runnable::run, Runnable::run, path -> true, clock);
		b.startup(shared.toString());
		aDeletesShow(shared, identity.id(), 0L);
		tickAfterDiscoveryInterval(b);
		assertEquals(List.of(), b.openConflicts(), "die Listennummer ist noch unbekannt");

		broken.set(false);
		clock.advance(Duration.ofSeconds(2));
		TestSyncServices.tick(b);

		assertEquals(1, b.openConflicts().size());
	}

	// --- Die Bestätigungsdatei

	@Test
	void unreadableAckFileIsNotTakenForEmptyAndNotOverwritten() throws IOException {
		Path appData = dir.resolve("app");
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		AtomicBoolean unreadable = new AtomicBoolean();
		layer.failWhen(p -> unreadable.get() && p.getFileName().toString().equals("conflicts-ack.json"), "read",
				new IOException("Sharing violation"));
		SyncService first = TestSyncServices.singleMode(appData, layer, clock);
		first.startup(null);
		String me = idOf(appData);
		Version a = own(first, SALE, List.of(), name("Anna"));
		Version noteA = own(first, SALE, List.of(a.id()), name("Carla"), false, noteFor(me));
		Version b = own(first, OTHER_SALE, List.of(), name("Dora"));
		Version noteB = own(first, OTHER_SALE, List.of(b.id()), name("Emil"), false, noteFor(me));
		first.acknowledge(List.of(new ConflictKey(noteA.id(), ConflictKey.Kind.NOTE)));
		unreadable.set(true);
		SyncService restarted = TestSyncServices.singleMode(appData, layer, clock);
		restarted.startup(null);

		assertEquals(2, restarted.openConflicts().size(), "unlesbar: lieber alles offen");
		assertThrows(IOException.class, () -> restarted.acknowledge(List.of(new ConflictKey(noteB.id(), ConflictKey.Kind.NOTE))));

		unreadable.set(false);
		assertEquals(List.of(noteB.id()), restarted.openConflicts().stream().map(OpenConflict::id).toList(),
				"die gültige Datei blieb unberührt, die erste Bestätigung gilt weiter");
	}

	@Test
	void ackFileIsReadAtStartup() throws IOException {
		Path appData = dir.resolve("app");
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService service = TestSyncServices.singleMode(appData, layer, clock);

		service.startup(null);

		assertTrue(layer.calls().stream().anyMatch(call -> call.startsWith("read ") && call.endsWith("conflicts-ack.json")),
				"vor der ersten Abfrage der Konflikte gelesen: " + layer.calls());
	}

	@Test
	void acknowledgeRejectsNullElementsAndChangesNothing() throws IOException {
		Path appData = dir.resolve("app");
		SyncService service = single(appData);
		Version first = own(service, SALE, List.of(), name("Anna"));
		Version second = own(service, SALE, List.of(first.id()), name("Carla"), false, noteFor(idOf(appData)));
		ConflictKey key = new ConflictKey(second.id(), ConflictKey.Kind.NOTE);

		assertThrows(NullPointerException.class, () -> service.acknowledge(Arrays.asList(key, null)));

		assertEquals(1, service.openConflicts().size());
		assertFalse(Files.exists(appData.resolve("conflicts-ack.json")));
	}

	// --- Ableitung auf Einträgen

	private static UnitIndex.Entry entry(UnitKey unit, VersionId version, boolean deleted, Map<String, Long> seen, Long ownSeq,
			List<UnitIndex.NotedVersion> notes) {
		UnitSnapshot snapshot = new UnitSnapshot(unit, version, UnitState.Status.OK, deleted, null);
		List<ChainStep> chain = version == null ? List.of()
				: List.of(new ChainStep(version, "save-" + version.value(), Instant.parse("2026-10-28T09:00:00Z"), "Kasse A", "Anna",
						deleted));
		return new UnitIndex.Entry(snapshot, "abdruck", false, false, chain, Set.of(), notes, seen, ownSeq, Set.of());
	}

	@Test
	void undecidedListNumberIsNotCountedButReported() {
		VersionId mine = VersionId.create(1_900_000_000_000L, A_ID, 1);
		VersionId theirs = VersionId.create(1_900_000_001_000L, FOREIGN, 1);
		List<UnitIndex.Entry> entries = List.of(entry(SHOW, theirs, true, Map.of(), null, List.of()),
				entry(SALE, mine, false, Map.of(), null, List.of()));

		ConflictLedger.Derivation derivation = ConflictLedger.derive(entries, OWN, Set.of());

		assertEquals(List.of(), derivation.conflicts());
		assertTrue(derivation.undecided());
	}

	@Test
	void foreignSaleUnderDeletedParentIsNotMine() {
		VersionId theirs = VersionId.create(1_900_000_001_000L, FOREIGN, 1);
		VersionId foreignSale = VersionId.create(1_900_000_002_000L, FOREIGN, 2);
		List<UnitIndex.Entry> entries = List.of(entry(SHOW, theirs, true, Map.of(), null, List.of()),
				entry(SALE, foreignSale, false, Map.of(), null, List.of()));

		ConflictLedger.Derivation derivation = ConflictLedger.derive(entries, OWN, Set.of());

		assertEquals(List.of(), derivation.conflicts());
		assertFalse(derivation.undecided(), "ein fremder Stand braucht keine eigene Listennummer");
	}

	@Test
	void eventDeletionCoversPresentationsAndBookingsBelow() {
		VersionId deletion = VersionId.create(1_900_000_001_000L, FOREIGN, 1);
		List<UnitIndex.Entry> entries = List.of(entry(EVENT, deletion, true, Map.of(A_ID, 3L), null, List.of()),
				entry(SHOW, VersionId.create(1_900_000_002_000L, A_ID, 2), false, Map.of(), 4L, List.of()),
				entry(SALE, VersionId.create(1_900_000_003_000L, A_ID, 3), false, Map.of(), 2L, List.of()),
				entry(OTHER_SALE, VersionId.create(1_900_000_004_000L, A_ID, 4), false, Map.of(), 0L, List.of()),
				entry(UnitKey.booking("e2", "p9", "B-1"), VersionId.create(1_900_000_005_000L, A_ID, 5), false, Map.of(), 0L,
						List.of()));

		List<OpenConflict> conflicts = ConflictLedger.derive(entries, OWN, Set.of()).conflicts();

		assertEquals(1, conflicts.size());
		assertEquals(List.of(SHOW, OTHER_SALE), conflicts.get(0).deletion().affected(),
				"nur Listennummer 0 oder größer als seen (3); sortiert nach Pfad");
	}

	@Test
	void parentDeletionCountsSalesOfAPreviousIdAsOwn() {
		VersionId deletion = VersionId.create(1_900_000_020_000L, FOREIGN, 1);
		UnitKey known = UnitKey.booking("e1", "p1", "A-3");
		UnitKey copied = UnitKey.booking("e1", "p1", "A-4");
		List<UnitIndex.Entry> entries = List.of(entry(SHOW, deletion, true, Map.of(A_ID, 3L), null, List.of()),
				// vor der Neuvergabe und in keiner Liste: dem Löschenden unbekannt
				entry(SALE, VersionId.create(1_900_000_003_000L, PREVIOUS_ID, 1), false, Map.of(), 0L, List.of()),
				// genau auf der Grenze, Listennummer hinter seen
				entry(OTHER_SALE, VersionId.create(1_900_000_010_000L, PREVIOUS_ID, 2), false, Map.of(), 4L, List.of()),
				// vor der Neuvergabe, dem Löschenden bekannt
				entry(known, VersionId.create(1_900_000_004_000L, PREVIOUS_ID, 3), false, Map.of(), 2L, List.of()),
				// nach der Grenze: der andere Rechner, der die alte ID weiter trägt; fremd, braucht keine Listennummer
				entry(copied, VersionId.create(1_900_000_010_001L, PREVIOUS_ID, 4), false, Map.of(), null, List.of()));

		ConflictLedger.Derivation derivation = ConflictLedger.derive(entries, OWN, Set.of());

		assertEquals(1, derivation.conflicts().size());
		assertEquals(List.of(SALE, OTHER_SALE), derivation.conflicts().get(0).deletion().affected());
		assertFalse(derivation.undecided(), "die Version nach der Grenze ist fremd");
	}

	@Test
	void deletionOfAPreviousIdIsOwnOnlyUntilTheChange() {
		VersionId sale = VersionId.create(1_900_000_002_000L, A_ID, 1);
		UnitIndex.Entry saleEntry = entry(SALE, sale, false, Map.of(), 0L, List.of());
		VersionId ownDeletion = VersionId.create(1_900_000_005_000L, PREVIOUS_ID, 1);
		VersionId copyDeletion = VersionId.create(1_900_000_011_000L, PREVIOUS_ID, 2);

		List<OpenConflict> own = ConflictLedger
				.derive(List.of(entry(SHOW, ownDeletion, true, Map.of(), null, List.of()), saleEntry), OWN, Set.of())
				.conflicts();
		List<OpenConflict> copy = ConflictLedger
				.derive(List.of(entry(SHOW, copyDeletion, true, Map.of(), null, List.of()), saleEntry), OWN, Set.of())
				.conflicts();

		assertEquals(List.of(), own, "die eigene Löschung vor der Neuvergabe ist kein Konflikt");
		assertEquals(1, copy.size());
		assertEquals(List.of(SALE), copy.get(0).deletion().affected());
	}

	@Test
	void noteForAPreviousIdCountsUntilTheChange() {
		VersionId before = VersionId.create(1_900_000_001_000L, A_ID, 1);
		VersionId after = VersionId.create(1_900_000_011_000L, A_ID, 2);
		VersionId foreignMerge = VersionId.create(1_900_000_012_000L, FOREIGN, 3);
		VersionId droppedBefore = VersionId.create(1_900_000_009_000L, PREVIOUS_ID, 4);
		Instant at = Instant.parse("2026-10-28T09:00:00Z");
		// ein fremder Rechner vermerkt nach der Neuvergabe, dass eine Änderung von vor ihr weicht: sie war eigen
		ConflictNote droppedNote = new ConflictNote(ConflictNote.Kind.FIELDS, PREVIOUS_ID, null, List.of(), null, null,
				droppedBefore);
		List<UnitIndex.Entry> entries = List.of(
				entry(SALE, after, false, Map.of(), 0L,
						List.of(noted(before, at, noteFor(PREVIOUS_ID)), noted(after, at, noteFor(PREVIOUS_ID)))),
				entry(OTHER_SALE, foreignMerge, false, Map.of(), null, List.of(noted(foreignMerge, at, droppedNote))));

		List<OpenConflict> conflicts = ConflictLedger.derive(entries, OWN, Set.of()).conflicts();

		assertEquals(List.of(before, foreignMerge), conflicts.stream().map(OpenConflict::id).toList());
	}

	@Test
	void conflictsAreSortedByTimeThenId() {
		VersionId early = VersionId.create(1_900_000_001_000L, A_ID, 1);
		VersionId late = VersionId.create(1_900_000_002_000L, A_ID, 2);
		VersionId sameTimeLow = VersionId.create(1_900_000_003_000L, A_ID, 3);
		VersionId sameTimeHigh = VersionId.create(1_900_000_003_000L, A_ID, 4);
		Instant t1 = Instant.parse("2026-10-28T09:00:00Z");
		Instant t2 = Instant.parse("2026-10-28T10:00:00Z");
		ConflictNote note = noteFor(A_ID);
		List<UnitIndex.Entry> entries = List.of(
				entry(SALE, late, false, Map.of(), 0L, List.of(noted(late, t2, note), noted(sameTimeHigh, t2, note))),
				entry(OTHER_SALE, early, false, Map.of(), 0L, List.of(noted(early, t1, note), noted(sameTimeLow, t2, note))));

		List<OpenConflict> conflicts = ConflictLedger.derive(entries, OWN, Set.of()).conflicts();

		assertEquals(List.of(early, late, sameTimeLow, sameTimeHigh), conflicts.stream().map(OpenConflict::id).toList());
	}

	private static UnitIndex.NotedVersion noted(VersionId id, Instant at, ConflictNote note) {
		return new UnitIndex.NotedVersion(id, A_ID, "Kasse A", "Anna", at, "save-" + id.value(), List.of(), note);
	}

	@Test
	void openConflictNeedsExactlyOneOfNoteAndDeletion() {
		VersionId id = VersionId.create(1_900_000_001_000L, A_ID, 1);
		Instant at = Instant.parse("2026-10-28T09:00:00Z");
		assertThrows(IllegalArgumentException.class, () -> new OpenConflict(id, SALE, at, "s", null, null));
		assertThrows(IllegalArgumentException.class, () -> new OpenConflict(id, SALE, at, "s", noteFor(A_ID),
				new OpenConflict.ParentDeletion(SHOW, null, null, List.of(SALE))));
	}
}
