package de.eltviller_carneval_verein.karten.ui.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.BackupStore;
import de.eltviller_carneval_verein.karten.repository.BackupStore.Backup;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Entry;

class EventBackupCatalogTest {

	@TempDir
	File dataDir;

	private final ObjectMapper mapper = JsonMapperFactory.create();
	private BackupStore store;
	private final List<Event> current = new ArrayList<>();
	private final List<Event> saved = new ArrayList<>();
	private final List<Event> replaced = new ArrayList<>();
	private final Set<String> existingHalls = Set.of("hallA");
	private RuntimeException saveFailure;

	@BeforeEach
	void setUp() {
		store = new BackupStore(dataDir);
	}

	private EventBackupCatalog catalog() {
		return new EventBackupCatalog(store, () -> current, existingHalls::contains, event -> {
			if (saveFailure != null) {
				throw saveFailure;
			}
			saved.add(event);
		}, replaced::add);
	}

	private static Event event(String name) {
		Event event = new Event();
		event.changeName(name);
		return event;
	}

	/** Schreibt die Datei des Events und sichert sie, wie es das Repository vor dem Überschreiben täte. */
	private Backup makeBackup(Event event) throws IOException {
		File file = new File(dataDir, event.getId() + ".json");
		mapper.writeValue(file, event);
		store.backupBeforeOverwrite(event.getId(), file);
		return store.list(event.getId()).get(0);
	}

	private Backup putRawBackup(String id, String content) throws IOException {
		File dir = new File(new File(dataDir, "backups"), id);
		dir.mkdirs();
		Files.writeString(new File(dir, "2026-01-01_00-00-00-000.json").toPath(), content, StandardCharsets.UTF_8);
		return store.list(id).get(0);
	}

	@Test
	void entries_listsExistingAndDeletedEvents_sortedByName() throws IOException {
		Event existing = event("Alpha");
		makeBackup(existing);
		existing.changeName("Alpha neu");
		current.add(existing);
		Event deleted = event("Beta");
		makeBackup(deleted);

		List<Entry> entries = catalog().entries();

		assertEquals(List.of(new Entry(existing.getId(), "Alpha neu", false), new Entry(deleted.getId(), "Beta", true)), entries);
	}

	@Test
	void entries_unreadableNewestCopy_usesIdAsName() throws IOException {
		putRawBackup("xyz", "kein json");

		assertEquals(List.of(new Entry("xyz", "xyz", true)), catalog().entries());
	}

	@Test
	void versions_areTheStoreCopiesNewestFirst() throws IOException {
		Event event = event("Alpha");
		makeBackup(event);
		event.changeName("Alpha 2");
		Backup newest = makeBackup(event);

		List<Backup> versions = catalog().versions(event.getId());

		assertEquals(2, versions.size());
		assertEquals(newest, versions.get(0));
	}

	@Test
	void restore_existingEvent_savesAndReplacesCache() throws IOException {
		Event event = event("Alpha");
		Backup backup = makeBackup(event);
		current.add(event);
		event.changeName("Kaputt");

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Kaputt", false), backup);

		assertTrue(result.success());
		assertTrue(result.message().contains("Alpha"));
		assertTrue(result.notes().isEmpty());
		assertEquals(1, saved.size());
		assertEquals(event.getId(), saved.get(0).getId());
		assertEquals("Alpha", saved.get(0).getName());
		assertEquals(1, replaced.size());
		assertSame(saved.get(0), replaced.get(0));
	}

	@Test
	void restore_deletedEvent_isSavedAgain() throws IOException {
		Event event = event("Beta");
		Backup backup = makeBackup(event);

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Beta", true), backup);

		assertTrue(result.success());
		assertEquals("Beta", saved.get(0).getName());
		assertEquals(1, replaced.size());
	}

	@Test
	void restore_removesMissingHall_withNote() throws IOException {
		Event event = event("Alpha");
		Presentation kept = event.addPresentation("Prunksitzung");
		kept.setHallId("hallA");
		Presentation orphaned = event.addPresentation("Damensitzung");
		orphaned.setHallId("hallB");
		Backup backup = makeBackup(event);

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Alpha", true), backup);

		assertTrue(result.success());
		Event restored = saved.get(0);
		assertEquals("hallA", restored.getPresentations().get(0).getHallId());
		assertNull(restored.getPresentations().get(1).getHallId());
		assertEquals(1, result.notes().size());
		assertTrue(result.notes().get(0).contains("Damensitzung"));
	}

	@Test
	void restore_eventWithoutPresentations_doesNotThrow() throws IOException {
		Event event = event("Alpha");
		event.setPresentations(null);
		Backup backup = makeBackup(event);

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Alpha", true), backup);

		assertTrue(result.success());
		assertEquals(1, saved.size());
	}

	@Test
	void restore_corruptCopy_changesNothing() throws IOException {
		Backup backup = putRawBackup("xyz", "das ist kein JSON");

		RestoreResult result = catalog().restore(new Entry("xyz", "xyz", true), backup);

		assertFalse(result.success());
		assertTrue(result.message().contains(backup.file().getFileName().toString()));
		assertTrue(saved.isEmpty());
		assertTrue(replaced.isEmpty());
	}

	@Test
	void restore_emptyCopy_changesNothing() throws IOException {
		Backup backup = putRawBackup("xyz", "");

		RestoreResult result = catalog().restore(new Entry("xyz", "xyz", true), backup);

		assertFalse(result.success());
		assertTrue(saved.isEmpty());
		assertTrue(replaced.isEmpty());
	}

	@Test
	void restore_copyOfAnotherEvent_isRejected() throws IOException {
		Event event = event("Alpha");
		Backup backup = makeBackup(event);

		RestoreResult result = catalog().restore(new Entry("anderes-event", "x", true), backup);

		assertFalse(result.success());
		assertTrue(saved.isEmpty());
	}

	@Test
	void restore_saveFails_doesNotReplaceCache() throws IOException {
		Event event = event("Alpha");
		Backup backup = makeBackup(event);
		saveFailure = new IllegalStateException("Datei gesperrt");

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Alpha", true), backup);

		assertFalse(result.success());
		assertTrue(replaced.isEmpty());
	}

	@Test
	void restore_matchingNewestCopy_createsNoDuplicate() throws IOException {
		Event event = event("Alpha");
		Backup backup = makeBackup(event);
		File file = new File(dataDir, event.getId() + ".json");
		// Wie das Repository: erst die Datei sichern, dann überschreiben
		EventBackupCatalog catalog = new EventBackupCatalog(store, () -> current, existingHalls::contains, restored -> {
			store.backupBeforeOverwrite(restored.getId(), file);
			try {
				mapper.writeValue(file, restored);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}, replaced::add);

		RestoreResult result = catalog.restore(new Entry(event.getId(), "Alpha", false), backup);

		assertTrue(result.success());
		assertEquals(1, store.list(event.getId()).size(), "Die Datei entsprach schon der neuesten Kopie");
	}
}
