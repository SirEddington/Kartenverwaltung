package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.FaultyFileLayer;
import de.eltviller_carneval_verein.karten.store.NioFileLayer;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.Version;

class StoreEventRepositoryTest {

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

	// Bauteile -->

	private static SyncService started(SyncService sync) {
		sync.startup(null, p -> {
		});
		return sync;
	}

	private static SyncService started(Path appData) {
		return started(TestSyncServices.singleMode(appData));
	}

	private static Event named(String name) {
		Event event = new Event();
		event.setName(name);
		return event;
	}

	private static Booking booking(Presentation presentation, String seatId, int seat, String lastName) {
		Booking booking = presentation.addBooking();
		booking.setSeatId(seatId);
		booking.setRowLabel("A");
		booking.setTableNumber(1);
		booking.setSeatNumber(seat);
		booking.setLastName(lastName);
		return booking;
	}

	/** Ein Event mit zwei Vorstellungen und vier Buchungen mit Daten. */
	private static Event eventWithBookings() {
		Event event = named("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		Presentation saturday = event.addPresentation("Samstag");
		booking(friday, "seat-1", 1, "Müller");
		booking(friday, "seat-2", 2, "Meier");
		booking(saturday, "seat-1", 1, "Schmitz");
		booking(saturday, "seat-3", 3, "Becker");
		return event;
	}

	/** Die Anzahl aller Dateien (Versionen und Ansprüche) im lokalen Bestand. */
	private static long filesIn(Path appData) throws IOException {
		try (Stream<Path> walk = Files.walk(appData.resolve("data"))) {
			return walk.filter(Files::isRegularFile).count();
		}
	}

	private static Booking bookingOf(Event event, int presentation, String lastName) {
		return event.getPresentations().get(presentation).getBookings().stream()
				.filter(b -> lastName.equals(b.getLastName())).findFirst().orElseThrow();
	}

	// <-- Bauteile

	@Test
	void savedEventLoadsInNewInstance(@TempDir Path appData) throws IOException {
		StoreEventRepository repository = TestRepositories.storeEvents(started(appData));
		Event event = eventWithBookings();
		event.setDescription("Große Sitzung");
		repository.saveEvent(event);

		StoreEventRepository fresh = TestRepositories.storeEvents(started(appData));
		List<Event> loaded = fresh.loadEvents();

		assertEquals(1, loaded.size());
		Event copy = loaded.get(0);
		assertNotSame(event, copy);
		assertEquals(event.getId(), copy.getId());
		assertEquals("Prunksitzung", copy.getName());
		assertEquals("Große Sitzung", copy.getDescription());
		assertEquals(2, copy.getPresentations().size());
		assertEquals("Meier", bookingOf(copy, 0, "Meier").getLastName());
		assertEquals("Becker", bookingOf(copy, 1, "Becker").getLastName());
		assertTrue(fresh.getAndClearLoadWarnings().isEmpty());
	}

	@Test
	void loadEventsReturnsSameReferences(@TempDir Path appData) {
		StoreEventRepository repository = TestRepositories.storeEvents(started(appData));
		Event event = named("Kampagne");
		repository.saveEvent(event);

		List<Event> first = repository.loadEvents();
		List<Event> second = repository.loadEvents();

		assertNotSame(first, second);
		assertSame(event, first.get(0));
		assertSame(first.get(0), second.get(0));
		first.clear();
		assertEquals(1, repository.loadEvents().size(), "die Liste ist eine Kopie");
	}

	@Test
	void saveWithoutChangesWritesNothing(@TempDir Path appData) throws IOException {
		StoreEventRepository repository = TestRepositories.storeEvents(started(appData));
		Event event = eventWithBookings();
		repository.saveEvent(event);
		long before = filesIn(appData);

		repository.saveEvent(event);
		repository.saveEvents(List.of(event));

		assertEquals(before, filesIn(appData));
	}

	@Test
	void saveAfterRestartWithoutChangesWritesNothing(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Hall hall = new Hall();
		hall.setName("Stadthalle");
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 2, 3);
		TestRepositories.storeHalls(sync).saveHall(hall);
		Event event = named("Prunksitzung");
		for (String name : List.of("Freitag", "Samstag")) {
			Presentation presentation = event.addPresentation(name);
			presentation.setHallId(hall.getId());
			presentation.setSeatingPlanId(plan.getId());
			BookingSync.sync(presentation, plan);
			presentation.getBookings().get(0).setLastName("Müller");
			presentation.getBookings().get(4).setLastName("Meier");
		}
		TestRepositories.storeEvents(sync).saveEvent(event);
		sync.flushIndex();
		long before = filesIn(appData);

		SyncService restarted = started(appData);
		StoreHallRepository halls = TestRepositories.storeHalls(restarted);
		StoreEventRepository events = TestRepositories.storeEvents(restarted);
		for (Event loaded : events.loadEvents()) {
			for (Presentation presentation : loaded.getPresentations()) {
				// wie beim Öffnen in der App: die leeren Buchungen kommen aus der Bestuhlung zurück
				BookingSync.sync(presentation, halls.findById(presentation.getHallId())
						.findSeatingPlan(presentation.getSeatingPlanId()));
			}
			events.saveEvent(loaded);
		}
		halls.saveHalls(halls.loadHalls());

		assertEquals(before, filesIn(appData));
		assertEquals(6, events.loadEvents().get(0).getPresentations().get(0).getBookings().size());
	}

	@Test
	void changingOneBookingWritesOneVersion(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.storeEvents(sync);
		Event event = eventWithBookings();
		repository.saveEvent(event);
		Booking changed = bookingOf(event, 1, "Becker");

		changed.setLastName("Beckmann");
		repository.saveEvent(event);

		UnitKey changedUnit = UnitKey.booking(event.getId(), event.getPresentations().get(1).getId(), "seat-3");
		for (UnitKey unit : sync.eventUnits(event.getId())) {
			int expected = unit.equals(changedUnit) ? 2 : 1;
			assertEquals(expected, sync.history(unit).size(), unit.relativePath());
		}
		assertEquals("Beckmann", TestRepositories.storeEvents(started(appData)).loadEvents().get(0).getPresentations()
				.get(1).getBookings().stream().filter(b -> "seat-3".equals(b.getSeatId())).findFirst().orElseThrow()
				.getLastName());
	}

	@Test
	void removedBookingIsDeletedWhenItWasShown(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = eventWithBookings();
		TestRepositories.storeEvents(sync).saveEvent(event);

		StoreEventRepository repository = TestRepositories.storeEvents(sync);
		Event loaded = repository.loadEvents().get(0);
		Booking removed = bookingOf(loaded, 0, "Meier");
		loaded.getPresentations().get(0).getBookings().remove(removed);
		repository.saveEvent(loaded);

		UnitKey unit = UnitKey.booking(event.getId(), event.getPresentations().get(0).getId(), "seat-2");
		List<Version> history = sync.history(unit);
		assertEquals(2, history.size());
		assertTrue(history.stream().anyMatch(Version::deleted));
		Event again = TestRepositories.storeEvents(started(appData)).loadEvents().get(0);
		assertEquals(List.of("Müller"),
				again.getPresentations().get(0).getBookings().stream().map(Booking::getLastName).toList());
	}

	@Test
	void foreignBookingSavedMeanwhileIsNotDeletedByMySave(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = eventWithBookings();
		StoreEventRepository repository = TestRepositories.storeEvents(sync);
		repository.saveEvent(event);

		// eine Buchung, die nach dem Laden dieses Repositorys von anderer Seite dazukommt
		Event other = TestRepositories.storeEvents(sync).loadEvents().get(0);
		booking(other.getPresentations().get(0), "seat-9", 9, "Fremd");
		TestRepositories.storeEvents(sync).saveEvent(other);
		bookingOf(event, 0, "Müller").setLastName("Mueller");
		repository.saveEvent(event);

		Event again = TestRepositories.storeEvents(started(appData)).loadEvents().get(0);
		assertEquals("Fremd", bookingOf(again, 0, "Fremd").getLastName());
		assertEquals("Mueller", bookingOf(again, 0, "Mueller").getLastName());
	}

	@Test
	void deleteEventWritesOnlyHeadDeletion(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.storeEvents(sync);
		Event event = eventWithBookings();
		repository.saveEvent(event);

		repository.deleteEvent(event);

		UnitKey head = UnitKey.event(event.getId());
		for (UnitKey unit : sync.eventUnits(event.getId())) {
			assertEquals(unit.equals(head) ? 2 : 1, sync.history(unit).size(), unit.relativePath());
		}
		List<Version> history = sync.history(head);
		assertTrue(history.stream().anyMatch(Version::deleted));
		assertFalse(repository.loadEvents().contains(event));
	}

	@Test
	void deletedEventIsGoneAfterReload(@TempDir Path appData) {
		StoreEventRepository repository = TestRepositories.storeEvents(started(appData));
		Event deleted = eventWithBookings();
		Event kept = named("Zweites");
		repository.saveEvents(List.of(deleted, kept));

		repository.deleteEvents(List.of(deleted));

		StoreEventRepository fresh = TestRepositories.storeEvents(started(appData));
		assertEquals(List.of(kept.getId()), fresh.loadEvents().stream().map(Event::getId).toList());
		assertEquals(List.of(kept.getId()), fresh.reloadEvents().stream().map(Event::getId).toList());
	}

	@Test
	void deleteOfNeverSavedEventOnlyNotifies(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.storeEvents(sync);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);

		repository.deleteEvent(named("Nie gespeichert"));
		repository.deleteEvent(null);

		assertEquals(List.of("deleted Nie gespeichert"), recorder.calls);
		assertTrue(sync.eventIds().isEmpty());
	}

	@Test
	void failedAppendKeepsBaselineConsistent(@TempDir Path appData) throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService sync = started(TestSyncServices.singleMode(appData, layer));
		StoreEventRepository repository = TestRepositories.storeEvents(sync);
		Event event = eventWithBookings();
		repository.saveEvent(event);
		bookingOf(event, 0, "Müller").setLastName("Mueller");
		bookingOf(event, 0, "Meier").setLastName("Maier");
		// die zweite Versionsdatei wird angelegt, aber der Aufrufer erfährt von einem Fehler
		AtomicInteger creations = new AtomicInteger();
		layer.failAfterWhen(path -> creations.incrementAndGet() == 2, "createNew", new IOException("Platte voll"));

		RuntimeException failure = assertThrows(RuntimeException.class, () -> repository.saveEvent(event));

		assertTrue(failure.getMessage().startsWith("Fehler beim Speichern von \"Prunksitzung\": "), failure.getMessage());
		assertInstanceOf(IOException.class, failure.getCause());
		repository.saveEvent(event);

		for (UnitKey unit : sync.eventUnits(event.getId())) {
			UnitState state = sync.unitState(unit);
			assertEquals(Status.OK, state.status(), unit.relativePath());
			assertTrue(state.lost().isEmpty(), unit.relativePath() + " hat eine verlorene Strecke");
		}
		sync.flushIndex();
		Event again = TestRepositories.storeEvents(started(appData)).loadEvents().get(0);
		assertEquals("Mueller", bookingOf(again, 0, "Mueller").getLastName());
		assertEquals("Maier", bookingOf(again, 0, "Maier").getLastName());
	}

	@Test
	void failedAppendBeforeWritingIsRetriedCompletely(@TempDir Path appData) throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService sync = started(TestSyncServices.singleMode(appData, layer));
		StoreEventRepository repository = TestRepositories.storeEvents(sync);
		Event event = eventWithBookings();
		repository.saveEvent(event);
		bookingOf(event, 0, "Müller").setLastName("Mueller");
		bookingOf(event, 0, "Meier").setLastName("Maier");
		AtomicInteger creations = new AtomicInteger();
		layer.failWhen(path -> creations.incrementAndGet() == 2, "createNew", new IOException("Platte voll"));

		assertThrows(RuntimeException.class, () -> repository.saveEvent(event));
		repository.saveEvent(event);

		// die erste Änderung kam durch, die zweite fehlte und wird beim zweiten Speichern nachgeholt
		for (UnitKey unit : sync.eventUnits(event.getId())) {
			UnitState state = sync.unitState(unit);
			assertEquals(Status.OK, state.status(), unit.relativePath());
			assertTrue(state.lost().isEmpty(), unit.relativePath());
			assertTrue(state.pending().isEmpty(), unit.relativePath());
		}
		Event again = TestRepositories.storeEvents(started(appData)).loadEvents().get(0);
		assertEquals("Mueller", bookingOf(again, 0, "Mueller").getLastName());
		assertEquals("Maier", bookingOf(again, 0, "Maier").getLastName());
	}

	@Test
	void duplicateSeatWritesNothing(@TempDir Path appData) throws IOException {
		StoreEventRepository repository = TestRepositories.storeEvents(started(appData));
		Event event = eventWithBookings();
		repository.saveEvent(event);
		long before = filesIn(appData);
		booking(event.getPresentations().get(0), "seat-1", 1, "Doppelt");
		Recorder recorder = new Recorder();
		repository.addListener(recorder);

		assertThrows(IllegalStateException.class, () -> repository.saveEvent(event));

		assertEquals(before, filesIn(appData));
		assertTrue(recorder.calls.isEmpty());
	}

	@Test
	void listenerGetsSavedAndDeleted(@TempDir Path appData) {
		StoreEventRepository repository = TestRepositories.storeEvents(started(appData));
		Recorder recorder = new Recorder();
		repository.addListener(recorder);
		Event event = named("Kampagne");

		repository.saveEvent(event);
		repository.saveEvents(List.of(event));
		repository.deleteEvent(event);

		assertEquals(List.of("saved Kampagne", "saved Kampagne", "deleted Kampagne"), recorder.calls);
	}

	@Test
	void invalidEventIsRejectedWithoutNotifying(@TempDir Path appData) {
		StoreEventRepository repository = TestRepositories.storeEvents(started(appData));
		Recorder recorder = new Recorder();
		repository.addListener(recorder);

		IllegalArgumentException isNull = assertThrows(IllegalArgumentException.class, () -> repository.saveEvent(null));
		IllegalArgumentException isBlank = assertThrows(IllegalArgumentException.class,
				() -> repository.saveEvent(named("  ")));

		assertEquals("Das Event-Objekt darf nicht null sein.", isNull.getMessage());
		assertEquals("Event-Name darf nicht leer oder null sein.", isBlank.getMessage());
		assertTrue(recorder.calls.isEmpty());
	}

	@Test
	void replaceCachedEventSwapsTheObjectWithoutWriting(@TempDir Path appData) throws IOException {
		StoreEventRepository repository = TestRepositories.storeEvents(started(appData));
		Event original = named("Vorher");
		repository.saveEvent(original);
		long before = filesIn(appData);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);

		Event replacement = new Event(original.getId());
		replacement.setName("Nachher");
		repository.replaceCachedEvent(replacement);
		repository.replaceCachedEvent(named("Unbekannt"));

		assertEquals(1, repository.loadEvents().size());
		assertSame(replacement, repository.loadEvents().get(0));
		assertEquals(before, filesIn(appData));
		assertTrue(recorder.calls.isEmpty(), "Ersetzen im Cache ist kein Speichern");
	}

	@Test
	void loadWarningsAreCollectedAndCleared(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		// der Inhalt nennt eine andere ID als der Ordner: die Einheit lässt sich nicht anzeigen
		ObjectNode payload = JsonMapperFactory.create().createObjectNode();
		payload.put("id", "andere-id");
		payload.put("name", "Fremd");
		sync.append("test", List.of(new Change(UnitKey.event("ordner-id"), List.of(), payload, false)));
		TestRepositories.storeEvents(sync).saveEvent(named("Sichtbar"));
		StoreEventRepository repository = TestRepositories.storeEvents(sync);

		List<Event> loaded = repository.loadEvents();

		assertEquals(List.of("Sichtbar"), loaded.stream().map(Event::getName).toList());
		assertEquals(List.of("Das Event ordner-id konnte nicht gelesen werden und fehlt."),
				repository.getAndClearLoadWarnings());
		assertTrue(repository.getAndClearLoadWarnings().isEmpty());
		repository.reloadEvents();
		assertEquals(1, repository.getAndClearLoadWarnings().size(), "jeder Ladevorgang meldet seine Warnungen neu");
	}

	@Test
	void unreadableUnitIsNeverDeletedBySave(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = eventWithBookings();
		TestRepositories.storeEvents(sync).saveEvent(event);
		UnitKey seat = UnitKey.booking(event.getId(), event.getPresentations().get(0).getId(), "seat-2");
		ObjectNode broken = JsonMapperFactory.create().createObjectNode();
		broken.put("id", "andere-id");
		sync.append("test", List.of(new Change(seat, List.of(sync.unitState(seat).head().id()), broken, false)));

		StoreEventRepository repository = TestRepositories.storeEvents(sync);
		Event loaded = repository.loadEvents().get(0);
		assertEquals(1, loaded.getPresentations().get(0).getBookings().size());
		bookingOf(loaded, 0, "Müller").setLastName("Mueller");
		repository.saveEvent(loaded);

		assertEquals(2, sync.history(seat).size(), "die unlesbare Buchung wurde nicht gelöscht");
		assertTrue(sync.history(seat).stream().noneMatch(Version::deleted));
		UnitKey changed = UnitKey.booking(event.getId(), event.getPresentations().get(0).getId(), "seat-1");
		assertEquals(2, sync.history(changed).size());
	}
}
