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

	// --- Aufbewahrung ---

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);

	/** Legt direkt eine Kopie mit dem Zeitstempel im Namen an (ohne den Weg über das Backup). */
	private void putBackup(String id, LocalDateTime timestamp) throws IOException {
		File dir = new File(new File(dataDir, "backups"), id);
		dir.mkdirs();
		String name = timestamp.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")) + ".json";
		Files.writeString(new File(dir, name).toPath(), "x" + timestamp, StandardCharsets.UTF_8);
	}

	private List<LocalDateTime> timestamps(String id) {
		return store.list(id).stream().map(Backup::timestamp).toList();
	}

	@Test
	void keepsEverythingFromLast24Hours() throws IOException {
		clock.set(NOW);
		putBackup("a", LocalDateTime.of(2026, 10, 13, 12, 30));
		putBackup("a", LocalDateTime.of(2026, 10, 14, 8, 0));
		putBackup("a", LocalDateTime.of(2026, 10, 14, 11, 59));

		store.prune("a");

		assertEquals(3, store.list("a").size());
	}

	@Test
	void olderThan24Hours_keepsOnlyLastPerDay() throws IOException {
		clock.set(NOW);
		putBackup("a", LocalDateTime.of(2026, 10, 12, 9, 0));
		putBackup("a", LocalDateTime.of(2026, 10, 12, 14, 0));
		putBackup("a", LocalDateTime.of(2026, 10, 12, 20, 0));
		putBackup("a", LocalDateTime.of(2026, 10, 11, 8, 0));
		putBackup("a", LocalDateTime.of(2026, 10, 11, 23, 0));

		store.prune("a");

		assertEquals(List.of(LocalDateTime.of(2026, 10, 12, 20, 0), LocalDateTime.of(2026, 10, 11, 23, 0)), timestamps("a"));
	}

	@Test
	void olderThan90Days_isDeleted() throws IOException {
		clock.set(NOW);
		putBackup("a", LocalDateTime.of(2026, 7, 10, 10, 0));
		putBackup("a", LocalDateTime.of(2026, 7, 20, 10, 0));

		store.prune("a");

		assertEquals(List.of(LocalDateTime.of(2026, 7, 20, 10, 0)), timestamps("a"));
	}

	@Test
	void futureTimestamp_countsAsRecent() throws IOException {
		clock.set(NOW);
		putBackup("a", LocalDateTime.of(2026, 10, 15, 9, 0));
		putBackup("a", LocalDateTime.of(2026, 10, 15, 10, 0));

		store.prune("a");

		assertEquals(2, store.list("a").size());
	}

	@Test
	void foreignFiles_areIgnoredAndKept() throws IOException {
		clock.set(NOW);
		putBackup("a", LocalDateTime.of(2026, 10, 12, 9, 0));
		File dir = new File(new File(dataDir, "backups"), "a");
		File note = new File(dir, "notiz.txt");
		File odd = new File(dir, "komisch.json");
		Files.writeString(note.toPath(), "n", StandardCharsets.UTF_8);
		Files.writeString(odd.toPath(), "k", StandardCharsets.UTF_8);

		store.prune("a");

		assertTrue(note.exists());
		assertTrue(odd.exists());
		assertEquals(1, store.list("a").size());
	}

	@Test
	void pruneAll_cleansDeletedEntityIds() throws IOException {
		clock.set(NOW);
		for (String id : List.of("x", "y")) {
			putBackup(id, LocalDateTime.of(2026, 10, 1, 9, 0));
			putBackup(id, LocalDateTime.of(2026, 10, 1, 18, 0));
		}

		store.pruneAll();

		assertEquals(List.of(LocalDateTime.of(2026, 10, 1, 18, 0)), timestamps("x"));
		assertEquals(List.of(LocalDateTime.of(2026, 10, 1, 18, 0)), timestamps("y"));
	}

	// --- Start-Wartung ---

	private File putFile(String name, String content, LocalDateTime lastModified) throws IOException {
		File file = new File(dataDir, name);
		Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
		assertTrue(file.setLastModified(lastModified.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
		return file;
	}

	@Test
	void legacyBak_isMovedAsFirstCopyWithModifiedTime() throws IOException {
		clock.set(NOW);
		LocalDateTime modified = LocalDateTime.of(2026, 10, 1, 10, 0);
		File legacy = putFile("Test1.json.bak", "alt", modified);

		store.runStartupMaintenance();

		assertFalse(legacy.exists());
		List<Backup> backups = store.list("Test1");
		assertEquals(1, backups.size());
		assertEquals(modified, backups.get(0).timestamp());
		assertArrayEquals("alt".getBytes(StandardCharsets.UTF_8), store.read(backups.get(0)));
	}

	@Test
	void duplicateBakFiles_areLeftAlone() throws IOException {
		clock.set(NOW);
		File duplicate = putFile("x.json.duplicate-123.bak", "dup", LocalDateTime.of(2026, 10, 1, 10, 0));

		store.runStartupMaintenance();

		assertTrue(duplicate.exists());
		assertTrue(store.idsWithBackups().isEmpty());
	}

	@Test
	void staleTmpFiles_olderThanOneDay_areDeleted() throws IOException {
		clock.set(NOW);
		File stale = putFile("a.tmp", "t", NOW.minusHours(25));
		File fresh = putFile("b.tmp", "t", NOW.minusHours(23));

		store.runStartupMaintenance();

		assertFalse(stale.exists());
		assertTrue(fresh.exists());
	}

	@Test
	void maintenance_prunesAllIds() throws IOException {
		clock.set(NOW);
		putBackup("x", LocalDateTime.of(2026, 10, 1, 9, 0));
		putBackup("x", LocalDateTime.of(2026, 10, 1, 18, 0));

		store.runStartupMaintenance();

		assertEquals(List.of(LocalDateTime.of(2026, 10, 1, 18, 0)), timestamps("x"));
	}

	@Test
	void maintenance_survivesMissingDataDir() {
		BackupStore missing = new BackupStore(new File(dataDir, "gibt-es-nicht"), clock);

		missing.runStartupMaintenance();
	}

	@Test
	void backupBeforeOverwrite_prunesAfterwards() throws IOException {
		clock.set(NOW);
		putBackup("a", LocalDateTime.of(2026, 10, 12, 9, 0));
		putBackup("a", LocalDateTime.of(2026, 10, 12, 20, 0));
		File current = writeCurrent("a", "neu");

		assertTrue(store.backupBeforeOverwrite("a", current));

		assertEquals(List.of(NOW, LocalDateTime.of(2026, 10, 12, 20, 0)), timestamps("a"));
	}
}
