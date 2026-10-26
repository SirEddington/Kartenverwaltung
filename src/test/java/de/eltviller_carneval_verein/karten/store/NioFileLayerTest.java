package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
	void replaceDamagedKeepsCorruptCopy() throws IOException {
		Path file = dir.resolve("v.json");
		Files.write(file, bytes("kaputt"));

		layer.replaceDamaged(file, bytes("gut"), "v.json.corrupt-2026-10-26_12-00-00");

		assertArrayEquals(bytes("gut"), Files.readAllBytes(file));
		assertArrayEquals(bytes("kaputt"), Files.readAllBytes(dir.resolve("v.json.corrupt-2026-10-26_12-00-00")));
		assertEquals(List.of("v.json", "v.json.corrupt-2026-10-26_12-00-00"), namesIn(dir));
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
}
