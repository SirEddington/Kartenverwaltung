package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class UnitIndexTest {

	private static final Instant AT = Instant.parse("2026-10-27T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
	private static final String ME = "maschine-1";
	private static final String OTHER = "fremder2";

	private static final UnitKey EVENT = UnitKey.event("e1");
	private static final UnitKey PRESENTATION = UnitKey.presentation("e1", "p1");
	private static final UnitKey EVENT2 = UnitKey.event("e2");
	private static final UnitKey HALL = UnitKey.hall("h1");

	@TempDir
	Path dir;

	private VersionStore local;
	private LocalArbiter arbiter;
	private Path indexDir;

	@BeforeEach
	void setUp() {
		local = new VersionStore(dir.resolve("data"), new NioFileLayer());
		arbiter = new LocalArbiter(local, CLOCK);
		indexDir = dir.resolve("index");
	}

	private UnitIndex index() {
		return new UnitIndex(indexDir, new NioFileLayer(), ME);
	}

	private static VersionId id(int n, String machine) {
		return VersionId.create(1_000_000L * n, machine, 0);
	}

	private static VersionId mine(int n) {
		return id(n, "maschine");
	}

	/** Schreibt eine Version und, wenn verlangt, ihren Anspruch. */
	private VersionId write(UnitKey unit, String machine, VersionId id, VersionId parent, String name, boolean claim)
			throws IOException {
		ObjectNode payload = new ObjectMapper().createObjectNode().put("name", name);
		Version version = new Version(1, id, parent == null ? List.of() : List.of(parent), machine, "Kasse", "Anna", AT,
				"save-" + id, false, payload);
		byte[] bytes = StoreCodec.write(version);
		assertTrue(local.writeVersion(unit, id, bytes));
		if (claim) {
			assertTrue(arbiter.claim(unit, version, bytes).won());
		}
		return id;
	}

	/** Zwei beanspruchte Versionen einer eigenen Einheit; die letzte heißt {@code <name>-2}. */
	private void saveTwice(UnitKey unit, String name) throws IOException {
		write(unit, ME, mine(1), null, name + "-1", true);
		write(unit, ME, mine(2), mine(1), name + "-2", true);
	}

	private void buildThreeUnits() throws IOException {
		saveTwice(EVENT, "event");
		saveTwice(PRESENTATION, "presentation");
		saveTwice(HALL, "hall");
	}

	private static String name(UnitIndex.Entry entry) {
		return entry.snapshot().payload().get("name").asText();
	}

	private Path eventIndexFile(String eventId) {
		return indexDir.resolve("events").resolve(eventId + ".json");
	}

	private static UnitIndex.Refresh refresh(UnitIndex index, VersionStore store) throws IOException {
		return index.refresh(store, p -> {
		});
	}

	@Test
	void refreshWithoutIndexResolvesEveryUnit() throws IOException {
		buildThreeUnits();
		UnitIndex index = index();

		UnitIndex.Refresh refresh = refresh(index, local);

		assertEquals(3, refresh.resolved());
		Map<UnitKey, UnitIndex.Entry> entries = index.entries();
		assertEquals(Set.of(EVENT, PRESENTATION, HALL), entries.keySet());
		assertEquals("event-2", name(entries.get(EVENT)));
		assertEquals("presentation-2", name(entries.get(PRESENTATION)));
		assertEquals("hall-2", name(entries.get(HALL)));
		assertEquals(mine(2), entries.get(EVENT).snapshot().version());
		assertEquals(UnitState.Status.OK, entries.get(EVENT).snapshot().status());
		assertFalse(entries.get(EVENT).snapshot().deleted());
		assertTrue(refresh.pendingUnits().isEmpty());
		assertTrue(refresh.lostUnits().isEmpty());
	}

	@Test
	void refreshAfterFlushResolvesNothing() throws IOException {
		buildThreeUnits();
		UnitIndex first = index();
		refresh(first, local);
		first.flush();

		UnitIndex second = index();
		UnitIndex.Refresh refresh = refresh(second, local);

		assertEquals(0, refresh.resolved());
		assertEquals(first.entries(), second.entries());
	}

	@Test
	void secondRefreshOnSameInstanceResolvesNothing() throws IOException {
		buildThreeUnits();
		UnitIndex index = index();
		refresh(index, local);

		assertEquals(0, refresh(index, local).resolved());
	}

	@Test
	void indexFilesLiveUnderEventsAndHalls() throws IOException {
		buildThreeUnits();
		UnitIndex index = index();
		refresh(index, local);
		index.flush();

		assertTrue(Files.isRegularFile(eventIndexFile("e1")));
		assertTrue(Files.isRegularFile(indexDir.resolve("halls.json")));
		ObjectNode root = (ObjectNode) new ObjectMapper().readTree(Files.readAllBytes(eventIndexFile("e1")));
		assertEquals(3, root.get("indexFormat").asInt());
		assertEquals(ME, root.get("machine").asText());
		assertEquals(2, root.get("units").size());
		assertEquals("events/e1", root.get("units").get(0).get("unit").asText());
	}

	@Test
	void staleEntryIsResolvedAgain() throws IOException {
		buildThreeUnits();
		UnitIndex first = index();
		refresh(first, local);
		first.flush();
		write(PRESENTATION, ME, mine(3), mine(2), "presentation-3", true);

		UnitIndex second = index();
		UnitIndex.Refresh refresh = refresh(second, local);

		assertEquals(1, refresh.resolved());
		assertEquals(mine(3), second.entries().get(PRESENTATION).snapshot().version());
		assertEquals("presentation-3", name(second.entries().get(PRESENTATION)));
		assertEquals("event-2", name(second.entries().get(EVENT)));
	}

	@Test
	void indexOfOtherMachineIsIgnored() throws IOException {
		buildThreeUnits();
		UnitIndex foreign = new UnitIndex(indexDir, new NioFileLayer(), "x");
		refresh(foreign, local);
		foreign.flush();

		UnitIndex own = index();
		UnitIndex.Refresh refresh = refresh(own, local);

		assertEquals(3, refresh.resolved());
		own.flush();
		assertEquals(ME, new ObjectMapper().readTree(Files.readAllBytes(eventIndexFile("e1"))).get("machine").asText());
	}

	@Test
	void indexWithOtherFormatIsIgnored() throws IOException {
		buildThreeUnits();
		UnitIndex first = index();
		refresh(first, local);
		first.flush();
		Files.writeString(indexDir.resolve("halls.json"), "{\"indexFormat\":4,\"machine\":\"" + ME + "\",\"units\":[]}");

		UnitIndex.Refresh refresh = refresh(index(), local);

		assertEquals(1, refresh.resolved());
	}

	@Test
	void formatOneIndexIsRebuilt() throws IOException {
		buildThreeUnits();
		UnitIndex first = index();
		refresh(first, local);
		first.flush();
		// ein Index, wie ihn die Fassung ohne Kette schrieb
		Path halls = indexDir.resolve("halls.json");
		Files.writeString(halls, Files.readString(halls).replace("\"indexFormat\":3", "\"indexFormat\":1"));
		Path event = eventIndexFile("e1");
		Files.writeString(event, Files.readString(event).replace("\"indexFormat\":3", "\"indexFormat\":1"));

		UnitIndex second = index();
		UnitIndex.Refresh refresh = refresh(second, local);

		assertEquals(3, refresh.resolved());
		assertEquals(first.entries(), second.entries());
		second.flush();
		assertEquals(0, refresh(index(), local).resolved());
	}

	@Test
	void chainIsStoredAndReloaded() throws IOException {
		saveTwice(EVENT, "event");
		// eine gelöschte Version ohne Bediener als drittes Glied
		Version deleted = new Version(1, mine(3), List.of(mine(2)), ME, "Kasse 2", null, AT.plusSeconds(90), "save-del", true,
				new ObjectMapper().createObjectNode());
		byte[] bytes = StoreCodec.write(deleted);
		assertTrue(local.writeVersion(EVENT, mine(3), bytes));
		assertTrue(arbiter.claim(EVENT, deleted, bytes).won());
		UnitIndex first = index();
		refresh(first, local);

		List<ChainStep> expected = List.of(new ChainStep(mine(1), "save-" + mine(1), AT, "Kasse", "Anna", false),
				new ChainStep(mine(2), "save-" + mine(2), AT, "Kasse", "Anna", false),
				new ChainStep(mine(3), "save-del", AT.plusSeconds(90), "Kasse 2", null, true));
		assertEquals(expected, first.entries().get(EVENT).chain());
		first.flush();

		UnitIndex second = index();
		assertEquals(0, refresh(second, local).resolved());
		assertEquals(expected, second.entries().get(EVENT).chain());
		assertEquals(first.entries(), second.entries());

		// auch über die Marke "sauber beendet" kommt die Kette mit
		assertTrue(second.writeCleanMarker());
		UnitIndex third = index();
		assertTrue(third.loadIfClean());
		assertEquals(expected, third.entries().get(EVENT).chain());
	}

	@Test
	void chainEndsBeforeTheBlockedVersion() throws IOException {
		write(EVENT, ME, mine(1), null, "e-1", true);
		write(EVENT, ME, mine(2), mine(1), "e-2", true);
		Files.writeString(local.dataRoot().resolve("events/e1/" + mine(2) + ".json"), "kaputt");
		UnitIndex index = index();

		refresh(index, local);

		assertEquals(UnitState.Status.LOCKED_DAMAGED, index.entries().get(EVENT).snapshot().status());
		assertEquals(List.of(mine(1)), index.entries().get(EVENT).chain().stream().map(ChainStep::version).toList());
	}

	@Test
	void unreadableIndexFileIsRebuilt() throws IOException {
		saveTwice(EVENT, "event");
		saveTwice(PRESENTATION, "presentation");
		saveTwice(EVENT2, "event2");
		UnitIndex first = index();
		refresh(first, local);
		first.flush();
		Files.writeString(eventIndexFile("e1"), "{");

		UnitIndex second = index();
		UnitIndex.Refresh refresh = refresh(second, local);

		assertEquals(2, refresh.resolved());
		assertEquals(first.entries(), second.entries());
		second.flush();
		assertEquals(0, refresh(index(), local).resolved());
	}

	@Test
	void removedUnitDisappears() throws IOException {
		buildThreeUnits();
		UnitIndex first = index();
		refresh(first, local);
		first.flush();
		Path presentations = local.dataRoot().resolve("events/e1/presentations");
		Files.move(presentations.resolve("p1"), presentations.resolve("p 1"));

		UnitIndex second = index();
		UnitIndex.Refresh refresh = refresh(second, local);

		assertEquals(0, refresh.resolved());
		assertEquals(Set.of(EVENT, HALL), second.entries().keySet());
		second.flush();
		UnitIndex third = index();
		refresh(third, local);
		assertEquals(Set.of(EVENT, HALL), third.entries().keySet());
		assertEquals(1, new ObjectMapper().readTree(Files.readAllBytes(eventIndexFile("e1"))).get("units").size());
	}

	@Test
	void removedLastUnitOfEventDisappears() throws IOException {
		saveTwice(EVENT, "event");
		saveTwice(EVENT2, "event2");
		UnitIndex first = index();
		refresh(first, local);
		first.flush();
		Files.move(local.dataRoot().resolve("events/e2"), local.dataRoot().resolve("events/e 2"));

		UnitIndex second = index();
		refresh(second, local);

		assertEquals(Set.of(EVENT), second.entries().keySet());
		second.flush();
		UnitIndex third = index();
		refresh(third, local);
		assertEquals(Set.of(EVENT), third.entries().keySet());
		assertTrue(third.entries().keySet().stream().noneMatch(EVENT2::equals));
	}

	@Test
	void pendingAndLostUnitsAreReported() throws IOException {
		// PRESENTATION: eigene Version auf dem Ende der Kette, noch ohne Anspruch
		write(PRESENTATION, ME, mine(1), null, "p-1", true);
		write(PRESENTATION, ME, mine(2), mine(1), "p-2", false);
		// EVENT: ein anderer Rechner hat den Platz hinter der ersten Version belegt, die eigene baut darauf auf
		write(EVENT, ME, mine(1), null, "e-1", true);
		write(EVENT, OTHER, id(2, OTHER), mine(1), "e-other", true);
		write(EVENT, ME, mine(3), mine(1), "e-3", false);
		// HALL: unauffällig
		saveTwice(HALL, "hall");
		UnitIndex index = index();

		UnitIndex.Refresh refresh = refresh(index, local);

		assertEquals(Set.of(PRESENTATION), refresh.pendingUnits());
		assertEquals(Set.of(EVENT), refresh.lostUnits());
		assertTrue(index.entries().get(PRESENTATION).pending());
		assertFalse(index.entries().get(PRESENTATION).lost());
		assertTrue(index.entries().get(EVENT).lost());
		assertEquals("p-2", name(index.entries().get(PRESENTATION)));

		// aus dem gespeicherten Index kommen dieselben Mengen
		index.flush();
		UnitIndex.Refresh again = refresh(index(), local);
		assertEquals(0, again.resolved());
		assertEquals(refresh.pendingUnits(), again.pendingUnits());
		assertEquals(refresh.lostUnits(), again.lostUnits());
	}

	@Test
	void fingerprintIgnoresForeignFileNames() throws IOException {
		saveTwice(EVENT, "event");
		UnitFiles before = local.readUnit(EVENT);
		UnitIndex first = index();
		refresh(first, local);
		first.flush();
		Path unitDir = local.dataRoot().resolve("events/e1");
		Files.writeString(unitDir.resolve("Thumbs.db"), "x");
		Files.writeString(unitDir.resolve("~karv-abc.tmp"), "x");
		Files.writeString(unitDir.resolve(mine(1) + " (Konfliktkopie).json"), "x");
		Files.writeString(unitDir.resolve(mine(9).value().toUpperCase() + ".json"), "x");
		Files.writeString(unitDir.resolve("claims").resolve("Thumbs.db"), "x");
		Files.createDirectories(unitDir.resolve("fremd"));

		assertEquals(UnitIndex.fingerprint(before), UnitIndex.fingerprint(local.readUnit(EVENT)));
		assertEquals(0, refresh(index(), local).resolved());
	}

	@Test
	void fingerprintChangesWithVersionAndClaimNames() throws IOException {
		saveTwice(EVENT, "event");
		String two = UnitIndex.fingerprint(local.readUnit(EVENT));
		write(EVENT, ME, mine(3), mine(2), "event-3", false);
		String withUnclaimed = UnitIndex.fingerprint(local.readUnit(EVENT));
		arbiter.claim(EVENT, StoreCodec.readVersion(local.readVersionBytes(EVENT, mine(3)).orElseThrow()),
				local.readVersionBytes(EVENT, mine(3)).orElseThrow());
		String withClaim = UnitIndex.fingerprint(local.readUnit(EVENT));

		assertNotEquals(two, withUnclaimed);
		assertNotEquals(withUnclaimed, withClaim);
		assertNotEquals(two, withClaim);
	}

	@Test
	void fingerprintIsSha256OfSortedLines() {
		VersionId a = mine(1);
		VersionId b = mine(2);
		String expected = StoreCodec.sha256(("v:" + a + "\nv:" + b + "\nc:" + a + "\nc:root\n").getBytes(StandardCharsets.UTF_8));

		assertEquals(expected, UnitIndex.fingerprint(List.of(b, a), List.of("root", a.value())));
		assertEquals(StoreCodec.sha256(new byte[0]), UnitIndex.fingerprint(List.of(), List.of()));
		assertNotEquals(UnitIndex.fingerprint(List.of(a), List.of()), UnitIndex.fingerprint(List.of(), List.of(a.value())));
	}

	@Test
	void fingerprintFromListingEqualsFingerprintFromReadUnit() throws IOException {
		buildThreeUnits();
		write(PRESENTATION, ME, mine(3), mine(2), "presentation-3", false);
		write(UnitKey.booking("e1", "p1", "s1"), ME, mine(1), null, "booking", true);
		write(UnitKey.unassignedBooking("e1", "p1", "b1"), ME, mine(1), null, "booking", false);
		Path unitDir = local.dataRoot().resolve("events/e1");
		Files.writeString(unitDir.resolve("Thumbs.db"), "x");
		Files.writeString(unitDir.resolve("claims").resolve("kaputt.json"), "x");
		Files.writeString(unitDir.resolve("claims").resolve("Thumbs.db"), "x");
		Files.writeString(unitDir.resolve("presentations/p1/~karv-1.tmp"), "x");
		Files.createDirectories(unitDir.resolve("presentations/p1").resolve(mine(9) + ".json"));
		Map<String, List<String>> tree = new NioFileLayer().listTree(local.dataRoot());

		List<UnitKey> units = local.allUnits();

		assertEquals(5, units.size());
		for (UnitKey unit : units) {
			assertEquals(UnitIndex.fingerprint(local.readUnit(unit)),
					UnitIndex.fingerprintFromListing(tree, unit.relativePath()), unit.relativePath());
		}
	}

	@Test
	void sameNameClaimReplacementIsResolvedAgain() throws IOException {
		// eigener Anspruch auf dem Platz hinter V1 nennt die eigene Version V2; die fremde V3 liegt schon lokal
		write(EVENT, ME, mine(1), null, "e-1", true);
		write(EVENT, ME, mine(2), mine(1), "e-2", true);
		VersionId foreign = id(3, OTHER);
		Version foreignVersion = new Version(1, foreign, List.of(mine(1)), OTHER, "Kasse", "Bea", AT, "save-" + foreign, false,
				new ObjectMapper().createObjectNode().put("name", "e-fremd"));
		byte[] foreignBytes = StoreCodec.write(foreignVersion);
		assertTrue(local.writeVersion(EVENT, foreign, foreignBytes));
		UnitIndex first = index();
		UnitIndex.Refresh before = refresh(first, local);
		first.flush();
		assertEquals(mine(2), first.entries().get(EVENT).snapshot().version());
		assertTrue(before.lostUnits().isEmpty());

		// späte Niederlage: der Anspruch wird unter gleichem Namen durch den des Siegers ersetzt (wie displace)
		byte[] winner = StoreCodec.write(new Claim(foreign, StoreCodec.sha256(foreignBytes), OTHER, AT));
		new NioFileLayer().replaceDamaged(local.dataRoot().resolve("events/e1/claims/" + mine(1) + ".json"), winner,
				mine(1) + ".json.verdraengt-20261027");

		UnitIndex second = index();
		UnitIndex.Refresh refresh = refresh(second, local);

		assertEquals(1, refresh.resolved());
		assertEquals(foreign, second.entries().get(EVENT).snapshot().version());
		assertEquals(Set.of(EVENT), refresh.lostUnits());
	}

	@Test
	void repairedVersionIsResolvedAgain() throws IOException {
		write(EVENT, ME, mine(1), null, "e-1", true);
		write(EVENT, ME, mine(2), mine(1), "e-2", true);
		byte[] good = local.readVersionBytes(EVENT, mine(2)).orElseThrow();
		Path file = local.dataRoot().resolve("events/e1/" + mine(2) + ".json");
		Files.writeString(file, "kaputt");
		UnitIndex first = index();
		refresh(first, local);
		first.flush();
		assertEquals(UnitState.Status.LOCKED_DAMAGED, first.entries().get(EVENT).snapshot().status());

		new NioFileLayer().replaceDamaged(file, good, mine(2) + ".json.corrupt-20261027");

		UnitIndex second = index();
		UnitIndex.Refresh refresh = refresh(second, local);

		assertEquals(1, refresh.resolved());
		assertEquals(UnitState.Status.OK, second.entries().get(EVENT).snapshot().status());
		assertEquals(mine(2), second.entries().get(EVENT).snapshot().version());
	}

	@Test
	void fingerprintFromListingCountsSideCopiesOfReplacedFilesOnly() {
		String v = mine(1) + ".json";
		Map<String, List<String>> plain = Map.of("events/e1", List.of(v), "events/e1/claims", List.of("root.json"));
		String base = UnitIndex.fingerprintFromListing(plain, "events/e1");

		assertNotEquals(base, UnitIndex.fingerprintFromListing(
				Map.of("events/e1", List.of(v, v + ".corrupt-1"), "events/e1/claims", List.of("root.json")), "events/e1"));
		assertNotEquals(base, UnitIndex.fingerprintFromListing(
				Map.of("events/e1", List.of(v), "events/e1/claims", List.of("root.json", "root.json.verdraengt-1")),
				"events/e1"));
		assertEquals(base, UnitIndex.fingerprintFromListing(Map.of("events/e1", List.of(v, "Thumbs.db", "x.json.corrupt-1",
				v + ".", "~karv-1.tmp", mine(1) + " (Konfliktkopie).json"), "events/e1/claims", List.of("root.json", "Thumbs.db",
						"abc.json.bak")), "events/e1"));
	}

	@Test
	void fingerprintFromListingOfMissingFolderIsFingerprintOfNothing() {
		assertEquals(UnitIndex.fingerprint(List.of(), List.of()), UnitIndex.fingerprintFromListing(Map.of(), "events/e1"));
	}

	@Test
	void progressEndsAtOne() throws IOException {
		buildThreeUnits();
		List<Double> values = new ArrayList<>();

		index().refresh(local, values::add);

		assertFalse(values.isEmpty());
		assertEquals(1.0, values.get(values.size() - 1));
		for (int i = 0; i < values.size(); i++) {
			assertTrue(values.get(i) >= 0.0 && values.get(i) <= 1.0, values.toString());
			if (i > 0) {
				assertTrue(values.get(i) >= values.get(i - 1), values.toString());
			}
		}
	}

	@Test
	void progressEndsAtOneWithoutUnits() throws IOException {
		List<Double> values = new ArrayList<>();

		UnitIndex.Refresh refresh = index().refresh(local, values::add);

		assertEquals(0, refresh.resolved());
		assertEquals(1.0, values.get(values.size() - 1));
	}

	@Test
	void flushWritesOnlyChangedFiles() throws IOException {
		saveTwice(EVENT, "event");
		saveTwice(EVENT2, "event2");
		saveTwice(HALL, "hall");
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		UnitIndex index = new UnitIndex(indexDir, layer, ME);
		refresh(index, local);
		index.flush();
		assertEquals(3, createdFiles(layer));

		index.flush();
		assertEquals(3, createdFiles(layer));

		write(EVENT2, ME, mine(3), mine(2), "event2-3", true);
		refresh(index, local);
		index.flush();
		assertEquals(4, createdFiles(layer));
		assertTrue(Files.isRegularFile(eventIndexFile("e2")));
	}

	@Test
	void failedFlushKeepsFileMarkedChanged() throws IOException {
		saveTwice(EVENT, "event");
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		UnitIndex index = new UnitIndex(indexDir, layer, ME);
		refresh(index, local);
		layer.failNext("createNew", new IOException("Platte voll"));

		assertThrows(IOException.class, index::flush);
		assertFalse(Files.exists(eventIndexFile("e1")));

		index.flush();
		assertTrue(Files.isRegularFile(eventIndexFile("e1")));
	}

	/** Ein Index auf einer Dateischicht, deren nächstes Schreiben angehalten wird, und der Thread, der {@code flush} aufruft. */
	private final class SlowWrite implements AutoCloseable {
		final UnitIndex index;
		final CountDownLatch release = new CountDownLatch(1);
		final ExecutorService executor = Executors.newCachedThreadPool();
		Future<?> flushing;

		SlowWrite() throws Exception {
			FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
			index = new UnitIndex(indexDir, layer, ME);
			refresh(index, local);
			CountDownLatch entered = new CountDownLatch(1);
			layer.blockNext("createNew", entered, release);
			flushing = executor.submit(() -> {
				index.flush();
				return null;
			});
			assertTrue(entered.await(10, TimeUnit.SECONDS), "flush hat nicht zu schreiben begonnen");
		}

		/** Führt {@code call} auf einem anderen Thread aus und liefert das Ergebnis; wartet er auf das Schreiben, scheitert der Test. */
		<T> T meanwhile(Callable<T> call) throws Exception {
			try {
				return executor.submit(call).get(2, TimeUnit.SECONDS);
			} catch (TimeoutException e) {
				throw new AssertionError("der Aufruf wartet auf das Schreiben des Index", e);
			}
		}

		void finish() throws Exception {
			release.countDown();
			flushing.get(10, TimeUnit.SECONDS);
		}

		@Override
		public void close() {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void snapshotsDoNotWaitForIndexWrite() throws Exception {
		saveTwice(EVENT, "event");
		try (SlowWrite write = new SlowWrite()) {
			Map<UnitKey, UnitSnapshot> snapshots = write.meanwhile(() -> write.index.snapshots(List.of(EVENT)));

			assertEquals("event-2", snapshots.get(EVENT).payload().get("name").asText());
			assertEquals(Set.of(EVENT), write.meanwhile(() -> write.index.chains(List.of(EVENT))).keySet());
			assertEquals(Set.of(EVENT), write.meanwhile(() -> write.index.eventSnapshots("e1")).keySet());
			assertFalse(Files.exists(eventIndexFile("e1")), "es wurde noch nichts geschrieben");

			write.finish();
			assertTrue(Files.isRegularFile(eventIndexFile("e1")));
			assertFalse(write.index.hasChanges());
		}
	}

	@Test
	void updateDuringIndexWriteStaysMarkedAsChanged() throws Exception {
		saveTwice(EVENT, "event");
		try (SlowWrite write = new SlowWrite()) {
			write.meanwhile(() -> addThirdVersion(write));

			write.finish();

			assertTrue(write.index.hasChanges(), "der Stand während des Schreibens steht noch nicht in der Datei");
			write.index.flush();
			assertFalse(write.index.hasChanges());
			UnitIndex reopened = index();
			assertEquals(0, refresh(reopened, local).resolved());
			assertEquals("event-3", name(reopened.entries().get(EVENT)));
		}
	}

	@Test
	void cleanMarkerIsNotWrittenWhileTheIndexIsBeingWritten() throws Exception {
		saveTwice(EVENT, "event");
		try (SlowWrite write = new SlowWrite()) {
			assertFalse(write.meanwhile(write.index::writeCleanMarker));

			write.finish();

			assertTrue(write.index.writeCleanMarker());
		}
	}

	@Test
	void secondFlushWaitsForTheRunningOne() throws Exception {
		saveTwice(EVENT, "event");
		try (SlowWrite write = new SlowWrite()) {
			Future<?> second = write.executor.submit(() -> {
				write.index.flush();
				return null;
			});
			assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));

			write.finish();
			second.get(10, TimeUnit.SECONDS);

			assertFalse(write.index.hasChanges());
			assertTrue(Files.isRegularFile(eventIndexFile("e1")));
		}
	}

	/** Schreibt eine dritte Version der Einheit {@link #EVENT} (namens {@code event-3}) und trägt sie in den Index der Probe ein. */
	private Void addThirdVersion(SlowWrite write) throws IOException {
		write(EVENT, ME, mine(3), mine(2), "event-3", true);
		UnitFiles files = local.readUnit(EVENT);
		write.index.update(files, ChainResolver.resolve(files, ME));
		return null;
	}

	@Test
	void flushLeavesNoTemporaryFilesBehind() throws IOException {
		buildThreeUnits();
		UnitIndex index = index();
		refresh(index, local);
		index.flush();

		try (var stream = Files.walk(indexDir)) {
			assertTrue(stream.filter(Files::isRegularFile).allMatch(p -> p.getFileName().toString().endsWith(".json")));
		}
	}

	@Test
	void updateReplacesEntryAndIsFlushed() throws IOException {
		saveTwice(EVENT, "event");
		UnitIndex index = index();
		refresh(index, local);
		index.flush();
		write(EVENT, ME, mine(3), mine(2), "event-3", false);
		UnitFiles files = local.readUnit(EVENT);
		UnitState state = ChainResolver.resolve(files, ME);

		index.update(files, state);

		assertEquals("event-3", name(index.entries().get(EVENT)));
		assertTrue(index.entries().get(EVENT).pending());
		index.flush();
		UnitIndex reopened = index();
		UnitIndex.Refresh refresh = refresh(reopened, local);
		assertEquals(0, refresh.resolved());
		assertEquals(Set.of(EVENT), refresh.pendingUnits());
	}

	@Test
	void clearDropsEntriesAndRewritesGroups() throws IOException {
		buildThreeUnits();
		UnitIndex index = index();
		refresh(index, local);
		index.flush();
		assertFalse(index.hasChanges());

		index.clear();

		assertTrue(index.entries().isEmpty());
		assertTrue(index.hasChanges());
		index.flush();
		assertFalse(index.hasChanges());
		for (Path file : List.of(eventIndexFile("e1"), indexDir.resolve("halls.json"))) {
			assertEquals(0, new ObjectMapper().readTree(Files.readAllBytes(file)).get("units").size(), file.toString());
		}
		// der alte Inhalt kommt nicht zurück: weder in dieser Instanz noch in einer neuen
		UnitIndex.Refresh again = refresh(index, local);
		assertEquals(3, again.resolved());
		index.flush();
		assertEquals(0, refresh(index(), local).resolved());
		// auch eine Instanz, die den Index noch nie gelesen hat, leert die Dateien
		UnitIndex fresh = index();
		fresh.clear();
		fresh.flush();
		assertEquals(3, refresh(index(), local).resolved());
	}

	@Test
	void entriesCopyIsUnmodifiable() throws IOException {
		saveTwice(EVENT, "event");
		UnitIndex index = index();
		refresh(index, local);

		assertThrows(UnsupportedOperationException.class, () -> index.entries().clear());
	}

	@Test
	void snapshotOfStateWithoutCurrentVersionHasNoVersionAndPayload() throws IOException {
		UnitFiles files = local.readUnit(EVENT);

		UnitSnapshot snapshot = UnitSnapshot.of(ChainResolver.resolve(files, ME));

		assertEquals(EVENT, snapshot.unit());
		assertNull(snapshot.version());
		assertNull(snapshot.payload());
		assertFalse(snapshot.deleted());
		assertEquals(UnitState.Status.OK, snapshot.status());
	}

	@Test
	void snapshotOfDeletedVersionIsMarkedDeleted() throws IOException {
		Version deleted = new Version(1, mine(1), List.of(), ME, "Kasse", "Anna", AT, "save-1", true,
				new ObjectMapper().createObjectNode());
		byte[] bytes = StoreCodec.write(deleted);
		local.writeVersion(EVENT, mine(1), bytes);
		arbiter.claim(EVENT, deleted, bytes);

		UnitSnapshot snapshot = UnitSnapshot.of(ChainResolver.resolve(local.readUnit(EVENT), ME));

		assertTrue(snapshot.deleted());
		assertEquals(mine(1), snapshot.version());
		assertEquals(0, snapshot.payload().size());
	}

	// --- Marke "sauber beendet"

	private Path cleanMarker() {
		return indexDir.resolve("clean");
	}

	/** Ein geschriebener Index samt Marke, wie ihn das saubere Beenden hinterlässt. */
	private void buildCleanIndex() throws IOException {
		UnitIndex first = index();
		refresh(first, local);
		first.flush();
		assertTrue(first.writeCleanMarker());
	}

	@Test
	void cleanMarkerIsDeletedWhenRead() throws IOException {
		buildThreeUnits();
		write(PRESENTATION, ME, mine(3), mine(2), "presentation-3", false);
		buildCleanIndex();
		assertEquals(ME, Files.readString(cleanMarker(), StandardCharsets.UTF_8));

		UnitIndex second = index();
		assertTrue(second.loadIfClean());

		assertFalse(Files.exists(cleanMarker()));
		assertEquals(Set.of(EVENT, PRESENTATION, HALL), second.entries().keySet());
		assertEquals("presentation-3", name(second.entries().get(PRESENTATION)));
		UnitIndex.Refresh refresh = second.cleanRefresh();
		assertEquals(0, refresh.resolved());
		assertEquals(Set.of(PRESENTATION), refresh.pendingUnits());
		assertTrue(refresh.lostUnits().isEmpty());
		// die Marke gilt nur für diesen einen Start
		assertFalse(index().loadIfClean());
	}

	@Test
	void cleanLoadWalksNoTree() throws IOException {
		buildThreeUnits();
		buildCleanIndex();
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());

		assertTrue(new UnitIndex(indexDir, layer, ME).loadIfClean());

		assertTrue(layer.calls().stream().noneMatch(c -> c.startsWith("listTree ")), layer.calls().toString());
	}

	@Test
	void withoutMarkerNothingIsLoaded() throws IOException {
		buildThreeUnits();
		UnitIndex first = index();
		refresh(first, local);
		first.flush();

		UnitIndex second = index();

		assertFalse(second.loadIfClean());
		assertTrue(second.entries().isEmpty());
		assertEquals(0, refresh(second, local).resolved());
	}

	@Test
	void cleanMarkerOfOtherMachineIsIgnored() throws IOException {
		buildThreeUnits();
		buildCleanIndex();
		Files.writeString(cleanMarker(), OTHER, StandardCharsets.UTF_8);

		UnitIndex second = index();

		assertFalse(second.loadIfClean());
		assertFalse(Files.exists(cleanMarker()));
		assertTrue(second.entries().isEmpty());
		assertEquals(0, refresh(second, local).resolved());
	}

	@Test
	void unreadableCleanMarkerIsIgnoredAndDeleted() throws IOException {
		buildThreeUnits();
		buildCleanIndex();
		Files.write(cleanMarker(), new byte[] { (byte) 0xC3, (byte) 0x28 });

		assertFalse(index().loadIfClean());
		assertFalse(Files.exists(cleanMarker()));
	}

	@Test
	void cleanLoadFallsBackWhenAnIndexFileIsUnreadable() throws IOException {
		saveTwice(EVENT, "event");
		saveTwice(PRESENTATION, "presentation");
		saveTwice(HALL, "hall");
		buildCleanIndex();
		Files.writeString(eventIndexFile("e1"), "{");

		UnitIndex second = index();

		assertFalse(second.loadIfClean());
		assertFalse(Files.exists(cleanMarker()));
		// auch der gut gelesene Teil (die Hallen) wird nicht übernommen
		assertTrue(second.entries().isEmpty());
		UnitIndex.Refresh refresh = refresh(second, local);
		assertEquals(2, refresh.resolved());
		assertEquals(Set.of(EVENT, PRESENTATION, HALL), second.entries().keySet());
	}

	@Test
	void cleanLoadFallsBackWhenAnIndexFileIsOfOtherMachine() throws IOException {
		buildThreeUnits();
		buildCleanIndex();
		Files.writeString(indexDir.resolve("halls.json"), "{\"indexFormat\":3,\"machine\":\"" + OTHER + "\",\"units\":[]}");

		assertFalse(index().loadIfClean());
	}

	@Test
	void cleanMarkerIsNotWrittenWithUnflushedChanges() throws IOException {
		buildThreeUnits();
		UnitIndex index = index();
		refresh(index, local);

		assertFalse(index.writeCleanMarker());
		assertFalse(Files.exists(cleanMarker()));

		index.flush();
		assertTrue(index.writeCleanMarker());
		assertTrue(Files.isRegularFile(cleanMarker()));
	}

	@Test
	void changeAfterMarkerDeletesItBeforeTheStoreIsWritten() throws IOException {
		saveTwice(EVENT, "event");
		UnitIndex index = index();
		refresh(index, local);
		index.flush();
		assertTrue(index.writeCleanMarker());

		// zuerst die Marke, dann der Bestand, dann der Index
		index.invalidateCleanMarker();
		assertFalse(Files.exists(cleanMarker()));
		write(EVENT, ME, mine(3), mine(2), "event-3", false);
		UnitFiles files = local.readUnit(EVENT);
		index.update(files, ChainResolver.resolve(files, ME));

		assertFalse(Files.exists(cleanMarker()));
		assertFalse(index().loadIfClean());
	}

	@Test
	void invalidateWithoutMarkerDoesNothing() throws IOException {
		saveTwice(EVENT, "event");
		UnitIndex index = index();
		refresh(index, local);
		index.flush();

		index.invalidateCleanMarker();

		assertFalse(Files.exists(cleanMarker()));
		assertFalse(index.hasChanges());
	}

	@Test
	void updateAfterMarkerDeletesIt() throws IOException {
		saveTwice(EVENT, "event");
		UnitIndex index = index();
		refresh(index, local);
		index.flush();
		assertTrue(index.writeCleanMarker());
		write(EVENT, ME, mine(3), mine(2), "event-3", false);
		UnitFiles files = local.readUnit(EVENT);

		index.update(files, ChainResolver.resolve(files, ME));

		assertFalse(Files.exists(cleanMarker()));
		assertFalse(index().loadIfClean());
	}

	@Test
	void failedMarkerDeleteInvalidatesMarker() throws IOException {
		saveTwice(EVENT, "event");
		UnitIndex index = index();
		refresh(index, local);
		index.flush();
		assertTrue(index.writeCleanMarker());
		index.useDeleter(file -> {
			throw new IOException("Datei gesperrt");
		});

		index.invalidateCleanMarker();

		// gelöscht ging nicht: die Datei ist leer und gilt beim Start als unbrauchbar
		assertTrue(Files.isRegularFile(cleanMarker()));
		assertEquals(0, Files.size(cleanMarker()));
		UnitIndex next = index();
		assertFalse(next.loadIfClean());
		assertTrue(next.entries().isEmpty());
	}

	@Test
	void failedMarkerDeleteInCleanLoadEmptiesMarker() throws IOException {
		buildThreeUnits();
		buildCleanIndex();
		UnitIndex second = index();
		second.useDeleter(file -> {
			throw new IOException("Datei gesperrt");
		});

		assertFalse(second.loadIfClean());

		// gelöscht ging nicht: die Marke ist leer und gilt für keinen späteren Start
		assertTrue(Files.isRegularFile(cleanMarker()));
		assertEquals(0, Files.size(cleanMarker()));
		UnitIndex next = index();
		assertFalse(next.loadIfClean());
		assertTrue(next.entries().isEmpty());
	}

	@Test
	void markerThatCannotBeRemovedInCleanLoadIsRetriedByInvalidate() throws IOException {
		buildThreeUnits();
		buildCleanIndex();
		Path marker = cleanMarker();
		assertTrue(marker.toFile().setWritable(false));
		assumeFalse(Files.isWritable(marker), "Schreibschutz greift auf diesem System nicht");
		try {
			UnitIndex second = index();
			second.useDeleter(file -> {
				throw new IOException("Datei gesperrt");
			});
			assertFalse(second.loadIfClean());
			// weder Löschen noch Leeren ging: die Marke steht noch, und mit ihr der Inhalt
			assertEquals(ME, Files.readString(marker, StandardCharsets.UTF_8));

			// jede spätere Änderung am Bestand versucht es erneut
			assertTrue(marker.toFile().setWritable(true));
			second.useDeleter(Files::deleteIfExists);
			second.invalidateCleanMarker();

			assertFalse(Files.exists(marker));
		} finally {
			marker.toFile().setWritable(true);
		}
	}

	@Test
	void refreshDeletesLeftoverTempFilesOfTheIndex() throws IOException {
		buildThreeUnits();
		buildCleanIndex();
		Path leftOver = indexDir.resolve("~karv-index-" + java.util.UUID.randomUUID() + ".tmp");
		Path leftOverEvents = indexDir.resolve("events").resolve("~karv-index-" + java.util.UUID.randomUUID() + ".tmp");
		Path foreign = indexDir.resolve("Thumbs.db");
		Files.writeString(leftOver, "halb");
		Files.writeString(leftOverEvents, "halb");
		Files.writeString(foreign, "bleibt");

		refresh(index(), local);

		assertFalse(Files.exists(leftOver));
		assertFalse(Files.exists(leftOverEvents));
		assertTrue(Files.exists(foreign));
	}

	@Test
	void cleanLoadDeletesLeftoverTempFilesOfTheIndex() throws IOException {
		buildThreeUnits();
		buildCleanIndex();
		Path leftOver = indexDir.resolve("events").resolve("~karv-index-" + java.util.UUID.randomUUID() + ".tmp");
		Files.writeString(leftOver, "halb");

		assertTrue(index().loadIfClean());

		assertFalse(Files.exists(leftOver));
	}

	@Test
	void clearDeletesCleanMarker() throws IOException {
		buildThreeUnits();
		UnitIndex index = index();
		refresh(index, local);
		index.flush();
		assertTrue(index.writeCleanMarker());

		index.clear();

		assertFalse(Files.exists(cleanMarker()));
	}

	// --- Format 3: aufgegangene Versionen, Vermerke, seen, Listennummern

	private static VersionId foreign(int n) {
		return id(n, OTHER);
	}

	/** Schreibt eine Version mit beliebig vielen Vorgängern, optional Vermerk, Löschmarke und seen, und, wenn verlangt, ihren Anspruch. */
	private Version put(UnitKey unit, String machine, VersionId id, List<VersionId> parents, boolean claim,
			ConflictNote note, boolean deleted, Map<String, Long> seen) throws IOException {
		ObjectNode payload = deleted ? new ObjectMapper().createObjectNode()
				: new ObjectMapper().createObjectNode().put("name", "n-" + id);
		Version version = new Version(1, id, parents, machine, "Kasse", "Anna", AT, "save-" + id, deleted, payload, note, seen);
		byte[] bytes = StoreCodec.write(version);
		assertTrue(local.writeVersion(unit, id, bytes));
		if (claim) {
			assertTrue(arbiter.claim(unit, version, bytes).won());
		}
		return version;
	}

	private Version put(UnitKey unit, String machine, VersionId id, List<VersionId> parents, boolean claim) throws IOException {
		return put(unit, machine, id, parents, claim, null, false, Map.of());
	}

	private static ConflictNote note(ConflictNote.Kind kind) {
		return new ConflictNote(kind, ME, new ConflictNote.Party(OTHER, "Kasse 2", "Bert", foreign(9)), List.of(), null, null, null);
	}

	/** Löst die Einheit aus den Dateien auf und trägt sie ein, wie es der Dienst nach jeder Änderung tut. */
	private UnitState resolveInto(UnitIndex index, UnitKey unit) throws IOException {
		UnitFiles files = local.readUnit(unit);
		UnitState state = ChainResolver.resolve(files, ME);
		index.update(files, state);
		return state;
	}

	private static FeedEntry feedEntry(UnitKey unit, VersionId version) {
		return new FeedEntry(unit.relativePath(), version, "root");
	}

	@Test
	void absorbedIncludesMergedVersionsTransitively() throws IOException {
		VersionId w = foreign(1);
		VersionId b = foreign(2);
		VersionId l = foreign(3);
		VersionId c = foreign(4);
		VersionId m2 = foreign(5);
		put(EVENT, OTHER, w, List.of(), true);
		put(EVENT, OTHER, b, List.of(w), true);
		put(EVENT, OTHER, l, List.of(w), false);
		put(EVENT, OTHER, c, List.of(w, l), false);
		put(EVENT, OTHER, m2, List.of(b, c), true);
		UnitIndex index = index();

		refresh(index, local);

		assertEquals(List.of(w, b, m2), index.entries().get(EVENT).chain().stream().map(ChainStep::version).toList());
		assertEquals(Map.of(EVENT, Set.of(c, l)), index.absorbed(List.of(EVENT, HALL)));
		assertEquals(Set.of(c, l), index.entries().get(EVENT).absorbed());
	}

	@Test
	void absorbedFollowsOwnPendingMerge() throws IOException {
		VersionId w = foreign(1);
		VersionId l = foreign(2);
		put(EVENT, OTHER, w, List.of(), true);
		put(EVENT, OTHER, l, List.of(w), false);
		put(EVENT, ME, mine(3), List.of(w, l), false);
		UnitIndex index = index();

		refresh(index, local);

		UnitIndex.Entry entry = index.entries().get(EVENT);
		assertTrue(entry.pending());
		assertEquals(mine(3), entry.snapshot().version());
		assertEquals(Set.of(l), entry.absorbed());
	}

	@Test
	void notesOfChainAbsorbedAndPendingAreKept() throws IOException {
		VersionId w = foreign(1);
		VersionId x = foreign(2);
		VersionId l = foreign(3);
		VersionId u = foreign(4);
		ConflictNote chainNote = note(ConflictNote.Kind.FIELDS);
		ConflictNote absorbedNote = note(ConflictNote.Kind.DOUBLE_SALE);
		ConflictNote pendingNote = note(ConflictNote.Kind.DELETE_WINS);
		put(EVENT, OTHER, w, List.of(), true);
		put(EVENT, OTHER, x, List.of(w), true, chainNote, false, Map.of());
		put(EVENT, OTHER, l, List.of(w), false, absorbedNote, false, Map.of());
		// eine fremde, nicht aufgegangene Version mit Vermerk zählt nicht
		put(EVENT, OTHER, u, List.of(w), false, note(ConflictNote.Kind.UNMERGED), false, Map.of());
		put(EVENT, ME, mine(5), List.of(x, l), false, pendingNote, false, Map.of());
		UnitIndex index = index();

		refresh(index, local);

		UnitIndex.Entry entry = index.entries().get(EVENT);
		assertEquals(Set.of(x, l, mine(5)), entry.notes().stream().map(UnitIndex.NotedVersion::version).collect(java.util.stream.Collectors.toSet()));
		UnitIndex.NotedVersion pending = entry.notes().stream().filter(n -> n.version().equals(mine(5))).findFirst().orElseThrow();
		assertEquals(new UnitIndex.NotedVersion(mine(5), ME, "Kasse", "Anna", AT, "save-" + mine(5), List.of(x, l), pendingNote), pending);
		assertEquals(List.of(entry), withNotesOrSeen(index));

		// die Vermerke überstehen Schreiben und Lesen des Index
		index.flush();
		UnitIndex second = index();
		assertEquals(0, refresh(second, local).resolved());
		assertEquals(entry, second.entries().get(EVENT));
	}

	@Test
	void entriesWithoutNotesOrSeenAreNotListed() throws IOException {
		buildThreeUnits();
		UnitIndex index = index();

		refresh(index, local);

		assertTrue(withNotesOrSeen(index).isEmpty());
	}

	/** Die Einträge des Index, die Vermerke oder {@code seen} haben. */
	private static List<UnitIndex.Entry> withNotesOrSeen(UnitIndex index) {
		return index.entries().values().stream().filter(entry -> !entry.notes().isEmpty() || !entry.seen().isEmpty())
				.toList();
	}

	@Test
	void seenOfCurrentDeletionIsKeptForEventAndPresentationOnly() throws IOException {
		UnitKey booking = UnitKey.booking("e1", "p1", "A-1");
		Map<String, Long> seen = Map.of(OTHER, 3L);
		put(PRESENTATION, ME, mine(1), List.of(), true);
		put(PRESENTATION, ME, mine(2), List.of(mine(1)), true, null, true, seen);
		put(EVENT, ME, mine(1), List.of(), true);
		put(booking, ME, mine(1), List.of(), true);
		put(booking, ME, mine(2), List.of(mine(1)), true, null, true, seen);
		put(HALL, ME, mine(1), List.of(), true);
		UnitIndex index = index();

		refresh(index, local);

		assertEquals(seen, index.entries().get(PRESENTATION).seen());
		assertEquals(Map.of(), index.entries().get(booking).seen());
		assertEquals(Map.of(), index.entries().get(EVENT).seen());
		assertEquals(List.of(index.entries().get(PRESENTATION)), withNotesOrSeen(index));
		index.flush();
		UnitIndex second = index();
		refresh(second, local);
		assertEquals(seen, second.entries().get(PRESENTATION).seen());
	}

	@Test
	void format2IndexIsRebuilt() throws IOException {
		buildThreeUnits();
		UnitIndex first = index();
		refresh(first, local);
		first.flush();
		Path halls = indexDir.resolve("halls.json");
		Files.writeString(halls, Files.readString(halls).replace("\"indexFormat\":3", "\"indexFormat\":2"));
		Path event = eventIndexFile("e1");
		Files.writeString(event, Files.readString(event).replace("\"indexFormat\":3", "\"indexFormat\":2"));

		UnitIndex second = index();
		UnitIndex.Refresh refresh = refresh(second, local);

		assertEquals(3, refresh.resolved());
		assertNull(second.entries().get(EVENT).ownSeq());
		assertEquals(first.entries(), second.entries());
	}

	@Test
	void ownSeqAndListedSurviveUpdate() throws IOException {
		VersionId w = foreign(1);
		put(EVENT, OTHER, w, List.of(), true);
		put(EVENT, ME, mine(2), List.of(w), false);
		UnitIndex index = index();
		refresh(index, local);
		assertNull(index.entries().get(EVENT).ownSeq());
		assertFalse(index.isListed(EVENT, w));

		index.markOwnListed(List.of(feedEntry(EVENT, mine(2))), 7);
		index.markListed(List.of(feedEntry(EVENT, w)));

		assertEquals(7L, index.entries().get(EVENT).ownSeq());
		assertEquals(Set.of(w), index.entries().get(EVENT).listed());
		assertTrue(index.isListed(EVENT, w));
		assertFalse(index.isListed(EVENT, mine(2)));
		assertFalse(index.isListed(HALL, w));

		// die Version wird beansprucht, die Einheit neu aufgelöst: beide Angaben bleiben
		UnitFiles files = local.readUnit(EVENT);
		Version own = files.versions().get(mine(2)).version();
		assertTrue(arbiter.claim(EVENT, own, files.versions().get(mine(2)).bytes()).won());
		resolveInto(index, EVENT);
		assertEquals(7L, index.entries().get(EVENT).ownSeq());
		assertEquals(Set.of(w), index.entries().get(EVENT).listed());

		// eine "keine Liste" ersetzt keine Nummer
		index.markOwnListed(List.of(feedEntry(EVENT, mine(2))), 0);
		assertEquals(7L, index.entries().get(EVENT).ownSeq());

		// eine neue eigene Version: ihre Nummer ist unbekannt, die gelesenen fremden Versionen bleiben
		put(EVENT, ME, mine(3), List.of(mine(2)), false);
		resolveInto(index, EVENT);
		assertNull(index.entries().get(EVENT).ownSeq());
		assertEquals(Set.of(w), index.entries().get(EVENT).listed());
		index.markOwnListed(List.of(feedEntry(EVENT, mine(3))), 0);
		assertEquals(0L, index.entries().get(EVENT).ownSeq());

		// beides steht in der Datei des Index
		index.markOwnListed(List.of(feedEntry(EVENT, mine(3))), 9);
		assertEquals(9L, index.entries().get(EVENT).ownSeq());
		index.flush();
		UnitIndex second = index();
		refresh(second, local);
		assertEquals(index.entries().get(EVENT), second.entries().get(EVENT));
	}

	@Test
	void markOwnListedIgnoresVersionsThatAreNotCurrent() throws IOException {
		saveTwice(EVENT, "event");
		UnitIndex index = index();
		refresh(index, local);

		index.markOwnListed(List.of(feedEntry(EVENT, mine(1)), feedEntry(UnitKey.event("fehlt"), mine(2))), 4);

		assertNull(index.entries().get(EVENT).ownSeq());
		index.markOwnListed(List.of(feedEntry(EVENT, mine(2))), 5);
		assertEquals(5L, index.entries().get(EVENT).ownSeq());
	}

	@Test
	void markUnknownOwnAsUnlistedOnlyTouchesOwnCurrentVersionsWithoutNumber() throws IOException {
		saveTwice(EVENT, "event");
		saveTwice(PRESENTATION, "presentation");
		put(HALL, OTHER, foreign(1), List.of(), true);
		UnitIndex index = index();
		refresh(index, local);
		index.markOwnListed(List.of(feedEntry(PRESENTATION, mine(2))), 4);

		// geprüft wurden die Stände zu einem früheren Zeitpunkt: für den Event-Kopf war es noch die Vorversion
		index.markUnknownOwnAsUnlisted(Map.of(EVENT, mine(1), PRESENTATION, mine(2), HALL, foreign(1)));
		assertNull(index.entries().get(EVENT).ownSeq(), "die geprüfte Version ist nicht mehr die aktuelle: keine falsche 0");
		index.markUnknownOwnAsUnlisted(Map.of(EVENT, mine(2), PRESENTATION, mine(2), HALL, foreign(1)));

		assertEquals(0L, index.entries().get(EVENT).ownSeq());
		assertEquals(4L, index.entries().get(PRESENTATION).ownSeq(), "eine bekannte Nummer bleibt");
		assertNull(index.entries().get(HALL).ownSeq(), "ein fremder Stand hat keine eigene Nummer");
		index.markOwnListed(List.of(feedEntry(EVENT, mine(2))), 6);
		assertEquals(6L, index.entries().get(EVENT).ownSeq(), "eine Nummer ab 1 ersetzt 'in keiner Liste'");
	}

	@Test
	void markListedBeforeTheEntryExistsIsAppliedByTheFirstUpdate() throws IOException {
		VersionId w = foreign(1);
		VersionId unknown = foreign(2);
		put(EVENT, OTHER, w, List.of(), true);
		UnitIndex index = index();

		index.markListed(List.of(feedEntry(EVENT, w), feedEntry(EVENT, unknown)));
		assertTrue(index.isListed(EVENT, w));
		resolveInto(index, EVENT);

		assertEquals(Set.of(w), index.entries().get(EVENT).listed(), "nur Versionen der Einheit werden gehalten");
		assertTrue(index.isListed(EVENT, w));
		assertFalse(index.isListed(EVENT, unknown));
	}

	@Test
	void markingChangesTheIndexAndInvalidatesTheCleanMarker() throws IOException {
		VersionId w = foreign(1);
		put(EVENT, OTHER, w, List.of(), true);
		UnitIndex index = index();
		refresh(index, local);
		index.flush();
		assertTrue(index.writeCleanMarker());

		index.markListed(List.of(feedEntry(EVENT, w)));

		assertTrue(index.hasChanges());
		assertFalse(Files.exists(cleanMarker()));
	}

	// --- R13: Listennummern über einen Absturz und bei Wiederholung

	@Test
	void persistedZeroIsUnknownAfterNonCleanStartButSurvivesCleanStart() throws IOException {
		put(EVENT, ME, mine(1), List.of(), false);
		UnitIndex first = index();
		refresh(first, local);
		first.markOwnListed(List.of(feedEntry(EVENT, mine(1))), 0);
		assertEquals(0L, first.entries().get(EVENT).ownSeq());
		first.flush();
		// die Liste wurde geschrieben und der Anspruch angelegt, danach brach der Prozess ab: kein Flush, keine Marke
		UnitFiles files = local.readUnit(EVENT);
		assertTrue(arbiter.claim(EVENT, files.versions().get(mine(1)).version(), files.versions().get(mine(1)).bytes()).won());

		UnitIndex restarted = index();
		refresh(restarted, local);

		assertNull(restarted.entries().get(EVENT).ownSeq(), "0 ist nach einem unsauberen Start nicht mehr gesichert");
		restarted.flush();
		assertTrue(restarted.writeCleanMarker());
		UnitIndex clean = index();
		assertTrue(clean.loadIfClean());
		assertNull(clean.entries().get(EVENT).ownSeq(), "die Datei wurde ohne die 0 neu geschrieben");
	}

	@Test
	void persistedNumberSurvivesNonCleanStartAndCleanStartKeepsZero() throws IOException {
		put(EVENT, ME, mine(1), List.of(), false);
		put(HALL, ME, mine(1), List.of(), false);
		UnitIndex first = index();
		refresh(first, local);
		first.markOwnListed(List.of(feedEntry(EVENT, mine(1))), 4);
		first.markOwnListed(List.of(feedEntry(HALL, mine(1))), 0);
		first.flush();
		assertTrue(first.writeCleanMarker());

		UnitIndex clean = index();
		assertTrue(clean.loadIfClean());
		assertEquals(0L, clean.entries().get(HALL).ownSeq());
		assertEquals(4L, clean.entries().get(EVENT).ownSeq());

		UnitIndex dirty = index();
		refresh(dirty, local);
		assertEquals(4L, dirty.entries().get(EVENT).ownSeq());
		assertNull(dirty.entries().get(HALL).ownSeq());
	}

	@Test
	void markOwnListedKeepsTheSmallestKnownNumber() throws IOException {
		put(EVENT, ME, mine(1), List.of(), false);
		UnitIndex index = index();
		refresh(index, local);

		index.markOwnListed(List.of(feedEntry(EVENT, mine(1))), 3);
		index.markOwnListed(List.of(feedEntry(EVENT, mine(1))), 4);
		assertEquals(3L, index.entries().get(EVENT).ownSeq());
		index.markOwnListed(List.of(feedEntry(EVENT, mine(1))), 2);
		assertEquals(2L, index.entries().get(EVENT).ownSeq());
		index.markOwnListed(List.of(feedEntry(EVENT, mine(1))), 0);
		assertEquals(2L, index.entries().get(EVENT).ownSeq());
	}

	@Test
	void markOwnListedBeforeTheEntryExistsSurvivesTheLaterUpdate() throws IOException {
		put(EVENT, ME, mine(1), List.of(), false);
		UnitIndex index = index();

		index.markOwnListed(List.of(feedEntry(EVENT, mine(1))), 5);
		assertNull(index.entries().get(EVENT));
		resolveInto(index, EVENT);
		assertEquals(5L, index.entries().get(EVENT).ownSeq());
		index.markOwnListed(List.of(feedEntry(EVENT, mine(1))), 0);
		assertEquals(5L, index.entries().get(EVENT).ownSeq());
	}

	@Test
	void markOwnListedForANewerVersionSurvivesTheLaterUpdate() throws IOException {
		put(EVENT, ME, mine(1), List.of(), false);
		UnitIndex index = index();
		refresh(index, local);
		// die Liste ist hochgeladen, bevor der Index die neue Version kennt
		put(EVENT, ME, mine(2), List.of(mine(1)), false);
		index.markOwnListed(List.of(feedEntry(EVENT, mine(1)), feedEntry(EVENT, mine(2))), 6);
		assertEquals(6L, index.entries().get(EVENT).ownSeq(), "die alte aktuelle Version steht in der Liste");

		resolveInto(index, EVENT);

		assertEquals(6L, index.entries().get(EVENT).ownSeq(), "die neue Version ebenfalls: vorgemerkt und übernommen");
		assertEquals(mine(2), index.entries().get(EVENT).snapshot().version());
		index.markOwnListed(List.of(feedEntry(EVENT, mine(2))), 0);
		assertEquals(6L, index.entries().get(EVENT).ownSeq());
	}

	private static long createdFiles(FaultyFileLayer layer) {
		return layer.calls().stream().filter(c -> c.startsWith("createNew ")).count();
	}
}
