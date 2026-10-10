package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Event;

class JsonEventRepositoryListenerTest {

	private static final class Recorder implements RepositoryListener<Event> {
		final List<String> calls = new ArrayList<>();

		@Override
		public void saved(Event event) {
			calls.add("saved " + event.getName());
		}

		@Override
		public void deleted(Event event) {
			calls.add("deleted " + event.getName());
		}
	}

	private static Event named(String name) {
		Event event = new Event();
		event.changeName(name);
		return event;
	}

	@Test
	void listenersHearAboutEverySaveAndDelete(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);
		Event event = named("Kampagne");

		repository.saveEvent(event);
		repository.saveEvents(List.of(event));
		repository.deleteEvent(event);

		assertEquals(List.of("saved Kampagne", "saved Kampagne", "deleted Kampagne"), recorder.calls);
	}

	@Test
	void failingListenerNeitherFailsTheSaveNorSkipsTheOthers(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		repository.addListener(new RepositoryListener<Event>() {
			@Override
			public void saved(Event event) {
				throw new IllegalStateException("kaputter Listener");
			}

			@Override
			public void deleted(Event event) {
				throw new IllegalStateException("kaputter Listener");
			}
		});
		Recorder recorder = new Recorder();
		repository.addListener(recorder);
		Event event = named("Kampagne");

		// Die Datei ist geschrieben, also darf der Aufrufer keine Ausnahme sehen
		repository.saveEvent(event);
		repository.deleteEvent(event);

		assertEquals(List.of("saved Kampagne", "deleted Kampagne"), recorder.calls);
		assertFalse(new File(storageDir, event.getId() + ".json").exists(), "gelöscht");
	}

	@Test
	void failedSaveDoesNotNotifyListeners(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);

		assertThrows(IllegalArgumentException.class, () -> repository.saveEvent(new Event()));

		assertTrue(recorder.calls.isEmpty());
	}

	@Test
	void replaceCachedEventSwapsTheObjectWithoutTouchingTheFile(@TempDir File storageDir) throws IOException {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		Event original = named("Vorher");
		repository.saveEvent(original);
		File file = new File(storageDir, original.getId() + ".json");
		byte[] fileBefore = Files.readAllBytes(file.toPath());
		Recorder recorder = new Recorder();
		repository.addListener(recorder);

		Event replacement = new Event(original.getId());
		replacement.changeName("Nachher");
		repository.replaceCachedEvent(replacement);

		assertEquals(1, repository.loadEvents().size());
		assertSame(replacement, repository.loadEvents().get(0));
		assertArrayEquals(fileBefore, Files.readAllBytes(file.toPath()));
		assertTrue(recorder.calls.isEmpty(), "Ersetzen im Cache ist kein Speichern");
	}

	@Test
	void replaceCachedEventIgnoresAnEventThatIsNotInTheCache(@TempDir File storageDir) {
		JsonEventRepository repository = new JsonEventRepository(storageDir);
		repository.loadEvents();

		repository.replaceCachedEvent(named("Unbekannt"));

		assertTrue(repository.loadEvents().isEmpty());
	}
}
