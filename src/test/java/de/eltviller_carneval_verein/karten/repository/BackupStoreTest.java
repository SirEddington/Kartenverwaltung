package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.repository.BackupStore.Backup;

class BackupStoreTest {

	/** Uhr, die der Test von Hand stellt. */
	private static final class MutableClock extends Clock {
		private Instant now;
		private final ZoneId zone = ZoneId.systemDefault();

		MutableClock(LocalDateTime start) {
			set(start);
		}

		void set(LocalDateTime time) {
			now = time.atZone(zone).toInstant();
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 14, 15, 30, 12, 123_000_000);

	@TempDir
	File dataDir;

	private MutableClock clock;
	private BackupStore store;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(T0);
		store = new BackupStore(dataDir, clock);
	}

	private File writeCurrent(String id, String content) throws IOException {
		File file = new File(dataDir, id + ".json");
		Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
		return file;
	}

	@Test
	void noBackup_whenCurrentFileMissing() {
		assertFalse(store.backupBeforeOverwrite("a", new File(dataDir, "a.json")));
		assertTrue(store.list("a").isEmpty());
	}

	@Test
	void firstOverwrite_createsCopyNamedByTimestamp() throws IOException {
		File current = writeCurrent("a", "v1");

		assertTrue(store.backupBeforeOverwrite("a", current));

		List<Backup> backups = store.list("a");
		assertEquals(1, backups.size());
		Backup backup = backups.get(0);
		assertEquals("2026-10-14_15-30-12-123.json", backup.file().getFileName().toString());
		assertEquals(T0, backup.timestamp());
		assertEquals("a", backup.entityId());
		assertEquals(2, backup.size());
		assertArrayEquals("v1".getBytes(StandardCharsets.UTF_8), store.read(backup));
	}

	@Test
	void identicalToNewestCopy_createsNoSecondCopy() throws IOException {
		File current = writeCurrent("a", "v1");
		store.backupBeforeOverwrite("a", current);

		clock.set(T0.plusSeconds(5));

		assertFalse(store.backupBeforeOverwrite("a", current));
		assertEquals(1, store.list("a").size());
	}

	@Test
	void changedFile_createsSecondCopy_newestFirst() throws IOException {
		File current = writeCurrent("a", "v1");
		store.backupBeforeOverwrite("a", current);

		clock.set(T0.plusSeconds(1));
		writeCurrent("a", "v2");

		assertTrue(store.backupBeforeOverwrite("a", current));
		List<Backup> backups = store.list("a");
		assertEquals(2, backups.size());
		assertArrayEquals("v2".getBytes(StandardCharsets.UTF_8), store.read(backups.get(0)));
		assertArrayEquals("v1".getBytes(StandardCharsets.UTF_8), store.read(backups.get(1)));
	}

	@Test
	void sameMillisecond_getsCounterSuffix() throws IOException {
		File current = writeCurrent("a", "v1");
		store.backupBeforeOverwrite("a", current);
		writeCurrent("a", "v2");

		assertTrue(store.backupBeforeOverwrite("a", current));

		List<String> names = store.list("a").stream().map(backup -> backup.file().getFileName().toString()).toList();
		assertEquals(List.of("2026-10-14_15-30-12-123-2.json", "2026-10-14_15-30-12-123.json"), names);
	}

	@Test
	void idsAreSeparated_includingLegacyNameIds() throws IOException {
		store.backupBeforeOverwrite("abc-123", writeCurrent("abc-123", "x"));
		store.backupBeforeOverwrite("Test1", writeCurrent("Test1", "y"));

		assertEquals(List.of("Test1", "abc-123"), store.idsWithBackups());
		assertEquals(1, store.list("Test1").size());
		assertArrayEquals("y".getBytes(StandardCharsets.UTF_8), store.read(store.list("Test1").get(0)));
		assertTrue(store.list("unbekannt").isEmpty());
	}

	@Test
	void backupFailure_returnsFalseAndDoesNotThrow() throws IOException {
		Files.writeString(new File(dataDir, "backups").toPath(), "ich bin eine Datei", StandardCharsets.UTF_8);
		File current = writeCurrent("a", "v1");

		assertFalse(store.backupBeforeOverwrite("a", current));
	}
}
