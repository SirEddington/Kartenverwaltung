package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.HallRepository;

class HallSaverTest {

	/** Repository, das nur merkt, welche Hallen gespeichert wurden. */
	private static final class RecordingRepository implements HallRepository {
		final List<Hall> saved = new ArrayList<>();
		RuntimeException failWith;

		@Override
		public void saveHall(Hall hall) {
			if (failWith != null) {
				throw failWith;
			}
			saved.add(hall);
		}

		@Override
		public List<Hall> loadHalls() {
			return List.of();
		}

		@Override
		public Hall findById(String hallId) {
			return null;
		}

		@Override
		public void saveHalls(List<Hall> halls) {
			halls.forEach(this::saveHall);
		}

		@Override
		public void deleteHalls(List<Hall> halls) {
		}

		@Override
		public void deleteHall(Hall hall) {
		}
	}

	private static Hall hall() {
		Hall hall = new Hall();
		hall.changeName("Halle 1");
		return hall;
	}

	@Test
	void hallIsSavedThroughTheRepository() {
		RecordingRepository repository = new RecordingRepository();
		Hall hall = hall();

		assertTrue(HallSaver.trySave(hall, repository));
		assertEquals(List.of(hall), repository.saved);
	}

	@Test
	void repositoryFailureIsNotSwallowed() {
		RecordingRepository repository = new RecordingRepository();
		repository.failWith = new IllegalStateException("Datei gesperrt");

		// Das Weiterreichen ist gewollt: den Fehler zeigt der GlobalErrorHandler als SYS-001
		assertThrows(IllegalStateException.class, () -> HallSaver.trySave(hall(), repository));
	}
}
