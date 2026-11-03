package de.eltviller_carneval_verein.karten.store;

import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.V1;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.V2;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.allDataFiles;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.bytes;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.claimBytes;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.put;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.version;
import static de.eltviller_carneval_verein.karten.store.VersionStoreFixtures.versionBytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pfade im {@link VersionStore}: Zugriff relativ zum Datenordner, der Anker des gemeinsamen Ordners und Ordnernamen mit
 * Leerzeichen und Umlauten. Warum diese Tests nicht in {@link VersionStoreTest} stehen, steht bei
 * {@link VersionStoreFixtures}.
 */
class VersionStorePathTest {

	@TempDir
	Path dir;

	private VersionStore store() {
		return new VersionStore(dir.resolve("data"), new NioFileLayer());
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
				"events/e1/presentations/p1/bookings/_b1/" + V1 + ".json"), allDataFiles(store));
		assertArrayEquals(bytes("{}"), store.readRelative("feeds/m1/feed.json").orElseThrow());
	}
}
