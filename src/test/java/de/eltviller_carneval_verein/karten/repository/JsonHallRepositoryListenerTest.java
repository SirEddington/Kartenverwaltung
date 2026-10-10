package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Hall;

class JsonHallRepositoryListenerTest {

	private static final class Recorder implements RepositoryListener<Hall> {
		final List<String> calls = new ArrayList<>();

		@Override
		public void saved(Hall hall) {
			calls.add("saved " + hall.getName());
		}

		@Override
		public void deleted(Hall hall) {
			calls.add("deleted " + hall.getName());
		}
	}

	private static Hall named(String name) {
		Hall hall = new Hall();
		hall.changeName(name);
		return hall;
	}

	@Test
	void listenersHearAboutEverySaveAndDelete(@TempDir File storageDir) {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);
		Hall hall = named("Halle 1");

		repository.saveHall(hall);
		repository.saveHalls(List.of(hall));
		repository.deleteHall(hall);

		assertEquals(List.of("saved Halle 1", "saved Halle 1", "deleted Halle 1"), recorder.calls);
	}

	@Test
	void replaceCachedHallSwapsTheObjectWithoutTouchingTheFile(@TempDir File storageDir) throws IOException {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Hall original = named("Vorher");
		repository.saveHall(original);
		File file = new File(storageDir, original.getId() + ".json");
		byte[] fileBefore = Files.readAllBytes(file.toPath());
		Recorder recorder = new Recorder();
		repository.addListener(recorder);

		Hall replacement = new Hall(original.getId());
		replacement.changeName("Nachher");
		repository.replaceCachedHall(replacement);

		assertEquals(1, repository.loadHalls().size());
		assertSame(replacement, repository.loadHalls().get(0));
		assertArrayEquals(fileBefore, Files.readAllBytes(file.toPath()));
		assertTrue(recorder.calls.isEmpty(), "Ersetzen im Cache ist kein Speichern");
	}

	@Test
	void replaceCachedHallIgnoresAHallThatIsNotInTheCache(@TempDir File storageDir) {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		repository.loadHalls();

		repository.replaceCachedHall(named("Unbekannt"));

		assertTrue(repository.loadHalls().isEmpty());
	}
}
