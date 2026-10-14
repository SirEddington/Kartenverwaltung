package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Event;

class JsonEventRepositoryTest {

	@Test
	void saveAndLoad_roundTrip(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event event = new Event();
		event.changeName("Testevent");
		repository.saveEvent(event);

		JsonEventRepository reopened = new JsonEventRepository(storageDir);
		List<Event> loaded = reopened.loadEvents();

		assertEquals(1, loaded.size());
		assertEquals(event.getId(), loaded.get(0).getId());
		assertEquals("Testevent", loaded.get(0).getName());
	}

	@Test
	void renamingEvent_doesNotLeaveOrphanFile(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event event = new Event();
		event.changeName("Sitzung 2026");
		repository.saveEvent(event);

		event.changeName("Sitzung 2026 (umbenannt)");
		repository.saveEvent(event);

		File[] jsonFiles = storageDir.listFiles((dir, name) -> name.endsWith(".json"));
		assertNotNull(jsonFiles);
		assertEquals(1, jsonFiles.length, "Nach dem Umbenennen sollte genau eine Event-Datei existieren, keine Dateileiche.");
		assertEquals(event.getId() + ".json", jsonFiles[0].getName());
	}

	@Test
	void twoEventsWithCollidingSanitizedNames_bothSaveable(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);

		Event first = new Event();
		first.changeName("Sitzung/2026");
		Event second = new Event();
		second.changeName("Sitzung 2026");

		assertDoesNotThrow(() -> {
			repository.saveEvent(first);
			repository.saveEvent(second);
		});

		List<Event> loaded = repository.reloadEvents();
		assertEquals(2, loaded.size());
	}

	@Test
	void secondSave_createsBackupInBackupFolder(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event event = new Event();
		event.changeName("Testevent");
		repository.saveEvent(event);

		event.changeName("Testevent (geaendert)");
		repository.saveEvent(event);

		assertEquals(1, repository.backups().list(event.getId()).size(), "Beim zweiten Speichern sollte die vorherige Fassung im Backup-Ordner liegen.");
		assertFalse(new File(storageDir, event.getId() + ".json.bak").exists(), "Die einzelne .bak-Datei gibt es nicht mehr.");
	}

	@Test
	void savingWithoutChange_createsNoSecondBackup(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event event = new Event();
		event.changeName("Testevent");
		repository.saveEvent(event);
		repository.saveEvent(event); // sichert die Datei von eben (noch keine Kopie vorhanden)

		repository.saveEvent(event); // Datei ist unverändert, also gleich der neuesten Kopie

		assertEquals(1, repository.backups().list(event.getId()).size());
	}

	@Test
	void deleteEvent_removesFile_butKeepsBackupsIncludingTheLastState(@TempDir File storageDir) throws IOException {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event event = new Event();
		event.changeName("Testevent");
		repository.saveEvent(event);
		event.changeName("Testevent (geaendert)");
		repository.saveEvent(event);

		repository.deleteEvent(event);

		assertFalse(new File(storageDir, event.getId() + ".json").exists());
		List<BackupStore.Backup> backups = repository.backups().list(event.getId());
		assertEquals(2, backups.size(), "Die Backups bleiben liegen, dazu der Stand zum Zeitpunkt des Löschens.");
		assertTrue(new String(repository.backups().read(backups.get(0)), StandardCharsets.UTF_8).contains("Testevent (geaendert)"));
	}

	@Test
	void deleteEvent_afterSingleSave_stillLeavesABackup(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event event = new Event();
		event.changeName("Testevent");
		repository.saveEvent(event);

		repository.deleteEvent(event);

		assertEquals(1, repository.backups().list(event.getId()).size());
	}

	@Test
	void saveEventRequiringBackup_failsWithoutTouchingTheFile_whenBackupIsImpossible(@TempDir File storageDir) throws IOException {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event event = new Event();
		event.changeName("Testevent");
		repository.saveEvent(event);
		Files.writeString(new File(storageDir, "backups").toPath(), "ich bin eine Datei", StandardCharsets.UTF_8);
		event.changeName("Ueberschrieben");

		assertThrows(IllegalStateException.class, () -> repository.saveEventRequiringBackup(event));

		assertEquals("Testevent", new JsonEventRepository(storageDir).loadEvents().get(0).getName());
	}

	@Test
	void saveEventRequiringBackup_savesAndBacksUpTheCurrentState(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event event = new Event();
		event.changeName("Testevent");
		repository.saveEvent(event);
		event.changeName("Wiederhergestellt");

		repository.saveEventRequiringBackup(event);

		assertEquals(1, repository.backups().list(event.getId()).size());
		assertEquals("Wiederhergestellt", new JsonEventRepository(storageDir).loadEvents().get(0).getName());
	}

	@Test
	void backupFailure_doesNotBlockSaving(@TempDir File storageDir) throws IOException {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event event = new Event();
		event.changeName("Testevent");
		repository.saveEvent(event);
		Files.writeString(new File(storageDir, "backups").toPath(), "ich bin eine Datei", StandardCharsets.UTF_8);
		event.changeName("Testevent (neu)");

		assertDoesNotThrow(() -> repository.saveEvent(event));

		assertEquals("Testevent (neu)", new JsonEventRepository(storageDir).loadEvents().get(0).getName());
	}

	@Test
	void unreadableFile_isSkippedAndReportedAsWarning(@TempDir File storageDir) throws IOException {
		File corruptFile = new File(storageDir, "kaputt.json");
		Files.writeString(corruptFile.toPath(), "das ist kein gueltiges JSON", StandardCharsets.UTF_8);

		JsonEventRepository repository = new JsonEventRepository(storageDir);
		List<Event> loaded = repository.loadEvents();

		assertTrue(loaded.isEmpty());
		List<String> warnings = repository.getAndClearLoadWarnings();
		assertFalse(warnings.isEmpty());
		assertTrue(warnings.get(0).contains("kaputt.json"));
	}

	@Test
	void duplicateEventId_keepsNewerFileAndSetsOlderAside(@TempDir File storageDir) throws IOException {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event event = new Event();
		event.changeName("Altlast");
		repository.saveEvent(event);

		// Alte, namensbasierte Datei simulieren: dieselbe Event-ID, aber unter einem anderen
		// Dateinamen abgelegt (wie es fruehere, namensbasierte Dateinamen hinterlassen haben).
		File legacyNamedFile = new File(storageDir, "Altlast_legacy.json");
		Files.copy(new File(storageDir, event.getId() + ".json").toPath(), legacyNamedFile.toPath());
		legacyNamedFile.setLastModified(System.currentTimeMillis() - 60_000);

		JsonEventRepository reopened = new JsonEventRepository(storageDir);
		List<Event> loaded = reopened.loadEvents();

		assertEquals(1, loaded.size(), "Zwei Dateien mit derselben Event-ID sollten beim Laden zu genau einem Event zusammengefuehrt werden.");
		assertFalse(legacyNamedFile.exists(), "Die aeltere Duplikat-Datei sollte umbenannt (beiseitegelegt) worden sein.");
		assertFalse(reopened.getAndClearLoadWarnings().isEmpty());
	}
}
