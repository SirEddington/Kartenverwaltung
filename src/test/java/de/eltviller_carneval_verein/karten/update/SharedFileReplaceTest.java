package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedFileReplaceTest {

	private static final String ID = "aaaa1111-0000-0000-0000-000000000001";

	@TempDir
	Path dir;

	private final OwnFiles own = new OwnFiles();

	private List<String> names() throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			return s.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	@Test
	void replacesExisting() throws IOException {
		Path f = dir.resolve("a.json");
		Files.writeString(f, "alt");
		SharedFileReplace.write(f, "neu".getBytes(StandardCharsets.UTF_8), ID, own);
		assertEquals("neu", Files.readString(f));
		assertEquals(List.of("a.json"), names());
	}

	@Test
	void failsWithoutParent() {
		Path missing = dir.resolve("fehlt").resolve("a.json");
		assertThrows(NoSuchFileException.class, () -> SharedFileReplace.write(missing, new byte[] { 1 }, ID, own));
		assertFalse(Files.exists(dir.resolve("fehlt")));
	}

	@Test
	void tempNameRegisteredInOwnFilesAndRetriedOnAccessDenied() throws IOException {
		Path f = dir.resolve("a.json");
		List<Boolean> registered = new ArrayList<>();
		AtomicInteger attempts = new AtomicInteger();
		SharedFileReplace.write(f, "x".getBytes(StandardCharsets.UTF_8), ID, own, (src, target) -> {
			registered.add(own.contains(src));
			assertTrue(src.getFileName().toString().startsWith("~karv-" + ID + "-"), src.toString());
			assertTrue(src.getFileName().toString().endsWith(".tmp"));
			if (attempts.incrementAndGet() < 3) {
				throw new AccessDeniedException(target.toString());
			}
			Files.move(src, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}, millis -> {
		});
		assertEquals(List.of(true, true, true), registered);
		assertEquals("x", Files.readString(f));
	}

	@Test
	void tempRemovedOnFailure() throws IOException {
		Path f = dir.resolve("a.json");
		assertThrows(IOException.class, () -> SharedFileReplace.write(f, new byte[] { 1 }, ID, own, (src, target) -> {
			throw new IOException("kaputt");
		}, millis -> {
		}));
		assertEquals(List.of(), names());
	}
}
