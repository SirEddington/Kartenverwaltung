package de.eltviller_carneval_verein.karten.tracking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker.Snapshot;

class StateRestorerTest {

	private ChangeTracker<Event> tracker;
	private final List<Event> cache = new ArrayList<>();
	private StateRestorer<Event> restorer;

	@BeforeEach
	void setUp() {
		tracker = new ChangeTracker<>(Event::getId);
		restorer = new StateRestorer<>(Event.class, tracker, cache::add);
	}

	private static Event event() {
		Event event = new Event();
		event.changeName("Kampagne");
		event.setDescription("Beschreibung");
		var presentation = event.addPresentation("Prunksitzung");
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.setHallId("halle-1");
		var table = presentation.addTable(1);
		table.addSeat(1).setPrice(1000);
		table.addSeat(2).setPrice(1200);
		return event;
	}

	private static Seat firstSeat(Event event) {
		return event.getPresentations().get(0).getTables().get(0).getSeats().get(0);
	}

	@Test
	void restoringTheBaselineUndoesTheChangesAndLeavesTheEventClean() {
		Event event = event();
		tracker.track(event);
		firstSeat(event).setPrice(9999);
		firstSeat(event).setLastName("Müller");
		tracker.check(event);
		assertTrue(tracker.isDirty(event));

		Event restored = restorer.restoreBaseline(event);

		assertNotSame(event, restored);
		assertEquals(event, restored, "gleiche ID");
		assertEquals(1000, firstSeat(restored).getPrice());
		assertEquals(null, firstSeat(restored).getLastName());
		assertFalse(tracker.isDirty(restored));
		assertFalse(tracker.anyDirty());
		assertFalse(tracker.check(restored), "Das wiederhergestellte Event darf nicht sofort wieder als geändert gelten");
	}

	@Test
	void hallsAreRestoredTheSameWay() {
		ChangeTracker<Hall> hallTracker = new ChangeTracker<>(Hall::getId);
		List<Hall> hallCache = new ArrayList<>();
		StateRestorer<Hall> hallRestorer = new StateRestorer<>(Hall.class, hallTracker, hallCache::add);
		Hall hall = new Hall();
		hall.changeName("Stadthalle");
		hall.setHallWidth(3000);
		hall.addHallObject("Bühne").setPosX(100);
		hallTracker.track(hall);
		hall.setHallWidth(1);
		hall.getHallObjects().get(0).setPosX(999);
		hallTracker.check(hall);
		assertTrue(hallTracker.isDirty(hall));

		Hall restored = hallRestorer.restoreBaseline(hall);

		assertNotSame(hall, restored);
		assertEquals(3000, restored.getHallWidth());
		assertEquals(100, restored.getHallObjects().get(0).getPosX());
		assertSame(restored, restored.getHallObjects().get(0).getParent(), "Rückverweis der Hallenobjekte gesetzt");
		assertEquals(List.of(restored), hallCache);
		assertFalse(hallTracker.isDirty(restored));
		assertFalse(hallTracker.check(restored), "Wiederhergestellte Halle ergibt dieselben Bytes");
	}

	@Test
	void restoredEventReplacesTheOldOneInCacheAndTracker() {
		Event event = event();
		tracker.track(event);
		firstSeat(event).setPrice(9999);
		tracker.check(event);

		Event restored = restorer.restoreBaseline(event);

		assertEquals(List.of(restored), cache);
		assertSame(restored, cache.get(0));
		// Der Tracker prüft jetzt das neue Objekt, nicht das alte
		firstSeat(restored).setPrice(5);
		assertTrue(tracker.check(restored));
		firstSeat(event).setPrice(77);
		assertFalse(tracker.check(event), "Eine Änderung am alten Objekt wird nicht mehr bemerkt, weil es abgelöst ist");
	}

	@Test
	void restoringAnEarlierStateKeepsTheBaseline() {
		Event event = event();
		tracker.track(event);
		Snapshot saved = tracker.baseline(event);

		firstSeat(event).setPrice(1500);
		tracker.check(event);
		Snapshot step1 = tracker.last(event);
		firstSeat(event).setPrice(2000);
		tracker.check(event);

		Event restored = restorer.restore(event, step1);

		assertEquals(1500, firstSeat(restored).getPrice());
		assertTrue(tracker.isDirty(restored), "Zustand vor dem Speichern weicht vom gespeicherten Stand ab");
		assertEquals(saved, tracker.baseline(restored));
		assertEquals(step1, tracker.last(restored));

		restorer.restore(restored, saved);
		assertFalse(tracker.isDirty(restored));
	}

	@Test
	void listenersAreToldAboutTheExchange() {
		Event event = event();
		tracker.track(event);
		List<Event[]> calls = new ArrayList<>();
		restorer.addListener((replaced, restored) -> calls.add(new Event[] { replaced, restored }));

		Event restored = restorer.restoreBaseline(event);

		assertEquals(1, calls.size());
		assertSame(event, calls.get(0)[0]);
		assertSame(restored, calls.get(0)[1]);
	}

	@Test
	void trackerListenersSeeTheRestoredChange() {
		Event event = event();
		tracker.track(event);
		firstSeat(event).setPrice(9999);
		tracker.check(event);
		List<Snapshot> afters = new ArrayList<>();
		tracker.addListener((e, before, after) -> afters.add(after));

		restorer.restoreBaseline(event);

		assertEquals(List.of(tracker.baseline(event)), afters);
	}

	@Test
	void restoringAnUntrackedEventIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> restorer.restoreBaseline(event()));
	}

	@Test
	void restoringAnUntrackedEventLeavesTheCacheUntouched() {
		Event tracked = event();
		tracker.track(tracked);
		Snapshot state = tracker.baseline(tracked);

		assertThrows(IllegalArgumentException.class, () -> restorer.restore(event(), state));

		assertTrue(cache.isEmpty(), "Der Cache darf nicht verändert sein, wenn der Tracker das Event nicht kennt");
	}

	@Test
	void restoredBytesAreIdenticalToTheSnapshot() throws IOException {
		Event event = event();
		tracker.track(event);
		Event restored = restorer.restoreBaseline(event);

		assertArrayEquals(tracker.baseline(event).bytes(), JsonMapperFactory.create().writeValueAsBytes(restored));
	}

	@Test
	void serializationIsStableForTheRealTestData() throws IOException {
		// Darauf beruht alles: Ein wiederhergestelltes Event muss wieder genau dieselben Bytes ergeben,
		// sonst würde es nach dem Verwerfen sofort wieder als geändert gelten.
		ObjectMapper mapper = JsonMapperFactory.create();
		File[] files = new File("events_data").listFiles((dir, name) -> name.endsWith(".json"));
		if (files == null || files.length == 0) {
			return; // Testdaten nicht vorhanden (z. B. anderes Arbeitsverzeichnis)
		}
		for (File file : files) {
			Event loaded = mapper.readValue(file, Event.class);
			byte[] first = mapper.writeValueAsBytes(loaded);
			byte[] second = mapper.writeValueAsBytes(mapper.readValue(first, Event.class));
			assertArrayEquals(first, second, "Rundlauf nicht stabil: " + file.getName());
		}
	}
}
