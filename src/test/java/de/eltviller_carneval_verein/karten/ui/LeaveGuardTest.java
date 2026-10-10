package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.tracking.StateRestorer;

class LeaveGuardTest {

	private ChangeTracker<Event> eventTracker;
	private ChangeTracker<Hall> hallTracker;
	private final List<Event> eventCache = new ArrayList<>();
	private final List<Hall> hallCache = new ArrayList<>();
	private final List<List<String>> questions = new ArrayList<>();
	private final List<Event> eventSaveAttempts = new ArrayList<>();
	private final List<Hall> hallSaveAttempts = new ArrayList<>();
	private boolean eventSaveSucceeds = true;
	private boolean hallSaveSucceeds = true;

	@BeforeEach
	void setUp() {
		eventTracker = new ChangeTracker<>(Event::getId);
		hallTracker = new ChangeTracker<>(Hall::getId);
	}

	private static Event event(String name) {
		Event event = new Event();
		event.changeName(name);
		event.addPresentation("Prunksitzung").addTable(1).addSeat(1).setPrice(1000);
		return event;
	}

	private static Hall hall(String name) {
		Hall hall = new Hall();
		hall.changeName(name);
		hall.setHallWidth(1000);
		return hall;
	}

	private static void change(Event event) {
		event.getPresentations().get(0).getTables().get(0).getSeats().get(0).setPrice(2500);
	}

	private static void change(Hall hall) {
		hall.setHallWidth(2000);
	}

	private List<LeaveGuard.Scope<?>> scopes() {
		Predicate<Event> eventSaver = event -> {
			eventSaveAttempts.add(event);
			if (eventSaveSucceeds) {
				eventTracker.markSaved(event);
			}
			return eventSaveSucceeds;
		};
		Predicate<Hall> hallSaver = hall -> {
			hallSaveAttempts.add(hall);
			if (hallSaveSucceeds) {
				hallTracker.markSaved(hall);
			}
			return hallSaveSucceeds;
		};
		return List.of(new LeaveGuard.Scope<>("Event", eventTracker, new StateRestorer<>(Event.class, eventTracker, eventCache::add), eventSaver),
				new LeaveGuard.Scope<>("Halle", hallTracker, new StateRestorer<>(Hall.class, hallTracker, hallCache::add), hallSaver));
	}

	private boolean leave(LeaveGuard.Decision decision) {
		Function<List<String>, LeaveGuard.Decision> ask = dirty -> {
			questions.add(dirty);
			return decision;
		};
		return LeaveGuard.confirmLeave(scopes(), ask);
	}

	@Test
	void cleanEntitiesMeanLeavingWithoutAQuestion() {
		Event event = event("Kampagne");
		Hall hall = hall("Halle 1");
		eventTracker.track(event);
		hallTracker.track(hall);

		assertTrue(leave(LeaveGuard.Decision.CANCEL));
		assertTrue(questions.isEmpty());
		assertNull(eventTracker.baseline(event), "Der Tracker gilt nur je Screen-Sitzung");
		assertNull(hallTracker.baseline(hall));
	}

	@Test
	void changeNotYetComparedByTheFilterStillCounts() {
		Event event = event("Kampagne");
		eventTracker.track(event);
		change(event); // die 300 ms Entprellung sind noch nicht abgelaufen, kein check() erfolgt

		assertTrue(leave(LeaveGuard.Decision.DISCARD));
		assertEquals(List.of(List.of("Event \"Kampagne\"")), questions);
	}

	@Test
	void changedHallAlsoTriggersTheQuestion() {
		Hall hall = hall("Halle 1");
		hallTracker.track(hall);
		change(hall);

		assertTrue(leave(LeaveGuard.Decision.DISCARD));
		assertEquals(List.of(List.of("Halle \"Halle 1\"")), questions);
		assertEquals(1, hallCache.size());
		assertEquals(1000, hallCache.get(0).getHallWidth(), "Gespeicherter Stand der Halle wiederhergestellt");
	}

	@Test
	void questionListsEventsAndHallsTogether() {
		Event event = event("Kampagne");
		Hall hall = hall("Halle 1");
		eventTracker.track(event);
		hallTracker.track(hall);
		change(event);
		change(hall);

		leave(LeaveGuard.Decision.CANCEL);

		assertEquals(List.of(List.of("Event \"Kampagne\"", "Halle \"Halle 1\"")), questions);
	}

	@Test
	void cancelKeepsTheScreenAndTheChanges() {
		Event event = event("Kampagne");
		Hall hall = hall("Halle 1");
		eventTracker.track(event);
		hallTracker.track(hall);
		change(event);
		change(hall);

		assertFalse(leave(LeaveGuard.Decision.CANCEL));
		assertTrue(eventTracker.isDirty(event));
		assertTrue(hallTracker.isDirty(hall));
		assertTrue(eventSaveAttempts.isEmpty() && hallSaveAttempts.isEmpty());
		assertTrue(eventCache.isEmpty() && hallCache.isEmpty());
	}

	@Test
	void saveSavesAllChangedEntitiesOfBothKindsAndLeaves() {
		Event changed1 = event("Eins");
		Event changed2 = event("Zwei");
		Event untouched = event("Drei");
		Hall hall = hall("Halle 1");
		eventTracker.track(changed1);
		eventTracker.track(changed2);
		eventTracker.track(untouched);
		hallTracker.track(hall);
		change(changed1);
		change(changed2);
		change(hall);

		assertTrue(leave(LeaveGuard.Decision.SAVE));
		assertEquals(List.of(changed1, changed2), eventSaveAttempts);
		assertEquals(List.of(hall), hallSaveAttempts);
		assertFalse(eventTracker.anyDirty() || hallTracker.anyDirty());
	}

	@Test
	void failedSaveKeepsTheScreen() {
		Event event = event("Kampagne");
		eventTracker.track(event);
		change(event);
		eventSaveSucceeds = false;

		assertFalse(leave(LeaveGuard.Decision.SAVE));
		assertEquals(List.of(event), eventSaveAttempts);
		assertTrue(eventTracker.isDirty(event), "Nichts gespeichert, die Änderungen bleiben erhalten");
		assertNotNull(eventTracker.baseline(event), "Der Tracker bleibt für den Screen erhalten");
	}

	@Test
	void failedEventSaveStopsBeforeTheHallsAreSaved() {
		Event event = event("Kampagne");
		Hall hall = hall("Halle 1");
		eventTracker.track(event);
		hallTracker.track(hall);
		change(event);
		change(hall);
		eventSaveSucceeds = false;

		assertFalse(leave(LeaveGuard.Decision.SAVE));
		assertTrue(hallSaveAttempts.isEmpty());
		assertTrue(hallTracker.isDirty(hall));
	}

	@Test
	void failedSaveStopsAtTheFirstFailure() {
		Event first = event("Eins");
		Event second = event("Zwei");
		eventTracker.track(first);
		eventTracker.track(second);
		change(first);
		change(second);
		eventSaveSucceeds = false;

		assertFalse(leave(LeaveGuard.Decision.SAVE));
		assertEquals(List.of(first), eventSaveAttempts);
	}

	@Test
	void discardRestoresTheSavedStateOfEveryChangedEntity() {
		Event changed = event("Eins");
		Event untouched = event("Zwei");
		Hall hall = hall("Halle 1");
		eventTracker.track(changed);
		eventTracker.track(untouched);
		hallTracker.track(hall);
		change(changed);
		change(hall);

		assertTrue(leave(LeaveGuard.Decision.DISCARD));
		assertEquals(1, eventCache.size());
		assertEquals(changed, eventCache.get(0));
		assertEquals(1000, eventCache.get(0).getPresentations().get(0).getTables().get(0).getSeats().get(0).getPrice());
		assertEquals(1, hallCache.size());
		assertTrue(eventSaveAttempts.isEmpty() && hallSaveAttempts.isEmpty());
	}

	@Test
	void messageListsWhatIsChanged() {
		String message = LeaveGuard.message(List.of("Event \"Kampagne\"", "Halle \"Halle 1\""));

		assertTrue(message.contains("• Event \"Kampagne\"\n• Halle \"Halle 1\""), message);
		assertTrue(message.endsWith("Möchtest du sie speichern?"));
	}

	@Test
	void messageShortensLongLists() {
		List<String> many = new ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			many.add("Event \"E" + i + "\"");
		}

		String message = LeaveGuard.message(many);

		assertTrue(message.contains("E5"), message);
		assertTrue(message.contains("... und 3 weitere"), message);
		assertFalse(message.contains("E6"));
	}
}
