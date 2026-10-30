package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class VersionStoreTest {

	private static final Instant AT = Instant.parse("2026-10-26T10:00:00Z");

	private static final VersionId V1 = VersionId.create(1_000_000L, "aaaaaaaa", 0);
	private static final VersionId V2 = VersionId.create(2_000_000L, "bbbbbbbb", 0);
	private static final VersionId V3 = VersionId.create(3_000_000L, "cccccccc", 0);

	@TempDir
	Path dir;

	private VersionStore store() {
		return new VersionStore(dir.resolve("data"), new NioFileLayer());
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static Version version(VersionId id, VersionId... parents) {
		return new Version(1, id, List.of(parents), "maschine-1", "Kasse 1", "Anna", AT, "save-" + id, false,
				new ObjectMapper().createObjectNode().put("name", "Sitzung"));
	}

	private static byte[] versionBytes(VersionId id, VersionId... parents) throws IOException {
		return StoreCodec.write(version(id, parents));
	}

	private static byte[] claimBytes(VersionId id, byte[] versionBytes) throws IOException {
		return StoreCodec.write(new Claim(id, StoreCodec.sha256(versionBytes), "maschine-1", AT));
	}

	private static void put(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, bytes(content));
	}

	@Test
	void versionIdOfAcceptsOnlyVersionFileNames() {
		assertEquals(Optional.of(V1), VersionStore.versionIdOf(V1 + ".json"));
		assertEquals(Optional.empty(), VersionStore.versionIdOf("Thumbs.db"));
		assertEquals(Optional.empty(), VersionStore.versionIdOf(V1 + " (Konflikt).json"));
		assertEquals(Optional.empty(), VersionStore.versionIdOf(V1.value().toUpperCase() + ".json"));
		assertEquals(Optional.empty(), VersionStore.versionIdOf(V1 + ".tmp"));
		assertEquals(Optional.empty(), VersionStore.versionIdOf(V1.value()));
		assertEquals(Optional.empty(), VersionStore.versionIdOf(".json"));
		assertEquals(Optional.empty(), VersionStore.versionIdOf("root.json"));
	}

	@Test
	void claimNameOfAcceptsRootAndVersionIds() {
		assertEquals(Optional.of("root"), VersionStore.claimNameOf("root.json"));
		assertEquals(Optional.of(V2.value()), VersionStore.claimNameOf(V2 + ".json"));
		assertEquals(Optional.empty(), VersionStore.claimNameOf("Root.json"));
		assertEquals(Optional.empty(), VersionStore.claimNameOf("root"));
		assertEquals(Optional.empty(), VersionStore.claimNameOf("root.tmp"));
		assertEquals(Optional.empty(), VersionStore.claimNameOf("Thumbs.db"));
		assertEquals(Optional.empty(), VersionStore.claimNameOf(V2 + " (Konflikt).json"));
		assertEquals(Optional.empty(), VersionStore.claimNameOf(V2.value().toUpperCase() + ".json"));
		assertEquals(Optional.empty(), VersionStore.claimNameOf(".json"));
	}

	@Test
	void writeAndReadUnit() throws IOException {
		VersionStore store = store();
		UnitKey unit = UnitKey.event("e1");
		byte[] first = versionBytes(V1);
		byte[] second = versionBytes(V2, V1);
		byte[] root = claimBytes(V1, first);

		assertTrue(store.writeVersion(unit, V1, first));
		assertTrue(store.writeVersion(unit, V2, second));
		assertTrue(store.createClaim(unit, "root", root));

		UnitFiles files = store.readUnit(unit);

		assertEquals(unit, files.unit());
		assertEquals(List.of(V1, V2), List.copyOf(files.versions().keySet()));
		VersionFile readFirst = files.versions().get(V1);
		assertEquals(V1, readFirst.id());
		assertArrayEquals(first, readFirst.bytes());
		assertEquals(version(V1), readFirst.version());
		assertNull(readFirst.unknownFormat());
		assertEquals(List.of(V1), files.versions().get(V2).version().parents());
		assertEquals(List.of("root"), List.copyOf(files.claims().keySet()));
		ClaimFile claim = files.claims().get("root");
		assertEquals("root", claim.name());
		assertArrayEquals(root, claim.bytes());
		assertEquals(V1, claim.claim().version());
		assertArrayEquals(first, store.readVersionBytes(unit, V1).orElseThrow());
		assertArrayEquals(root, store.readClaimBytes(unit, "root").orElseThrow());
	}

	@Test
	void writeVersionTwiceReturnsFalse() throws IOException {
		VersionStore store = store();
		UnitKey unit = UnitKey.hall("h1");
		byte[] first = versionBytes(V1);

		assertTrue(store.writeVersion(unit, V1, first));
		assertFalse(store.writeVersion(unit, V1, bytes("anderer Inhalt")));

		assertArrayEquals(first, store.readVersionBytes(unit, V1).orElseThrow());
		assertTrue(store.createClaim(unit, "root", claimBytes(V1, first)));
		assertFalse(store.createClaim(unit, "root", bytes("anderer Inhalt")));
	}

	@Test
	void missingFilesReadAsEmpty() throws IOException {
		VersionStore store = store();
		UnitKey unit = UnitKey.event("e1");

		assertEquals(Optional.empty(), store.readVersionBytes(unit, V1));
		assertEquals(Optional.empty(), store.readClaimBytes(unit, "root"));
		UnitFiles files = store.readUnit(unit);
		assertTrue(files.versions().isEmpty());
		assertTrue(files.claims().isEmpty());
	}

	@Test
	void invalidClaimNamesAreRejected() {
		VersionStore store = store();
		UnitKey unit = UnitKey.event("e1");

		assertThrows(IllegalArgumentException.class, () -> store.createClaim(unit, "../x", bytes("x")));
		assertThrows(IllegalArgumentException.class, () -> store.createClaim(unit, "ABC", bytes("x")));
		assertThrows(IllegalArgumentException.class, () -> store.readClaimBytes(unit, "Thumbs.db"));
	}

	@Test
	void unreadableAndUnknownFormatAreMarked() throws IOException {
		VersionStore store = store();
		UnitKey unit = UnitKey.event("e1");
		Path unitDir = store.dataRoot().resolve(unit.relativePath());
		put(unitDir.resolve(V1 + ".json"), "das ist kein JSON");
		put(unitDir.resolve(V2 + ".json"), "{\"format\":2,\"irgendwas\":true}");
		put(unitDir.resolve("claims").resolve("root.json"), "Müll");
		put(unitDir.resolve("claims").resolve(V1 + ".json"), "{\"format\":3}");
		// eine Datei, deren Inhalt zu einer anderen ID gehört, gilt als unlesbar
		Files.write(unitDir.resolve(V3 + ".json"), versionBytes(V1));

		UnitFiles files = store.readUnit(unit);

		VersionFile garbage = files.versions().get(V1);
		assertNull(garbage.version());
		assertNull(garbage.unknownFormat());
		assertArrayEquals(bytes("das ist kein JSON"), garbage.bytes());
		VersionFile newer = files.versions().get(V2);
		assertNull(newer.version());
		assertEquals(2, newer.unknownFormat());
		assertArrayEquals(bytes("{\"format\":2,\"irgendwas\":true}"), newer.bytes());
		VersionFile foreignId = files.versions().get(V3);
		assertNull(foreignId.version());
		assertNull(foreignId.unknownFormat());
		assertNotNull(foreignId.bytes());
		assertNull(files.claims().get("root").claim());
		assertNull(files.claims().get(V1.value()).claim());
		assertArrayEquals(bytes("Müll"), files.claims().get("root").bytes());
	}

	@Test
	void foreignNamesAreSkipped() throws IOException {
		VersionStore store = store();
		UnitKey unit = UnitKey.event("e1");
		Path unitDir = store.dataRoot().resolve(unit.relativePath());
		Path claims = unitDir.resolve("claims");
		byte[] first = versionBytes(V1);
		assertTrue(store.writeVersion(unit, V1, first));
		assertTrue(store.createClaim(unit, "root", claimBytes(V1, first)));
		for (Path folder : List.of(unitDir, claims)) {
			put(folder.resolve("Thumbs.db"), "x");
			put(folder.resolve("x (conflicted copy).json"), "x");
			put(folder.resolve("ABC.json"), "x");
			put(folder.resolve(V2.value().toUpperCase() + ".json"), "x");
			put(folder.resolve(V2 + ".json.tmp"), "x");
			put(folder.resolve("~karv-123.tmp"), "x");
			Files.createDirectories(folder.resolve(V3 + ".json"));
		}
		Files.createDirectories(unitDir.resolve("unterordner"));

		UnitFiles files = store.readUnit(unit);

		assertEquals(List.of(V1), List.copyOf(files.versions().keySet()));
		assertEquals(List.of("root"), List.copyOf(files.claims().keySet()));
		assertEquals(List.of("events/e1/" + V1 + ".json", "events/e1/claims/root.json"), store.allDataFiles());
	}

	@Test
	void eventUnitsListsWholeTree() throws IOException {
		VersionStore store = store();
		byte[] bytes = versionBytes(V1);
		store.writeVersion(UnitKey.event("e1"), V1, bytes);
		store.writeVersion(UnitKey.presentation("e1", "p2"), V1, bytes);
		store.writeVersion(UnitKey.presentation("e1", "p1"), V1, bytes);
		store.writeVersion(UnitKey.booking("e1", "p1", "s2"), V1, bytes);
		store.writeVersion(UnitKey.booking("e1", "p1", "s1"), V1, bytes);
		store.writeVersion(UnitKey.unassignedBooking("e1", "p2", "b7"), V1, bytes);
		// fremde Einträge und andere Events zählen nicht
		store.writeVersion(UnitKey.event("e2"), V1, bytes);
		Path eventDir = store.dataRoot().resolve("events/e1");
		put(eventDir.resolve("presentations/p1/bookings/Thumbs.db"), "x");
		put(eventDir.resolve("presentations/readme.txt"), "x");
		Files.createDirectories(eventDir.resolve("presentations/p1/bookings/ungültig!"));

		List<UnitKey> units = store.eventUnits("e1");

		assertEquals(List.of(UnitKey.event("e1"), UnitKey.presentation("e1", "p1"), UnitKey.booking("e1", "p1", "s1"),
				UnitKey.booking("e1", "p1", "s2"), UnitKey.presentation("e1", "p2"),
				UnitKey.unassignedBooking("e1", "p2", "b7")), units);
		assertEquals(6, units.size());
	}

	@Test
	void idsAndAllUnits() throws IOException {
		VersionStore store = store();
		assertEquals(List.of(), store.eventIds());
		assertEquals(List.of(), store.hallIds());
		assertEquals(List.of(), store.allUnits());
		byte[] bytes = versionBytes(V1);
		store.writeVersion(UnitKey.event("e2"), V1, bytes);
		store.writeVersion(UnitKey.event("e1"), V1, bytes);
		store.writeVersion(UnitKey.presentation("e1", "p1"), V1, bytes);
		store.writeVersion(UnitKey.hall("h2"), V1, bytes);
		store.writeVersion(UnitKey.hall("h1"), V1, bytes);
		put(store.dataRoot().resolve("events/Thumbs.db"), "x");
		Files.createDirectories(store.dataRoot().resolve("events/ungültig"));
		Files.createDirectories(store.dataRoot().resolve("feeds"));

		assertEquals(List.of("e1", "e2"), store.eventIds());
		assertEquals(List.of("h1", "h2"), store.hallIds());
		assertEquals(List.of(UnitKey.event("e1"), UnitKey.presentation("e1", "p1"), UnitKey.event("e2"),
				UnitKey.hall("h1"), UnitKey.hall("h2")), store.allUnits());
	}

	@Test
	void allDataFilesExcludesFeeds() throws IOException {
		VersionStore store = store();
		byte[] first = versionBytes(V1);
		store.writeVersion(UnitKey.event("e1"), V1, first);
		store.createClaim(UnitKey.event("e1"), "root", claimBytes(V1, first));
		store.writeVersion(UnitKey.booking("e1", "p1", "s1"), V2, versionBytes(V2));
		store.writeVersion(UnitKey.hall("h1"), V3, versionBytes(V3));
		assertTrue(store.createRelative("feeds/maschine-1/0001.json", bytes("{}")));
		put(store.dataRoot().resolve("events/e1/notizen.txt"), "x");

		assertEquals(List.of("events/e1/" + V1 + ".json", "events/e1/claims/root.json",
				"events/e1/presentations/p1/bookings/s1/" + V2 + ".json", "halls/h1/" + V3 + ".json"),
				store.allDataFiles());
	}

	@Test
	void unitDataFilesListsOnlyThatUnit() throws IOException {
		VersionStore store = store();
		UnitKey event = UnitKey.event("e1");
		UnitKey presentation = UnitKey.presentation("e1", "p1");
		byte[] first = versionBytes(V1);
		store.writeVersion(event, V2, versionBytes(V2, V1));
		store.writeVersion(event, V1, first);
		store.createClaim(event, V1.value(), claimBytes(V2, versionBytes(V2, V1)));
		store.createClaim(event, "root", claimBytes(V1, first));
		store.writeVersion(presentation, V3, versionBytes(V3));
		store.createClaim(presentation, "root", claimBytes(V3, versionBytes(V3)));
		store.writeVersion(UnitKey.booking("e1", "p1", "s1"), V1, first);
		put(store.dataRoot().resolve("events/e1/Thumbs.db"), "x");
		put(store.dataRoot().resolve("events/e1/claims/~karv-1.tmp"), "x");
		Files.createDirectories(store.dataRoot().resolve("events/e1/presentations/ungültig!"));

		assertEquals(List.of("events/e1/" + V1 + ".json", "events/e1/" + V2 + ".json",
				"events/e1/claims/" + V1 + ".json", "events/e1/claims/root.json"), store.unitDataFiles(event));
		assertEquals(List.of("events/e1/presentations/p1/" + V3 + ".json", "events/e1/presentations/p1/claims/root.json"),
				store.unitDataFiles(presentation));
		assertEquals(List.of(), store.unitDataFiles(UnitKey.hall("h1")));
		assertEquals(List.of("p1"), store.childNames(event, "presentations"));
		assertEquals(List.of("s1"), store.childNames(presentation, "bookings"));
		assertEquals(List.of(), store.childNames(UnitKey.event("e2"), "presentations"));
	}

	@Test
	void listingUnitsAsksNoPerEntryFolderQuestion() throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		VersionStore store = new VersionStore(dir.resolve("data"), layer);
		UnitKey event = UnitKey.event("e1");
		UnitKey presentation = UnitKey.presentation("e1", "p1");
		int bookings = 40;
		for (int n = 0; n < bookings; n++) {
			store.writeVersion(UnitKey.booking("e1", "p1", String.format("s%03d", n)), V1, versionBytes(V1));
		}
		store.writeVersion(event, V1, versionBytes(V1));
		store.createClaim(event, "root", claimBytes(V1, versionBytes(V1)));
		store.writeVersion(UnitKey.hall("h1"), V2, versionBytes(V2));
		put(store.dataRoot().resolve("events/e1/presentations/p1/bookings/Datei.txt"), "x");
		Files.createDirectories(store.dataRoot().resolve("events/e1/presentations/p1/bookings/ungültig!"));
		int before = layer.calls().size();

		List<String> names = store.childNames(presentation, "bookings");
		store.eventIds();
		store.hallIds();
		store.unitDataFiles(event);
		store.hasVersions(event);
		store.scanUnit(event);

		assertEquals(bookings, names.size());
		assertEquals(List.of(), layer.calls().subList(before, layer.calls().size()).stream()
				.filter(c -> c.startsWith("isDirectory")).toList());
	}

	@Test
	void scanUnitYieldsDataAndTempFilesWithOneListingPerFolder() throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		VersionStore store = new VersionStore(dir.resolve("data"), layer);
		UnitKey event = UnitKey.event("e1");
		store.writeVersion(event, V2, versionBytes(V2, V1));
		store.writeVersion(event, V1, versionBytes(V1));
		store.createClaim(event, "root", claimBytes(V1, versionBytes(V1)));
		store.writeVersion(UnitKey.presentation("e1", "p1"), V3, versionBytes(V3));
		Path unitDir = store.dataRoot().resolve("events/e1");
		String tagged = "~karv-maschine-1-0f8fad5b-d9cb-469f-a165-70867728950e.tmp";
		put(unitDir.resolve(tagged), "x");
		put(unitDir.resolve("claims/~karv-2.tmp"), "x");
		put(unitDir.resolve("Thumbs.db"), "x");
		put(unitDir.resolve("presentations/p1/~karv-5.tmp"), "x");
		List<String> data = store.unitDataFiles(event);
		List<String> temps = store.unitTempFiles(event);
		int before = layer.calls().size();

		VersionStore.UnitScan scan = store.scanUnit(event);

		assertEquals(data, scan.dataFiles());
		assertEquals(temps, scan.tempFiles());
		assertEquals(List.of("events/e1/" + V1 + ".json", "events/e1/" + V2 + ".json", "events/e1/claims/root.json"),
				scan.dataFiles());
		assertEquals(List.of("events/e1/" + tagged, "events/e1/claims/~karv-2.tmp"), scan.tempFiles());
		List<String> listings = layer.calls().subList(before, layer.calls().size()).stream()
				.filter(c -> c.startsWith("listNames")).toList();
		assertEquals(List.of("listNamesWithTemps " + unitDir, "listNamesWithTemps " + unitDir.resolve("claims")), listings);
		assertEquals(new VersionStore.UnitScan(List.of(), List.of()), store.scanUnit(UnitKey.hall("h1")));
	}

	@Test
	void unitTempFilesListsTempsOfUnitAndClaims() throws IOException {
		VersionStore store = store();
		UnitKey event = UnitKey.event("e1");
		store.writeVersion(event, V1, versionBytes(V1));
		Path unitDir = store.dataRoot().resolve("events/e1");
		String tagged = "~karv-maschine-1-0f8fad5b-d9cb-469f-a165-70867728950e.tmp";
		put(unitDir.resolve(tagged), "x");
		put(unitDir.resolve("~karv-1.tmp"), "x");
		put(unitDir.resolve("claims/~karv-2.tmp"), "x");
		// keine temporären Dateien dieser Schicht, oder die einer anderen Einheit
		put(unitDir.resolve("~karv-3.json"), "x");
		put(unitDir.resolve("karv-4.tmp"), "x");
		put(unitDir.resolve("presentations/p1/~karv-5.tmp"), "x");

		assertEquals(List.of("events/e1/~karv-1.tmp", "events/e1/" + tagged, "events/e1/claims/~karv-2.tmp"),
				store.unitTempFiles(event));
		assertEquals(List.of("events/e1/presentations/p1/~karv-5.tmp"),
				store.unitTempFiles(UnitKey.presentation("e1", "p1")));
		assertEquals(List.of(), store.unitTempFiles(UnitKey.hall("h1")));
	}

	@Test
	void relativeAccessReadsAndCreatesBelowRoot() throws IOException {
		VersionStore store = store();

		assertEquals(Optional.empty(), store.readRelative("feeds/m1/feed.json"));
		assertTrue(store.createRelative("feeds/m1/feed.json", bytes("eins")));
		assertFalse(store.createRelative("feeds/m1/feed.json", bytes("zwei")));

		assertArrayEquals(bytes("eins"), store.readRelative("feeds/m1/feed.json").orElseThrow());
		assertEquals(store.dataRoot().resolve("feeds").resolve("m1").resolve("feed.json"),
				store.resolve("feeds/m1/feed.json"));
	}

	@Test
	void relativePathsOutsideTheRootAreRejected() {
		VersionStore store = store();

		for (String bad : new String[] { "", "/etc/passwd", "../x", "a/../../x", "a/./b", "a//b", "a\\b", "C:/x", "C:x",
				"a/" }) {
			assertThrows(IllegalArgumentException.class, () -> store.resolve(bad), bad);
			assertThrows(IllegalArgumentException.class, () -> store.readRelative(bad), bad);
			assertThrows(IllegalArgumentException.class, () -> store.createRelative(bad, bytes("x")), bad);
		}
		assertThrows(IllegalArgumentException.class, () -> store.resolve(null));
	}

	@Test
	void sharedStoreWithAnchorDoesNotCreateAMissingFolder() throws IOException {
		Path anchor = dir.resolve("gemeinsam");
		VersionStore store = new VersionStore(anchor.resolve("data"), new NioFileLayer(), anchor, () -> "abc");
		UnitKey unit = UnitKey.event("e1");
		byte[] version = versionBytes(V1);

		assertEquals(anchor, store.anchor());
		assertThrows(NoSuchFileException.class, () -> store.writeVersion(unit, V1, version));
		assertThrows(NoSuchFileException.class, () -> store.createClaim(unit, "root", claimBytes(V1, version)));
		assertThrows(NoSuchFileException.class, () -> store.createRelative("feeds/m1/feed.json", bytes("{}")));

		assertFalse(Files.exists(anchor));
		Files.createDirectory(anchor);
		assertTrue(store.writeVersion(unit, V1, version));
		assertTrue(Files.isDirectory(anchor.resolve("data").resolve("events").resolve("e1")));
	}

	@Test
	void storeHandsAnchorAndCurrentTagToTheFileLayer() throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		Path anchor = Files.createDirectory(dir.resolve("gemeinsam"));
		AtomicReference<String> tag = new AtomicReference<>("abc");
		VersionStore store = new VersionStore(anchor.resolve("data"), layer, anchor, tag::get);
		UnitKey unit = UnitKey.event("e1");
		byte[] version = versionBytes(V1);

		store.writeVersion(unit, V1, version);
		tag.set("xyz");
		store.createClaim(unit, "root", claimBytes(V1, version));
		store.createRelative("feeds/m1/feed.json", bytes("{}"));

		assertEquals(List.of(new FaultyFileLayer.Creation(store.resolve("events/e1/" + V1 + ".json"), anchor, "abc"),
				new FaultyFileLayer.Creation(store.resolve("events/e1/claims/root.json"), anchor, "xyz"),
				new FaultyFileLayer.Creation(store.resolve("feeds/m1/feed.json"), anchor, "xyz")), layer.creations());
	}

	@Test
	void storeWithoutAnchorHandsNoAnchorAndNoTagToTheFileLayer() throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		VersionStore store = new VersionStore(dir.resolve("data"), layer);

		store.writeVersion(UnitKey.event("e1"), V1, versionBytes(V1));

		assertEquals(List.of(new FaultyFileLayer.Creation(store.resolve("events/e1/" + V1 + ".json"), null, null)),
				layer.creations());
	}

	@Test
	void storeWithoutAnchorCreatesMissingFolders() throws IOException {
		VersionStore store = store();

		assertNull(store.anchor());
		assertTrue(store.writeVersion(UnitKey.event("e1"), V1, versionBytes(V1)));
	}

	@Test
	void worksInFolderWithSpacesAndUmlauts() throws IOException {
		Path root = dir.resolve("Gemeinsame Ablage").resolve("Kartenverkäufe").resolve("data");
		VersionStore store = new VersionStore(root, new NioFileLayer());
		UnitKey event = UnitKey.event("e1");
		UnitKey booking = UnitKey.unassignedBooking("e1", "p1", "b1");
		byte[] first = versionBytes(V1);
		byte[] second = versionBytes(V2, V1);

		assertTrue(store.writeVersion(event, V1, first));
		assertFalse(store.writeVersion(event, V1, first));
		assertTrue(store.writeVersion(event, V2, second));
		assertTrue(store.createClaim(event, "root", claimBytes(V1, first)));
		assertTrue(store.writeVersion(booking, V1, first));
		assertTrue(store.createRelative("feeds/m1/feed.json", bytes("{}")));
		put(root.resolve("events/e1/presentations/p1/Thumbs.db"), "x");

		assertArrayEquals(first, store.readVersionBytes(event, V1).orElseThrow());
		UnitFiles files = store.readUnit(event);
		assertEquals(List.of(V1, V2), List.copyOf(files.versions().keySet()));
		assertEquals(version(V2, V1), files.versions().get(V2).version());
		assertEquals(V1, files.claims().get("root").claim().version());
		assertEquals(List.of("e1"), store.eventIds());
		assertEquals(List.of(event, UnitKey.presentation("e1", "p1"), booking), store.eventUnits("e1"));
		assertEquals(List.of(event, UnitKey.presentation("e1", "p1"), booking), store.allUnits());
		assertEquals(List.of("events/e1/" + V1 + ".json", "events/e1/" + V2 + ".json", "events/e1/claims/root.json",
				"events/e1/presentations/p1/bookings/_b1/" + V1 + ".json"), store.allDataFiles());
		assertArrayEquals(bytes("{}"), store.readRelative("feeds/m1/feed.json").orElseThrow());
	}
}
