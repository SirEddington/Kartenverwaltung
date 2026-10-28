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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
		foreignCount++;
		Version version = new Version(StoreCodec.FORMAT, VersionId.create(1_900_000_000_000L + foreignCount, FOREIGN, foreignCount),
				parents, FOREIGN, "Kasse 2", "Bert", Instant.parse("2026-10-28T09:00:00Z").plusSeconds(foreignCount),
				"fremd-" + foreignCount, deleted, deleted ? MAPPER.createObjectNode() : payload, null, seen);
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
		VersionStore store = new VersionStore(shared.resolve("data"), new NioFileLayer());
		byte[] bytes = StoreCodec.write(version);
		assertTrue(store.writeVersion(unit, version.id(), bytes));
		publishedCount++;
		Feed feed = new Feed(StoreCodec.FORMAT, A_ID, publishedCount, Instant.parse("2026-10-28T09:00:00Z"),
				List.of(new FeedEntry(unit.relativePath(), version.id(), version.claimName())));
		assertTrue(store.createRelative("feeds/" + A_ID + "/" + publishedCount + ".json", StoreCodec.write(feed)));
		Claim claim = new Claim(version.id(), StoreCodec.sha256(bytes), A_ID, Instant.parse("2026-10-28T09:00:00Z"));
		assertTrue(store.createClaim(unit, version.claimName(), StoreCodec.write(claim)));
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

		service.acknowledge(List.of(second.id()));

		assertEquals(List.of(), service.openConflicts());
		assertEquals(1, recorder.conflicts, "das Bestätigen meldet die geänderte Menge");
		String file = Files.readString(appData.resolve("conflicts-ack.json"), StandardCharsets.UTF_8);
		assertEquals(List.of(second.id().value()), List.of(MAPPER.readTree(file).get("acknowledged").get(0).asText()));
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

		service.acknowledge(List.of(deletion.id()));

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
		service.acknowledge(List.of(second.id()));
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

		assertThrows(IOException.class, () -> service.acknowledge(List.of(second.id())));

		assertEquals(List.of(second.id()), service.openConflicts().stream().map(OpenConflict::id).toList());
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

		ConflictLedger.Derivation derivation = ConflictLedger.derive(entries, A_ID, Set.of());

		assertEquals(List.of(), derivation.conflicts());
		assertTrue(derivation.undecided());
	}

	@Test
	void foreignSaleUnderDeletedParentIsNotMine() {
		VersionId theirs = VersionId.create(1_900_000_001_000L, FOREIGN, 1);
		VersionId foreignSale = VersionId.create(1_900_000_002_000L, FOREIGN, 2);
		List<UnitIndex.Entry> entries = List.of(entry(SHOW, theirs, true, Map.of(), null, List.of()),
				entry(SALE, foreignSale, false, Map.of(), null, List.of()));

		ConflictLedger.Derivation derivation = ConflictLedger.derive(entries, A_ID, Set.of());

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

		List<OpenConflict> conflicts = ConflictLedger.derive(entries, A_ID, Set.of()).conflicts();

		assertEquals(1, conflicts.size());
		assertEquals(List.of(SHOW, OTHER_SALE), conflicts.get(0).deletion().affected(),
				"nur Listennummer 0 oder größer als seen (3); sortiert nach Pfad");
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

		List<OpenConflict> conflicts = ConflictLedger.derive(entries, A_ID, Set.of()).conflicts();

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
