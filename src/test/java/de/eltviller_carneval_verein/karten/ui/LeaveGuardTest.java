package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.tracking.EventRestorer;

class LeaveGuardTest {

	private ChangeTracker tracker;
	private EventRestorer restorer;
	private final List<Event> cache = new ArrayList<>();
	private final List<List<Event>> questions = new ArrayList<>();
	private final List<Event> saveAttempts = new ArrayList<>();

	@BeforeEach
	void setUp() {
		tracker = new ChangeTracker();
		restorer = new EventRestorer(tracker, cache::add);
	}

	private static Event event(String name) {
		Event event = new Event();
		event.changeName(name);
		event.addPresentation("Prunksitzung").addTable(1).addSeat(1).setPrice(1000);
		return event;
	}

	private static void change(Event event) {
		event.getPresentations().get(0).getTables().get(0).getSeats().get(0).setPrice(2500);
	}

	private Function<List<Event>, LeaveGuard.Decision> answering(LeaveGuard.Decision decision) {
		return dirty -> {
			questions.add(dirty);
			return decision;
		};
	}

	private Predicate<Event> saver(boolean succeeds) {
		return event -> {
			saveAttempts.add(event);
			if (succeeds) {
				tracker.markSaved(event);
			}
			return succeeds;
		};
	}

	private boolean leave(LeaveGuard.Decision decision, boolean saveSucceeds) {
		return LeaveGuard.confirmLeave(tracker, answering(decision), saver(saveSucceeds), restorer);
	}

	@Test
	void cleanEventsMeanLeavingWithoutAQuestion() {
		Event event = event("Kampagne");
		tracker.track(event);

		assertTrue(leave(LeaveGuard.Decision.CANCEL, true));
		assertTrue(questions.isEmpty());
		assertEquals(null, tracker.baseline(event), "Der Tracker gilt nur je Screen-Sitzung");
	}

	@Test
	void changeNotYetComparedByTheFilterStillCounts() {
		Event event = event("Kampagne");
		tracker.track(event);
		change(event); // die 300 ms Entprellung sind noch nicht abgelaufen, kein check() erfolgt

		assertTrue(leave(LeaveGuard.Decision.DISCARD, true));
		assertEquals(1, questions.size());
		assertEquals(List.of(event), questions.get(0));
	}

	@Test
	void cancelKeepsTheScreenAndTheChanges() {
		Event event = event("Kampagne");
		tracker.track(event);
		change(event);

		assertFalse(leave(LeaveGuard.Decision.CANCEL, true));
		assertTrue(tracker.isDirty(event));
		assertTrue(saveAttempts.isEmpty());
		assertTrue(cache.isEmpty());
	}

	@Test
	void saveSavesAllChangedEventsAndLeaves() {
		Event changed1 = event("Eins");
		Event changed2 = event("Zwei");
		Event untouched = event("Drei");
		tracker.track(changed1);
		tracker.track(changed2);
		tracker.track(untouched);
		change(changed1);
		change(changed2);

		assertTrue(leave(LeaveGuard.Decision.SAVE, true));
		assertEquals(List.of(changed1, changed2), saveAttempts);
		assertFalse(tracker.anyDirty());
	}

	@Test
	void failedSaveKeepsTheScreen() {
		Event event = event("Kampagne");
		tracker.track(event);
		change(event);

		assertFalse(leave(LeaveGuard.Decision.SAVE, false));
		assertEquals(List.of(event), saveAttempts);
		assertTrue(tracker.isDirty(event), "Nichts gespeichert, die Änderungen bleiben erhalten");
		assertNotNull(tracker.baseline(event), "Der Tracker bleibt für den Screen erhalten");
	}

	@Test
	void failedSaveStopsAtTheFirstFailure() {
		Event first = event("Eins");
		Event second = event("Zwei");
		tracker.track(first);
		tracker.track(second);
		change(first);
		change(second);

		assertFalse(leave(LeaveGuard.Decision.SAVE, false));
		assertEquals(List.of(first), saveAttempts);
	}

	@Test
	void discardRestoresTheSavedStateOfEveryChangedEvent() {
		Event changed = event("Eins");
		Event untouched = event("Zwei");
		tracker.track(changed);
		tracker.track(untouched);
		change(changed);

		assertTrue(leave(LeaveGuard.Decision.DISCARD, true));
		assertEquals(1, cache.size());
		assertEquals(changed, cache.get(0));
		assertEquals(1000, cache.get(0).getPresentations().get(0).getTables().get(0).getSeats().get(0).getPrice());
		assertTrue(saveAttempts.isEmpty());
	}

	@Test
	void messageNamesTheChangedEvents() {
		assertEquals("Das Event \"Kampagne\" hat ungespeicherte Änderungen.\n\nMöchtest du sie speichern?", LeaveGuard.message(List.of(event("Kampagne"))));
		assertTrue(LeaveGuard.message(List.of(event("A"), event("B"))).startsWith("Die Events \"A\", \"B\" haben"));
	}

	@Test
	void messageShortensLongLists() {
		List<Event> many = new ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			many.add(event("E" + i));
		}

		String message = LeaveGuard.message(many);

		assertTrue(message.contains("\"E5\" und 3 weitere"), message);
		assertFalse(message.contains("E6"));
	}
}
