package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.EventRepository;

class EventSaverTest {

	/** Repository, das nur merkt, welche Events gespeichert wurden. */
	private static final class RecordingRepository implements EventRepository {
		final List<Event> saved = new ArrayList<>();
		RuntimeException failWith;

		@Override
		public void saveEvent(Event event) {
			if (failWith != null) {
				throw failWith;
			}
			saved.add(event);
		}

		@Override
		public List<Event> loadEvents() {
			return List.of();
		}

		@Override
		public void saveEvents(List<Event> events) {
			events.forEach(this::saveEvent);
		}

		@Override
		public void deleteEvents(List<Event> events) {
		}

		@Override
		public void deleteEvent(Event event) {
		}
	}

	/** Vollständige Vorstellung mit einem Tisch und einem Sitz zu 10 Euro, ohne jede Meldung. */
	private static Event validEvent() {
		Event event = new Event();
		event.changeName("Kampagne");
		Presentation presentation = event.addPresentation("Prunksitzung");
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.setHallId("halle-1");
		Table table = presentation.addTable(1);
		table.addSeat(1).setPrice(1000);
		return event;
	}

	private static void firstSeat(Event event, java.util.function.Consumer<de.eltviller_carneval_verein.karten.model.Seat> change) {
		change.accept(event.getPresentations().get(0).getTables().get(0).getSeats().get(0));
	}

	@Test
	void validEventIsSavedWithoutMessages() {
		RecordingRepository repository = new RecordingRepository();
		Event event = validEvent();

		EventSaver.SaveResult result = EventSaver.trySave(event, repository);

		assertTrue(result.saved());
		assertTrue(result.validation().isEmpty());
		assertEquals(List.of(event), repository.saved);
	}

	@Test
	void eventWithAnErrorIsNotSaved() {
		RecordingRepository repository = new RecordingRepository();
		Event event = validEvent();
		firstSeat(event, seat -> seat.setPrice(-100));

		EventSaver.SaveResult result = EventSaver.trySave(event, repository);

		assertFalse(result.saved());
		assertTrue(repository.saved.isEmpty(), "Bei Fehlern darf nichts gespeichert werden");
		assertEquals("SEA-001", result.validation().mostSevere().orElseThrow().code());
	}

	@Test
	void allErrorsAreReportedNotOnlyTheFirst() {
		RecordingRepository repository = new RecordingRepository();
		Event event = validEvent();
		event.getPresentations().get(0).addTable(2).addSeat(1).setPrice(-5);
		firstSeat(event, seat -> seat.setPrice(2_000_000));

		EventSaver.SaveResult result = EventSaver.trySave(event, repository);

		assertFalse(result.saved());
		assertEquals(2, result.validation().getIssues().size());
	}

	@Test
	void eventWithOnlyWarningsIsSavedAndWarningsAreReturned() {
		RecordingRepository repository = new RecordingRepository();
		Event event = validEvent();
		event.getPresentations().get(0).setHallId(null);

		EventSaver.SaveResult result = EventSaver.trySave(event, repository);

		assertTrue(result.saved());
		assertEquals(List.of(event), repository.saved);
		assertEquals(1, result.validation().getIssues().size());
		assertEquals("PRS-003", result.validation().getIssues().get(0).code());
	}

	@Test
	void errorsAndWarningsTogetherStillBlockSaving() {
		RecordingRepository repository = new RecordingRepository();
		Event event = validEvent();
		event.getPresentations().get(0).setHallId(null);
		firstSeat(event, seat -> seat.setPrice(-1));

		assertFalse(EventSaver.trySave(event, repository).saved());
		assertTrue(repository.saved.isEmpty());
	}

	@Test
	void repositoryFailureIsNotSwallowed() {
		RecordingRepository repository = new RecordingRepository();
		repository.failWith = new IllegalStateException("Datei gesperrt");

		// Das Weiterreichen ist gewollt: den Fehler zeigt der GlobalErrorHandler als SYS-001
		assertThrows(IllegalStateException.class, () -> EventSaver.trySave(validEvent(), repository));
	}
}
