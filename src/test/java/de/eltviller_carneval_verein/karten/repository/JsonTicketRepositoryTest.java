package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Event;

class JsonTicketRepositoryTest {

	@Test
	void saveAndLoad_roundTrip(@TempDir File storageDir) {
		JsonTicketRepository repository = new JsonTicketRepository(storageDir);
		Event event = new Event();
		event.changeName("Testevent");
		repository.saveEvent(event);

		JsonTicketRepository reopened = new JsonTicketRepository(storageDir);
		List<Event> loaded = reopened.loadEvents();

		assertEquals(1, loaded.size());
		assertEquals(event.getId(), loaded.get(0).getId());
		assertEquals("Testevent", loaded.get(0).getName());
	}

	@Test
	void renamingEvent_doesNotLeaveOrphanFile(@TempDir File storageDir) {
		JsonTicketRepository repository = new JsonTicketRepository(storageDir);
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
		JsonTicketRepository repository = new JsonTicketRepository(storageDir);

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
	void secondSave_createsBackupOfPreviousVersion(@TempDir File storageDir) {
		JsonTicketRepository repository = new JsonTicketRepository(storageDir);
		Event event = new Event();
		event.changeName("Testevent");
		repository.saveEvent(event);

		event.changeName("Testevent (geaendert)");
		repository.saveEvent(event);

		File backupFile = new File(storageDir, event.getId() + ".json.bak");
		assertTrue(backupFile.exists(), "Beim zweiten Speichern sollte die vorherige Fassung als .bak gesichert werden.");
	}

	@Test
	void unreadableFile_isSkippedAndReportedAsWarning(@TempDir File storageDir) throws IOException {
		File corruptFile = new File(storageDir, "kaputt.json");
		Files.writeString(corruptFile.toPath(), "das ist kein gueltiges JSON", StandardCharsets.UTF_8);

		JsonTicketRepository repository = new JsonTicketRepository(storageDir);
		List<Event> loaded = repository.loadEvents();

		assertTrue(loaded.isEmpty());
		List<String> warnings = repository.getAndClearLoadWarnings();
		assertFalse(warnings.isEmpty());
		assertTrue(warnings.get(0).contains("kaputt.json"));
	}

	@Test
	void duplicateEventId_keepsNewerFileAndSetsOlderAside(@TempDir File storageDir) throws IOException {
		JsonTicketRepository repository = new JsonTicketRepository(storageDir);
		Event event = new Event();
		event.changeName("Altlast");
		repository.saveEvent(event);

		// Alte, namensbasierte Datei simulieren: dieselbe Event-ID, aber unter einem anderen
		// Dateinamen abgelegt (wie es fruehere, namensbasierte Dateinamen hinterlassen haben).
		File legacyNamedFile = new File(storageDir, "Altlast_legacy.json");
		Files.copy(new File(storageDir, event.getId() + ".json").toPath(), legacyNamedFile.toPath());
		legacyNamedFile.setLastModified(System.currentTimeMillis() - 60_000);

		JsonTicketRepository reopened = new JsonTicketRepository(storageDir);
		List<Event> loaded = reopened.loadEvents();

		assertEquals(1, loaded.size(), "Zwei Dateien mit derselben Event-ID sollten beim Laden zu genau einem Event zusammengefuehrt werden.");
		assertFalse(legacyNamedFile.exists(), "Die aeltere Duplikat-Datei sollte umbenannt (beiseitegelegt) worden sein.");
		assertFalse(reopened.getAndClearLoadWarnings().isEmpty());
	}
}
