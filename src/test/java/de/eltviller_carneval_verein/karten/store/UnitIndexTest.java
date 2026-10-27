package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		assertEquals(1, root.get("indexFormat").asInt());
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
		Files.writeString(indexDir.resolve("halls.json"), "{\"indexFormat\":2,\"machine\":\"" + ME + "\",\"units\":[]}");

		UnitIndex.Refresh refresh = refresh(index(), local);

		assertEquals(1, refresh.resolved());
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

	private static long createdFiles(FaultyFileLayer layer) {
		return layer.calls().stream().filter(c -> c.startsWith("createNew ")).count();
	}
}
