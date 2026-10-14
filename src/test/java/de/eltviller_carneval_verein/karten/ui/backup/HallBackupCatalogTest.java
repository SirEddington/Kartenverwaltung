package de.eltviller_carneval_verein.karten.ui.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.BackupStore;
import de.eltviller_carneval_verein.karten.repository.BackupStore.Backup;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Entry;

class HallBackupCatalogTest {

	@TempDir
	File dataDir;

	private final ObjectMapper mapper = JsonMapperFactory.create();
	private BackupStore store;
	private final List<Hall> current = new ArrayList<>();
	private final List<Hall> saved = new ArrayList<>();
	private final List<Hall> replaced = new ArrayList<>();

	@BeforeEach
	void setUp() {
		store = new BackupStore(dataDir);
	}

	private HallBackupCatalog catalog() {
		return new HallBackupCatalog(store, () -> current, saved::add, replaced::add);
	}

	private Backup makeBackup(Hall hall) throws IOException {
		File file = new File(dataDir, hall.getId() + ".json");
		mapper.writeValue(file, hall);
		store.backupBeforeOverwrite(hall.getId(), file);
		return store.list(hall.getId()).get(0);
	}

	private static Hall hall(String name) {
		Hall hall = new Hall();
		hall.changeName(name);
		return hall;
	}

	@Test
	void entries_listsExistingAndDeletedHalls() throws IOException {
		Hall existing = hall("Stadthalle");
		makeBackup(existing);
		current.add(existing);
		Hall deleted = hall("Alte Halle");
		makeBackup(deleted);

		assertEquals(List.of(new Entry(deleted.getId(), "Alte Halle", true), new Entry(existing.getId(), "Stadthalle", false)), catalog().entries());
		assertEquals("Halle", catalog().entityLabel());
	}

	@Test
	void restore_currentStateCannotBeBackedUp_changesNothing() throws IOException {
		// Feste Uhr: Der Name der nächsten Kopie ist bekannt, ein Hindernis dort lässt das Sichern scheitern
		Clock fixed = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneId.systemDefault());
		store = new BackupStore(dataDir, fixed);
		Hall hall = hall("Stadthalle");
		Backup backup = makeBackup(hall);
		hall.changeName("Neuer Stand");
		mapper.writeValue(new File(dataDir, hall.getId() + ".json"), hall);
		String nextCopy = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS").format(LocalDateTime.now(fixed)) + "-2.json.part";
		File obstacle = new File(new File(new File(dataDir, "backups"), hall.getId()), nextCopy);
		assertTrue(new File(obstacle, "blockiert").mkdirs());

		RestoreResult result = catalog().restore(new Entry(hall.getId(), "Neuer Stand", false), backup);

		assertFalse(result.success());
		assertTrue(saved.isEmpty());
		assertTrue(replaced.isEmpty());
	}

	@Test
	void restore_hall_savesAndReplacesCache() throws IOException {
		Hall hall = hall("Stadthalle");
		Backup backup = makeBackup(hall);

		RestoreResult result = catalog().restore(new Entry(hall.getId(), "Stadthalle", true), backup);

		assertTrue(result.success());
		assertTrue(result.message().contains("Stadthalle"));
		assertEquals(hall.getId(), saved.get(0).getId());
		assertSame(saved.get(0), replaced.get(0));
	}
}
