package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.FaultyFileLayer;
import de.eltviller_carneval_verein.karten.store.FileLayer;
import de.eltviller_carneval_verein.karten.store.LocalArbiter;
import de.eltviller_carneval_verein.karten.store.NioFileLayer;
import de.eltviller_carneval_verein.karten.store.StoreCodec;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.store.VersionStore;

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

	/** Uhr, die der Test von Hand stellt: Jeder Speicherstand bekommt so seinen eigenen Zeitpunkt. */
	private static final class SteppedClock extends Clock {
		private Instant now = Instant.parse("2026-10-27T10:00:00Z");

		/** Stellt die Uhr {@code minutes} Minuten vor. */
		void advance(int minutes) {
			now = now.plusSeconds(60L * minutes);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
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

	/**
	 * Schreibt und beansprucht eine Version eines anderen Rechners auf dem Vorgänger, wie sie das Abholen aus dem
	 * gemeinsamen Ordner in den lokalen Bestand bringt.
	 */
	private static Version writeForeignVersion(Path appData, UnitKey unit, Version parent, String lastName)
			throws IOException {
		ObjectNode payload = parent.payload().deepCopy();
		payload.put("lastName", lastName);
		Version version = new Version(StoreCodec.FORMAT, VersionId.create(1_900_000_000_000L, "abcdefgh", 1),
				List.of(parent.id()), "0123456789abcdefghijklmnop", "Fremd", null, Instant.now(), "fremd", false,
				payload);
		byte[] bytes = StoreCodec.write(version);
		VersionStore store = new VersionStore(appData.resolve("data"), new NioFileLayer());
		assertTrue(store.writeVersion(unit, version.id(), bytes));
		new LocalArbiter(store, Clock.systemUTC()).claim(unit, version, bytes);
		return version;
	}

	/** Ein Event mit einer Vorstellung und einer Buchung "Müller" auf seat-1. */
	private static Event smallEvent() {
		Event event = named("Prunksitzung");
		booking(event.addPresentation("Freitag"), "seat-1", 1, "Müller");
		return event;
	}

	/** Dasselbe Event wie ein neu angelegtes Objekt, ohne Buchungen: Vorstellung gleich, Buchung entfernt. */
	private static Event withoutBookings(Event event) {
		Event copy = new Event(event.getId());
		copy.setName(event.getName());
		List<Presentation> presentations = new ArrayList<>();
		for (Presentation presentation : event.getPresentations()) {
			Presentation emptied = new Presentation(presentation.getId());
			emptied.setName(presentation.getName());
			presentations.add(emptied);
		}
		copy.setPresentations(presentations);
		return copy;
	}

	private static List<String> bookingNames(Event event) {
		return event.getPresentations().stream().flatMap(p -> p.getBookings().stream()).map(Booking::getLastName)
				.toList();
	}

	// <-- Bauteile

	@Test
	void savedEventLoadsInNewInstance(@TempDir Path appData) throws IOException {
		StoreEventRepository repository = TestRepositories.events(started(appData));
		Event event = eventWithBookings();
		event.setDescription("Große Sitzung");
		repository.saveEvent(event);

		StoreEventRepository fresh = TestRepositories.events(started(appData));
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
		StoreEventRepository repository = TestRepositories.events(started(appData));
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
		StoreEventRepository repository = TestRepositories.events(started(appData));
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
		TestRepositories.halls(sync).saveHall(hall);
		Event event = named("Prunksitzung");
		for (String name : List.of("Freitag", "Samstag")) {
			Presentation presentation = event.addPresentation(name);
			presentation.setHallId(hall.getId());
			presentation.setSeatingPlanId(plan.getId());
			BookingSync.sync(presentation, plan);
			presentation.getBookings().get(0).setLastName("Müller");
			presentation.getBookings().get(4).setLastName("Meier");
		}
		TestRepositories.events(sync).saveEvent(event);
		sync.flushIndex();
		long before = filesIn(appData);

		SyncService restarted = started(appData);
		StoreHallRepository halls = TestRepositories.halls(restarted);
		StoreEventRepository events = TestRepositories.events(restarted);
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
		StoreEventRepository repository = TestRepositories.events(sync);
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
		assertEquals("Beckmann", TestRepositories.events(started(appData)).loadEvents().get(0).getPresentations()
				.get(1).getBookings().stream().filter(b -> "seat-3".equals(b.getSeatId())).findFirst().orElseThrow()
				.getLastName());
	}

	@Test
	void removedBookingIsDeletedWhenItWasShown(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = eventWithBookings();
		TestRepositories.events(sync).saveEvent(event);

		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().get(0);
		Booking removed = bookingOf(loaded, 0, "Meier");
		loaded.getPresentations().get(0).getBookings().remove(removed);
		repository.saveEvent(loaded);

		UnitKey unit = UnitKey.booking(event.getId(), event.getPresentations().get(0).getId(), "seat-2");
		List<Version> history = sync.history(unit);
		assertEquals(2, history.size());
		assertTrue(history.stream().anyMatch(Version::deleted));
		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals(List.of("Müller"),
				again.getPresentations().get(0).getBookings().stream().map(Booking::getLastName).toList());
	}

	@Test
	void foreignBookingSavedMeanwhileIsNotDeletedByMySave(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = eventWithBookings();
		StoreEventRepository repository = TestRepositories.events(sync);
		repository.saveEvent(event);

		// eine Buchung, die nach dem Laden dieses Repositorys von anderer Seite dazukommt
		Event other = TestRepositories.events(sync).loadEvents().get(0);
		booking(other.getPresentations().get(0), "seat-9", 9, "Fremd");
		TestRepositories.events(sync).saveEvent(other);
		bookingOf(event, 0, "Müller").setLastName("Mueller");
		repository.saveEvent(event);

		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals("Fremd", bookingOf(again, 0, "Fremd").getLastName());
		assertEquals("Mueller", bookingOf(again, 0, "Mueller").getLastName());
	}

	@Test
	void deleteEventWritesOnlyHeadDeletion(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
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
		StoreEventRepository repository = TestRepositories.events(started(appData));
		Event deleted = eventWithBookings();
		Event kept = named("Zweites");
		repository.saveEvents(List.of(deleted, kept));

		repository.deleteEvents(List.of(deleted));

		StoreEventRepository fresh = TestRepositories.events(started(appData));
		assertEquals(List.of(kept.getId()), fresh.loadEvents().stream().map(Event::getId).toList());
		assertEquals(List.of(kept.getId()), fresh.reloadEvents().stream().map(Event::getId).toList());
	}

	@Test
	void deleteOfNeverSavedEventOnlyNotifies(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
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
		StoreEventRepository repository = TestRepositories.events(sync);
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
		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals("Mueller", bookingOf(again, 0, "Mueller").getLastName());
		assertEquals("Maier", bookingOf(again, 0, "Maier").getLastName());
	}

	/** Eine Dateischicht, deren Anlegen nach dem {@code failAt}-ten Aufruf mit einer ungeprüften Ausnahme meldet, die Datei steht dann schon. */
	private static final class UncheckedFailingLayer implements FileLayer {
		private final FileLayer delegate = new NioFileLayer();
		private final AtomicInteger creations = new AtomicInteger();
		private volatile int failAt = Integer.MAX_VALUE;

		void failAtCreation(int n) {
			creations.set(0);
			failAt = n;
		}

		@Override
		public boolean createNew(Path file, byte[] content) throws IOException {
			boolean result = delegate.createNew(file, content);
			if (creations.incrementAndGet() == failAt) {
				throw new UncheckedIOException(new IOException("Platte voll"));
			}
			return result;
		}

		@Override
		public Optional<byte[]> read(Path file) throws IOException {
			return delegate.read(file);
		}

		@Override
		public boolean exists(Path file) {
			return delegate.exists(file);
		}

		@Override
		public List<String> listNames(Path dir) throws IOException {
			return delegate.listNames(dir);
		}

		@Override
		public Map<String, List<String>> listTree(Path root) throws IOException {
			return delegate.listTree(root);
		}

		@Override
		public boolean isDirectory(Path path) {
			return delegate.isDirectory(path);
		}

		@Override
		public void replaceDamaged(Path file, byte[] good, String corruptName) throws IOException {
			delegate.replaceDamaged(file, good, corruptName);
		}

		@Override
		public void rename(Path from, Path to) throws IOException {
			delegate.rename(from, to);
		}

		@Override
		public void deleteOwnTemps(Path dir, Duration olderThan) throws IOException {
			delegate.deleteOwnTemps(dir, olderThan);
		}
	}

	@Test
	void uncheckedFailureOfAppendKeepsBaselineConsistent(@TempDir Path appData) throws IOException {
		UncheckedFailingLayer layer = new UncheckedFailingLayer();
		SyncService sync = started(TestSyncServices.singleMode(appData, layer));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = eventWithBookings();
		repository.saveEvent(event);
		bookingOf(event, 0, "Müller").setLastName("Mueller");
		bookingOf(event, 0, "Meier").setLastName("Maier");
		// die zweite Datei wird angelegt, aber der Aufrufer erfährt von einer ungeprüften Ausnahme
		layer.failAtCreation(2);

		UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> repository.saveEvent(event));

		assertEquals("Platte voll", failure.getCause().getMessage());
		repository.saveEvent(event);

		for (UnitKey unit : sync.eventUnits(event.getId())) {
			UnitState state = sync.unitState(unit);
			assertEquals(Status.OK, state.status(), unit.relativePath());
			assertTrue(state.lost().isEmpty(), unit.relativePath() + " hat eine verlorene Strecke");
		}
		sync.flushIndex();
		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals("Mueller", bookingOf(again, 0, "Mueller").getLastName());
		assertEquals("Maier", bookingOf(again, 0, "Maier").getLastName());
	}

	@Test
	void failedAppendBeforeWritingIsRetriedCompletely(@TempDir Path appData) throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService sync = started(TestSyncServices.singleMode(appData, layer));
		StoreEventRepository repository = TestRepositories.events(sync);
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
		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals("Mueller", bookingOf(again, 0, "Mueller").getLastName());
		assertEquals("Maier", bookingOf(again, 0, "Maier").getLastName());
	}

	@Test
	void duplicateSeatWritesNothing(@TempDir Path appData) throws IOException {
		StoreEventRepository repository = TestRepositories.events(started(appData));
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
		StoreEventRepository repository = TestRepositories.events(started(appData));
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
		StoreEventRepository repository = TestRepositories.events(started(appData));
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
		StoreEventRepository repository = TestRepositories.events(started(appData));
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
		TestRepositories.events(sync).saveEvent(named("Sichtbar"));
		StoreEventRepository repository = TestRepositories.events(sync);

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
		TestRepositories.events(sync).saveEvent(event);
		UnitKey seat = UnitKey.booking(event.getId(), event.getPresentations().get(0).getId(), "seat-2");
		ObjectNode broken = JsonMapperFactory.create().createObjectNode();
		broken.put("id", "andere-id");
		sync.append("test", List.of(new Change(seat, List.of(sync.unitState(seat).head().id()), broken, false)));

		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().get(0);
		assertEquals(1, loaded.getPresentations().get(0).getBookings().size());
		bookingOf(loaded, 0, "Müller").setLastName("Mueller");
		repository.saveEvent(loaded);

		assertEquals(2, sync.history(seat).size(), "die unlesbare Buchung wurde nicht gelöscht");
		assertTrue(sync.history(seat).stream().noneMatch(Version::deleted));
		UnitKey changed = UnitKey.booking(event.getId(), event.getPresentations().get(0).getId(), "seat-1");
		assertEquals(2, sync.history(changed).size());
	}

	@Test
	void failedSaveDoesNotAdoptAForeignVersionAsBaseline(@TempDir Path appData) throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService sync = started(TestSyncServices.singleMode(appData, layer));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = smallEvent();
		repository.saveEvent(event);
		UnitKey seat = UnitKey.booking(event.getId(), event.getPresentations().get(0).getId(), "seat-1");
		// nach dem Laden kommt von anderer Seite eine Änderung der Buchung, die der Nutzer nie gesehen hat
		Version foreign = writeForeignVersion(appData, seat, sync.unitState(seat).head(), "Fremd");
		bookingOf(event, 0, "Müller").setLastName("Mueller");
		layer.failNext("createNew", new IOException("Platte voll"));

		assertThrows(RuntimeException.class, () -> repository.saveEvent(event));
		repository.saveEvent(event);

		// Der Vergleichsstand blieb die Version, die der Nutzer sah: Die zweite Änderung baut darauf auf, verliert gegen
		// die fremde und überschreibt sie nicht
		UnitState state = sync.unitState(seat);
		assertEquals(foreign.id(), state.head().id());
		assertEquals("Fremd", state.head().payload().get("lastName").asText());
		assertEquals(1, state.lost().size());
	}

	@Test
	void failedSaveDoesNotMakeAForeignUnitDeletable(@TempDir Path appData) throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService sync = started(TestSyncServices.singleMode(appData, layer));
		Event event = smallEvent();
		TestRepositories.events(sync).saveEvent(event);
		// eine unlesbare Buchung auf seat-2: Sie wird nicht angezeigt und nie vom Repository gelöscht
		UnitKey seat = UnitKey.booking(event.getId(), event.getPresentations().get(0).getId(), "seat-2");
		ObjectNode broken = JsonMapperFactory.create().createObjectNode();
		broken.put("id", "andere-id");
		sync.append("test", List.of(new Change(seat, List.of(), broken, false)));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().get(0);
		// der Nutzer legt eine Buchung auf diesen Sitz, doch dazwischen kommt eine fremde Version auf die unlesbare
		booking(loaded.getPresentations().get(0), "seat-2", 2, "Neu");
		writeForeignVersion(appData, seat, sync.unitState(seat).head(), "Fremd");
		layer.failNext("createNew", new IOException("Platte voll"));
		assertThrows(RuntimeException.class, () -> repository.saveEvent(loaded));

		loaded.getPresentations().get(0).getBookings().removeIf(b -> "Neu".equals(b.getLastName()));
		repository.saveEvent(loaded);

		assertEquals(2, sync.history(seat).size(), "die fremde Version wurde nicht gelöscht");
		assertTrue(sync.history(seat).stream().noneMatch(Version::deleted));
	}

	@Test
	void deleteOfLockedEventIsRefused(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = smallEvent();
		TestRepositories.events(sync).saveEvent(event);
		UnitKey head = UnitKey.event(event.getId());
		Version first = sync.unitState(head).head();
		ObjectNode changed = first.payload().deepCopy();
		changed.put("description", "geändert");
		Version second = sync.append("test", List.of(new Change(head, List.of(first.id()), changed, false))).get(0);
		// die zweite Version wird beschädigt: Die Kette endet vor ihr, die erste bleibt lesbar
		Files.writeString(appData.resolve("data/events/" + event.getId() + "/" + second.id() + ".json"), "kaputt");

		SyncService restarted = started(appData);
		StoreEventRepository repository = TestRepositories.events(restarted);
		Event loaded = repository.loadEvents().get(0);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);
		long versionsBefore = restarted.history(head).size();

		LockedUnitException refused = assertThrows(LockedUnitException.class, () -> repository.deleteEvent(loaded));

		assertTrue(refused.getMessage().contains("events/" + event.getId()), refused.getMessage());
		assertEquals(versionsBefore, restarted.history(head).size());
		assertTrue(repository.loadEvents().contains(loaded));
		assertTrue(recorder.calls.isEmpty());
	}

	@Test
	void revivingDeletedEventAfterRestartRemovesItsOldBookings(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = smallEvent();
		repository.saveEvent(event);
		repository.deleteEvent(event);
		sync.flushIndex();

		StoreEventRepository restarted = TestRepositories.events(started(appData));
		assertTrue(restarted.loadEvents().isEmpty());
		restarted.saveEvent(withoutBookings(event));

		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals(1, again.getPresentations().size());
		assertEquals(List.of(), bookingNames(again));
	}

	@Test
	void revivingDeletedEventInTheSameSessionRemovesItsOldBookings(@TempDir Path appData) throws IOException {
		StoreEventRepository repository = TestRepositories.events(started(appData));
		Event event = smallEvent();
		repository.saveEvent(event);
		repository.deleteEvent(event);

		repository.saveEvent(withoutBookings(event));

		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals(1, again.getPresentations().size());
		assertEquals(List.of(), bookingNames(again));
	}

	@Test
	void revivingDeletedPresentationRemovesItsOldBookings(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = smallEvent();
		repository.saveEvent(event);
		Event withoutPresentation = new Event(event.getId());
		withoutPresentation.setName(event.getName());
		repository.saveEvent(withoutPresentation);
		sync.flushIndex();

		StoreEventRepository restarted = TestRepositories.events(started(appData));
		Event loaded = restarted.loadEvents().get(0);
		assertTrue(loaded.getPresentations().isEmpty());
		restarted.saveEvent(withoutBookings(event));

		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals(1, again.getPresentations().size());
		assertEquals(List.of(), bookingNames(again));
	}

	@Test
	void revivingDeletedPresentationInTheSameSessionRemovesItsOldBookings(@TempDir Path appData) throws IOException {
		StoreEventRepository repository = TestRepositories.events(started(appData));
		Event event = smallEvent();
		repository.saveEvent(event);
		Event withoutPresentation = new Event(event.getId());
		withoutPresentation.setName(event.getName());
		repository.saveEvent(withoutPresentation);

		repository.saveEvent(withoutBookings(event));

		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals(1, again.getPresentations().size());
		assertEquals(List.of(), bookingNames(again));
	}

	/** Der Speicherstand mit der angegebenen Zahl: 0 ist der neueste. */
	private static SavePoint savePoint(StoreEventRepository repository, String eventId, int newestFirst)
			throws IOException {
		return repository.savePoints(eventId).get(newestFirst);
	}

	@Test
	void prepareRestoreTouchesNothing(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		StoreEventRepository repository = TestRepositories.events(started(TestSyncServices.singleMode(appData, clock)));
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		bookingOf(event, 0, "Müller").setLastName("Schulz");
		repository.saveEvent(event);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);
		long files = filesIn(appData);
		long changes = repository.baselineChanges(event.getId());

		RestorePlan<Event> plan = repository.prepareRestoreEvent(event.getId(), s1);

		assertEquals(List.of("Müller"), bookingNames(plan.target()));
		assertEquals(event.getId(), plan.ownerId());
		assertEquals(changes, plan.changes());
		assertEquals(changes, repository.baselineChanges(event.getId()));
		assertEquals(files, filesIn(appData));
		assertSame(event, repository.loadEvents().get(0));
		assertEquals(List.of("Schulz"), bookingNames(event));
		assertEquals(List.of(), recorder.calls);
		// ohne Speichern danach gelingt das Wiederherstellen
		repository.commitRestore(plan, e -> List.of());
		assertEquals(List.of("Müller"), bookingNames(TestRepositories.events(started(appData)).loadEvents().get(0)));
	}

	@Test
	void prepareRestoreRefusesLikeRestore(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		SyncService sync = started(TestSyncServices.singleMode(appData, clock));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = smallEvent();
		repository.saveEvent(event);
		clock.advance(10);
		StoreEventRepository other = TestRepositories.events(sync);
		other.deleteEvent(other.loadEvents().get(0));
		SavePoint deletion = savePoint(other, event.getId(), 0);

		LockedUnitException refused = assertThrows(LockedUnitException.class,
				() -> repository.prepareRestoreEvent(event.getId(), deletion));

		assertEquals("Zu diesem Speicherstand war das Event gelöscht.", refused.getMessage());
	}

	@Test
	void commitRestoreRefusesWhenEventChangedSincePrepare(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		StoreEventRepository repository = TestRepositories.events(started(TestSyncServices.singleMode(appData, clock)));
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		RestorePlan<Event> plan = repository.prepareRestoreEvent(event.getId(), s1);
		// ein normales Speichern dazwischen
		bookingOf(event, 0, "Müller").setLastName("Schulz");
		repository.saveEvent(event);
		long files = filesIn(appData);

		LockedUnitException refused = assertThrows(LockedUnitException.class,
				() -> repository.commitRestore(plan, e -> List.of("Hinweis")));

		assertEquals("Das Event wurde inzwischen geändert. Bitte erneut wiederherstellen.", refused.getMessage());
		assertEquals(files, filesIn(appData));
		assertSame(event, repository.loadEvents().get(0));
		assertEquals(List.of("Schulz"), bookingNames(event));
		// neu vorbereitet geht es
		clock.advance(10);
		repository.commitRestore(repository.prepareRestoreEvent(event.getId(), s1), e -> List.of());
		assertEquals(List.of("Müller"), bookingNames(TestRepositories.events(started(appData)).loadEvents().get(0)));
	}

	@Test
	void savePointsFromIndexEqualFreshResolution(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		SyncService sync = started(TestSyncServices.singleMode(appData, clock));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = eventWithBookings();
		repository.saveEvent(event);
		clock.advance(5);
		event.setDescription("Große Sitzung");
		bookingOf(event, 0, "Müller").setLastName("Schulz");
		repository.saveEvent(event);
		clock.advance(5);
		// eine Buchung wird gelöscht, eine kommt hinzu
		event.getPresentations().get(0).getBookings().removeIf(b -> "Meier".equals(b.getLastName()));
		booking(event.getPresentations().get(1), "seat-4", 4, "Neu");
		repository.saveEvent(event);
		clock.advance(5);
		// von anderer Seite kommt eine Änderung, die dieses Repository nicht kennt; die nächste eigene Änderung verliert
		UnitKey seat = UnitKey.booking(event.getId(), event.getPresentations().get(1).getId(), "seat-1");
		writeForeignVersion(appData, seat, sync.unitState(seat).head(), "Fremd");
		bookingOf(event, 1, "Schmitz").setLastName("Schmidt");
		event.setName("Prunksitzung 2");
		repository.saveEvent(event);

		List<SavePoint> fromIndex = repository.savePoints(event.getId());

		List<List<ChainStep>> fresh = new ArrayList<>();
		boolean anyLost = false;
		boolean anyDeleted = false;
		for (UnitKey unit : sync.eventUnits(event.getId())) {
			UnitState state = sync.unitState(unit);
			anyLost |= !state.lost().isEmpty();
			anyDeleted |= state.chain().stream().anyMatch(Version::deleted);
			fresh.add(state.chain().stream().map(ChainStep::of).toList());
		}
		assertTrue(anyLost, "der Test braucht eine verlorene Version");
		assertTrue(anyDeleted, "der Test braucht eine Löschung");
		assertTrue(fromIndex.size() >= 4, fromIndex.toString());
		assertEquals(RestorePlanner.savePoints(fresh), fromIndex);
		// der Index bleibt auch nach einem Neustart gleich
		sync.flushIndex();
		assertEquals(fromIndex, TestRepositories.events(started(appData)).savePoints(event.getId()));
	}

	@Test
	void restoreSetsBookingsBackAndDeletesLaterOnes(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		StoreEventRepository repository = TestRepositories.events(started(TestSyncServices.singleMode(appData, clock)));
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		Presentation friday = event.getPresentations().get(0);
		booking(friday, "seat-2", 2, "Meier");
		bookingOf(event, 0, "Müller").setLastName("Schulz");
		repository.saveEvent(event);

		repository.restoreEvent(event.getId(), s1, e -> List.of());

		Event restored = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals(List.of("Müller"), bookingNames(restored));
	}

	@Test
	void eventAtWritesNothingAndKeepsTheCache(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		StoreEventRepository repository = TestRepositories.events(started(TestSyncServices.singleMode(appData, clock)));
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		bookingOf(event, 0, "Müller").setLastName("Schulz");
		repository.saveEvent(event);
		long before = filesIn(appData);

		Event earlier = repository.eventAt(event.getId(), s1);

		assertNotSame(event, earlier);
		assertEquals(List.of("Müller"), bookingNames(earlier));
		assertEquals(before, filesIn(appData));
		assertSame(event, repository.loadEvents().get(0));
		assertEquals(List.of("Schulz"), bookingNames(event));
	}

	@Test
	void restoreIsItselfASavePoint(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		StoreEventRepository repository = TestRepositories.events(started(TestSyncServices.singleMode(appData, clock)));
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		booking(event.getPresentations().get(0), "seat-2", 2, "Meier");
		repository.saveEvent(event);
		SavePoint s2 = savePoint(repository, event.getId(), 0);
		assertEquals(2, repository.savePoints(event.getId()).size());
		clock.advance(10);

		repository.restoreEvent(event.getId(), s1, e -> List.of());

		assertEquals(3, repository.savePoints(event.getId()).size());
		assertEquals(List.of("Müller"), bookingNames(TestRepositories.events(started(appData)).loadEvents().get(0)));
		clock.advance(10);

		// Zurück zum Stand vor dem Wiederherstellen
		repository.restoreEvent(event.getId(), s2, e -> List.of());

		assertEquals(4, repository.savePoints(event.getId()).size());
		assertEquals(List.of("Müller", "Meier"),
				bookingNames(TestRepositories.events(started(appData)).loadEvents().get(0)));
	}

	@Test
	void restoreBringsBackDeletedPresentationWithBookings(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		StoreEventRepository repository = TestRepositories.events(started(TestSyncServices.singleMode(appData, clock)));
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		Event withoutPresentation = new Event(event.getId());
		withoutPresentation.setName(event.getName());
		repository.saveEvent(withoutPresentation);
		assertTrue(TestRepositories.events(started(appData)).loadEvents().get(0).getPresentations().isEmpty());
		clock.advance(10);

		repository.restoreEvent(event.getId(), s1, e -> List.of());

		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals(1, again.getPresentations().size());
		assertEquals(List.of("Müller"), bookingNames(again));
	}

	@Test
	void restoreBringsBackDeletedEvent(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		StoreEventRepository repository = TestRepositories.events(started(TestSyncServices.singleMode(appData, clock)));
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		repository.deleteEvent(event);

		assertEquals(List.of(new KnownEntity(event.getId(), "Prunksitzung", true)), repository.knownEvents());
		StoreEventRepository fresh = TestRepositories.events(started(appData));
		assertTrue(fresh.loadEvents().isEmpty());
		assertEquals(List.of(new KnownEntity(event.getId(), "Prunksitzung", true)), fresh.knownEvents());

		fresh.restoreEvent(event.getId(), s1, e -> List.of());

		StoreEventRepository again = TestRepositories.events(started(appData));
		assertEquals(List.of(new KnownEntity(event.getId(), "Prunksitzung", false)), again.knownEvents());
		assertEquals(List.of("Müller"), bookingNames(again.loadEvents().get(0)));
	}

	@Test
	void restoreOfDeletedEventDeletesUnitsCreatedAfterT(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		StoreEventRepository repository = TestRepositories.events(started(TestSyncServices.singleMode(appData, clock)));
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		booking(event.getPresentations().get(0), "seat-2", 2, "Meier");
		repository.saveEvent(event);
		clock.advance(10);
		repository.deleteEvent(event);

		StoreEventRepository fresh = TestRepositories.events(started(appData));
		fresh.restoreEvent(event.getId(), s1, e -> List.of());

		Event again = TestRepositories.events(started(appData)).loadEvents().get(0);
		assertEquals(List.of("Müller"), bookingNames(again));
	}

	@Test
	void restoreToDeletionPointIsRefused(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		SyncService sync = started(TestSyncServices.singleMode(appData, clock));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = smallEvent();
		repository.saveEvent(event);
		clock.advance(10);
		// das Event wird von anderer Seite gelöscht, nachdem dieses Repository es geladen hat
		StoreEventRepository other = TestRepositories.events(sync);
		other.deleteEvent(other.loadEvents().get(0));
		SavePoint deletion = savePoint(other, event.getId(), 0);
		long before = filesIn(appData);

		LockedUnitException refused = assertThrows(LockedUnitException.class,
				() -> repository.restoreEvent(event.getId(), deletion, e -> List.of()));

		assertEquals("Zu diesem Speicherstand war das Event gelöscht.", refused.getMessage());
		assertEquals(before, filesIn(appData));
		// Cache und Vergleichsstand sind unberührt: Das gehaltene Objekt ist noch dasselbe, und Speichern schreibt nichts
		assertSame(event, repository.loadEvents().get(0));
		repository.saveEvent(event);
		assertEquals(before, filesIn(appData));
	}

	@Test
	void restoreAfterOutsideChangesResetsThemAndBuildsOnCurrentEnd(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		SyncService sync = started(TestSyncServices.singleMode(appData, clock));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		// Änderungen, die nach dem Laden dieses Repositorys von anderer Seite dazukommen
		StoreEventRepository other = TestRepositories.events(sync);
		Event outside = other.loadEvents().get(0);
		bookingOf(outside, 0, "Müller").setLastName("Fremd1");
		booking(outside.getPresentations().get(0), "seat-2", 2, "Fremd2");
		other.saveEvent(outside);
		clock.advance(10);
		String presentationId = event.getPresentations().get(0).getId();
		UnitKey seat1 = UnitKey.booking(event.getId(), presentationId, "seat-1");
		UnitKey seat2 = UnitKey.booking(event.getId(), presentationId, "seat-2");

		Restored<Event> restored = repository.restoreEvent(event.getId(), s1, e -> List.of("Hinweis"));

		assertEquals(List.of("Hinweis"), restored.notes());
		assertSame(restored.restored(), repository.loadEvents().get(0));
		List<Version> chain1 = sync.unitState(seat1).chain();
		List<Version> chain2 = sync.unitState(seat2).chain();
		assertEquals(3, chain1.size());
		assertEquals(2, chain2.size());
		assertEquals(chain1.get(1).id(), chain1.get(2).firstParent());
		assertEquals(chain2.get(0).id(), chain2.get(1).firstParent());
		assertEquals("Müller", chain1.get(2).payload().get("lastName").asText());
		assertTrue(chain2.get(1).deleted());
		assertEquals(chain1.get(2).saveId(), chain2.get(1).saveId());
		assertEquals(1, sync.unitState(UnitKey.event(event.getId())).chain().size());
		assertEquals(1, sync.unitState(UnitKey.presentation(event.getId(), presentationId)).chain().size());
		assertEquals(3, repository.savePoints(event.getId()).size());
		assertEquals(List.of("Müller"), bookingNames(TestRepositories.events(started(appData)).loadEvents().get(0)));
	}

	@Test
	void restoreKeepsCacheConsistentWhenSaveFails(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService sync = started(TestSyncServices.singleMode(appData, layer, clock));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		StoreEventRepository other = TestRepositories.events(sync);
		Event outside = other.loadEvents().get(0);
		bookingOf(outside, 0, "Müller").setLastName("Fremd1");
		other.saveEvent(outside);
		clock.advance(10);
		long before = filesIn(appData);
		layer.failNext("createNew", new IOException("Platte voll"));

		RuntimeException failure = assertThrows(RuntimeException.class,
				() -> repository.restoreEvent(event.getId(), s1, e -> List.of()));

		assertTrue(failure.getMessage().startsWith("Fehler beim Speichern von \"Prunksitzung\": "), failure.getMessage());
		// im Cache steht der aktuelle Stand des Speichers, nicht der Zielstand und nicht der alte Stand
		Event cached = repository.loadEvents().get(0);
		assertNotSame(event, cached);
		assertEquals(List.of("Fremd1"), bookingNames(cached));
		assertEquals(before, filesIn(appData));
		repository.saveEvent(cached);
		assertEquals(before, filesIn(appData));
	}

	@Test
	void restoreRefusesTargetWithUnreadableParts(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		SyncService sync = started(TestSyncServices.singleMode(appData, clock));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		// eine Buchung, deren Inhalt nicht zu ihrem Ordner passt (Sitz seat-1 im Ordner seat-5)
		String presentationId = event.getPresentations().get(0).getId();
		UnitKey seat1 = UnitKey.booking(event.getId(), presentationId, "seat-1");
		UnitKey seat5 = UnitKey.booking(event.getId(), presentationId, "seat-5");
		ObjectNode misplaced = sync.unitState(seat1).head().payload().deepCopy();
		sync.append("fremd", List.of(new Change(seat5, List.of(), misplaced, false)));
		clock.advance(10);
		bookingOf(event, 0, "Müller").setLastName("Schulz");
		repository.saveEvent(event);
		clock.advance(10);
		// eine Änderung von anderer Seite nach dem Speichern dieses Repositorys
		StoreEventRepository other = TestRepositories.events(sync);
		Event outside = other.loadEvents().get(0);
		bookingOf(outside, 0, "Schulz").setLastName("Fremd1");
		other.saveEvent(outside);
		long before = filesIn(appData);

		LockedUnitException refused = assertThrows(LockedUnitException.class,
				() -> repository.restoreEvent(event.getId(), s1, e -> List.of()));

		assertTrue(refused.getMessage()
				.startsWith("Der Speicherstand enthält unlesbare Teile und wird nicht wiederhergestellt: "),
				refused.getMessage());
		assertEquals(before, filesIn(appData));
		// Cache und Vergleichsstand sind unberührt: Das gehaltene Objekt ist noch dasselbe, und Speichern schreibt nichts
		assertSame(event, repository.loadEvents().get(0));
		repository.saveEvent(event);
		assertEquals(before, filesIn(appData));
	}

	/**
	 * Müller (Speicherstand s1), dann Schulz; danach bekommt {@code broken} (Kopf oder Vorstellung) von anderer Seite eine
	 * unlesbare, nicht gesperrte Nachfolgeversion (die ID passt nicht zum Ordner). Ein frisch geladenes Repository stellt
	 * s1 wieder her: Die Buchung darunter wird zum gültigen Nachfolger, nicht zu einer unterlegenen Gabelung.
	 */
	private static void restoreUnderUnreadableParent(Path appData, boolean breakHead) throws IOException {
		SteppedClock clock = new SteppedClock();
		SyncService sync = started(TestSyncServices.singleMode(appData, clock));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		clock.advance(10);
		bookingOf(event, 0, "Müller").setLastName("Schulz");
		repository.saveEvent(event);
		clock.advance(10);
		String presentationId = event.getPresentations().get(0).getId();
		UnitKey broken = breakHead ? UnitKey.event(event.getId()) : UnitKey.presentation(event.getId(), presentationId);
		Version parent = sync.unitState(broken).head();
		ObjectNode payload = parent.payload().deepCopy();
		payload.put("id", "andere-id");
		sync.append("fremd", List.of(new Change(broken, List.of(parent.id()), payload, false)));
		clock.advance(10);
		StoreEventRepository fresh = TestRepositories.events(sync);
		fresh.loadEvents();

		Restored<Event> restored = fresh.restoreEvent(event.getId(), s1, e -> List.of());

		assertEquals(List.of("Müller"), bookingNames(restored.restored()));
		UnitState seat1 = sync.unitState(UnitKey.booking(event.getId(), presentationId, "seat-1"));
		assertEquals("Müller", seat1.head().payload().get("lastName").asText());
		assertTrue(seat1.lost().isEmpty(), "die Buchung wird Nachfolger, keine unterlegene Gabelung");
		assertEquals(3, seat1.chain().size());
		UnitState parentState = sync.unitState(broken);
		assertTrue(parentState.lost().isEmpty());
		assertEquals(3, parentState.chain().size(), "auf die unlesbare Version folgt der Zielstand");
		assertEquals(List.of("Müller"), bookingNames(TestRepositories.events(started(appData)).loadEvents().get(0)));
	}

	@Test
	void restoreUnderUnreadablePresentationWritesItsBookingsAsSuccessors(@TempDir Path appData) throws IOException {
		restoreUnderUnreadableParent(appData, false);
	}

	@Test
	void restoreUnderUnreadableHeadWritesItsChildrenAsSuccessors(@TempDir Path appData) throws IOException {
		restoreUnderUnreadableParent(appData, true);
	}

	@Test
	void eventAtOfLockedUnitIsRefused(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = smallEvent();
		repository.saveEvent(event);
		SavePoint s1 = savePoint(repository, event.getId(), 0);
		UnitKey unit = UnitKey.event(event.getId());
		Version first = sync.unitState(unit).head();
		ObjectNode changed = first.payload().deepCopy();
		changed.put("description", "geändert");
		Version second = sync.append("test", List.of(new Change(unit, List.of(first.id()), changed, false))).get(0);
		Files.writeString(appData.resolve("data/events/" + event.getId() + "/" + second.id() + ".json"), "kaputt");

		StoreEventRepository restarted = TestRepositories.events(started(appData));

		LockedUnitException refused = assertThrows(LockedUnitException.class,
				() -> restarted.restoreEvent(event.getId(), s1, e -> List.of()));
		assertTrue(refused.getMessage().contains(unit.relativePath()));
	}

	@Test
	void knownEventsAreSortedByNameIgnoringCase(@TempDir Path appData) {
		StoreEventRepository repository = TestRepositories.events(started(appData));
		Event beta = named("beta");
		Event alpha = named("Alpha");
		Event gamma = named("Gamma");
		repository.saveEvents(List.of(beta, alpha, gamma));
		repository.deleteEvent(gamma);

		assertEquals(List.of(new KnownEntity(alpha.getId(), "Alpha", false), new KnownEntity(beta.getId(), "beta", false),
				new KnownEntity(gamma.getId(), "Gamma", true)), repository.knownEvents());
	}

	@Test
	void loadBeforeStartupIsRefused(@TempDir Path appData) {
		SyncService notStarted = TestSyncServices.singleMode(appData);
		StoreEventRepository repository = TestRepositories.events(notStarted);

		IllegalStateException load = assertThrows(IllegalStateException.class, repository::loadEvents);
		assertThrows(IllegalStateException.class, repository::reloadEvents);
		assertThrows(IllegalStateException.class, () -> repository.saveEvent(named("Zu früh")));

		assertEquals("Der Versionsspeicher wurde noch nicht gestartet.", load.getMessage());
		assertFalse(notStarted.isStarted());
		started(notStarted);
		assertTrue(notStarted.isStarted());
		assertTrue(repository.loadEvents().isEmpty());
	}
}
