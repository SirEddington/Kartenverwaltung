package de.eltviller_carneval_verein.karten.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker.Snapshot;

class ChangeTrackerTest {

	/** Mitschrift der Listener-Aufrufe. */
	private record Call(Event event, Snapshot before, Snapshot after) {
	}

	private ChangeTracker<Event> tracker;
	private final List<Call> calls = new ArrayList<>();

	@BeforeEach
	void setUp() {
		tracker = new ChangeTracker<>(Event::getId);
		tracker.addListener((event, before, after) -> calls.add(new Call(event, before, after)));
	}

	private static Event event(String name) {
		Event event = new Event();
		event.changeName(name);
		Presentation presentation = event.addPresentation("Prunksitzung");
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.addTable(1).addSeat(1).setPrice(1000);
		return event;
	}

	private static Seat firstSeat(Event event) {
		return event.getPresentations().get(0).getTables().get(0).getSeats().get(0);
	}

	@Test
	void trackedEventIsCleanAndUnchangedCheckReportsNothing() {
		Event event = event("Kampagne");
		tracker.track(event);

		assertFalse(tracker.isDirty(event));
		assertFalse(tracker.check(event));
		assertTrue(calls.isEmpty());
		assertEquals(tracker.baseline(event), tracker.last(event));
	}

	@Test
	void changeDeepInsideTheEventMakesItDirtyAndNotifiesListeners() {
		Event event = event("Kampagne");
		tracker.track(event);
		Snapshot before = tracker.last(event);

		firstSeat(event).setPrice(1500);

		assertTrue(tracker.check(event));
		assertTrue(tracker.isDirty(event));
		assertTrue(tracker.anyDirty());
		assertEquals(1, calls.size());
		assertSame(event, calls.get(0).event());
		assertEquals(before, calls.get(0).before());
		assertNotEquals(before, calls.get(0).after());
		assertEquals(tracker.last(event), calls.get(0).after());
	}

	@Test
	void nothingIsDirtyBeforeTheNextCheck() {
		Event event = event("Kampagne");
		tracker.track(event);

		firstSeat(event).setPrice(1500);

		// Der Tracker beobachtet nichts selbst: Erst der Vergleich stellt die Änderung fest
		assertFalse(tracker.isDirty(event));
	}

	@Test
	void takingTheChangeBackMakesTheEventCleanAgain() {
		Event event = event("Kampagne");
		tracker.track(event);

		firstSeat(event).setPrice(1500);
		tracker.check(event);
		firstSeat(event).setPrice(1000);

		assertTrue(tracker.check(event));
		assertFalse(tracker.isDirty(event));
		assertFalse(tracker.anyDirty());
		assertEquals(2, calls.size());
		assertEquals(tracker.baseline(event), calls.get(1).after());
	}

	@Test
	void sameChangeIsReportedOnlyOnce() {
		Event event = event("Kampagne");
		tracker.track(event);

		firstSeat(event).setPrice(1500);
		tracker.check(event);

		assertFalse(tracker.check(event));
		assertEquals(1, calls.size());
	}

	@Test
	void markSavedMakesTheCurrentStateTheNewBaseline() {
		Event event = event("Kampagne");
		tracker.track(event);
		Snapshot oldBaseline = tracker.baseline(event);
		firstSeat(event).setPrice(1500);
		tracker.check(event);

		tracker.markSaved(event);

		assertFalse(tracker.isDirty(event));
		assertFalse(tracker.anyDirty());
		assertNotEquals(oldBaseline, tracker.baseline(event));
		assertFalse(tracker.check(event));

		// Zurück auf den alten Wert ist jetzt eine Änderung gegenüber dem gespeicherten Stand
		firstSeat(event).setPrice(1000);
		assertTrue(tracker.check(event));
		assertTrue(tracker.isDirty(event));
	}

	@Test
	void markSavedStartsTrackingForAnUnknownEvent() {
		Event event = event("Neu");

		tracker.markSaved(event);

		assertFalse(tracker.isDirty(event));
		firstSeat(event).setPrice(1);
		assertTrue(tracker.check(event));
		assertTrue(tracker.isDirty(event));
	}

	@Test
	void eventsAreTrackedIndependently() {
		Event first = event("Eins");
		Event second = event("Zwei");
		tracker.track(first);
		tracker.track(second);

		firstSeat(second).setPrice(2000);
		tracker.checkAll();

		assertFalse(tracker.isDirty(first));
		assertTrue(tracker.isDirty(second));
		assertTrue(tracker.anyDirty());
		assertEquals(1, calls.size());
		assertSame(second, calls.get(0).event());

		tracker.markSaved(second);
		assertFalse(tracker.anyDirty());
	}

	@Test
	void trackingAgainStartsFromScratch() {
		Event event = event("Kampagne");
		tracker.track(event);
		firstSeat(event).setPrice(1500);
		tracker.check(event);

		tracker.track(event);

		assertFalse(tracker.isDirty(event));
		assertFalse(tracker.check(event));
	}

	@Test
	void resetForgetsAllEvents() {
		Event event = event("Kampagne");
		tracker.track(event);
		firstSeat(event).setPrice(1500);
		tracker.check(event);

		tracker.reset();

		assertFalse(tracker.isDirty(event));
		assertFalse(tracker.anyDirty());
		assertNull(tracker.baseline(event));
		assertNull(tracker.last(event));
		assertFalse(tracker.check(event), "Nicht verfolgte Events werden ignoriert");
	}

	@Test
	void listenersSurviveReset() {
		Event event = event("Kampagne");
		tracker.track(event);
		tracker.reset();

		tracker.track(event);
		firstSeat(event).setPrice(1500);
		tracker.check(event);

		assertEquals(1, calls.size());
	}

	@Test
	void removedListenerIsNotCalledAnymore() {
		ChangeTracker.Listener<Event> extra = (event, before, after) -> calls.add(new Call(event, before, after));
		tracker.addListener(extra);
		tracker.removeListener(extra);
		Event event = event("Kampagne");
		tracker.track(event);

		firstSeat(event).setPrice(1500);
		tracker.check(event);

		assertEquals(1, calls.size());
	}

	@Test
	void dirtyPropertyFollowsTheState() {
		Event event = event("Kampagne");
		tracker.track(event);
		var property = tracker.dirtyProperty(event);
		assertFalse(property.get());

		firstSeat(event).setPrice(1500);
		tracker.check(event);
		assertTrue(property.get());

		tracker.markSaved(event);
		assertFalse(property.get());
		assertSame(property, tracker.dirtyProperty(event), "Eine gebundene Eigenschaft muss beim erneuten Verfolgen dieselbe bleiben");
	}

	@Test
	void anyDirtyPropertyFollowsTheState() {
		Event event = event("Kampagne");
		tracker.track(event);
		var any = tracker.anyDirtyProperty();

		firstSeat(event).setPrice(1500);
		tracker.check(event);
		assertTrue(any.get());

		tracker.reset();
		assertFalse(any.get());
	}

	@Test
	void dirtyPropertyOfUntrackedEventIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> tracker.dirtyProperty(event("Unbekannt")));
	}

	@Test
	void snapshotKeepsTheBytesForRestoring() {
		Event event = event("Kampagne");
		tracker.track(event);

		Snapshot baseline = tracker.baseline(event);

		assertTrue(baseline.bytes().length > 0);
		assertEquals(64, baseline.hash().length(), "SHA-256 als Hex");
		assertTrue(new String(baseline.bytes(), java.nio.charset.StandardCharsets.UTF_8).contains("Kampagne"));
	}

	@Test
	void ensureTrackedKeepsAnAlreadyDetectedChange() {
		Event event = event("Kampagne");
		tracker.track(event);
		firstSeat(event).setPrice(1500);
		tracker.check(event);

		tracker.ensureTracked(event);

		assertTrue(tracker.isDirty(event));
	}

	@Test
	void ensureTrackedStartsTrackingAnUnknownEvent() {
		Event event = event("Kampagne");

		tracker.ensureTracked(event);

		assertFalse(tracker.isDirty(event));
		assertEquals(tracker.baseline(event), tracker.last(event));
	}

	@Test
	void untrackedEventCanNoLongerBeDirty() {
		Event event = event("Kampagne");
		tracker.track(event);
		firstSeat(event).setPrice(1500);
		tracker.check(event);

		tracker.untrack(event);

		assertFalse(tracker.isDirty(event));
		assertFalse(tracker.anyDirty());
		assertTrue(tracker.dirtyEntities().isEmpty());
		assertNull(tracker.baseline(event));
	}

	@Test
	void dirtyEventsListsOnlyChangedEventsInTrackingOrder() {
		Event first = event("Eins");
		Event second = event("Zwei");
		Event third = event("Drei");
		tracker.track(first);
		tracker.track(second);
		tracker.track(third);
		firstSeat(third).setPrice(1);
		firstSeat(first).setPrice(1);
		tracker.checkAll();

		assertEquals(List.of(first, third), tracker.dirtyEntities());
	}

	@Test
	void replaceEventSwapsTheObjectAndKeepsTheBaseline() {
		Event original = event("Kampagne");
		tracker.track(original);
		Snapshot baseline = tracker.baseline(original);
		firstSeat(original).setPrice(1500);
		tracker.check(original);
		Snapshot changed = tracker.last(original);

		Event sameId = new Event(original.getId());
		sameId.changeName("Kampagne");

		tracker.replaceEntity(sameId, baseline);

		assertFalse(tracker.isDirty(sameId));
		assertEquals(baseline, tracker.baseline(sameId));
		assertEquals(baseline, tracker.last(sameId));
		assertEquals(2, calls.size());
		assertEquals(changed, calls.get(1).before());
		assertEquals(baseline, calls.get(1).after());
	}

	@Test
	void replaceEventRejectsAnUntrackedEvent() {
		assertThrows(IllegalArgumentException.class, () -> tracker.replaceEntity(event("Unbekannt"), new Snapshot(new byte[0], "x")));
	}

	@Test
	void listenerMayResetTheTrackerWhileChecking() {
		Event first = event("Eins");
		Event second = event("Zwei");
		tracker.track(first);
		tracker.track(second);
		tracker.addListener((event, before, after) -> tracker.reset());

		firstSeat(first).setPrice(1500);
		firstSeat(second).setPrice(1500);

		tracker.checkAll();

		// Nach dem Reset im ersten Listener-Aufruf wird das zweite Event nicht mehr geprüft
		assertEquals(1, calls.size());
		assertFalse(tracker.anyDirty());
	}
}
