package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableWriteTest {

	@TempDir
	Path dir;

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	/** Zeichnet beim Erzwingen die Größe des Kanals und den Inhalt der Datei auf. */
	private static final class RecordingForcer implements DurableWrite.Forcer {
		final List<Long> sizes = new ArrayList<>();
		final List<byte[]> contents = new ArrayList<>();
		private final Path file;

		RecordingForcer(Path file) {
			this.file = file;
		}

		@Override
		public void force(java.nio.channels.FileChannel channel) throws IOException {
			sizes.add(channel.size());
			contents.add(Files.readAllBytes(file));
			channel.force(true);
		}
	}

	@Test
	void writeNewForcesOnceAfterTheWholeContent() throws IOException {
		Path file = dir.resolve("a.json");
		byte[] content = bytes("{\"x\":\"" + "y".repeat(100_000) + "\"}");
		RecordingForcer forcer = new RecordingForcer(file);

		DurableWrite.writeNew(file, content, forcer);

		assertEquals(List.of((long) content.length), forcer.sizes);
		assertArrayEquals(content, forcer.contents.get(0));
		assertArrayEquals(content, Files.readAllBytes(file));
	}

	@Test
	void writeNewRefusesAnExistingFileAndLeavesItUnchanged() throws IOException {
		Path file = dir.resolve("a.json");
		Files.write(file, bytes("alt"));
		RecordingForcer forcer = new RecordingForcer(file);

		assertThrows(FileAlreadyExistsException.class, () -> DurableWrite.writeNew(file, bytes("neu"), forcer));

		assertArrayEquals(bytes("alt"), Files.readAllBytes(file));
		assertEquals(List.of(), forcer.sizes);
	}

	@Test
	void aFailingForceReachesTheCaller() throws IOException {
		Path file = dir.resolve("a.json");
		IOException failure = new IOException("Platte weg");

		IOException thrown = assertThrows(IOException.class,
				() -> DurableWrite.writeNew(file, bytes("neu"), channel -> {
					throw failure;
				}));

		assertSame(failure, thrown);
	}

	@Test
	void overwriteReplacesALongerContentCompletelyAndForces() throws IOException {
		Path file = dir.resolve("clean");
		Files.write(file, bytes("ein viel längerer alter Inhalt"));
		RecordingForcer forcer = new RecordingForcer(file);

		DurableWrite.overwrite(file, bytes("kurz"), forcer);

		assertEquals(List.of((long) bytes("kurz").length), forcer.sizes);
		assertArrayEquals(bytes("kurz"), Files.readAllBytes(file));
	}

	@Test
	void overwriteCreatesAMissingFile() throws IOException {
		Path file = dir.resolve("clean");

		DurableWrite.overwrite(file, bytes("id"));

		assertArrayEquals(bytes("id"), Files.readAllBytes(file));
	}

	@Test
	void writeNewWithoutForcerWritesTheContent() throws IOException {
		Path file = dir.resolve("a.json");

		DurableWrite.writeNew(file, bytes("inhalt"));

		assertArrayEquals(bytes("inhalt"), Files.readAllBytes(file));
	}
}
