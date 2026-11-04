package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileReplaceTest {

	@TempDir
	Path dir;

	/** Zählt die Pausen, statt zu schlafen. */
	private final List<Long> pauses = new ArrayList<>();

	private final AtomicFileReplace.Sleeper sleeper = pauses::add;

	@Test
	void replacesTheTargetWithTheTempFile() throws IOException {
		Path temp = Files.writeString(dir.resolve("E1.tmp"), "neu", StandardCharsets.UTF_8);
		Path target = Files.writeString(dir.resolve("E1.json"), "alt", StandardCharsets.UTF_8);

		AtomicFileReplace.replace(temp, target);

		assertArrayEquals("neu".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
		assertEquals(List.of(target), listFiles());
	}

	@Test
	void writeCreatesTheFolderAndReplacesTheFileWithoutLeftovers() throws IOException {
		Path target = dir.resolve("unter").resolve("E1.json");

		AtomicFileReplace.write(target, "alt".getBytes(StandardCharsets.UTF_8), "marke");
		AtomicFileReplace.write(target, "neu".getBytes(StandardCharsets.UTF_8), null);

		assertArrayEquals("neu".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
		try (var entries = Files.list(target.getParent())) {
			assertEquals(List.of(target), entries.toList());
		}
	}

	@Test
	void failedWriteKeepsTheFileAndRemovesItsTempFile() throws IOException {
		// ein Ordner mit Inhalt an der Stelle der Datei: Das Ersetzen scheitert dauerhaft
		Path target = Files.createDirectories(dir.resolve("E1.json"));
		Files.writeString(target.resolve("drin"), "x", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> AtomicFileReplace.write(target, "neu".getBytes(StandardCharsets.UTF_8), "marke"));

		assertEquals(List.of(target), listFiles());
		try (var entries = Files.list(target)) {
			assertEquals(List.of(target.resolve("drin")), entries.toList());
		}
	}

	@Test
	void retriesAMoveThatFailsTwiceWithAccessDenied() throws IOException {
		Path temp = dir.resolve("E1.tmp");
		Path target = dir.resolve("E1.json");
		List<String> calls = new ArrayList<>();
		AtomicFileReplace.Mover mover = (source, destination) -> {
			calls.add(source + " -> " + destination);
			if (calls.size() <= 2) {
				throw new AccessDeniedException(source.toString(), destination.toString(), null);
			}
		};

		AtomicFileReplace.replace(temp, target, mover, sleeper);

		assertEquals(3, calls.size());
		assertEquals(temp + " -> " + target, calls.get(2));
		assertEquals(List.of(AtomicFileReplace.PAUSE_MILLIS, AtomicFileReplace.PAUSE_MILLIS), pauses);
	}

	@Test
	void retriesAPlainFileSystemException() throws IOException {
		int[] calls = { 0 };
		AtomicFileReplace.Mover mover = (source, destination) -> {
			if (++calls[0] == 1) {
				// So meldet Windows eine Freigabeverletzung (Datei von einem anderen Prozess geöffnet)
				throw new FileSystemException(source.toString(), destination.toString(),
						"Der Prozess kann nicht auf die Datei zugreifen, da sie von einem anderen Prozess verwendet wird");
			}
		};

		AtomicFileReplace.replace(dir.resolve("E1.tmp"), dir.resolve("E1.json"), mover, sleeper);

		assertEquals(2, calls[0]);
		assertEquals(1, pauses.size());
	}

	@Test
	void givesUpAfterTheLastAttemptAndRethrows() {
		Path temp = dir.resolve("E1.tmp");
		Path target = dir.resolve("E1.json");
		List<AccessDeniedException> thrown = new ArrayList<>();
		AtomicFileReplace.Mover mover = (source, destination) -> {
			AccessDeniedException e = new AccessDeniedException(source.toString(), destination.toString(),
					"Versuch " + (thrown.size() + 1));
			e.initCause(new IllegalStateException("Ursache"));
			thrown.add(e);
			throw e;
		};

		AccessDeniedException e = assertThrows(AccessDeniedException.class,
				() -> AtomicFileReplace.replace(temp, target, mover, sleeper));

		assertEquals(AtomicFileReplace.ATTEMPTS, thrown.size());
		// Die letzte Ausnahme kommt unverändert beim Aufrufer an, samt Meldung und Ursache
		assertSame(thrown.get(thrown.size() - 1), e);
		assertEquals(temp + " -> " + target + ": Versuch " + AtomicFileReplace.ATTEMPTS, e.getMessage());
		assertEquals("Ursache", e.getCause().getMessage());
		// Nach dem letzten Versuch wird nicht mehr gewartet
		assertEquals(AtomicFileReplace.ATTEMPTS - 1, pauses.size());
	}

	@Test
	void doesNotRetryOtherIOExceptions() {
		int[] calls = { 0 };
		NoSuchFileException missing = new NoSuchFileException("E1.tmp");
		AtomicFileReplace.Mover noSuchFile = (source, destination) -> {
			calls[0]++;
			throw missing;
		};

		assertSame(missing, assertThrows(NoSuchFileException.class,
				() -> AtomicFileReplace.replace(dir.resolve("E1.tmp"), dir.resolve("E1.json"), noSuchFile, sleeper)));
		assertEquals(1, calls[0]);

		IOException plain = new IOException("Datenträger voll");
		AtomicFileReplace.Mover ioException = (source, destination) -> {
			calls[0]++;
			throw plain;
		};

		assertSame(plain, assertThrows(IOException.class,
				() -> AtomicFileReplace.replace(dir.resolve("E1.tmp"), dir.resolve("E1.json"), ioException, sleeper)));
		assertEquals(2, calls[0]);
		assertEquals(List.of(), pauses);
	}

	private List<Path> listFiles() throws IOException {
		try (var files = Files.list(dir)) {
			return files.toList();
		}
	}
}
