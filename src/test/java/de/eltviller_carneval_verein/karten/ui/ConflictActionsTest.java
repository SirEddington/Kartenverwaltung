package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.repository.ConflictPolicies;
import de.eltviller_carneval_verein.karten.repository.LockedUnitException;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.FaultyFileLayer;
import de.eltviller_carneval_verein.karten.store.NioFileLayer;
import de.eltviller_carneval_verein.karten.store.SyncListener;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestClock;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.ConflictActions.Availability;
import de.eltviller_carneval_verein.karten.ui.ConflictList.Action;
import de.eltviller_carneval_verein.karten.ui.ConflictList.Row;
import de.eltviller_carneval_verein.karten.ui.sales.AssignPlaceDialog;

/**
 * Die Aktionen des Sammel-Dialogs auf echten Konflikten: Zwei Rechner mit eigenem Dienst und den Strategien der App tauschen
 * über einen gemeinsamen Ordner aus; der Verlierer führt die Zusammenführung ein wie die App und wählt dann die Aktion.
 * Gespeichert wird über die Repositories des Verlierers (statt {@code EventSaver}, der an die App-Singletons gebunden ist).
 */
class ConflictActionsTest {

	private static final Duration ROUND = Duration.ofSeconds(31);

	/** Ein Rechner: eigener Dienst, eigene Repositories, eigene Tracker, die Registratur der Bearbeitungen. */
	private final class Machine implements SyncListener {
		final String name;
		final SyncService sync;
		final StoreHallRepository halls;
		final StoreEventRepository repository;
		final ChangeTracker<Event> eventTracker = new ChangeTracker<>(Event::getId);
		final ChangeTracker<Hall> hallTracker = new ChangeTracker<>(Hall::getId);
		final EditingUnits editing = new EditingUnits();
		/** Die Dateischicht des Rechners; Tests lassen sie gezielt scheitern. */
		final FaultyFileLayer files = new FaultyFileLayer(new NioFileLayer());
		Hall hall;
		Event event;

		Machine(String name, Path shared) {
			this.name = name;
			sync = TestSyncServices.unstarted(dir.resolve("app-" + name), name, files, p -> true, clock);
			sync.setListener(this);
			ConflictPolicies.install(sync);
			sync.startup(shared.toString());
			halls = TestRepositories.halls(sync);
			repository = TestRepositories.events(sync);
			repository.setHallLookup(id -> halls.findById(id));
		}

		@Override
		public void unitsChanged(Set<UnitKey> units) {
			halls.applyForeign(units);
			repository.applyForeign(units);
		}

		void load() {
			hall = halls.loadHalls().get(0);
			event = repository.loadEvents().get(0);
			for (Presentation presentation : event.getPresentations()) {
				BookingSync.sync(presentation, plan());
			}
		}

		SeatingPlan plan() {
			return hall.getSeatingPlans().get(0);
		}

		Presentation friday() {
			return event.getPresentations().get(0);
		}

		Booking seat(int n) {
			String seatId = plan().getSeats().get(n).getId();
			return friday().getBookings().stream().filter(b -> seatId.equals(b.getSeatId()) && !b.isUnassigned()).findFirst()
					.orElseThrow();
		}

		UnitKey seatUnit(int n) {
			return UnitKey.booking(event.getId(), friday().getId(), plan().getSeats().get(n).getId());
		}

		void saveEvent() {
			repository.saveEvent(event);
		}

		boolean save(Event toSave) {
			repository.saveEvent(toSave);
			return true;
		}

		boolean save(Hall toSave) {
			halls.saveHall(toSave);
			return true;
		}

		ConflictActions actions() {
			return actions(this::save, this::save);
		}

		ConflictActions actions(Predicate<Event> saveEvent, Predicate<Hall> saveHall) {
			return new ConflictActions(repository, halls, eventTracker, hallTracker, editing, sync, saveEvent, saveHall);
		}

		ConflictList list() {
			return new ConflictList(sync::openConflicts, unit -> unit.relativePath(), actions()::isAdopted, hallId -> null);
		}

		Row onlyRow() {
			List<Row> rows = list().rows();
			assertEquals(1, rows.size(), name + ": " + rows);
			return rows.get(0);
		}

		void tick() {
			TestSyncServices.tick(sync);
		}
	}

	@TempDir
	Path dir;

	private final TestClock clock = new TestClock();
	private Machine a;
	private Machine b;
	/** Die Meldungen des Dienstes und der Repositories ab WARNING; INFO (Gabelungen zwischendurch) bleibt aus der Ausgabe. */
	private final List<LogRecord> warnings = new ArrayList<>();
	private final Handler capture = new Handler() {
		@Override
		public void publish(LogRecord record) {
			if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
				warnings.add(record);
			}
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	};
	private final Map<Logger, Boolean> loggers = new LinkedHashMap<>();

	private void captureLogs() {
		for (Class<?> type : List.of(SyncService.class, StoreEventRepository.class, StoreHallRepository.class)) {
			Logger logger = Logger.getLogger(type.getName());
			loggers.put(logger, logger.getUseParentHandlers());
			logger.addHandler(capture);
			logger.setUseParentHandlers(false);
		}
	}

	/** Kein Dienst und kein Repository hat etwas Schlimmes gemeldet (SEVERE). */
	@AfterEach
	void noSevereMessages() {
		loggers.forEach((logger, parentHandlers) -> {
			logger.removeHandler(capture);
			logger.setUseParentHandlers(parentHandlers);
		});
		for (LogRecord record : warnings) {
			assertTrue(record.getLevel().intValue() < Level.SEVERE.intValue(), "SEVERE: " + record.getMessage());
		}
	}

	/** Rechner "a" legt Halle und Event an ("Müller" auf dem ersten Sitz), "b" holt beides ab. */
	@BeforeEach
	void setUp() throws IOException {
		captureLogs();
		Path shared = Files.createDirectories(dir.resolve("shared"));
		a = new Machine("a", shared);
		b = new Machine("b", shared);
		Hall hall = new Hall();
		hall.setName("Stadthalle");
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 2, 3);
		a.halls.saveHall(hall);
		a.hall = hall;
		Event event = new Event();
		event.setName("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		friday.setHallId(hall.getId());
		friday.setSeatingPlanId(plan.getId());
		BookingSync.sync(friday, plan);
		a.event = event;
		a.seat(0).setLastName("Müller");
		a.saveEvent();
		a.tick();
		clock.advance(ROUND);
		b.tick();
		b.load();
	}

	/** Takte beide Rechner reihum, lange genug, dass Verlierer und Gewinner den Stand des anderen eingearbeitet haben. */
	private void settle() {
		for (int round = 0; round < 6; round++) {
			clock.advance(ROUND);
			a.tick();
			b.tick();
		}
	}

	/** Beide ändern den Kommentar der Buchung auf dem ersten Sitz verschieden; "a" gewinnt, "b" hat einen FIELDS-Konflikt. */
	private Row commentConflict() {
		a.seat(0).setComment("von A");
		a.saveEvent();
		b.seat(0).setComment("von B");
		b.saveEvent();
		settle();
		return b.onlyRow();
	}

	/** Beide verkaufen den zweiten Sitz; "a" gewinnt, "b" hat einen Doppelverkauf. */
	private Row doubleSale() {
		a.seat(1).setLastName("Anna");
		a.saveEvent();
		b.seat(1).setLastName("Bert");
		b.saveEvent();
		settle();
		return b.onlyRow();
	}

	@Test
	void actionsAreBlockedWhileEventIsDirty() {
		Row row = commentConflict();
		ConflictActions actions = b.actions();
		assertEquals(new Availability(true, ""), actions.availability(row));

		b.eventTracker.track(b.event);
		b.seat(3).setLastName("Neu");
		b.eventTracker.check(b.event);

		assertEquals(new Availability(false, "Erst speichern oder verwerfen"), actions.availability(row));
		assertThrows(IllegalStateException.class, () -> actions.takeMyValue(row, "comment"));
		assertEquals("von A", b.seat(0).getComment(), "nichts angefasst");
		assertEquals(1, b.sync.openConflicts().size());
	}

	@Test
	void actionsAreBlockedWhileBookingIsInEditing() {
		Row row = commentConflict();
		ConflictActions actions = b.actions();
		Object owner = new Object();

		b.editing.begin(owner, List.of(b.seat(4)));

		assertEquals(new Availability(false, "Erst speichern oder verwerfen"), actions.availability(row));
		b.editing.end(owner);
		assertTrue(actions.availability(row).enabled());
	}

	@Test
	void hallActionIsBlockedWhileHallIsDirty() {
		a.hall.setName("Halle A");
		a.halls.saveHall(a.hall);
		b.halls.findById(a.hall.getId()).setName("Halle B");
		b.halls.saveHall(b.halls.findById(a.hall.getId()));
		settle();
		Row row = b.onlyRow();
		assertEquals(Action.TAKE_MY_HALL, row.action());
		Hall current = b.halls.findById(a.hall.getId());
		b.hallTracker.track(current);
		current.setName("Halle C");
		b.hallTracker.check(current);

		assertEquals(new Availability(false, "Erst speichern oder verwerfen"), b.actions().availability(row));
	}

	@Test
	void takeMyValueWritesMyGroupAndAcknowledges() throws IOException {
		Row row = commentConflict();
		assertEquals(Action.TAKE_MY_VALUE, row.action());
		assertEquals("von A", b.seat(0).getComment(), "bis zur Wahl gilt der Wert des Gewinners");

		b.actions().takeMyValue(row, "comment");

		UnitKey unit = b.seatUnit(0);
		assertEquals("von B", b.seat(0).getComment());
		assertEquals("Müller", b.seat(0).getLastName(), "andere Gruppen bleiben");
		assertEquals("von B", b.sync.unitState(unit).current().payload().get("comment").asText());
		assertEquals(List.of(), b.sync.openConflicts());
		settle();
		assertEquals("von B", a.seat(0).getComment(), "der andere Rechner bekommt die neue Version");
	}

	@Test
	void takeMyValueOnTransferGoesToTheMovedUnit() throws IOException {
		// a verschiebt Müller auf den zweiten Sitz und ändert den Namen, b ändert den Namen auf dem ersten Sitz
		Booking from = a.seat(0);
		Booking to = a.seat(1);
		Seat target = a.plan().getSeats().get(1);
		String bookingId = from.getId();
		to.copyFrom(from);
		to.takeSnapshot(target.getParent(), target);
		to.setLastName("Müller-A");
		from.clearCustomerData();
		a.saveEvent();
		b.seat(0).setLastName("Müller-B");
		b.saveEvent();
		settle();
		Row row = b.onlyRow();
		assertEquals(Action.TAKE_MY_VALUE, row.action());
		assertEquals(b.seatUnit(1), row.conflict().note().moved());
		assertEquals("Müller-A", b.seat(1).getLastName());

		b.actions().takeMyValue(row, "customer");

		assertEquals("Müller-B", b.seat(1).getLastName(), "die Änderung gilt für die verschobene Buchung");
		assertEquals(bookingId, b.seat(1).getId());
		assertFalse(b.seat(0).hasData(), "der alte Sitz bleibt leer");
		assertEquals(List.of(), b.sync.openConflicts());
	}

	@Test
	void restoreBookingOnFreeSeatPutsItBack() throws IOException {
		String bookingId = b.seat(0).getId();
		a.seat(0).clearCustomerData();
		a.saveEvent();
		b.seat(0).setComment("von B");
		b.saveEvent();
		settle();
		Row row = b.onlyRow();
		assertEquals(Action.RESTORE_BOOKING, row.action());
		assertFalse(b.seat(0).hasData(), "die Stornierung gilt");

		b.actions().restoreBooking(row);

		assertEquals("Müller", b.seat(0).getLastName());
		assertEquals("von B", b.seat(0).getComment());
		assertEquals(bookingId, b.seat(0).getId());
		assertEquals(1, b.friday().getBookings().stream().filter(booking -> bookingId.equals(booking.getId())).count());
		assertFalse(b.seat(0).isUnassigned());
		var current = b.sync.unitState(b.seatUnit(0)).current();
		assertFalse(current.deleted());
		assertEquals("Müller", current.payload().get("lastName").asText());
		assertEquals(List.of(), b.sync.openConflicts());
	}

	@Test
	void restoreBookingOnTakenSeatAddsItWithoutPlace() throws IOException {
		String bookingId = b.seat(0).getId();
		a.seat(0).clearCustomerData();
		a.seat(0).setLastName("Neu");
		a.saveEvent();
		b.seat(0).setComment("von B");
		b.saveEvent();
		settle();
		Row row = b.onlyRow();
		assertEquals(Action.RESTORE_BOOKING, row.action());
		assertEquals("Neu", b.seat(0).getLastName(), "der Sitz gehört dem neuen Verkauf");

		b.actions().restoreBooking(row);

		assertEquals("Neu", b.seat(0).getLastName(), "der neue Verkauf bleibt unangetastet");
		List<Booking> restored = b.friday().getBookings().stream().filter(booking -> bookingId.equals(booking.getId()))
				.toList();
		assertEquals(1, restored.size());
		assertTrue(restored.get(0).isUnassigned());
		assertNull(restored.get(0).getSeatId());
		assertEquals("Müller", restored.get(0).getLastName());
		assertEquals("von B", restored.get(0).getComment());
		UnitKey without = UnitKey.unassignedBooking(b.event.getId(), b.friday().getId(), bookingId);
		var current = b.sync.unitState(without).current();
		assertFalse(current.deleted());
		assertEquals("", current.payload().get("rowLabel").asText());
		assertEquals(0, current.payload().get("tableNumber").asInt());
		assertEquals(List.of(), b.sync.openConflicts());
	}

	@Test
	void restoreBookingIsNotOfferedWhileItsIdLivesElsewhere() {
		a.seat(0).clearCustomerData();
		a.saveEvent();
		b.seat(0).setComment("von B");
		b.saveEvent();
		settle();
		Row row = b.onlyRow();
		// Müller steht inzwischen (ungespeichert wie gespeichert) auf einem anderen Sitz des Modells
		Booking elsewhere = b.seat(2);
		elsewhere.setId(droppedBookingId(b, row));
		elsewhere.setLastName("Müller");

		assertEquals(new Availability(false, "Nicht mehr nötig"), b.actions().availability(row));
		assertThrows(IllegalStateException.class, () -> b.actions().restoreBooking(row));
		assertEquals(1, b.sync.openConflicts().size());
	}

	private String droppedBookingId(Machine machine, Row row) {
		try {
			return machine.sync.version(row.conflict().unit(), row.conflict().note().dropped()).payload().get("id").asText();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void takeMyHallWritesMyHallAgain() throws IOException {
		String hallId = a.hall.getId();
		a.hall.setName("Halle A");
		a.halls.saveHall(a.hall);
		Hall mine = b.halls.findById(hallId);
		mine.setName("Halle B");
		b.halls.saveHall(mine);
		settle();
		Row row = b.onlyRow();
		assertEquals(Action.TAKE_MY_HALL, row.action());
		assertEquals("Halle A", b.halls.findById(hallId).getName(), "bis zur Wahl gilt die Halle des Gewinners");

		b.actions().takeMyHall(row);

		assertEquals("Halle B", b.halls.findById(hallId).getName());
		assertEquals("Halle B", b.sync.unitState(UnitKey.hall(hallId)).current().payload().get("name").asText());
		assertEquals(List.of(), b.sync.openConflicts());
		settle();
		assertEquals("Halle B", a.halls.findById(hallId).getName());
	}

	@Test
	void failedActionKeepsConflictOpen() {
		Row row = commentConflict();

		ConflictActions refusing = b.actions(saved -> false, hall -> true);
		assertThrows(IllegalStateException.class, () -> refusing.takeMyValue(row, "comment"));
		ConflictActions locked = b.actions(saved -> {
			throw new LockedUnitException("gesperrt");
		}, hall -> true);
		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> locked.takeMyValue(row, "comment"));

		assertTrue(failure.getMessage().contains("gesperrt"), failure.getMessage());
		assertEquals(1, b.sync.openConflicts().size(), "nichts bestätigt");
		assertEquals("von A", b.seat(0).getComment(), "das Modell ist wieder wie vorher");
		assertEquals(Set.of(), b.repository.locallyChangedUnits(), "nichts gilt als ungespeichert");
	}

	@Test
	void vanishedTargetOffersNothing() {
		Row sale = doubleSale();
		Booking withoutPlace = b.actions().bookingToAssign(sale);
		assertNotNull(withoutPlace);
		assertEquals("Bert", withoutPlace.getLastName());
		assertTrue(withoutPlace.isUnassigned());
		assertTrue(b.actions().availability(sale).enabled());

		// der Nutzer setzt die Buchung selbst auf einen freien Sitz
		Seat free = b.plan().getSeats().get(5);
		assertTrue(BookingSync.assign(b.friday(), b.plan(), withoutPlace, free));
		b.saveEvent();

		assertEquals(new Availability(false, "Nicht mehr nötig"), b.actions().availability(sale));
		assertNull(b.actions().bookingToAssign(sale));
		assertEquals(Action.ASSIGN_PLACE, sale.action(), "die Art der Zeile ändert sich nicht, nur ihre Verfügbarkeit");
	}

	@Test
	void anotherBookingOnTheSeatIsNeverOverwritten() {
		Row row = commentConflict();
		b.seat(0).clearCustomerData();
		b.seat(0).setLastName("Neu");
		b.saveEvent();

		assertEquals(new Availability(false, "Nicht mehr nötig"), b.actions().availability(row));
		assertThrows(IllegalStateException.class, () -> b.actions().takeMyValue(row, "comment"));
		assertEquals("Neu", b.seat(0).getLastName());
		assertNull(b.seat(0).getComment());
	}

	@Test
	void takeMyValueWorksForThePresentationToo() {
		a.friday().setName("Freitag A");
		a.saveEvent();
		b.friday().setName("Freitag B");
		b.saveEvent();
		settle();
		Row row = b.onlyRow();
		assertEquals(Action.TAKE_MY_VALUE, row.action());
		assertEquals("Freitag A", b.friday().getName());

		b.actions().takeMyValue(row, "name");

		assertEquals("Freitag B", b.friday().getName());
		assertEquals(List.of(), b.sync.openConflicts());
	}

	@Test
	void vanishedBookingForTakeMyValueOffersNothing() {
		Row row = commentConflict();
		b.seat(0).clearCustomerData();
		b.saveEvent();

		assertEquals(new Availability(false, "Nicht mehr nötig"), b.actions().availability(row));
		assertThrows(IllegalStateException.class, () -> b.actions().takeMyValue(row, "comment"));
	}

	// Mehrere Gruppen (R28) -->

	@Test
	void rowWithTwoGroupsStaysOpenUntilEveryGroupIsAdopted() {
		a.seat(0).setLastName("Meier-A");
		a.seat(0).setComment("von A");
		a.saveEvent();
		b.seat(0).setLastName("Meier-B");
		b.seat(0).setComment("von B");
		b.saveEvent();
		settle();
		Row row = b.onlyRow();
		assertEquals(2, row.fields().size());
		assertEquals(List.of(false, false), row.fields().stream().map(ConflictList.FieldRow::adopted).toList());
		ConflictActions actions = b.actions();

		actions.takeMyValue(row, "comment");

		assertEquals(1, b.sync.openConflicts().size(), "die Zeile bleibt offen");
		assertEquals("von B", b.seat(0).getComment());
		assertEquals("Meier-A", b.seat(0).getLastName(), "die andere Gruppe bleibt, wie sie ist");
		Row after = b.onlyRow();
		assertEquals(List.of("customer", "comment"), after.fields().stream().map(ConflictList.FieldRow::group).toList());
		assertEquals(List.of(false, true), after.fields().stream().map(ConflictList.FieldRow::adopted).toList());
		assertTrue(actions.availability(after).enabled());
		assertThrows(IllegalStateException.class, () -> actions.takeMyValue(after, "comment"),
				"eine übernommene Gruppe bietet keine Aktion mehr");

		actions.takeMyValue(after, "customer");

		assertEquals("Meier-B", b.seat(0).getLastName());
		assertEquals(List.of(), b.sync.openConflicts(), "mit der letzten Gruppe ist die Zeile bestätigt");
	}

	@Test
	void refreshCacheGivesTheSameAnswersAndEndsWithTheRefresh() {
		a.seat(0).setLastName("Meier-A");
		a.seat(0).setComment("von A");
		a.saveEvent();
		b.seat(0).setLastName("Meier-B");
		b.seat(0).setComment("von B");
		b.saveEvent();
		settle();
		ConflictActions actions = b.actions();
		Row row = b.onlyRow();
		assertFalse(actions.isAdopted(row.conflict(), "comment"));
		Availability plain = actions.availability(row);
		List<Object> cached = new ArrayList<>();

		actions.duringRefresh(() -> {
			cached.add(actions.isAdopted(row.conflict(), "customer"));
			cached.add(actions.isAdopted(row.conflict(), "comment"));
			cached.add(actions.availability(row));
			cached.add(actions.availability(row));
		});

		assertEquals(List.of(false, false, plain, plain), cached);
		b.seat(0).setComment("von B");
		assertTrue(actions.isAdopted(row.conflict(), "comment"), "nach dem Auffrischen gilt der Zwischenspeicher nicht mehr");
	}

	@Test
	void rowWithOneGroupIsAcknowledgedByTheAction() {
		Row row = commentConflict();
		assertEquals(1, row.fields().size());

		b.actions().takeMyValue(row, "comment");

		assertEquals(List.of(), b.sync.openConflicts());
	}

	// <-- Mehrere Gruppen

	// Zurücksetzen nach einer gescheiterten Aktion (I2) -->

	@Test
	void valueThatWasAlreadyWrittenIsKeptWhenOnlyTheAnswerIsLost() throws IOException {
		Row row = commentConflict();
		UnitKey unit = b.seatUnit(0);
		b.files.failAfterWhen(p -> p.toString().replace('\\', '/').contains(unit.relativePath()), "createNew",
				new IOException("Antwort verloren"));

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> b.actions().takeMyValue(row, "comment"));

		assertTrue(failure.getMessage().contains("nicht bestätigt"), failure.getMessage());
		assertEquals("von B", b.sync.unitState(unit).current().payload().get("comment").asText(), "es ist geschrieben");
		assertEquals("von B", b.seat(0).getComment(), "das Modell behält den geschriebenen Wert");
		assertEquals(Set.of(), b.repository.locallyChangedUnits(), "das nächste Speichern schreibt den Wert nicht zurück");
		assertEquals(1, b.sync.openConflicts().size(), "nicht bestätigt");
	}

	@Test
	void hallThatWasAlreadyWrittenIsKeptWhenOnlyTheAnswerIsLost() throws IOException {
		Row row = hallConflict();
		String hallId = a.hall.getId();
		b.files.failAfterWhen(p -> p.toString().replace('\\', '/').contains(UnitKey.hall(hallId).relativePath()),
				"createNew", new IOException("Antwort verloren"));

		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> b.actions().takeMyHall(row));

		assertTrue(failure.getMessage().contains("nicht bestätigt"), failure.getMessage());
		assertEquals("Halle B", b.halls.findById(hallId).getName());
		assertEquals("Halle B", b.sync.unitState(UnitKey.hall(hallId)).current().payload().get("name").asText());
		assertEquals(1, b.sync.openConflicts().size());
	}

	@Test
	void failedRestoreOnTheFreeSeatResetsThePlaceholder() {
		Row row = freeSeatRestore();
		Booking placeholder = b.seat(0);
		String id = placeholder.getId();

		assertThrows(IllegalStateException.class, () -> b.actions(saved -> false, hall -> true).restoreBooking(row));

		assertSame(placeholder, b.seat(0));
		assertFalse(placeholder.hasData(), "der Sitz ist wieder frei");
		assertEquals(id, placeholder.getId());
		assertEquals(Set.of(), b.repository.locallyChangedUnits());
		assertEquals(1, b.sync.openConflicts().size());
	}

	@Test
	void failedRestoreWithoutPlaceRemovesTheAddedBooking() {
		String bookingId = b.seat(0).getId();
		a.seat(0).clearCustomerData();
		a.seat(0).setLastName("Neu");
		a.saveEvent();
		b.seat(0).setComment("von B");
		b.saveEvent();
		settle();
		Row row = b.onlyRow();
		int before = b.friday().getBookings().size();

		assertThrows(IllegalStateException.class, () -> b.actions(saved -> false, hall -> true).restoreBooking(row));

		assertEquals(before, b.friday().getBookings().size());
		assertTrue(b.friday().getBookings().stream().noneMatch(booking -> bookingId.equals(booking.getId())));
		assertEquals(Set.of(), b.repository.locallyChangedUnits());
		assertEquals(1, b.sync.openConflicts().size());
	}

	@Test
	void failedTakeMyHallPutsTheOldHallBack() {
		Row row = hallConflict();
		String hallId = a.hall.getId();
		Hall current = b.halls.findById(hallId);

		assertThrows(IllegalStateException.class, () -> b.actions(saved -> true, hall -> false).takeMyHall(row));

		assertSame(current, b.halls.findById(hallId));
		assertEquals("Halle A", current.getName());
		assertEquals(1, b.sync.openConflicts().size());
	}

	/** "a" und "b" benennen die Halle verschieden; "a" gewinnt, "b" hat einen Hallenkonflikt. */
	private Row hallConflict() {
		String hallId = a.hall.getId();
		a.hall.setName("Halle A");
		a.halls.saveHall(a.hall);
		Hall mine = b.halls.findById(hallId);
		mine.setName("Halle B");
		b.halls.saveHall(mine);
		settle();
		return b.onlyRow();
	}

	/** "a" storniert den ersten Sitz, "b" ändert dort den Kommentar; der Sitz ist danach frei. */
	private Row freeSeatRestore() {
		a.seat(0).clearCustomerData();
		a.saveEvent();
		b.seat(0).setComment("von B");
		b.saveEvent();
		settle();
		return b.onlyRow();
	}

	// <-- Zurücksetzen

	// Buchung noch am Leben (I3) -->

	/** Setzt den Inhalt als Buchung "ohne Platz" (wie beim Doppelverkauf). */
	private static ObjectNode withoutPlace(ObjectNode payload) {
		ObjectNode copy = payload.deepCopy();
		copy.put("unassigned", true);
		copy.putNull("seatId");
		copy.put("rowLabel", "");
		copy.put("tableNumber", 0);
		copy.put("seatNumber", 0);
		return copy;
	}

	@Test
	void restoreIsNotOfferedWhileTheBookingIsInAnOpenLostStretch() throws IOException {
		String bookingId = b.seat(0).getId();
		UnitKey other = b.seatUnit(3);
		a.seat(0).clearCustomerData();
		a.seat(3).setLastName("Zed");
		a.saveEvent();
		a.tick();
		b.seat(0).setComment("von B");
		b.saveEvent();
		// eine ältere eigene Version derselben Buchung auf einem anderen Sitz, den "a" zuerst verkauft hat: Sie verliert dort
		ObjectNode payload = b.repository.payloadOf(b.seatUnit(0)).deepCopy();
		Booking seat = b.seat(3);
		payload.put("seatId", seat.getSeatId());
		payload.put("rowLabel", seat.getRowLabel());
		payload.put("tableNumber", seat.getTableNumber());
		payload.put("seatNumber", seat.getSeatNumber());
		b.sync.append("lost-copy", List.of(new Change(other, List.of(), payload, false)));
		// die Auflösung kann die Buchung "ohne Platz" nicht schreiben: Die verlorene Strecke bleibt offen
		AtomicBoolean broken = new AtomicBoolean(true);
		b.files.failWhen(p -> broken.get() && p.toString().contains("_" + bookingId), "createNew",
				new IOException("Platte voll"));
		settle();
		warnings.clear();
		Row row = b.list().rows().stream().filter(candidate -> candidate.action() == Action.RESTORE_BOOKING).findFirst()
				.orElseThrow();
		assertEquals(Set.of(other), b.sync.lostUnits(List.of(other)), "die Strecke ist offen");
		assertFalse(b.repository.bookingLives(b.event.getId(), b.friday().getId(), bookingId),
				"weder Modell noch aktueller Stand kennen die Buchung");

		assertEquals(new Availability(false, "Nicht mehr nötig"), b.actions().availability(row));

		broken.set(false);
		settle();
		assertEquals(Set.of(), b.sync.lostUnits(List.of(other)), "aufgelöst");
		assertTrue(b.repository.bookingLives(b.event.getId(), b.friday().getId(), bookingId), "jetzt lebt sie ohne Platz");
		assertEquals(new Availability(false, "Nicht mehr nötig"), b.actions().availability(row));
	}

	@Test
	void restoreIsNotOfferedWhenOnlyTheStoreKnowsTheBooking() throws IOException {
		Row row = freeSeatRestore();
		assertTrue(b.actions().availability(row).enabled());
		String bookingId = droppedBookingId(b, row);
		UnitKey without = UnitKey.unassignedBooking(b.event.getId(), b.friday().getId(), bookingId);
		ObjectNode payload = withoutPlace(b.sync.version(row.conflict().unit(), row.conflict().note().dropped()).payload());
		b.sync.append("store-only", List.of(new Change(without, List.of(), payload, false)));
		assertTrue(b.friday().getBookings().stream().noneMatch(booking -> booking.hasData() && bookingId.equals(booking.getId())),
				"das Modell kennt sie nicht");

		assertTrue(b.repository.bookingLives(b.event.getId(), b.friday().getId(), bookingId));
		assertEquals(new Availability(false, "Nicht mehr nötig"), b.actions().availability(row));
	}

	@Test
	void anUnreadableBookingUnitCountsAsPossiblyAlive() throws IOException {
		Row row = freeSeatRestore();
		UnitKey broken = b.seatUnit(4);
		ObjectNode garbage = JsonNodeFactory.instance.objectNode().put("tableNumber", "keine Zahl");
		b.sync.append("unreadable", List.of(new Change(broken, List.of(), garbage, false)));

		assertTrue(b.repository.bookingLives(b.event.getId(), b.friday().getId(), droppedBookingId(b, row)));
		assertEquals(new Availability(false, "Nicht mehr nötig"), b.actions().availability(row));
	}

	// <-- Buchung noch am Leben

	// Platz zuweisen und Hinweise vor dem Übernehmen (Aufgabe 16) -->

	@Test
	void assignPlaceGivesTheBookingTheSeatAndAcknowledges() throws IOException {
		Row sale = doubleSale();
		Seat free = b.plan().getSeats().get(5);

		b.actions().assignPlace(sale, free);

		Booking bert = b.seat(5);
		assertEquals("Bert", bert.getLastName());
		assertFalse(bert.isUnassigned());
		assertEquals(1, b.friday().getBookings().stream().filter(booking -> free.getId().equals(booking.getSeatId())).count());
		assertEquals("Bert", b.sync.unitState(b.seatUnit(5)).current().payload().get("lastName").asText());
		assertEquals(List.of(), b.sync.openConflicts());
	}

	@Test
	void assignPlaceToATakenSeatSaysSoAndChangesNothing() {
		Row sale = doubleSale();
		Seat taken = b.plan().getSeats().get(0);
		List<Booking> before = new ArrayList<>(b.friday().getBookings());

		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> b.actions().assignPlace(sale, taken));

		assertEquals("Platz zuweisen nicht möglich: " + AssignPlaceDialog.label(taken) + " ist bereits belegt.",
				failure.getMessage());
		assertEquals(before, b.friday().getBookings());
		assertTrue(b.actions().bookingToAssign(sale).isUnassigned());
		assertEquals(1, b.sync.openConflicts().size());
	}

	@Test
	void failedAssignPutsTheBookingBackWithoutPlace() {
		Row sale = doubleSale();
		Booking bert = b.actions().bookingToAssign(sale);
		Booking snapshot = bert.copy();
		List<Booking> before = new ArrayList<>(b.friday().getBookings());

		assertThrows(IllegalStateException.class,
				() -> b.actions(saved -> false, hall -> true).assignPlace(sale, b.plan().getSeats().get(5)));

		assertEquals(before, b.friday().getBookings(), "auch der leere Platzhalter des Sitzes ist wieder da");
		assertTrue(bert.isUnassigned());
		assertEquals(snapshot.getSeatId(), bert.getSeatId());
		assertEquals(snapshot.getPlaceLabel(), bert.getPlaceLabel());
		assertEquals(Set.of(), b.repository.locallyChangedUnits());
		assertEquals(1, b.sync.openConflicts().size());
	}

	@Test
	void assignPlaceIsRefusedWhileTheEventIsDirty() {
		Row sale = doubleSale();
		b.eventTracker.track(b.event);
		b.seat(3).setLastName("Neu");
		b.eventTracker.check(b.event);

		assertThrows(IllegalStateException.class, () -> b.actions().assignPlace(sale, b.plan().getSeats().get(5)));
		assertTrue(b.actions().bookingToAssign(sale).isUnassigned());
	}

	@Test
	void dismissAcknowledgesWithoutChangingTheModel() {
		Row row = commentConflict();

		b.actions().dismiss(row);

		assertEquals(List.of(), b.sync.openConflicts());
		assertEquals("von A", b.seat(0).getComment(), "ohne Aktion bleibt der gültige Wert");
		assertEquals(Set.of(), b.repository.locallyChangedUnits());
	}

	@Test
	void dismissFailureKeepsTheConflictAndSaysSo() {
		Row row = commentConflict();
		b.files.failWhen(p -> p.toString().contains("conflicts-ack"), "createNew", new IOException("Platte voll"));

		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> b.actions().dismiss(row));

		assertTrue(failure.getMessage().contains("nicht bestätigen"), failure.getMessage());
		assertEquals(1, b.sync.openConflicts().size());
	}

	@Test
	void valueWithoutConsequencesForBookingsHasNoNotice() {
		Row row = commentConflict();

		assertNull(b.actions().bookingImpact(row, "comment"));
	}

	@Test
	void takeMyHallNamesTheBookingsThatLoseTheirSeat() {
		String hallId = a.hall.getId();
		a.hall.setName("Halle A");
		a.halls.saveHall(a.hall);
		Hall mine = b.halls.findById(hallId);
		SeatingPlan plan = mine.getSeatingPlans().get(0);
		plan.deleteTable(plan.getTables().get(0));
		b.halls.saveHall(mine);
		settle();
		Row row = b.onlyRow();
		assertEquals(Action.TAKE_MY_HALL, row.action());

		String note = b.actions().bookingImpact(row, null);

		assertNotNull(note);
		assertTrue(note.contains("1 Buchung mit Daten in Vorstellung Freitag (Event Prunksitzung)"), note);
	}

	/** Merkt sich, welche Einheiten ein Repository als "anderswo geändert" meldet. */
	private static final class Announced<T> implements RepositoryListener<T> {
		final List<Set<UnitKey>> calls = new ArrayList<>();

		@Override
		public void changedElsewhere(T entity, Set<UnitKey> units) {
			calls.add(units);
		}
	}

	@Test
	void actionsTellTheViewsWhatChanged() throws IOException {
		Announced<Event> eventViews = new Announced<>();
		b.repository.addListener(eventViews);

		Row sale = doubleSale();
		Seat free = b.plan().getSeats().get(2);
		b.actions().assignPlace(sale, free);

		assertEquals(1, eventViews.calls.size());
		assertTrue(eventViews.calls.get(0).contains(UnitKey.booking(b.event.getId(), b.friday().getId(), free.getId())),
				"der zugewiesene Sitz: " + eventViews.calls);
		assertTrue(eventViews.calls.get(0).contains(sale.conflict().note().moved()), "die Buchung ohne Platz");

		Row comment = commentConflict();
		b.actions().takeMyValue(comment, "comment");

		assertEquals(Set.of(b.seatUnit(0)), eventViews.calls.get(1));
	}

	@Test
	void failedActionTellsTheViewsNothing() {
		Announced<Event> eventViews = new Announced<>();
		b.repository.addListener(eventViews);
		Row row = commentConflict();

		assertThrows(IllegalStateException.class, () -> b.actions(saved -> false, hall -> true).takeMyValue(row, "comment"));

		assertEquals(List.of(), eventViews.calls);
	}

	@Test
	void takeMyHallTellsTheHallViews() {
		Announced<Hall> hallViews = new Announced<>();
		b.halls.addListener(hallViews);
		Row row = hallConflict();

		b.actions().takeMyHall(row);

		assertEquals(List.of(Set.of(UnitKey.hall(a.hall.getId()))), hallViews.calls);
	}

	@Test
	void hallVersionsDescribeBothSeatingPlans() {
		String hallId = a.hall.getId();
		a.hall.setName("Halle A");
		a.halls.saveHall(a.hall);
		Hall mine = b.halls.findById(hallId);
		SeatingPlan plan = mine.getSeatingPlans().get(0);
		plan.setName("Voll");
		plan.deleteTable(plan.getTables().get(0));
		b.halls.saveHall(mine);
		settle();
		Row row = b.onlyRow();

		List<String> lines = b.actions().hallVersions(row);

		assertEquals(List.of("Deine Fassung: „Stadthalle“; Voll: 1 Tisch, 3 Sitze",
				"Gültig: „Halle A“; Bestuhlung 1: 2 Tische, 6 Sitze"), lines);
	}

	@Test
	void hallVersionsWithTheSameCountsSayThatTheLayoutDiffers() {
		Row row = hallConflict();

		List<String> lines = b.actions().hallVersions(row);

		assertEquals(3, lines.size(), lines.toString());
		assertEquals("Gleich viele Tische und Sitze; die Fassungen unterscheiden sich in Namen, Anordnung oder Maßen.",
				lines.get(2));
	}

	@Test
	void takeMyHallWithoutLostSeatsHasNoNotice() {
		assertNull(b.actions().bookingImpact(hallConflict(), null));
	}

	@Test
	void takeMyValueOfAnotherSeatingPlanNamesTheEffectOnTheBookings() {
		Hall shared = a.hall;
		SeatingPlan second = shared.addSeatingPlan();
		TableRow secondRow = second.addRow();
		secondRow.setLabel("B");
		second.fillRow(secondRow, 1, 1);
		SeatingPlan third = shared.addSeatingPlan();
		TableRow thirdRow = third.addRow();
		thirdRow.setLabel("C");
		third.fillRow(thirdRow, 1, 1);
		a.halls.saveHall(shared);
		settle();
		a.friday().setSeatingPlanId(second.getId());
		a.saveEvent();
		b.friday().setSeatingPlanId(third.getId());
		b.saveEvent();
		settle();
		Row row = b.onlyRow();
		assertEquals(Action.TAKE_MY_VALUE, row.action());
		assertEquals(List.of("hall"), row.fields().stream().map(ConflictList.FieldRow::group).toList());

		String note = b.actions().bookingImpact(row, "hall");

		assertNotNull(note);
		assertTrue(note.contains("1 ohne Platz"), note);
	}

	// <-- Platz zuweisen und Hinweise
}
