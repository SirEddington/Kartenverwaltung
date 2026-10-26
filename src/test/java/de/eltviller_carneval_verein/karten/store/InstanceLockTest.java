package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceLockTest {

	@TempDir
	Path dir;

	@Test
	void secondLockFails() throws IOException {
		Optional<InstanceLock> first = InstanceLock.tryAcquire(dir);
		assertTrue(first.isPresent());

		assertTrue(InstanceLock.tryAcquire(dir).isEmpty());

		first.get().close();
		Optional<InstanceLock> again = InstanceLock.tryAcquire(dir);
		assertTrue(again.isPresent());
		again.get().close();
	}

	@Test
	void failedAttemptDoesNotReleaseTheFirstLock() throws IOException {
		try (InstanceLock first = InstanceLock.tryAcquire(dir).orElseThrow()) {
			assertTrue(InstanceLock.tryAcquire(dir).isEmpty());
			assertTrue(InstanceLock.tryAcquire(dir).isEmpty());
		}
	}

	@Test
	void lockFileStaysAfterClose() throws IOException {
		InstanceLock.tryAcquire(dir).orElseThrow().close();

		assertTrue(Files.isRegularFile(dir.resolve("instance.lock")));
		try (Stream<Path> stream = Files.list(dir)) {
			assertEquals(List.of("instance.lock"), stream.map(p -> p.getFileName().toString()).toList());
		}
	}

	@Test
	void closeIsIdempotent() throws IOException {
		InstanceLock lock = InstanceLock.tryAcquire(dir).orElseThrow();

		lock.close();
		lock.close();
	}

	@Test
	void createsMissingDirectory() throws IOException {
		Path nested = dir.resolve("a").resolve("b");

		try (InstanceLock lock = InstanceLock.tryAcquire(nested).orElseThrow()) {
			assertTrue(Files.isRegularFile(nested.resolve("instance.lock")));
		}
	}

	@Test
	void foreignLockOnTheFileCountsAsOccupied() throws IOException {
		Path file = dir.resolve("instance.lock");
		try (FileChannel other = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE,
				StandardOpenOption.CREATE); FileLock held = other.tryLock()) {
			assertTrue(held != null && held.isValid());

			assertFalse(InstanceLock.tryAcquire(dir).isPresent());
		}

		InstanceLock.tryAcquire(dir).orElseThrow().close();
	}
}
