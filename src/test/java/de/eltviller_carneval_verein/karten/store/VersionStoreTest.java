package de.eltviller_carneval_verein.karten.store;

import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.V1;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.V2;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.V3;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.allDataFiles;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.bytes;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.claimBytes;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.put;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.version;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.versionBytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lesen, Schreiben und Auflisten im {@link VersionStore}. Pfade, Anker und Ordnernamen prüft {@link VersionStorePathTest};
 * warum die Tests in zwei Klassen stehen, steht bei {@link VersionStoreFixtures}.
 */
class VersionStoreTest {

	@TempDir
	Path dir;

	private VersionStore store() {
		return new VersionStore(dir.resolve("data"), new NioFileLayer());
	}

	@Test
	void missingAnchorIsNotAnEmptyStore() throws IOException {
		Path anchor = Files.createDirectories(dir.resolve("gemeinsam"));
		VersionStore shared = new VersionStore(anchor.resolve("data"), new NioFileLayer(), anchor, null);
		UnitKey event = UnitKey.event("e1");
		UnitKey booking = UnitKey.booking("e1", "p1", "A-1");

		// der Anker ist da, nur data oder die Einheit fehlt: leer wie bisher
		assertEquals(List.of(), shared.eventIds());
		assertEquals(List.of(), shared.childNames(event, VersionStore.PRESENTATIONS));
		assertTrue(shared.readUnit(booking).versions().isEmpty());

		// der Anker fehlt (Ordner umbenannt, Verknüpfung entfernt): kein leerer Bestand, sondern ein Fehler
		Files.delete(anchor);
		assertThrows(NoSuchFileException.class, shared::eventIds);
		assertThrows(NoSuchFileException.class, shared::hallIds);
		assertThrows(NoSuchFileException.class, () -> shared.childNames(event, VersionStore.PRESENTATIONS));
		assertThrows(NoSuchFileException.class, () -> shared.readUnit(booking));
		assertThrows(NoSuchFileException.class, () -> shared.hasVersions(booking));
		assertThrows(NoSuchFileException.class, () -> shared.unitDataFiles(booking));
		assertThrows(NoSuchFileException.class, () -> shared.scanUnit(booking));
		assertThrows(NoSuchFileException.class, () -> shared.listRelative("feeds"));

		// ohne Anker (der lokale Bestand) bleibt ein fehlender Ordner leer
		assertEquals(List.of(), new VersionStore(dir.resolve("weg").resolve("data"), new NioFileLayer()).eventIds());
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
		assertEquals(List.of("events/e1/" + V1 + ".json", "events/e1/claims/root.json"), allDataFiles(store));
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
				allDataFiles(store));
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
		int before = layer.calls().size();

		VersionStore.UnitScan scan = store.scanUnit(event);

		assertEquals(data, scan.dataFiles());
		assertEquals(List.of("events/e1/" + V1 + ".json", "events/e1/" + V2 + ".json", "events/e1/claims/root.json"),
				scan.dataFiles());
		assertEquals(List.of("events/e1/" + tagged, "events/e1/claims/~karv-2.tmp"), scan.tempFiles());
		List<String> listings = layer.calls().subList(before, layer.calls().size()).stream()
				.filter(c -> c.startsWith("listNames")).toList();
		assertEquals(List.of("listNamesWithTemps " + unitDir, "listNamesWithTemps " + unitDir.resolve("claims")), listings);
		assertEquals(new VersionStore.UnitScan(List.of(), List.of()), store.scanUnit(UnitKey.hall("h1")));
	}

	@Test
	void scanUnitListsTempsOfUnitAndClaims() throws IOException {
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
				store.scanUnit(event).tempFiles());
		assertEquals(List.of("events/e1/presentations/p1/~karv-5.tmp"),
				store.scanUnit(UnitKey.presentation("e1", "p1")).tempFiles());
		assertEquals(List.of(), store.scanUnit(UnitKey.hall("h1")).tempFiles());
	}

	private VersionStore storeWith(ContentGate gate) {
		return new VersionStore(dir.resolve("data"), new NioFileLayer(), null, null, gate);
	}

	/** Ein Tor, das jeden Payload mit dem Schlüssel {@code x-test-neu} als inhaltlich zu neu ansieht. */
	private static ContentGate gateOnNewKey(List<UnitKey> asked) {
		return new ContentGate() {
			@Override
			public boolean isNewer(UnitKey unit, ObjectNode payload) {
				asked.add(unit);
				return payload.has("x-test-neu");
			}

			@Override
			public String rulesId() {
				return "test";
			}
		};
	}

	private static byte[] versionBytesWith(VersionId id, boolean deleted, ObjectNode payload) throws IOException {
		return StoreCodec.write(new Version(1, id, List.of(), "maschine-1", "Kasse 1", "Anna", VersionStoreFixtures.AT,
				"save-" + id, deleted, payload));
	}

	@Test
	void gateHitKeepsVersionAndMarksContent() throws IOException {
		List<UnitKey> asked = new ArrayList<>();
		VersionStore store = storeWith(gateOnNewKey(asked));
		UnitKey unit = UnitKey.event("e1");
		ObjectNode newer = new ObjectMapper().createObjectNode().put("name", "Sitzung").put("x-test-neu", 1);
		store.writeVersion(unit, V1, versionBytesWith(V1, false, newer));
		store.writeVersion(unit, V2, versionBytes(V2, V1));

		UnitFiles files = store.readUnit(unit);

		VersionFile hit = files.versions().get(V1);
		assertEquals(VersionFile.Reason.CONTENT, hit.reason());
		assertNotNull(hit.version());
		assertTrue(hit.version().newerContent());
		assertEquals(newer, hit.version().payload());
		assertNull(hit.unknownFormat());
		assertArrayEquals(versionBytesWith(V1, false, newer), hit.bytes());
		VersionFile fine = files.versions().get(V2);
		assertEquals(VersionFile.Reason.OK, fine.reason());
		assertFalse(fine.version().newerContent());
		assertEquals(List.of(unit, unit), asked);
		// auch die einzelne Version wird geliefert, markiert
		assertTrue(store.readVersion(unit, V1).orElseThrow().newerContent());
		assertFalse(store.readVersion(unit, V2).orElseThrow().newerContent());
	}

	@Test
	void deletionVersionIsNeverAskedAsNewer() throws IOException {
		List<UnitKey> asked = new ArrayList<>();
		VersionStore store = storeWith(gateOnNewKey(asked));
		UnitKey unit = UnitKey.booking("e1", "p1", "A-1");
		store.writeVersion(unit, V1, versionBytesWith(V1, true, new ObjectMapper().createObjectNode()));

		VersionFile file = store.readUnit(unit).versions().get(V1);

		assertEquals(VersionFile.Reason.OK, file.reason());
		assertTrue(file.version().deleted());
		assertFalse(file.version().newerContent());
		assertEquals(List.of(), asked);
	}

	@Test
	void throwingGateCountsAsContent() throws IOException {
		ContentGate throwing = new ContentGate() {
			@Override
			public boolean isNewer(UnitKey unit, ObjectNode payload) {
				throw new IllegalStateException("x-test-kaputt");
			}

			@Override
			public String rulesId() {
				return "test";
			}
		};
		VersionStore store = storeWith(throwing);
		UnitKey unit = UnitKey.hall("h1");
		store.writeVersion(unit, V1, versionBytes(V1));
		Logger logger = Logger.getLogger(VersionStore.class.getName());
		boolean parentHandlers = logger.getUseParentHandlers();
		logger.setUseParentHandlers(false);

		VersionFile file;
		try (LogCapture log = new LogCapture(VersionStore.class)) {
			file = store.readUnit(unit).versions().get(V1);

			assertEquals(1, log.warnings("Die Inhaltsprüfung von halls/h1/"));
		} finally {
			logger.setUseParentHandlers(parentHandlers);
		}

		assertEquals(VersionFile.Reason.CONTENT, file.reason());
		assertTrue(file.version().newerContent());
	}

	@Test
	void unknownFormatStillGivesFormatReason() throws IOException {
		List<UnitKey> asked = new ArrayList<>();
		VersionStore store = storeWith(gateOnNewKey(asked));
		UnitKey unit = UnitKey.event("e1");
		Path unitDir = store.dataRoot().resolve(unit.relativePath());
		put(unitDir.resolve(V1 + ".json"), "{\"format\":2,\"x-test-neu\":true}");
		put(unitDir.resolve(V2 + ".json"), "kein JSON");
		Files.write(unitDir.resolve(V3 + ".json"), versionBytes(V1));

		UnitFiles files = store.readUnit(unit);

		VersionFile newer = files.versions().get(V1);
		assertEquals(VersionFile.Reason.FORMAT, newer.reason());
		assertNull(newer.version());
		assertEquals(2, newer.unknownFormat());
		assertEquals(VersionFile.Reason.DAMAGED, files.versions().get(V2).reason());
		assertEquals(VersionFile.Reason.DAMAGED, files.versions().get(V3).reason());
		assertEquals(List.of(), asked);
	}
}
