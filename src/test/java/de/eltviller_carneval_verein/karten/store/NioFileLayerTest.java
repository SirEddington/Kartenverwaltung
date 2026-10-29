package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NioFileLayerTest {

	@TempDir
	Path dir;

	private final FileLayer layer = new NioFileLayer();

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static List<String> namesIn(Path folder) throws IOException {
		try (Stream<Path> stream = Files.list(folder)) {
			return stream.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	/** Die temporären Dateien im Ordner (auch die, die {@code listNames} verbirgt). */
	private static List<String> tempsIn(Path folder) throws IOException {
		return namesIn(folder).stream().filter(n -> n.startsWith(FileLayer.TEMP_PREFIX)).toList();
	}

	@Test
	void createNewWritesOnce() throws IOException {
		Path file = dir.resolve("unit").resolve("a.json");

		assertTrue(layer.createNew(file, bytes("erste")));
		assertFalse(layer.createNew(file, bytes("zweite")));

		assertArrayEquals(bytes("erste"), Files.readAllBytes(file));
		assertEquals(List.of("a.json"), namesIn(file.getParent()));
	}

	@Test
	void createNewIsRaceSafe() throws Exception {
		Path file = dir.resolve("race").resolve("claim.json");
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch ready = new CountDownLatch(threads);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				byte[] content = bytes("von " + i);
				results.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					return layer.createNew(file, content);
				}));
			}
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();

			int winners = 0;
			for (Future<Boolean> result : results) {
				if (result.get(10, TimeUnit.SECONDS)) {
					winners++;
				}
			}
			assertEquals(1, winners);
			assertEquals(List.of("claim.json"), namesIn(file.getParent()));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void readReturnsContentOrEmptyForMissingFile() throws IOException {
		Path file = dir.resolve("x.json");
		assertEquals(Optional.empty(), layer.read(file));

		Files.write(file, bytes("inhalt"));
		assertArrayEquals(bytes("inhalt"), layer.read(file).orElseThrow());
	}

	@Test
	void existsAndIsDirectory() throws IOException {
		Path file = dir.resolve("x.json");
		assertFalse(layer.exists(file));
		assertFalse(layer.isDirectory(dir.resolve("sub")));

		Files.write(file, bytes("x"));
		assertTrue(layer.exists(file));
		assertFalse(layer.isDirectory(file));
		assertTrue(layer.isDirectory(dir));
	}

	@Test
	void listNamesHidesTemps() throws IOException {
		Files.write(dir.resolve("b.json"), bytes("b"));
		Files.write(dir.resolve("a.json"), bytes("a"));
		Files.write(dir.resolve("~karv-x.tmp"), bytes("t"));
		Files.createDirectory(dir.resolve("sub"));
		Files.createDirectory(dir.resolve("~karv-dir"));

		assertEquals(List.of("a.json", "b.json", "sub"), layer.listNames(dir));
	}

	@Test
	void listNamesOfMissingFolderIsEmpty() throws IOException {
		assertEquals(List.of(), layer.listNames(dir.resolve("gibt-es-nicht")));
	}

	@Test
	void listTreeReturnsFileNamesPerDirectory() throws IOException {
		Files.createDirectories(dir.resolve("a").resolve("b"));
		Files.createDirectory(dir.resolve("c"));
		Files.write(dir.resolve("a").resolve("x.json"), bytes("x"));
		Files.write(dir.resolve("a").resolve("b").resolve("y.json"), bytes("y"));

		Map<String, List<String>> tree = layer.listTree(dir);

		assertEquals(Map.of("", List.of(), "a", List.of("x.json"), "a/b", List.of("y.json"), "c", List.of()), tree);
	}

	@Test
	void listTreeSortsFileNamesAndLeavesOutFolders() throws IOException {
		Files.createDirectory(dir.resolve("sub"));
		Files.write(dir.resolve("b.json"), bytes("b"));
		Files.write(dir.resolve("a.json"), bytes("a"));

		assertEquals(Map.of("", List.of("a.json", "b.json"), "sub", List.of()), layer.listTree(dir));
	}

	@Test
	void listTreeOfMissingRootIsEmpty() throws IOException {
		assertEquals(Map.of(), layer.listTree(dir.resolve("gibt-es-nicht")));
	}

	@Test
	void replaceDamagedKeepsCorruptCopy() throws IOException {
		Path file = dir.resolve("v.json");
		Files.write(file, bytes("kaputt"));

		layer.replaceDamaged(file, bytes("gut"), "v.json.corrupt-2026-10-26_12-00-00");

		assertArrayEquals(bytes("gut"), Files.readAllBytes(file));
		assertArrayEquals(bytes("kaputt"), Files.readAllBytes(dir.resolve("v.json.corrupt-2026-10-26_12-00-00")));
		assertEquals(List.of("v.json", "v.json.corrupt-2026-10-26_12-00-00"), namesIn(dir));
	}

	@Test
	void replaceDamagedFailsWhenCorruptNameExists() throws IOException {
		Path file = dir.resolve("v.json");
		Path corrupt = dir.resolve("v.json.corrupt-2026-10-26_12-00-00");
		Files.write(file, bytes("kaputt"));
		Files.write(corrupt, bytes("aelter"));

		assertThrows(FileAlreadyExistsException.class,
				() -> layer.replaceDamaged(file, bytes("gut"), corrupt.getFileName().toString()));

		assertArrayEquals(bytes("kaputt"), Files.readAllBytes(file));
		assertArrayEquals(bytes("aelter"), Files.readAllBytes(corrupt));
		assertEquals(List.of("v.json", "v.json.corrupt-2026-10-26_12-00-00"), namesIn(dir));
		assertEquals(List.of(), tempsIn(dir));
	}

	@Test
	void createNewCleansUpTempOnOtherFailures() throws IOException {
		// Ein Dateiname über 255 Zeichen: Die temporäre Datei lässt sich schreiben, das Verschieben scheitert.
		Path file = dir.resolve("a".repeat(300) + ".json");

		assertThrows(IOException.class, () -> layer.createNew(file, bytes("x")));

		assertEquals(List.of(), tempsIn(dir));
		assertEquals(List.of(), namesIn(dir));
	}

	@Test
	void renameDoesNotReplaceExistingTarget() throws IOException {
		Path from = dir.resolve("a.json");
		Path to = dir.resolve("b.json");
		Files.write(from, bytes("a"));
		Files.write(to, bytes("b"));

		assertThrows(FileAlreadyExistsException.class, () -> layer.rename(from, to));

		assertArrayEquals(bytes("a"), Files.readAllBytes(from));
		assertArrayEquals(bytes("b"), Files.readAllBytes(to));
	}

	@Test
	void renameMovesFile() throws IOException {
		Path from = dir.resolve("a.json");
		Files.write(from, bytes("a"));

		layer.rename(from, dir.resolve("b.json"));

		assertFalse(Files.exists(from));
		assertArrayEquals(bytes("a"), Files.readAllBytes(dir.resolve("b.json")));
	}

	@Test
	void deleteOwnTempsOnlyOldOwnTemps() throws IOException {
		Path sub = Files.createDirectories(dir.resolve("sub").resolve("tief"));
		Path oldTemp = dir.resolve("~karv-alt.tmp");
		Path oldNestedTemp = sub.resolve("~karv-alt2.tmp");
		Path youngTemp = dir.resolve("~karv-jung.tmp");
		Path foreignTemp = dir.resolve("x.tmp");
		Path foreignOldTilde = dir.resolve("~fremd.tmp");
		Path data = dir.resolve("a.json");
		for (Path p : List.of(oldTemp, oldNestedTemp, youngTemp, foreignTemp, foreignOldTilde, data)) {
			Files.write(p, bytes("x"));
		}
		FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(2)));
		for (Path p : List.of(oldTemp, oldNestedTemp, foreignTemp, foreignOldTilde, data)) {
			Files.setLastModifiedTime(p, old);
		}

		layer.deleteOwnTemps(dir, Duration.ofHours(1));

		assertFalse(Files.exists(oldTemp));
		assertFalse(Files.exists(oldNestedTemp));
		assertTrue(Files.exists(youngTemp));
		assertTrue(Files.exists(foreignTemp));
		assertTrue(Files.exists(foreignOldTilde));
		assertTrue(Files.exists(data));
	}

	@Test
	void deleteOwnTempsOfMissingFolderDoesNothing() throws IOException {
		layer.deleteOwnTemps(dir.resolve("gibt-es-nicht"), Duration.ZERO);
	}

	@Test
	void createNewNeverCreatesTheAnchor() throws IOException {
		Path anchor = dir.resolve("gemeinsam");
		Path file = anchor.resolve("data").resolve("a.json");

		assertThrows(NoSuchFileException.class, () -> layer.createNew(file, bytes("x"), anchor, null));

		assertFalse(Files.exists(anchor));
		assertEquals(List.of(), namesIn(dir));
	}

	@Test
	void createNewCreatesMissingLevelsBelowTheAnchor() throws IOException {
		Path anchor = Files.createDirectory(dir.resolve("gemeinsam"));
		Path file = anchor.resolve("data").resolve("events").resolve("e1").resolve("x.json");

		assertTrue(layer.createNew(file, bytes("x"), anchor, null));

		assertArrayEquals(bytes("x"), Files.readAllBytes(file));
		assertEquals(List.of("x.json"), namesIn(file.getParent()));
	}

	@Test
	void createNewAcceptsLevelsThatExistAlready() throws IOException {
		Path anchor = Files.createDirectory(dir.resolve("gemeinsam"));
		Files.createDirectories(anchor.resolve("data").resolve("events"));
		Path file = anchor.resolve("data").resolve("events").resolve("e1").resolve("x.json");

		assertTrue(layer.createNew(file, bytes("x"), anchor, null));
		assertFalse(layer.createNew(file, bytes("y"), anchor, null));

		assertArrayEquals(bytes("x"), Files.readAllBytes(file));
	}

	@Test
	void createNewDirectlyInTheAnchorNeedsNoNewLevel() throws IOException {
		Path anchor = Files.createDirectory(dir.resolve("gemeinsam"));

		assertTrue(layer.createNew(anchor.resolve("a.json"), bytes("x"), anchor, null));

		assertEquals(List.of("a.json"), namesIn(anchor));
	}

	@Test
	void createNewFailsWhenAnchorVanishedBeforeAnIntermediateLevel() throws IOException {
		Path anchor = Files.createDirectory(dir.resolve("gemeinsam"));
		Files.delete(anchor);
		Path file = anchor.resolve("data").resolve("events").resolve("e1").resolve("x.json");

		assertThrows(NoSuchFileException.class, () -> layer.createNew(file, bytes("x"), anchor, null));

		assertEquals(List.of(), namesIn(dir));
	}

	@Test
	void createNewOutsideTheAnchorIsRejected() throws IOException {
		Path anchor = Files.createDirectory(dir.resolve("gemeinsam"));
		Path other = dir.resolve("anderswo").resolve("a.json");
		Path escaping = anchor.resolve("..").resolve("anderswo").resolve("a.json");
		Path sibling = dir.resolve("gemeinsam-2").resolve("a.json");

		assertThrows(IllegalArgumentException.class, () -> layer.createNew(other, bytes("x"), anchor, null));
		assertThrows(IllegalArgumentException.class, () -> layer.createNew(escaping, bytes("x"), anchor, null));
		assertThrows(IllegalArgumentException.class, () -> layer.createNew(sibling, bytes("x"), anchor, null));
		assertThrows(IllegalArgumentException.class, () -> layer.createNew(anchor, bytes("x"), anchor, null));

		assertEquals(List.of("gemeinsam"), namesIn(dir));
		assertEquals(List.of(), namesIn(anchor));
	}

	@Test
	void anchorIsComparedNormalized() throws IOException {
		Path anchor = Files.createDirectory(dir.resolve("gemeinsam"));
		Path file = anchor.resolve("data").resolve("a.json");
		Path withSlash = Path.of(anchor + File.separator);
		Path withDots = dir.resolve("x").resolve("..").resolve("gemeinsam");

		assertTrue(layer.createNew(file, bytes("1"), withSlash, null));
		assertTrue(layer.createNew(anchor.resolve("data").resolve("b.json"), bytes("2"), withDots, null));
		assertTrue(layer.createNew(withDots.resolve("neu").resolve("c.json"), bytes("3"), anchor, null));

		assertEquals(List.of("a.json", "b.json"), namesIn(anchor.resolve("data")));
		assertEquals(List.of("c.json"), namesIn(anchor.resolve("neu")));
		assertEquals(List.of("gemeinsam"), namesIn(dir));

		Files.delete(file);
		Files.delete(anchor.resolve("data").resolve("b.json"));
		Files.delete(anchor.resolve("data"));
		Files.delete(anchor.resolve("neu").resolve("c.json"));
		Files.delete(anchor.resolve("neu"));
		Files.delete(anchor);
		assertThrows(NoSuchFileException.class, () -> layer.createNew(file, bytes("1"), withDots, null));
		assertFalse(Files.exists(anchor));
	}

	@Test
	void taggedTempFilesCarryTheTag() throws Exception {
		Path anchor = Files.createDirectory(dir.resolve("gemeinsam"));
		Path file = anchor.resolve("a.json");
		List<String> created = new ArrayList<>();
		try (WatchService watch = dir.getFileSystem().newWatchService()) {
			anchor.register(watch, StandardWatchEventKinds.ENTRY_CREATE);
			// Der Inhalt ist groß genug, dass das Schreiben der temporären Datei einen Moment dauert
			assertTrue(layer.createNew(file, new byte[4 * 1024 * 1024], anchor, "abc"));

			WatchKey key;
			while ((key = watch.poll(500, TimeUnit.MILLISECONDS)) != null) {
				for (WatchEvent<?> event : key.pollEvents()) {
					created.add(event.context().toString());
				}
				key.reset();
			}
		}

		List<String> temps = created.stream().filter(n -> n.startsWith(FileLayer.TEMP_PREFIX)).toList();
		assertEquals(1, temps.size(), created.toString());
		assertTrue(temps.get(0).startsWith("~karv-abc-"), temps.get(0));
		assertTrue(temps.get(0).endsWith(".tmp"), temps.get(0));
		assertEquals("abc", FileLayer.tempTagOf(temps.get(0)));
		assertEquals(List.of("a.json"), namesIn(anchor));
	}

	@Test
	void tempTagOfReadsTheTagBetweenPrefixAndUuid() {
		String uuid = "123e4567-e89b-12d3-a456-426614174000";

		assertNull(FileLayer.tempTagOf("~karv-" + uuid + ".tmp"));
		assertEquals("abc", FileLayer.tempTagOf("~karv-abc-" + uuid + ".tmp"));
		assertEquals("a-b-c", FileLayer.tempTagOf("~karv-a-b-c-" + uuid + ".tmp"));
		assertNull(FileLayer.tempTagOf("~karv-x.tmp"));
		assertNull(FileLayer.tempTagOf("~karv--" + uuid + ".tmp"));
		assertNull(FileLayer.tempTagOf("~karv-abc-" + uuid + ".json"));
		assertNull(FileLayer.tempTagOf("abc-" + uuid + ".tmp"));
		assertNull(FileLayer.tempTagOf("~karv-abc-not-a-uuid-at-all-0123456789012345.tmp"));
		assertNull(FileLayer.tempTagOf(""));
	}

	@Test
	void listNamesWithTempsShowsTempsListNamesHidesThem() throws IOException {
		Files.write(dir.resolve("b.json"), bytes("b"));
		Files.write(dir.resolve("~karv-abc-123e4567-e89b-12d3-a456-426614174000.tmp"), bytes("t"));
		Files.write(dir.resolve("~karv-x.tmp"), bytes("t"));
		Files.createDirectory(dir.resolve("sub"));

		assertEquals(List.of("b.json", "sub"), layer.listNames(dir));
		assertEquals(List.of("b.json", "sub", "~karv-abc-123e4567-e89b-12d3-a456-426614174000.tmp", "~karv-x.tmp"),
				layer.listNamesWithTemps(dir));
		assertEquals(List.of(), layer.listNamesWithTemps(dir.resolve("gibt-es-nicht")));
	}

	@Test
	void deleteOldTempsDeletesOnlyNamedOldTemps() throws IOException {
		Path oldOwn = dir.resolve("~karv-abc-123e4567-e89b-12d3-a456-426614174000.tmp");
		Path oldOwn2 = dir.resolve("~karv-abc-223e4567-e89b-12d3-a456-426614174000.tmp");
		Path oldNotNamed = dir.resolve("~karv-abc-323e4567-e89b-12d3-a456-426614174000.tmp");
		Path young = dir.resolve("~karv-abc-423e4567-e89b-12d3-a456-426614174000.tmp");
		Path foreignName = dir.resolve("x.tmp");
		Path tildeOnly = dir.resolve("~fremd.tmp");
		Path wrongSuffix = dir.resolve("~karv-abc.json");
		Path data = dir.resolve("a.json");
		List<Path> all = List.of(oldOwn, oldOwn2, oldNotNamed, young, foreignName, tildeOnly, wrongSuffix, data);
		for (Path p : all) {
			Files.write(p, bytes("x"));
		}
		FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(2)));
		for (Path p : all) {
			if (p != young) {
				Files.setLastModifiedTime(p, old);
			}
		}

		layer.deleteOldTemps(dir, List.of(oldOwn.getFileName().toString(), oldOwn2.getFileName().toString(),
				young.getFileName().toString(), "x.tmp", "~fremd.tmp", "~karv-abc.json", "a.json",
				"~karv-gibt-es-nicht.tmp", "~karv-/../a.tmp"), Duration.ofHours(1));

		assertFalse(Files.exists(oldOwn));
		assertFalse(Files.exists(oldOwn2));
		assertTrue(Files.exists(oldNotNamed));
		assertTrue(Files.exists(young));
		assertTrue(Files.exists(foreignName));
		assertTrue(Files.exists(tildeOnly));
		assertTrue(Files.exists(wrongSuffix));
		assertTrue(Files.exists(data));
	}

	@Test
	void deleteOldTempsOfMissingFolderDoesNothing() throws IOException {
		layer.deleteOldTemps(dir.resolve("gibt-es-nicht"), List.of("~karv-a.tmp"), Duration.ZERO);
	}
}
