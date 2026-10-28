package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestClock;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

/**
 * Fremde Stände im Event-Repository. "Der andere Rechner" ist meist ein zweites Repository auf demselben Dienst: Es
 * speichert, und das erste bekommt die Einheiten mit {@link StoreEventRepository#applyForeign} gemeldet.
 */
class StoreEventRepositoryForeignTest {

	/** Merkt sich die Benachrichtigungen über fremde Stände. */
	private static final class Recorder implements RepositoryListener<Event> {
		final List<String> calls = new ArrayList<>();
		final List<ForeignBatch<Event>> batches = new ArrayList<>();
		final List<Event> entities = new ArrayList<>();

		@Override
		public void changingByOthers(Event event) {
			calls.add("changing " + event.getName());
		}

		@Override
		public void changedByOthers(Event event, ForeignBatch<Event> batch) {
			calls.add("changed " + event.getName());
			batches.add(batch);
			entities.add(event);
		}

		@Override
		public void addedByOthers(Event event) {
			calls.add("added " + event.getName());
			entities.add(event);
		}

		@Override
		public void removedByOthers(Event event) {
			calls.add("removed " + event.getName());
			entities.add(event);
		}
	}

	/** Das Event, wie es der andere Rechner geladen und für die Anzeige vorbereitet hat. */
	private record Other(StoreEventRepository repository, Event event) {
		void save() {
			repository.saveEvent(event);
		}

		Presentation friday() {
			return event.getPresentations().get(0);
		}
	}

	@TempDir
	Path appData;

	private SyncService sync;
	private Hall hall;
	private SeatingPlan plan;
	private StoreEventRepository mine;
	private Event event;
	private Presentation friday;
	private final Recorder recorder = new Recorder();
	private ForeignVersions foreign;

	/**
	 * Eine Halle mit 2 Tischen zu je 3 Sitzen; ein Event "Prunksitzung" mit der Vorstellung "Freitag" in dieser Halle,
	 * vorbereitet wie in der App (leere Platzhalter auf jedem Sitz), "Müller" auf dem ersten Sitz, gespeichert.
	 */
	@BeforeEach
	void setUp() {
		sync = TestSyncServices.singleMode(appData);
		foreign = new ForeignVersions(sync, appData);
		sync.startup(null, p -> {
		});
		hall = new Hall();
		hall.setName("Stadthalle");
		plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 2, 3);
		TestRepositories.halls(sync).saveHall(hall);

		event = new Event();
		event.setName("Prunksitzung");
		friday = event.addPresentation("Freitag");
		friday.setHallId(hall.getId());
		friday.setSeatingPlanId(plan.getId());
		BookingSync.sync(friday, plan);
		seat(friday, 0).setLastName("Müller");
		mine = TestRepositories.events(sync);
		mine.saveEvent(event);
		mine.setHallLookup(id -> hall.getId().equals(id) ? hall : null);
		mine.addListener(recorder);
	}

	// Bauteile -->

	private Seat planSeat(int n) {
		return plan.getSeats().get(n);
	}

	/** Die Buchung auf dem n-ten Sitz der Bestuhlung. */
	private Booking seat(Presentation presentation, int n) {
		String seatId = planSeat(n).getId();
		return presentation.getBookings().stream().filter(b -> seatId.equals(b.getSeatId())).findFirst().orElseThrow();
	}

	private UnitKey seatUnit(Presentation presentation, int n) {
		return UnitKey.booking(event.getId(), presentation.getId(), planSeat(n).getId());
	}

	private UnitKey head() {
		return UnitKey.event(event.getId());
	}

	private List<UnitKey> allUnits() throws IOException {
		return sync.eventUnits(event.getId());
	}

	/** Ein zweites Repository auf demselben Dienst lädt das Event und bereitet es vor wie die App. */
	private Other other() {
		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().stream().filter(e -> e.getId().equals(event.getId())).findFirst()
				.orElseThrow();
		for (Presentation presentation : loaded.getPresentations()) {
			BookingSync.sync(presentation, plan);
		}
		return new Other(repository, loaded);
	}

	/** Die Anzahl aller Dateien (Versionen und Ansprüche) im lokalen Bestand. */
	private long files() throws IOException {
		return foreign.files();
	}

	/** Die Anzahl aller Versionen aller Einheiten des Events. */
	private long versions() throws IOException {
		long count = 0;
		for (UnitKey unit : allUnits()) {
			count += sync.history(unit).size();
		}
		return count;
	}

	/** Eine zweite Halle "Bürgerhaus" mit einer Reihe A aus {@code tables} Tischen zu je {@code seats} Sitzen, gespeichert. */
	private Hall secondHall(int tables, int seats) {
		Hall other = new Hall();
		other.setName("Bürgerhaus");
		SeatingPlan otherPlan = other.addSeatingPlan();
		TableRow row = otherPlan.addRow();
		row.setLabel("A");
		otherPlan.fillRow(row, tables, seats);
		TestRepositories.halls(sync).saveHall(other);
		return other;
	}

	/** Wie {@code halls}, nur die {@code failAt}-te Abfrage (ab 1 gezählt) wirft. */
	private static Function<String, Hall> failingAt(int failAt, Function<String, Hall> halls) {
		AtomicInteger calls = new AtomicInteger();
		return id -> {
			if (calls.incrementAndGet() == failAt) {
				throw new IllegalStateException("Halle nicht lesbar");
			}
			return halls.apply(id);
		};
	}

	/** Die Hallen des Tests nach ID. */
	private Function<String, Hall> lookup(Hall... halls) {
		return id -> {
			for (Hall candidate : halls) {
				if (candidate.getId().equals(id)) {
					return candidate;
				}
			}
			return hall.getId().equals(id) ? hall : null;
		};
	}

	/**
	 * Schreibt eine Version des Rechners "Fremd" auf das Ende der Kette und bringt sie in den Index, wie es das Abholen
	 * aus dem gemeinsamen Ordner tut.
	 */
	private Version foreignVersion(UnitKey unit, String lastName) throws IOException {
		return foreign.write(unit, "Fremd", payload -> payload.put("lastName", lastName));
	}

	// <-- Bauteile

	// Einarbeiten -->

	@Test
	void foreignBookingOnFreeSeatFillsThePlaceholder() throws IOException {
		Booking placeholder = seat(friday, 1);
		assertFalse(placeholder.hasData());
		Other other = other();
		Booking sold = seat(other.friday(), 1);
		sold.setLastName("Fremd");
		other.save();
		long before = files();

		ForeignResult result = mine.applyForeign(List.of(seatUnit(friday, 1)));

		assertSame(placeholder, seat(friday, 1));
		assertEquals("Fremd", placeholder.getLastName());
		assertEquals(sold.getId(), placeholder.getId());
		assertEquals(6, friday.getBookings().size());
		assertTrue(result.deferred().isEmpty());
		assertTrue(result.warnings().isEmpty());
		mine.saveEvent(event);
		assertEquals(before, files(), "der fremde Verkauf wird weder gelöscht noch neu geschrieben");
	}

	@Test
	void foreignChangeOfBookingUpdatesTheSameObject() {
		Booking sold = seat(friday, 0);
		Other other = other();
		seat(other.friday(), 0).setLastName("Fremd");
		other.save();

		mine.applyForeign(List.of(seatUnit(friday, 0)));

		assertSame(sold, seat(friday, 0));
		assertEquals("Fremd", sold.getLastName());
		assertEquals(List.of("changing Prunksitzung", "changed Prunksitzung"), recorder.calls);
		assertSame(event, recorder.entities.get(0));
		assertEquals(Set.of(seatUnit(friday, 0)), recorder.batches.get(0).units());
	}

	@Test
	void foreignDeletionEmptiesThePlaceholderWithNewId() throws IOException {
		Booking sold = seat(friday, 0);
		String oldId = sold.getId();
		Other other = other();
		seat(other.friday(), 0).clearCustomerData();
		other.save();
		long before = files();

		mine.applyForeign(List.of(seatUnit(friday, 0)));

		assertSame(sold, seat(friday, 0));
		assertFalse(sold.hasData());
		assertNotEquals(oldId, sold.getId());
		assertEquals(planSeat(0).getId(), sold.getSeatId());
		assertEquals("A", sold.getRowLabel());
		assertEquals(1, sold.getTableNumber());
		assertEquals(planSeat(0).getSeatNumber(), sold.getSeatNumber());
		assertEquals(6, friday.getBookings().size());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void foreignUnassignedBookingIsAddedAndRemoved() throws IOException {
		Other other = other();
		Booking unassigned = other.friday().addBooking();
		unassigned.setUnassigned(true);
		unassigned.setLastName("Ohne");
		other.save();
		UnitKey unit = UnitKey.unassignedBooking(event.getId(), friday.getId(), unassigned.getId());

		mine.applyForeign(List.of(unit));

		Booking added = friday.getBookings().stream().filter(b -> unassigned.getId().equals(b.getId())).findFirst()
				.orElseThrow();
		assertNotSame(unassigned, added);
		assertEquals("Ohne", added.getLastName());
		assertTrue(added.isUnassigned());
		assertEquals(7, friday.getBookings().size());

		other.friday().getBookings().remove(unassigned);
		other.save();
		long before = files();
		mine.applyForeign(List.of(unit));

		assertFalse(friday.getBookings().contains(added));
		assertEquals(6, friday.getBookings().size());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void foreignBookingOnUnknownSeatWaitsUntilItsSeatArrives() throws IOException {
		Hall other = new Hall();
		other.setName("Bürgerhaus");
		SeatingPlan otherPlan = other.addSeatingPlan();
		TableRow row = otherPlan.addRow();
		row.setLabel("A");
		otherPlan.fillRow(row, 1, 2);
		TestRepositories.halls(sync).saveHall(other);
		mine.setHallLookup(id -> hall.getId().equals(id) ? hall : other.getId().equals(id) ? other : null);
		Other remote = other();
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), otherPlan);
		String newSeat = otherPlan.getSeats().get(1).getId();
		Booking sold = remote.friday().getBookings().stream().filter(b -> newSeat.equals(b.getSeatId())).findFirst()
				.orElseThrow();
		sold.setLastName("Fremd");
		remote.save();
		UnitKey unit = UnitKey.booking(event.getId(), friday.getId(), newSeat);
		long before = files();

		// die Vorstellung mit der neuen Bestuhlung ist noch nicht gemeldet: Der Sitz fehlt in As Bestuhlung
		ForeignResult result = mine.applyForeign(List.of(unit));

		assertTrue(result.deferred().isEmpty(), "still, ohne Hinweis");
		assertNull(mine.knownUnit(unit), "der Vergleichsstand bleibt unberührt");
		assertEquals(6, friday.getBookings().size());
		assertTrue(friday.getBookings().stream().noneMatch(b -> "Fremd".equals(b.getLastName())));
		assertTrue(recorder.calls.isEmpty());
		assertTrue(mine.retryDeferred().deferred().isEmpty());
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");

		mine.applyForeign(allUnits());

		assertEquals(otherPlan.getId(), friday.getSeatingPlanId());
		Booking placeholder = friday.getBookings().stream().filter(b -> newSeat.equals(b.getSeatId())).findFirst()
				.orElseThrow();
		assertEquals("Fremd", placeholder.getLastName());
		assertEquals(sold.getId(), placeholder.getId());
		assertEquals(2, friday.getBookings().size());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void foreignHeadChangeUpdatesTheSameEvent() throws IOException {
		Other other = other();
		other.event().setDescription("Große Sitzung");
		other.save();
		long before = files();

		mine.applyForeign(List.of(head()));

		assertSame(event, mine.loadEvents().get(0));
		assertEquals("Große Sitzung", event.getDescription());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void foreignPresentationIsAddedWithItsEarlierBookings() throws IOException {
		Other other = other();
		Presentation saturday = other.event().addPresentation("Samstag");
		saturday.setHallId(hall.getId());
		saturday.setSeatingPlanId(plan.getId());
		BookingSync.sync(saturday, plan);
		seat(saturday, 2).setLastName("Schmitz");
		other.save();
		long before = files();

		// die Buchung kommt vor ihrer Vorstellung an: Sie bleibt unsichtbar
		mine.applyForeign(List.of(seatUnit(saturday, 2)));
		assertEquals(1, event.getPresentations().size());
		mine.applyForeign(List.of(UnitKey.presentation(event.getId(), saturday.getId())));

		assertEquals(List.of("Freitag", "Samstag"),
				event.getPresentations().stream().map(Presentation::getName).toList());
		Presentation added = event.getPresentations().get(1);
		assertSame(event, added.getParent());
		assertEquals("Schmitz", seat(added, 2).getLastName());
		assertEquals(6, added.getBookings().size(), "abgeglichen mit der Bestuhlung");
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void foreignEventIsAddedAndRemoved() throws IOException {
		StoreEventRepository others = TestRepositories.events(sync);
		others.loadEvents();
		Event children = new Event();
		children.setName("Kinderfasching");
		Presentation sunday = children.addPresentation("Sonntag");
		sunday.setHallId(hall.getId());
		sunday.setSeatingPlanId(plan.getId());
		BookingSync.sync(sunday, plan);
		seat(sunday, 0).setLastName("Klein");
		others.saveEvent(children);

		mine.applyForeign(sync.eventUnits(children.getId()));

		assertEquals(List.of("added Kinderfasching"), recorder.calls);
		assertEquals(List.of("Kinderfasching", "Prunksitzung"),
				mine.loadEvents().stream().map(Event::getName).toList(), "einsortiert wie beim Laden");
		Event added = mine.loadEvents().stream().filter(e -> e.getId().equals(children.getId())).findFirst()
				.orElseThrow();
		assertSame(added, recorder.entities.get(0));
		assertNotSame(children, added);
		assertEquals("Klein", seat(added.getPresentations().get(0), 0).getLastName());
		assertEquals(6, added.getPresentations().get(0).getBookings().size(), "abgeglichen mit der Bestuhlung");
		long before = files();
		mine.saveEvent(added);
		assertEquals(before, files());

		others.deleteEvent(children);
		mine.applyForeign(List.of(UnitKey.event(children.getId())));

		assertEquals(List.of("added Kinderfasching", "removed Kinderfasching"), recorder.calls);
		assertSame(added, recorder.entities.get(1));
		assertFalse(mine.loadEvents().contains(added));
		assertTrue(mine.loadEvents().contains(event));
	}

	@Test
	void foreignRevivedEventComesBackWithChildren() {
		Other other = other();
		other.repository().deleteEvent(other.event());
		mine.applyForeign(List.of(head()));
		assertTrue(mine.loadEvents().isEmpty());

		other.repository().saveEvent(other.event());
		mine.applyForeign(List.of(head()));

		assertEquals(List.of("removed Prunksitzung", "added Prunksitzung"), recorder.calls);
		Event revived = mine.loadEvents().get(0);
		assertNotSame(event, revived);
		assertEquals("Müller", seat(revived.getPresentations().get(0), 0).getLastName());
		assertEquals(6, revived.getPresentations().get(0).getBookings().size(), "abgeglichen mit der Bestuhlung");
	}

	@Test
	void foreignHallSwitchOfPresentationResyncsBookings() throws IOException {
		Hall other = new Hall();
		other.setName("Bürgerhaus");
		SeatingPlan otherPlan = other.addSeatingPlan();
		TableRow row = otherPlan.addRow();
		row.setLabel("A");
		otherPlan.fillRow(row, 1, 2);
		TestRepositories.halls(sync).saveHall(other);
		mine.setHallLookup(id -> hall.getId().equals(id) ? hall : other.getId().equals(id) ? other : null);
		Booking sold = seat(friday, 0);
		Other remote = other();
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), otherPlan);
		remote.save();
		long before = files();

		mine.applyForeign(allUnits());

		assertEquals(other.getId(), friday.getHallId());
		assertEquals(otherPlan.getId(), friday.getSeatingPlanId());
		assertEquals(2, friday.getBookings().size());
		assertSame(sold, friday.getBookings().get(0));
		assertEquals(otherPlan.getSeats().get(0).getId(), sold.getSeatId());
		assertEquals("Müller", sold.getLastName());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	/** Der andere Rechner setzt die Vorstellung in die Halle {@code other} (Bestuhlung umgestellt wie in der App) und speichert. */
	private Other remoteSwitchesTo(Hall other) {
		Other remote = other();
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), other.getSeatingPlans().get(0));
		remote.save();
		return remote;
	}

	@Test
	void hallSwitchWhoseBookingsArriveLaterIsNoUnsavedChange() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(lookup(other));
		Booking sold = seat(friday, 0);
		remoteSwitchesTo(other);
		long before = files();

		// erst nur die Vorstellung: Der Abgleich setzt Müller auf den neuen Sitz, wie es der andere Rechner tat
		ForeignResult first = mine.applyForeign(List.of(UnitKey.presentation(event.getId(), friday.getId())));

		assertTrue(first.deferred().isEmpty());
		assertEquals(other.getSeatingPlans().get(0).getSeats().get(0).getId(), sold.getSeatId());
		assertEquals(Set.of(), mine.locallyChangedUnits(), "was der Abgleich umsetzte, hat der Nutzer nicht geändert");

		// dann die Buchungen: alter Sitz gelöscht, neuer Sitz angelegt, beides steht schon so im Modell
		ForeignResult second = mine.applyForeign(allUnits());

		assertTrue(second.deferred().isEmpty(), second.deferred().toString());
		assertTrue(mine.retryDeferred().deferred().isEmpty());
		assertEquals(Set.of(), mine.locallyChangedUnits());
		assertSame(sold, friday.getBookings().get(0));
		assertEquals("Müller", sold.getLastName());
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird als eigene Version geschrieben");
	}

	@Test
	void hallSwitchWithALaterForeignChangeOfTheMovedBookingShowsTheChange() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(lookup(other));
		Booking sold = seat(friday, 0);
		Other remote = remoteSwitchesTo(other);
		// zwischen den beiden Meldungen ändert der andere Rechner die umgesetzte Buchung
		remote.friday().getBookings().get(0).setLastName("Meier");
		remote.save();
		long before = files();

		mine.applyForeign(List.of(UnitKey.presentation(event.getId(), friday.getId())));
		assertEquals("Müller", sold.getLastName());
		ForeignResult second = mine.applyForeign(allUnits());

		assertTrue(second.deferred().isEmpty(), second.deferred().toString());
		assertSame(sold, friday.getBookings().get(0));
		assertEquals("Meier", sold.getLastName(), "die spätere fremde Änderung kommt an");
		assertEquals(Set.of(), mine.locallyChangedUnits());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void movedBookingChangedByTheUserAfterTheHallSwitchIsDeferred() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(lookup(other));
		Booking sold = seat(friday, 0);
		Other remote = remoteSwitchesTo(other);
		remote.friday().getBookings().get(0).setLastName("Meier");
		remote.save();
		mine.applyForeign(List.of(UnitKey.presentation(event.getId(), friday.getId())));

		// der Nutzer ändert die umgesetzte Buchung, bevor ihre Einheit eintrifft: Das ist seine Änderung
		sold.setLastName("Eigen");
		ForeignResult second = mine.applyForeign(allUnits());

		assertEquals(2, second.deferred().size(), "die Buchung und die Löschung ihres alten Sitzes: " + second.deferred());
		assertEquals("Eigen", sold.getLastName());
	}

	@Test
	void cancellationIsDeferredWhileTheMovedBookingIsChangedByTheUser() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(lookup(other));
		UnitKey presentation = remoteCancelsThenSwitches(other);
		UnitKey oldSeat = seatUnit(friday, 0);
		mine.applyForeign(List.of(presentation));
		Booking moved = friday.getBookings().stream().filter(Booking::hasData).findFirst().orElseThrow();
		moved.setFirstName("Hans");

		ForeignResult result = mine.applyForeign(List.of(oldSeat));

		assertEquals(List.of(oldSeat), result.deferred().stream().map(Deferral::unit).toList(),
				"die Stornierung wird nicht stillschweigend übernommen");
		assertEquals("Hans", moved.getFirstName());
		assertEquals("Müller", moved.getLastName());
		assertNotNull(mine.knownUnit(oldSeat));
		assertFalse(mine.knownUnit(oldSeat).deleted(), "der Vergleichsstand kennt die Löschung noch nicht");
	}

	@Test
	void cancellationIsAppliedAfterTheEditingOfTheMovedBookingEnds() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(lookup(other));
		UnitKey presentation = remoteCancelsThenSwitches(other);
		UnitKey oldSeat = seatUnit(friday, 0);
		long before = files();
		mine.applyForeign(List.of(presentation));
		Booking moved = friday.getBookings().stream().filter(Booking::hasData).findFirst().orElseThrow();
		AtomicBoolean editing = new AtomicBoolean(true);
		mine.setEditingCheck(o -> editing.get() && o == moved);

		ForeignResult result = mine.applyForeign(List.of(oldSeat));
		assertEquals(List.of(oldSeat), result.deferred().stream().map(Deferral::unit).toList());
		assertEquals("Müller", moved.getLastName());

		editing.set(false);
		ForeignResult retried = mine.retryDeferred();

		assertTrue(retried.deferred().isEmpty());
		assertCancelledMullerIsGoneAndNothingIsWritten(before);
	}

	@Test
	void manyCancellationsOfASwitchedPresentationAreAllApplied() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(lookup(other));
		for (int n = 0; n < 6; n++) {
			seat(friday, n).setLastName("Gast" + n);
		}
		mine.saveEvent(event);
		Other remote = other();
		for (int n = 0; n < 6; n++) {
			seat(remote.friday(), n).clearCustomerData();
		}
		remote.save();
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), other.getSeatingPlans().get(0));
		remote.save();
		long before = files();

		mine.applyForeign(List.of(UnitKey.presentation(event.getId(), friday.getId())));
		mine.applyForeign(allUnits());

		assertCancelledMullerIsGoneAndNothingIsWritten(before);
	}

	@Test
	void bookingClearedOnBothSidesIsNoUnsavedChange() throws IOException {
		seat(friday, 0).clearCustomerData();
		Other other = other();
		seat(other.friday(), 0).clearCustomerData();
		other.save();
		long before = files();

		ForeignResult result = mine.applyForeign(List.of(seatUnit(friday, 0)));

		assertTrue(result.deferred().isEmpty(), result.deferred().toString());
		assertEquals(Set.of(), mine.locallyChangedUnits());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void presentationDeletedOnBothSidesIsNoUnsavedChange() throws IOException {
		event.deletePresentation(friday);
		Other other = other();
		other.event().deletePresentation(other.friday());
		other.save();
		long before = files();

		ForeignResult result = mine.applyForeign(allUnits());

		assertTrue(result.deferred().isEmpty(), result.deferred().toString());
		assertEquals(Set.of(), mine.locallyChangedUnits(), "auch die Buchungen darunter sind mit ihr weg");
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void seatChangeSplitOverTwoBatchesSavesNothingForeign() throws IOException {
		Other other = other();
		Booking moved = seat(other.friday(), 0);
		Booking target = seat(other.friday(), 1);
		target.copyFrom(moved);
		target.takeSnapshot(planSeat(1).getParent(), planSeat(1));
		moved.clearCustomerData();
		other.save();
		long before = files();

		mine.applyForeign(List.of(seatUnit(friday, 1)));
		assertEquals("Müller", seat(friday, 0).getLastName(), "die ID steht kurz zweimal im Modell");
		assertEquals("Müller", seat(friday, 1).getLastName());
		mine.saveEvent(event);
		assertEquals(before, files());

		mine.applyForeign(List.of(seatUnit(friday, 0)));
		assertFalse(seat(friday, 0).hasData());
		assertEquals(target.getId(), seat(friday, 1).getId());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void saveAfterForeignMergeWritesOnlyOwnChanges() throws IOException {
		Other other = other();
		seat(other.friday(), 1).setLastName("Fremd");
		other.save();
		mine.applyForeign(List.of(seatUnit(friday, 1)));

		seat(friday, 2).setLastName("Eigen");
		mine.saveEvent(event);

		for (UnitKey unit : allUnits()) {
			assertEquals(1, sync.history(unit).size(), unit.relativePath());
		}
		Event again = TestRepositories.events(sync).loadEvents().get(0);
		assertEquals(List.of("Müller", "Fremd", "Eigen"), again.getPresentations().get(0).getBookings().stream()
				.map(Booking::getLastName).toList());
	}

	@Test
	void failureWhileMergingRebuildsACleanEventFromTheIndex() throws IOException {
		Hall other = secondHall(1, 2);
		SeatingPlan otherPlan = other.getSeatingPlans().get(0);
		// 1. Abfrage: Abgleich der Vorstellung (ihre Buchungen fragen erst danach, beim Einarbeiten)
		mine.setHallLookup(failingAt(1, lookup(other)));
		Other remote = other();
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), otherPlan);
		remote.save();
		long before = files();

		ForeignResult result = mine.applyForeign(allUnits());

		assertEquals(1, result.warnings().size(), result.warnings().toString());
		assertTrue(result.deferred().isEmpty());
		Event rebuilt = mine.loadEvents().get(0);
		assertNotSame(event, rebuilt);
		assertEquals(other.getId(), rebuilt.getPresentations().get(0).getHallId());
		assertEquals(List.of("changing Prunksitzung", "changed Prunksitzung"), recorder.calls);
		assertSame(rebuilt, recorder.entities.get(0));
		mine.saveEvent(rebuilt);
		assertEquals(before, files());
	}

	@Test
	void staleEventObjectIsRefusedAfterARebuild() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(failingAt(1, lookup(other)));
		Other remote = other();
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), other.getSeatingPlans().get(0));
		remote.save();
		mine.applyForeign(allUnits());
		Event rebuilt = mine.loadEvents().get(0);
		assertNotSame(event, rebuilt);
		AtomicInteger saved = new AtomicInteger();
		mine.addListener(new RepositoryListener<>() {
			@Override
			public void saved(Event entity) {
				saved.incrementAndGet();
			}
		});
		long before = files();
		event.setName("Alt geändert");

		LockedUnitException refused = assertThrows(LockedUnitException.class, () -> mine.saveEvent(event));

		assertEquals("„Alt geändert“ wurde inzwischen auf einem anderen Rechner geändert. Bitte die Ansicht neu öffnen.",
				refused.getMessage());
		assertThrows(LockedUnitException.class, () -> mine.saveEvents(List.of(event)));
		assertEquals(before, files(), "nichts geschrieben");
		assertEquals(0, saved.get(), "nichts gemeldet");
		assertEquals(List.of(rebuilt), mine.loadEvents());

		rebuilt.setName("Neu");
		mine.saveEvent(rebuilt);
		assertEquals(1, saved.get());
	}

	@Test
	void eventDeletedElsewhereIsRefusedFromAStaleView() throws IOException {
		Other other = other();
		other.repository().deleteEvent(other.event());
		mine.applyForeign(List.of(head()));
		assertFalse(mine.loadEvents().contains(event));
		long before = files();
		// eine Ansicht hält das Event noch
		event.setName("Alt geändert");

		LockedUnitException refused = assertThrows(LockedUnitException.class, () -> mine.saveEvent(event));

		assertEquals("„Alt geändert“ wurde inzwischen auf einem anderen Rechner geändert. Bitte die Ansicht neu öffnen.",
				refused.getMessage());
		assertEquals(before, files(), "die fremde Löschung wird nicht überschrieben");
		assertTrue(sync.unitState(head()).head().deleted());
		assertTrue(mine.loadEvents().isEmpty());
	}

	@Test
	void deletingStaleEventObjectIsRejected() throws IOException {
		Other other = other();
		other.repository().deleteEvent(other.event());
		mine.applyForeign(List.of(head()));
		long before = files();
		AtomicInteger deleted = new AtomicInteger();
		mine.addListener(new RepositoryListener<>() {
			@Override
			public void deleted(Event entity) {
				deleted.incrementAndGet();
			}
		});

		LockedUnitException removed = assertThrows(LockedUnitException.class, () -> mine.deleteEvent(event));

		assertEquals("„Prunksitzung“ wurde inzwischen auf einem anderen Rechner geändert. Bitte die Ansicht neu öffnen.",
				removed.getMessage());
		assertEquals(before, files(), "nichts geschrieben");
		// der andere Rechner holt das Event zurück: Die Ansicht hält das Objekt von vorher
		other.repository().saveEvent(other.event());
		mine.applyForeign(List.of(head()));
		Event revived = mine.loadEvents().get(0);
		assertNotSame(event, revived);
		long afterRevive = files();

		assertThrows(LockedUnitException.class, () -> mine.deleteEvent(event));
		assertThrows(LockedUnitException.class, () -> mine.deleteEvents(List.of(event)));

		assertEquals(afterRevive, files(), "nichts geschrieben");
		assertEquals(0, deleted.get(), "nichts gemeldet");
		assertEquals(List.of(revived), mine.loadEvents());
		assertFalse(sync.unitState(head()).head().deleted());

		mine.deleteEvent(revived);
		assertTrue(mine.loadEvents().isEmpty());
		assertEquals(1, deleted.get());
	}

	@Test
	void eventDeletedLocallyCanBeSavedAgain() throws IOException {
		mine.deleteEvent(event);

		mine.saveEvent(event);

		assertFalse(sync.unitState(head()).head().deleted(), "der Nutzer holt sein Event zurück");
		assertTrue(mine.loadEvents().contains(event));
	}

	// <-- Einarbeiten

	// Regeln -->

	@Test
	void ownUploadIsKnownAndChangesNothing() throws IOException {
		seat(friday, 1).setLastName("Eigen");
		mine.saveEvent(event);

		ForeignResult result = mine.applyForeign(allUnits());

		assertTrue(recorder.calls.isEmpty());
		assertTrue(result.deferred().isEmpty());
		assertTrue(result.warnings().isEmpty());
		assertEquals("Eigen", seat(friday, 1).getLastName());
	}

	@Test
	void secondReportOfAppliedUnitIsKnown() {
		Other other = other();
		seat(other.friday(), 0).setLastName("Fremd");
		other.save();
		mine.applyForeign(List.of(seatUnit(friday, 0)));

		mine.applyForeign(List.of(seatUnit(friday, 0)));

		assertEquals(List.of("changing Prunksitzung", "changed Prunksitzung"), recorder.calls);
	}

	@Test
	void lostOwnVersionIsNotApplied(@TempDir Path dir) throws IOException {
		TestClock clock = new TestClock();
		Path shared = Files.createDirectories(dir.resolve("shared"));
		SyncService a = TestSyncServices.sharedMode(dir.resolve("a"), shared, "a", clock);
		SyncService b = TestSyncServices.sharedMode(dir.resolve("b"), shared, "b", clock);
		StoreEventRepository repositoryA = TestRepositories.events(a);
		Event eventA = new Event();
		eventA.setName("Prunksitzung");
		eventA.addPresentation("Freitag");
		repositoryA.saveEvent(eventA);
		TestSyncServices.tick(a);
		clock.advance(Duration.ofSeconds(30));
		TestSyncServices.tick(b);
		StoreEventRepository repositoryB = TestRepositories.events(b);
		Event eventB = repositoryB.loadEvents().get(0);
		Booking bert = eventB.getPresentations().get(0).addBooking();
		bert.setSeatId("sitz-1");
		bert.setLastName("Bert");
		repositoryB.saveEvent(eventB);
		TestSyncServices.tick(b);
		Booking anna = eventA.getPresentations().get(0).addBooking();
		anna.setSeatId("sitz-1");
		anna.setLastName("Anna");
		repositoryA.saveEvent(eventA);
		clock.advance(Duration.ofSeconds(30));
		// A lädt hoch, unterliegt und holt die Version von B ab
		TestSyncServices.tick(a);
		UnitKey seat = UnitKey.booking(eventA.getId(), eventA.getPresentations().get(0).getId(), "sitz-1");
		assertEquals("Bert", a.snapshots(List.of(seat)).get(seat).payload().get("lastName").asText());
		UnitSnapshot before = repositoryA.knownUnit(seat);

		ForeignResult result = repositoryA.applyForeign(List.of(seat));

		assertEquals("Anna", anna.getLastName());
		assertSame(anna, eventA.getPresentations().get(0).getBookings().get(0));
		assertEquals(1, eventA.getPresentations().get(0).getBookings().size());
		assertSame(before, repositoryA.knownUnit(seat));
		assertTrue(result.deferred().isEmpty());
	}

	@Test
	void locallyChangedBookingIsDeferredAndBaselineKept() throws IOException {
		Booking sold = seat(friday, 0);
		sold.setLastName("Mueller");
		UnitKey unit = seatUnit(friday, 0);
		VersionId before = mine.knownUnit(unit).version();
		Version foreign = foreignVersion(unit, "Fremd");

		ForeignResult result = mine.applyForeign(List.of(unit));

		assertEquals(List.of(new Deferral(unit, foreign.id(), "Fremd", sold)), result.deferred());
		assertEquals("Mueller", sold.getLastName());
		assertEquals(before, mine.knownUnit(unit).version());
		assertTrue(recorder.calls.isEmpty());
		assertEquals(result.deferred(), mine.retryDeferred().deferred(), "bleibt zurückgestellt");
	}

	@Test
	void unsavedSaleOnSameSeatIsDeferred() {
		Booking mySale = seat(friday, 1);
		mySale.setLastName("Eigen");
		Other other = other();
		seat(other.friday(), 1).setLastName("Fremd");
		other.save();

		ForeignResult result = mine.applyForeign(List.of(seatUnit(friday, 1)));

		assertEquals(1, result.deferred().size());
		assertSame(mySale, result.deferred().get(0).model());
		assertEquals("Eigen", mySale.getLastName());
		assertTrue(recorder.calls.isEmpty());
	}

	@Test
	void headDeletionIsDeferredWhileChildIsChanged() {
		seat(friday, 0).setLastName("Mueller");
		Other other = other();
		other.repository().deleteEvent(other.event());

		ForeignResult result = mine.applyForeign(List.of(head()));

		assertEquals(List.of(head()), result.deferred().stream().map(Deferral::unit).toList());
		assertSame(event, result.deferred().get(0).model());
		assertTrue(mine.loadEvents().contains(event));
		assertTrue(recorder.calls.isEmpty());
	}

	@Test
	void headDeletionIsDeferredWhileABookingIsInEditing() {
		Booking placeholder = seat(friday, 1);
		mine.setEditingCheck(o -> o == placeholder);
		Other other = other();
		other.repository().deleteEvent(other.event());

		ForeignResult result = mine.applyForeign(List.of(head()));

		assertEquals(List.of(head()), result.deferred().stream().map(Deferral::unit).toList());
		assertTrue(mine.loadEvents().contains(event));
	}

	@Test
	void archivingIsDeferredWhileABookingBelowIsChanged() {
		seat(friday, 1).setLastName("Eigen");
		Other other = other();
		other.event().setArchived(true);
		other.save();

		ForeignResult result = mine.applyForeign(List.of(head()));

		assertEquals(List.of(head()), result.deferred().stream().map(Deferral::unit).toList());
		assertFalse(event.isArchived(), "die Verkaufsansichten zeigen das Event weiter, der Verkauf bleibt speicherbar");
		assertTrue(recorder.calls.isEmpty());

		mine.saveEvent(event);
		mine.retryDeferred();

		assertTrue(event.isArchived(), "nach dem Speichern greift das Archivieren");
	}

	@Test
	void archivingIsDeferredWhileABookingIsInEditing() {
		Booking placeholder = seat(friday, 1);
		mine.setEditingCheck(o -> o == placeholder);
		Other other = other();
		other.event().setArchived(true);
		other.save();

		ForeignResult result = mine.applyForeign(List.of(head()));

		assertEquals(List.of(head()), result.deferred().stream().map(Deferral::unit).toList());
		assertFalse(event.isArchived());
	}

	@Test
	void archivingACleanEventIsApplied() {
		Other other = other();
		other.event().setArchived(true);
		other.save();

		ForeignResult result = mine.applyForeign(List.of(head()));

		assertTrue(result.deferred().isEmpty());
		assertTrue(event.isArchived());
	}

	@Test
	void hallSwitchIsDeferredWithItsBookingsWhileABookingBelowIsChanged() throws IOException {
		Booking mySale = seat(friday, 2);
		mySale.setLastName("Eigen");
		Booking sold = seat(friday, 0);
		Other other = other();
		// ohne Bestuhlung wird Müller "ohne Platz": seine Sitz-Einheit wird gelöscht, eine Einheit _<id> entsteht
		other.friday().setSeatingPlanId(null);
		BookingSync.sync(other.friday(), null);
		other.save();
		UnitKey presentation = UnitKey.presentation(event.getId(), friday.getId());

		ForeignResult result = mine.applyForeign(allUnits());

		assertEquals(List.of(presentation), result.deferred().stream().map(Deferral::unit).toList());
		assertEquals(plan.getId(), friday.getSeatingPlanId());
		assertSame(sold, seat(friday, 0));
		assertEquals("Müller", sold.getLastName());
		assertEquals(6, friday.getBookings().size());
		assertSame(mySale, seat(friday, 2));
		assertEquals("Eigen", mySale.getLastName());
		assertEquals(Set.of(seatUnit(friday, 2)), mine.locallyChangedUnits());

		long versionsBefore = versions();
		mine.saveEvent(event);

		assertEquals(versionsBefore + 1, versions(), "nur der eigene Verkauf wird geschrieben");
		assertEquals(1, sync.history(seatUnit(friday, 2)).size());
	}

	@Test
	void unassignedForeignBookingWaitsWhileItsOldSeatIsInMyPlan() throws IOException {
		Booking sold = seat(friday, 0);
		Other other = other();
		// ohne Bestuhlung wird Müller "ohne Platz" unter _<id> und behält die Kennung seines alten Sitzes
		other.friday().setSeatingPlanId(null);
		BookingSync.sync(other.friday(), null);
		other.save();
		Booking remote = other.friday().getBookings().get(0);
		UnitKey unassigned = UnitKey.unassignedBooking(event.getId(), friday.getId(), remote.getId());
		long before = files();

		// die Vorstellung ohne Bestuhlung ist noch nicht gemeldet: Der Abgleich setzte Müller auf seinen alten Sitz
		ForeignResult result = mine.applyForeign(List.of(seatUnit(friday, 0), unassigned));

		assertTrue(result.deferred().isEmpty(), "still, ohne Hinweis");
		assertSame(sold, seat(friday, 0));
		assertEquals("Müller", sold.getLastName(), "auch die Löschung der Sitz-Einheit wartet mit der Vorstellung");
		assertNull(mine.knownUnit(unassigned));
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");

		mine.applyForeign(List.of(UnitKey.presentation(event.getId(), friday.getId())));

		assertNull(friday.getSeatingPlanId());
		assertEquals(List.of("Müller"), friday.getBookings().stream().map(Booking::getLastName).toList());
		assertTrue(friday.getBookings().get(0).isUnassigned());
		assertEquals(remote.getId(), friday.getBookings().get(0).getId());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void bookingsWaitWhileTheirPresentationIsAForkThatSwitchesThePlan() throws IOException {
		Booking sold = seat(friday, 0);
		Other other = other();
		other.friday().setSeatingPlanId(null);
		BookingSync.sync(other.friday(), null);
		other.save();
		// A ändert die Vorstellung selbst und speichert, ohne Bs Stand zu kennen: seine Version unterliegt
		friday.setName("Freitagabend");
		mine.saveEvent(event);
		UnitKey presentation = UnitKey.presentation(event.getId(), friday.getId());
		assertEquals(1, sync.unitState(presentation).lost().size());
		long before = files();

		ForeignResult result = mine.applyForeign(allUnits());

		assertTrue(result.deferred().isEmpty(), "eine Gabelung bekommt keinen Hinweis, ihre Buchungen warten still");
		assertSame(sold, seat(friday, 0));
		assertEquals("Müller", sold.getLastName());
		assertEquals(plan.getId(), friday.getSeatingPlanId());
		assertEquals(6, friday.getBookings().size());
		mine.retryDeferred();
		assertEquals("Müller", sold.getLastName(), "sie warten, solange die Gabelung besteht");
		assertTrue(friday.getBookings().stream().noneMatch(Booking::isUnassigned));
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");

		// passt der Stand wieder (hier: neu geladen, bis KARV-86 Gabelungen auflöst), ist die Buchung da
		Event reloaded = mine.reloadEvents().get(0);
		mine.applyForeign(allUnits());
		Presentation reloadedFriday = reloaded.getPresentations().get(0);
		assertEquals(List.of("Müller"), reloadedFriday.getBookings().stream().map(Booking::getLastName).toList());
		assertTrue(reloadedFriday.getBookings().get(0).isUnassigned());
		mine.saveEvent(reloaded);
		assertEquals(before, files());
	}

	@Test
	void bookingsUnderForkedPresentationWaitWithoutForkedParentsSet() throws IOException {
		Booking sold = seat(friday, 0);
		Other other = other();
		other.friday().setSeatingPlanId(null);
		BookingSync.sync(other.friday(), null);
		other.save();
		friday.setName("Freitagabend");
		mine.saveEvent(event);
		UnitKey presentation = UnitKey.presentation(event.getId(), friday.getId());
		assertEquals(1, sync.unitState(presentation).lost().size());
		UnitKey unassigned = UnitKey.unassignedBooking(event.getId(), friday.getId(),
				other.friday().getBookings().get(0).getId());
		long before = files();

		// Die Gabelung der Vorstellung wird nie gemeldet; ihr Index-Stand (ohne Bestuhlung) genügt, damit die Buchungen warten
		ForeignResult result = mine.applyForeign(List.of(seatUnit(friday, 0), unassigned));

		assertTrue(result.deferred().isEmpty(), "still, ohne Hinweis");
		assertSame(sold, seat(friday, 0));
		assertEquals("Müller", sold.getLastName(), "auch die Löschung des Sitzes wartet");
		assertEquals(6, friday.getBookings().size());
		assertTrue(friday.getBookings().stream().noneMatch(Booking::isUnassigned));
		assertNull(mine.knownUnit(unassigned));
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");
	}

	@Test
	void bookingChangedInTheSameBatchAsItsDeletedPresentationIsOnlyAdopted() throws IOException {
		Other remote = other();
		remote.event().getPresentations().remove(remote.friday());
		remote.save();
		UnitKey unit = seatUnit(friday, 0);
		Version written = foreignVersion(unit, "Fremd");

		// Vorstellung und Buchung kommen in einem Schub: Mit der Vorstellung verlässt auch die Buchung das Modell, nichts wartet
		mine.applyForeign(allUnits());

		assertTrue(event.getPresentations().isEmpty());
		assertNotNull(mine.knownUnit(unit), "die Buchung geht in den Vergleichsstand, statt zu warten");
		assertEquals(written.id(), mine.knownUnit(unit).version());
		assertTrue(mine.retryDeferred().deferred().isEmpty());
		assertEquals(written.id(), mine.knownUnit(unit).version());
	}

	/** B storniert Müller (Sitz 0) und stellt danach die Vorstellung auf die zweite Halle um. */
	private UnitKey remoteCancelsThenSwitches(Hall other) {
		Other remote = other();
		seat(remote.friday(), 0).clearCustomerData();
		remote.save();
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), other.getSeatingPlans().get(0));
		remote.save();
		return UnitKey.presentation(event.getId(), friday.getId());
	}

	private void assertCancelledMullerIsGoneAndNothingIsWritten(long before) throws IOException {
		assertTrue(friday.getBookings().stream().noneMatch(Booking::hasData),
				"die Stornierung gilt: " + friday.getBookings().stream().filter(Booking::hasData)
						.map(b -> b.getLastName() + "@" + b.getSeatId()).toList());
		mine.saveEvent(event);
		assertEquals(before, files(), "die Stornierung wird nicht durch einen neuen Verkauf überschrieben");
	}

	@Test
	void cancellationReportedBeforeThePlanSwitchOfItsPresentationStaysCancelled() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(lookup(other));
		UnitKey presentation = remoteCancelsThenSwitches(other);
		long before = files();

		mine.applyForeign(List.of(seatUnit(friday, 0)));
		assertEquals("Müller", seat(friday, 0).getLastName(), "die Löschung wartet mit der Vorstellung");
		mine.applyForeign(List.of(presentation));

		assertCancelledMullerIsGoneAndNothingIsWritten(before);
	}

	@Test
	void cancellationInTheSameBatchAsThePlanSwitchOfItsPresentationStaysCancelled() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(lookup(other));
		remoteCancelsThenSwitches(other);
		long before = files();

		mine.applyForeign(allUnits());

		assertCancelledMullerIsGoneAndNothingIsWritten(before);
	}

	@Test
	void cancellationReportedAfterThePlanSwitchOfItsPresentationStaysCancelled() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(lookup(other));
		UnitKey presentation = remoteCancelsThenSwitches(other);
		long before = files();

		// erst die Vorstellung: Der Abgleich setzt Müller auf einen Sitz der neuen Bestuhlung; dann die Löschung seines alten
		mine.applyForeign(List.of(presentation));
		mine.applyForeign(List.of(seatUnit(friday, 0)));

		assertCancelledMullerIsGoneAndNothingIsWritten(before);
	}

	@Test
	void cancellationReportedAfterTheMovedBookingArrivedDoesNotClearTheMovedBooking() throws IOException {
		Hall other = secondHall(1, 2);
		mine.setHallLookup(lookup(other));
		Booking sold = seat(friday, 0);
		remoteSwitchesTo(other);
		long before = files();
		UnitKey oldSeat = seatUnit(friday, 0);
		UnitKey newSeat = UnitKey.booking(event.getId(), friday.getId(), other.getSeatingPlans().get(0).getSeats().get(0).getId());

		// Müllers neue Einheit kommt vor der Löschung der alten: Dann gehört die Buchung dem neuen Sitz
		mine.applyForeign(List.of(UnitKey.presentation(event.getId(), friday.getId())));
		mine.applyForeign(List.of(newSeat));
		mine.applyForeign(List.of(oldSeat));

		assertSame(sold, friday.getBookings().get(0));
		assertEquals("Müller", sold.getLastName());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void moveAgainstForeignCancellationIsDeferredNotConverged() throws IOException {
		Booking sold = seat(friday, 0);
		UnitKey oldSeat = seatUnit(friday, 0);
		// B verschiebt Müller auf Sitz 1 (ungespeichert): Sitz 0 fehlt im Modell, Sitz 1 trägt dieselbe Buchung
		assertTrue(BookingSync.assign(friday, plan, sold, planSeat(1)));
		// A storniert Müller auf Sitz 0
		Other remote = other();
		seat(remote.friday(), 0).clearCustomerData();
		remote.save();
		long before = files();

		ForeignResult result = mine.applyForeign(List.of(oldSeat));

		assertEquals(List.of(oldSeat), result.deferred().stream().map(Deferral::unit).toList(),
				"die Stornierung wird nicht still übernommen");
		assertEquals("Müller", sold.getLastName());
		assertEquals(planSeat(1).getId(), sold.getSeatId());
		assertFalse(mine.knownUnit(oldSeat).deleted(), "der Vergleichsstand kennt die Löschung noch nicht");
		assertEquals(before, files());
	}

	@Test
	void cancellationOfABookingEditedOnItsSeatThenDerivedAwayIsDeferred() throws IOException {
		Booking sold = seat(friday, 0);
		UnitKey oldSeat = seatUnit(friday, 0);
		// der Nutzer ändert Müller auf Sitz 0 (ungespeichert) ...
		sold.setFirstName("Hans");
		// ... dann setzt ein Abgleich die Buchung vom Sitz (etwa weil ein fremder Hallenstand den Sitz streicht): ohne Platz
		mine.deriving(event, () -> sold.setUnassigned(true));
		assertEquals(Set.of(oldSeat), mine.locallyChangedUnits(), "die eigene Änderung steht noch auf dem alten Sitz");
		// A storniert Müller auf Sitz 0
		Other remote = other();
		seat(remote.friday(), 0).clearCustomerData();
		remote.save();
		long before = files();

		ForeignResult result = mine.applyForeign(List.of(oldSeat));

		assertEquals(List.of(oldSeat), result.deferred().stream().map(Deferral::unit).toList(),
				"die Stornierung trifft die Eingabe nicht still");
		assertEquals("Hans", sold.getFirstName());
		assertEquals("Müller", sold.getLastName());
		assertTrue(friday.getBookings().contains(sold));
		assertEquals(before, files());
	}

	@Test
	void deletionOfTheOldSeatOfAMoveWhoseNewSeatIsAlreadyKnownIsAdoptedSilently() throws IOException {
		Booking sold = seat(friday, 0);
		UnitKey oldSeat = seatUnit(friday, 0);
		UnitKey newSeat = seatUnit(friday, 1);
		// A verschiebt Müller auf Sitz 1 und speichert
		Other remote = other();
		assertTrue(BookingSync.assign(remote.friday(), plan, seat(remote.friday(), 0), planSeat(1)));
		remote.save();
		// B bekommt zuerst Sitz 1: Müller steht kurz doppelt im Modell (Sitz 0 und Sitz 1)
		mine.applyForeign(List.of(newSeat));
		assertEquals(2, friday.getBookings().stream().filter(b -> sold.getId().equals(b.getId())).count(),
				"das kurze Fenster, in dem die Buchung doppelt im Modell steht");
		// der Nutzer räumt die Dublette auf Sitz 0 ab
		sold.clearCustomerData();
		assertEquals(Set.of(oldSeat), mine.locallyChangedUnits());
		long before = files();

		ForeignResult result = mine.applyForeign(List.of(oldSeat));

		assertTrue(result.deferred().isEmpty(), "das Modell hat genau den fremden Stand: " + result.deferred());
		assertTrue(mine.retryDeferred().deferred().isEmpty());
		assertEquals(Set.of(), mine.locallyChangedUnits());
		assertNotNull(mine.knownUnit(oldSeat));
		assertTrue(mine.knownUnit(oldSeat).deleted());
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird als eigene Version geschrieben");
	}

	@Test
	void cancellationOfABookingNotMovedInTheModelIsStillAdoptedSilently() throws IOException {
		UnitKey oldSeat = seatUnit(friday, 0);
		// B hat Müller auf Sitz 0 gelöscht (ungespeichert), A storniert ihn ebenfalls
		seat(friday, 0).clearCustomerData();
		Other remote = other();
		seat(remote.friday(), 0).clearCustomerData();
		remote.save();

		ForeignResult result = mine.applyForeign(List.of(oldSeat));

		assertTrue(result.deferred().isEmpty(), result.deferred().toString());
		assertEquals(Set.of(), mine.locallyChangedUnits());
	}

	@Disabled("H2 hingenommen (kein Verlust), siehe Spec: nach U1/U2 bleibt die Buchungs-ID im kurzen Fenster doppelt im Modell;"
			+ " sie ohne neue Sonderregel auszuschließen, gelingt nicht")
	@Test
	void derivedBookingMovedAwayBeforeForeignStateArrivesIsNotShownTwice() throws IOException {
		Hall other = secondHall(1, 2);
		SeatingPlan otherPlan = other.getSeatingPlans().get(0);
		mine.setHallLookup(lookup(other));
		Booking sold = seat(friday, 0);
		remoteSwitchesTo(other);
		// erst nur die Vorstellung: Der Abgleich setzt Müller auf den ersten Sitz der neuen Bestuhlung ("abgeleitet")
		mine.applyForeign(List.of(UnitKey.presentation(event.getId(), friday.getId())));
		assertEquals(otherPlan.getSeats().get(0).getId(), sold.getSeatId());
		// der Nutzer verschiebt die abgeleitete Buchung auf den zweiten Sitz, bevor der fremde Buchungsstand kommt
		assertTrue(BookingSync.assign(friday, otherPlan, sold, otherPlan.getSeats().get(1)));

		mine.applyForeign(allUnits());

		long shown = friday.getBookings().stream().filter(b -> sold.getId().equals(b.getId())).count();
		assertTrue(shown <= 1, "die Buchungs-ID steht höchstens einmal im Modell, war " + shown);
	}

	@Test
	void newPresentationLeavesOutBookingsOnSeatsOfAHallNotYetKnown() throws IOException {
		Hall other = secondHall(1, 2);
		SeatingPlan otherPlan = other.getSeatingPlans().get(0);
		AtomicBoolean arrived = new AtomicBoolean(false);
		Function<String, Hall> halls = lookup(other);
		mine.setHallLookup(id -> other.getId().equals(id) && !arrived.get() ? null : halls.apply(id));
		Other remote = other();
		Presentation saturday = remote.event().addPresentation("Samstag");
		saturday.setHallId(other.getId());
		saturday.setSeatingPlanId(otherPlan.getId());
		BookingSync.sync(saturday, otherPlan);
		saturday.getBookings().get(1).setLastName("Schmitz");
		remote.save();
		UnitKey booking = UnitKey.booking(event.getId(), saturday.getId(), otherPlan.getSeats().get(1).getId());
		long before = files();

		ForeignResult result = mine.applyForeign(allUnits());

		assertTrue(result.deferred().isEmpty());
		assertEquals(List.of("Freitag", "Samstag"),
				event.getPresentations().stream().map(Presentation::getName).toList());
		Presentation added = event.getPresentations().get(1);
		assertTrue(added.getBookings().isEmpty(), "ohne bekannte Halle bleibt die Buchung draußen");
		assertNull(mine.knownUnit(booking));
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");

		arrived.set(true);
		mine.retryDeferred();

		assertEquals(List.of("Schmitz"), added.getBookings().stream().map(Booking::getLastName).toList());
		assertSame(added, added.getBookings().get(0).getParent());
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	/** B legt die Vorstellung "Samstag" in einer zweiten Halle an und verkauft dort Sitz 2 an "Schmitz". */
	private record SplitSale(Presentation saturday, UnitKey booking, UnitKey presentation, SeatingPlan plan) {
	}

	/** Wie {@link SplitSale}; A kennt die zweite Halle erst, wenn {@code arrived} gesetzt ist. */
	private SplitSale saleInAPresentationOfAHallNotYetKnown(AtomicBoolean arrived) {
		Hall other = secondHall(1, 2);
		SeatingPlan otherPlan = other.getSeatingPlans().get(0);
		Function<String, Hall> halls = lookup(other);
		mine.setHallLookup(id -> other.getId().equals(id) && !arrived.get() ? null : halls.apply(id));
		Other remote = other();
		Presentation saturday = remote.event().addPresentation("Samstag");
		saturday.setHallId(other.getId());
		saturday.setSeatingPlanId(otherPlan.getId());
		BookingSync.sync(saturday, otherPlan);
		saturday.getBookings().get(1).setLastName("Schmitz");
		remote.save();
		return new SplitSale(saturday,
				UnitKey.booking(event.getId(), saturday.getId(), otherPlan.getSeats().get(1).getId()),
				UnitKey.presentation(event.getId(), saturday.getId()), otherPlan);
	}

	@Test
	void bookingBeforeItsPresentationWaitsUntilTheHallArrives() throws IOException {
		AtomicBoolean arrived = new AtomicBoolean(false);
		SplitSale sale = saleInAPresentationOfAHallNotYetKnown(arrived);
		long before = files();

		// die Buchung kommt vor ihrer Vorstellung, die Vorstellung vor ihrer Halle
		mine.applyForeign(List.of(sale.booking()));
		assertEquals(1, event.getPresentations().size());
		mine.applyForeign(List.of(sale.presentation()));
		Presentation added = event.getPresentations().get(1);
		assertTrue(added.getBookings().isEmpty());
		mine.retryDeferred();
		assertTrue(added.getBookings().isEmpty(), "sie wartet weiter, statt als bekannt herauszufallen");
		mine.saveEvent(event);
		assertEquals(before, files());

		arrived.set(true);
		mine.retryDeferred();

		assertEquals(List.of("Schmitz"), added.getBookings().stream().map(Booking::getLastName).toList());
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");
	}

	@Test
	void saleOnASeatWhoseForeignBookingWaitsForksInsteadOfOverwritingIt() throws IOException {
		AtomicBoolean arrived = new AtomicBoolean(false);
		SplitSale sale = saleInAPresentationOfAHallNotYetKnown(arrived);
		mine.applyForeign(List.of(sale.booking()));
		mine.applyForeign(List.of(sale.presentation()));
		Presentation added = event.getPresentations().get(1);
		// die Halle kommt an und eine Ansicht bereitet die Vorstellung vor, bevor die Buchung erneut geprüft wird
		arrived.set(true);
		BookingSync.sync(added, sale.plan());
		Booking mySale = added.getBookings().stream()
				.filter(b -> sale.booking().bookingDir().equals(b.getSeatId())).findFirst().orElseThrow();
		assertFalse(mySale.hasData(), "der Sitz sieht frei aus");
		mySale.setLastName("Eigen");

		ForeignResult result = mine.applyForeign(List.of());
		assertEquals(List.of(sale.booking()), result.deferred().stream().map(Deferral::unit).toList(),
				"der fremde Verkauf auf demselben Platz wird gemeldet");
		mine.saveEvent(event);

		UnitState state = sync.unitState(sale.booking());
		assertEquals("Schmitz", state.head().payload().get("lastName").asText(), "Bs Verkauf bleibt gültig");
		assertEquals(1, state.lost().size(), "As Verkauf tritt als Gabelung an, statt still darüber zu schreiben");
		assertEquals(2, sync.history(sale.booking()).size());
	}

	/** Ein neues Event "Kinderfasching" von B, dessen Vorstellung in einer Halle liegt, die A erst kennt, wenn {@code arrived} gesetzt ist. */
	private record NewEventInUnknownHall(Event children, UnitKey booking, SeatingPlan plan) {
	}

	private NewEventInUnknownHall newEventWithSaleInAHallNotYetKnown(AtomicBoolean arrived) {
		Hall other = secondHall(1, 2);
		SeatingPlan otherPlan = other.getSeatingPlans().get(0);
		Function<String, Hall> halls = lookup(other);
		mine.setHallLookup(id -> other.getId().equals(id) && !arrived.get() ? null : halls.apply(id));
		StoreEventRepository others = TestRepositories.events(sync);
		others.loadEvents();
		Event children = new Event();
		children.setName("Kinderfasching");
		Presentation sunday = children.addPresentation("Sonntag");
		sunday.setHallId(other.getId());
		sunday.setSeatingPlanId(otherPlan.getId());
		BookingSync.sync(sunday, otherPlan);
		sunday.getBookings().get(1).setLastName("Klein");
		others.saveEvent(children);
		return new NewEventInUnknownHall(children,
				UnitKey.booking(children.getId(), sunday.getId(), otherPlan.getSeats().get(1).getId()), otherPlan);
	}

	private Event cachedEvent(String id) {
		return mine.loadEvents().stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow();
	}

	@Test
	void waitingBookingHasNoBaselineUntilPlaced() throws IOException {
		AtomicBoolean arrived = new AtomicBoolean(false);
		NewEventInUnknownHall sale = newEventWithSaleInAHallNotYetKnown(arrived);

		// das neue Event kommt, die Halle seiner Vorstellung nicht: Die Buchung findet ihren Sitz nicht
		mine.applyForeign(sync.eventUnits(sale.children().getId()));

		Event added = cachedEvent(sale.children().getId());
		assertTrue(added.getPresentations().get(0).getBookings().stream().noneMatch(Booking::hasData));
		assertNull(mine.knownUnit(sale.booking()), "eine wartende Einheit gehört nicht in den Vergleichsstand");
		assertNotNull(mine.knownUnit(UnitKey.event(added.getId())));
		long before = files();
		mine.saveEvent(added);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");

		arrived.set(true);
		mine.retryDeferred();

		Booking placed = added.getPresentations().get(0).getBookings().stream()
				.filter(b -> sale.booking().bookingDir().equals(b.getSeatId())).findFirst().orElseThrow();
		assertEquals("Klein", placed.getLastName());
		assertNotNull(mine.knownUnit(sale.booking()));
		assertTrue(mine.locallyChangedUnits().isEmpty(), "angezeigt und unverändert: kein Anlass zur Rückstellung");
		mine.saveEvent(added);
		assertEquals(before, files());
	}

	@Test
	void waitingBookingStaysWaitingAfterReload() throws IOException {
		Hall other = secondHall(1, 2);
		SeatingPlan otherPlan = other.getSeatingPlans().get(0);
		String missingSeat = otherPlan.getSeats().get(1).getId();
		Other remote = other();
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), otherPlan);
		remote.friday().getBookings().stream().filter(b -> missingSeat.equals(b.getSeatId())).findFirst().orElseThrow()
				.setLastName("Fremd");
		remote.save();
		UnitKey unit = UnitKey.booking(event.getId(), friday.getId(), missingSeat);
		// Nach dem Neustart kennt dieser Rechner die Halle noch ohne den zweiten Sitz
		ObjectMapper mapper = JsonMapperFactory.create();
		Hall stale = mapper.readValue(mapper.writeValueAsBytes(other), Hall.class);
		stale.getSeatingPlans().get(0).getTables().get(0).getSeats().remove(1);
		AtomicReference<Hall> known = new AtomicReference<>(stale);
		StoreEventRepository restarted = TestRepositories.events(sync);
		restarted.setHallLookup(id -> other.getId().equals(id) ? known.get() : hall.getId().equals(id) ? hall : null);

		Event loaded = restarted.loadEvents().stream().filter(e -> e.getId().equals(event.getId())).findFirst()
				.orElseThrow();

		Presentation loadedFriday = loaded.getPresentations().get(0);
		assertEquals(otherPlan.getId(), loadedFriday.getSeatingPlanId());
		assertTrue(loadedFriday.getBookings().stream().noneMatch(b -> "Fremd".equals(b.getLastName())),
				"die Buchung auf dem fehlenden Sitz steht nicht im Modell");
		assertNull(restarted.knownUnit(unit), "sie hat keinen Vergleichsstand");
		long before = files();
		assertTrue(restarted.retryDeferred().deferred().isEmpty(), "sie wartet still und wird erneut geprüft");
		assertTrue(loadedFriday.getBookings().stream().noneMatch(b -> "Fremd".equals(b.getLastName())));
		assertNull(restarted.knownUnit(unit));
		restarted.saveEvent(loaded);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");

		known.set(other);
		restarted.retryDeferred();

		Booking placed = loadedFriday.getBookings().stream().filter(b -> missingSeat.equals(b.getSeatId())).findFirst()
				.orElseThrow();
		assertEquals("Fremd", placed.getLastName());
		assertNotNull(restarted.knownUnit(unit));
		restarted.saveEvent(loaded);
		assertEquals(before, files());
	}

	@Test
	void bookingBeforeItsPresentationIsNotInBaselineAndAppearsWithIt() throws IOException {
		Other other = other();
		Presentation saturday = other.event().addPresentation("Samstag");
		saturday.setHallId(hall.getId());
		saturday.setSeatingPlanId(plan.getId());
		BookingSync.sync(saturday, plan);
		seat(saturday, 2).setLastName("Schmitz");
		other.save();
		UnitKey booking = seatUnit(saturday, 2);
		long before = files();

		mine.applyForeign(List.of(booking));

		assertNull(mine.knownUnit(booking), "die Buchung wartet auf ihre Vorstellung, ohne Vergleichsstand");
		assertEquals(1, event.getPresentations().size());
		assertTrue(mine.retryDeferred().deferred().isEmpty());
		assertTrue(recorder.calls.isEmpty(), "das Warten ändert nichts, also meldet es nichts");

		mine.applyForeign(List.of(UnitKey.presentation(event.getId(), saturday.getId())));

		Presentation added = event.getPresentations().get(1);
		assertEquals("Schmitz", seat(added, 2).getLastName());
		assertNotNull(mine.knownUnit(booking));
		assertTrue(mine.locallyChangedUnits().isEmpty());
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");
	}

	@Test
	void saleOnSeatOfWaitingForeignBookingForks() throws IOException {
		AtomicBoolean arrived = new AtomicBoolean(false);
		NewEventInUnknownHall sale = newEventWithSaleInAHallNotYetKnown(arrived);
		mine.applyForeign(sync.eventUnits(sale.children().getId()));
		Event added = cachedEvent(sale.children().getId());
		// die Halle kommt an, eine Ansicht bereitet die Vorstellung vor: Der Sitz sieht frei aus, und der Nutzer verkauft ihn
		arrived.set(true);
		Presentation sunday = added.getPresentations().get(0);
		BookingSync.sync(sunday, sale.plan());
		Booking mySale = sunday.getBookings().stream().filter(b -> sale.booking().bookingDir().equals(b.getSeatId()))
				.findFirst().orElseThrow();
		assertFalse(mySale.hasData(), "der Sitz sieht frei aus");
		mySale.setLastName("Eigen");

		mine.saveEvent(added);

		List<Version> history = sync.history(sale.booking());
		assertEquals(2, history.size());
		Version ours = history.stream().filter(v -> "Eigen".equals(v.payload().get("lastName").asText())).findFirst()
				.orElseThrow();
		assertEquals(List.of(), ours.parents(), "eine Gabelung, nicht der Nachfolger des fremden Verkaufs");
		UnitState state = sync.unitState(sale.booking());
		assertEquals("Klein", state.head().payload().get("lastName").asText(), "Bs Verkauf bleibt gültig");
		assertEquals(1, state.lost().size());
	}

	@Test
	void failedAssemblyOfANewEventIsRetried() throws IOException {
		StoreEventRepository others = TestRepositories.events(sync);
		others.loadEvents();
		Event children = new Event();
		children.setName("Kinderfasching");
		Presentation sunday = children.addPresentation("Sonntag");
		sunday.setHallId(hall.getId());
		sunday.setSeatingPlanId(plan.getId());
		BookingSync.sync(sunday, plan);
		seat(sunday, 0).setLastName("Klein");
		others.saveEvent(children);
		// beim neuen Event fragt erst das Zusammensetzen nach der Halle
		mine.setHallLookup(failingAt(1, lookup()));

		ForeignResult failed = mine.applyForeign(List.of(UnitKey.event(children.getId())));
		assertEquals(1, failed.warnings().size(), failed.warnings().toString());
		assertEquals(1, mine.loadEvents().size());

		mine.retryDeferred();

		Event added = mine.loadEvents().stream().filter(e -> e.getId().equals(children.getId())).findFirst()
				.orElseThrow();
		assertEquals("Klein", seat(added.getPresentations().get(0), 0).getLastName());
	}

	@Test
	void unitsOfAnEventWhoseDecisionFailedAreRetried() {
		AtomicBoolean broken = new AtomicBoolean(true);
		mine.setEditingCheck(o -> {
			if (broken.get()) {
				throw new IllegalStateException("Registratur kaputt");
			}
			return false;
		});
		Other other = other();
		seat(other.friday(), 0).setLastName("Fremd");
		other.save();

		ForeignResult failed = mine.applyForeign(List.of(seatUnit(friday, 0)));
		assertEquals(1, failed.warnings().size(), failed.warnings().toString());
		assertEquals("Müller", seat(friday, 0).getLastName());

		broken.set(false);
		ForeignResult retried = mine.retryDeferred();

		assertTrue(retried.warnings().isEmpty());
		assertEquals("Fremd", seat(friday, 0).getLastName());
	}

	@Test
	void failingEditingCheckDuringRecoveryStillPairsTheNotifications() {
		Hall other = secondHall(1, 2);
		// gemeldet ist nur die Vorstellung: Die 1. Abfrage ist ihr Abgleich
		mine.setHallLookup(failingAt(1, lookup(other)));
		mine.setEditingCheck(o -> {
			if (o == event) {
				throw new IllegalStateException("Registratur kaputt");
			}
			return false;
		});
		Other remote = other();
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), other.getSeatingPlans().get(0));
		remote.save();
		UnitKey presentation = UnitKey.presentation(event.getId(), friday.getId());

		ForeignResult result = mine.applyForeign(List.of(presentation));

		assertEquals(List.of("changing Prunksitzung", "changed Prunksitzung"), recorder.calls);
		assertSame(event, mine.loadEvents().get(0));
		assertEquals(List.of(presentation), result.deferred().stream().map(Deferral::unit).toList());
	}

	@Test
	void deferredUnitIsAppliedOnRetryAfterEditingEnds() throws IOException {
		Booking sold = seat(friday, 0);
		AtomicBoolean editing = new AtomicBoolean(true);
		mine.setEditingCheck(o -> editing.get() && o == sold);
		Other other = other();
		seat(other.friday(), 0).setLastName("Fremd");
		other.save();
		assertEquals(1, mine.applyForeign(List.of(seatUnit(friday, 0))).deferred().size());
		assertEquals("Müller", sold.getLastName());
		long before = files();

		editing.set(false);
		ForeignResult result = mine.retryDeferred();

		assertTrue(result.deferred().isEmpty());
		assertEquals("Fremd", sold.getLastName());
		assertEquals(List.of("changing Prunksitzung", "changed Prunksitzung"), recorder.calls);
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void unreadableForeignBookingLeavesTheModel() throws IOException {
		Booking sold = seat(friday, 0);
		UnitKey unit = seatUnit(friday, 0);
		ObjectNode broken = JsonMapperFactory.create().createObjectNode();
		broken.put("id", "andere-id");
		sync.append("fremd", List.of(new Change(unit, List.of(sync.unitState(unit).head().id()), broken, false)));
		long before = files();

		ForeignResult result = mine.applyForeign(List.of(unit));

		assertSame(sold, seat(friday, 0));
		assertFalse(sold.hasData(), "der Sitz zeigt wieder den leeren Platzhalter");
		assertEquals(List.of("Eine Buchung von \"Freitag\" konnte nicht gelesen werden und fehlt."), result.warnings());
		mine.saveEvent(event);
		assertEquals(before, files(), "die unlesbare Einheit wird weder gelöscht noch überschrieben");
		seat(friday, 1).setLastName("Eigen");
		mine.saveEvent(event);
		assertEquals(2, sync.history(unit).size());
		assertTrue(sync.history(unit).stream().noneMatch(Version::deleted));
	}

	@Test
	void lockedForeignUnitIsLoadedWithWarning() throws IOException {
		Booking sold = seat(friday, 0);
		UnitKey unit = seatUnit(friday, 0);
		foreignVersion(unit, "Fremd");
		Version damaged = foreignVersion(unit, "Kaputt");
		Files.writeString(appData.resolve("data/" + unit.relativePath() + "/" + damaged.id() + ".json"), "kaputt");
		sync.unitState(unit);

		ForeignResult result = mine.applyForeign(List.of(unit));

		assertEquals("Fremd", sold.getLastName());
		assertEquals(List.of(StoreMessages.damaged(unit.relativePath())), result.warnings());
		sold.setLastName("Neu");
		assertThrows(LockedUnitException.class, () -> mine.saveEvent(event));
	}

	/** Hängt an den Kopf eine unlesbare, nicht gesperrte Version von anderer Seite (die ID passt nicht zum Ordner). */
	private void unreadableForeignHead() throws IOException {
		Version parent = sync.unitState(head()).head();
		ObjectNode broken = parent.payload().deepCopy();
		broken.put("id", "andere-id");
		sync.append("fremd", List.of(new Change(head(), List.of(parent.id()), broken, false)));
	}

	@Test
	void savingAHeldEventAfterAForeignUnreadableHeadIsRefused() throws IOException {
		unreadableForeignHead();
		mine.applyForeign(List.of(head()));
		assertEquals(List.of("removed Prunksitzung"), recorder.calls);
		long before = files();
		// eine Ansicht hält das Event noch und will Änderungen darunter speichern
		friday.setName("Freitagabend");
		seat(friday, 0).setLastName("Neu");

		LockedUnitException refused = assertThrows(LockedUnitException.class, () -> mine.saveEvent(event));

		assertEquals("„Prunksitzung“ wurde inzwischen auf einem anderen Rechner geändert. Bitte die Ansicht neu öffnen.",
				refused.getMessage());
		assertEquals(before, files(), "nichts geschrieben: der alte Stand überschriebe neuere fremde Stände");
		assertEquals("Müller", sync.unitState(seatUnit(friday, 0)).head().payload().get("lastName").asText());
		assertTrue(mine.loadEvents().isEmpty());
	}

	@Test
	void presentationThatArrivedWhileItsEventWasHiddenIsPlacedLater() throws IOException {
		Other other = other();
		Presentation saturday = other.event().addPresentation("Samstag");
		saturday.setHallId(hall.getId());
		saturday.setSeatingPlanId(plan.getId());
		BookingSync.sync(saturday, plan);
		seat(saturday, 2).setLastName("Schmitz");
		other.save();
		unreadableForeignHead();
		UnitKey presentation = UnitKey.presentation(event.getId(), saturday.getId());

		// der unlesbare Kopf nimmt das Event aus dem Cache; die neue Vorstellung kommt nur in den Vergleichsstand
		mine.applyForeign(allUnits());
		assertFalse(mine.loadEvents().contains(event));
		assertNotNull(mine.knownUnit(presentation));
		// das Wiederherstellen des ersten Stands bringt das Event zurück (eine Ansicht, die das alte Objekt hält, wird
		// abgelehnt); die Vorstellung kam nie hinein
		List<SavePoint> points = mine.savePoints(event.getId());
		Event restored = mine.restoreEvent(event.getId(), points.get(points.size() - 1), e -> List.of()).restored();
		assertTrue(mine.loadEvents().contains(restored));
		assertEquals(1, restored.getPresentations().size());
		long before = files();

		mine.applyForeign(List.of(presentation));

		assertEquals(List.of("Freitag", "Samstag"),
				restored.getPresentations().stream().map(Presentation::getName).toList());
		assertEquals("Schmitz", seat(restored.getPresentations().get(1), 2).getLastName());
		mine.saveEvent(restored);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");
	}

	@Test
	void presentationLeftOutByARestoreIsPlacedByTheBacklogWithoutANotification() throws IOException {
		Other other = other();
		Presentation saturday = other.event().addPresentation("Samstag");
		saturday.setHallId(hall.getId());
		saturday.setSeatingPlanId(plan.getId());
		BookingSync.sync(saturday, plan);
		seat(saturday, 2).setLastName("Schmitz");
		other.save();
		unreadableForeignHead();
		mine.applyForeign(allUnits());
		List<SavePoint> points = mine.savePoints(event.getId());
		Event restored = mine.restoreEvent(event.getId(), points.get(points.size() - 1), e -> List.of()).restored();
		assertEquals(1, restored.getPresentations().size());
		long before = files();

		// kein Hinweis auf die Vorstellung: Sie wartet in der Warteliste, bis sie ins Modell passt
		mine.retryDeferred();

		assertEquals(List.of("Freitag", "Samstag"),
				restored.getPresentations().stream().map(Presentation::getName).toList());
		assertEquals("Schmitz", seat(restored.getPresentations().get(1), 2).getLastName());
		mine.saveEvent(restored);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");
	}

	@Test
	void hiddenBookingOfARevivedPresentationKeepsWaitingAndIsNotOverwritten() throws IOException {
		Hall second = secondHall(1, 2);
		mine.setHallLookup(lookup(second));
		Other remote = other();
		Presentation theirs = remote.friday();
		// B löscht Freitag: Müllers Buchung verlässt mit ihm das Modell, bleibt aber im Vergleichsstand
		remote.event().getPresentations().remove(theirs);
		remote.save();
		mine.applyForeign(allUnits());
		assertTrue(event.getPresentations().isEmpty());
		// B belebt Freitag im Bürgerhaus wieder: Müllers Sitz fehlt dort, seine Buchung wartet
		theirs.setHallId(second.getId());
		theirs.setSeatingPlanId(second.getSeatingPlans().get(0).getId());
		remote.event().getPresentations().add(theirs);
		remote.save();
		mine.applyForeign(allUnits());
		assertEquals(1, event.getPresentations().size());
		assertNull(mine.knownUnit(seatUnit(friday, 0)), "wartend: ohne Vergleichsstand");
		// B setzt Freitag zurück in die Stadthalle
		theirs.setHallId(hall.getId());
		theirs.setSeatingPlanId(plan.getId());
		remote.save();
		long before = files();

		mine.applyForeign(allUnits());
		mine.retryDeferred();

		Presentation back = event.getPresentations().get(0);
		assertEquals("Müller", seat(back, 0).getLastName(), "Müller kommt mit der Stadthalle zurück");
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");
	}

	@Test
	void saleOnSeatOfHiddenBookingAfterTheHallReturnsDoesNotBuildOnTheForeignSale() throws IOException {
		Hall second = secondHall(1, 2);
		mine.setHallLookup(lookup(second));
		Other remote = other();
		Presentation theirs = remote.friday();
		remote.event().getPresentations().remove(theirs);
		remote.save();
		mine.applyForeign(allUnits());
		theirs.setHallId(second.getId());
		theirs.setSeatingPlanId(second.getSeatingPlans().get(0).getId());
		remote.event().getPresentations().add(theirs);
		remote.save();
		mine.applyForeign(allUnits());
		UnitKey seatZero = seatUnit(friday, 0);

		// Die Buchung wartet ohne Vergleichsstand; ein Verkauf auf ihrem Sitz tritt gegen sie an
		assertNull(mine.knownUnit(seatZero));
		Presentation shown = event.getPresentations().get(0);
		shown.setHallId(hall.getId());
		shown.setSeatingPlanId(plan.getId());
		BookingSync.switchPlan(shown, plan);
		seat(shown, 0).setLastName("Eigen");
		mine.saveEvent(event);

		Version ours = sync.history(seatZero).stream()
				.filter(v -> "Eigen".equals(v.payload().get("lastName").asText())).findFirst().orElseThrow();
		assertEquals(List.of(), ours.parents(), "eine Gabelung, nicht der Nachfolger des fremden Verkaufs");
		assertEquals("Müller", sync.unitState(seatZero).head().payload().get("lastName").asText());
	}

	@Test
	void foreignWriteToBookingOfADeletedPresentationIsOnlyAdopted() throws IOException {
		Other remote = other();
		remote.event().getPresentations().remove(remote.friday());
		remote.save();
		mine.applyForeign(allUnits());
		assertTrue(event.getPresentations().isEmpty());
		UnitKey unit = seatUnit(friday, 0);

		Version written = foreignVersion(unit, "Fremd");
		mine.applyForeign(List.of(unit));

		assertEquals(written.id(), mine.knownUnit(unit).version(), "nichts wartet auf einen Platz: nur der Vergleichsstand");
		assertTrue(mine.retryDeferred().deferred().isEmpty());
	}

	@Test
	void unassignedBookingBeforeItsPresentationWaitsWhileItsOldSeatIsInThePlan() throws IOException {
		Other remote = other();
		Presentation saturday = remote.event().addPresentation("Samstag");
		saturday.setHallId(hall.getId());
		saturday.setSeatingPlanId(plan.getId());
		// ohne Platz, aber mit der Kennung eines Sitzes der Bestuhlung: Der Abgleich setzte sie auf diesen Sitz
		Booking unassigned = saturday.addBooking();
		unassigned.setUnassigned(true);
		unassigned.setSeatId(planSeat(1).getId());
		unassigned.setLastName("Schmitz");
		remote.save();
		UnitKey booking = UnitKey.unassignedBooking(event.getId(), saturday.getId(), unassigned.getId());
		UnitKey presentation = UnitKey.presentation(event.getId(), saturday.getId());
		long before = files();

		// die Buchung kommt vor ihrer Vorstellung: Sie wartet, ohne Vergleichsstand
		mine.applyForeign(List.of(booking));
		assertNull(mine.knownUnit(booking));
		mine.applyForeign(List.of(presentation));
		Presentation added = event.getPresentations().get(1);
		assertTrue(added.getBookings().stream().noneMatch(Booking::hasData));
		mine.retryDeferred();
		assertTrue(added.getBookings().stream().noneMatch(Booking::hasData),
				"sie wartet weiter, statt als bekannt herauszufallen");
		mine.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird geschrieben oder gelöscht");

		// B nimmt der Vorstellung die Bestuhlung: Jetzt passt die Buchung
		saturday.setSeatingPlanId(null);
		BookingSync.sync(saturday, null);
		remote.save();
		long afterSwitch = files();
		mine.applyForeign(List.of(presentation));

		assertNull(added.getSeatingPlanId());
		assertEquals(List.of("Schmitz"), added.getBookings().stream().map(Booking::getLastName).toList());
		assertTrue(added.getBookings().get(0).isUnassigned());
		assertEquals(unassigned.getId(), added.getBookings().get(0).getId());
		mine.saveEvent(event);
		assertEquals(afterSwitch, files());
	}

	@Test
	void applyForeignBeforeFirstLoadDoesNothing() throws IOException {
		ObjectNode payload = JsonMapperFactory.create().createObjectNode();
		payload.put("id", "andere-id");
		sync.append("test", List.of(new Change(UnitKey.event("ordner-id"), List.of(), payload, false)));
		Other other = other();
		seat(other.friday(), 1).setLastName("Fremd");
		other.save();
		StoreEventRepository fresh = TestRepositories.events(sync);
		Recorder freshRecorder = new Recorder();
		fresh.addListener(freshRecorder);

		ForeignResult result = fresh.applyForeign(allUnits());

		assertTrue(result.deferred().isEmpty());
		assertTrue(result.warnings().isEmpty());
		assertTrue(freshRecorder.calls.isEmpty());
		assertTrue(fresh.locallyChangedUnits().isEmpty());
		assertTrue(fresh.getAndClearLoadWarnings().isEmpty(), "geladen hätte die unlesbare Einheit gewarnt");
	}

	@Test
	void batchReplayOnCopyGivesSameStateAsCache() throws IOException {
		ObjectMapper mapper = JsonMapperFactory.create();
		Event copy = mapper.readValue(mapper.writeValueAsBytes(event), Event.class);
		Other other = other();
		other.event().setDescription("Große Sitzung");
		other.friday().setName("Freitagabend");
		seat(other.friday(), 0).setLastName("Fremd");
		seat(other.friday(), 1).setLastName("Neu");
		other.save();

		mine.applyForeign(allUnits());
		ForeignBatch<Event> batch = recorder.batches.get(0);
		batch.replayOn(copy);

		assertEquals(Set.of(head(), UnitKey.presentation(event.getId(), friday.getId()), seatUnit(friday, 0),
				seatUnit(friday, 1)), batch.units());
		assertEquals(mapper.valueToTree(event), mapper.valueToTree(copy));
		assertNotSame(seat(friday, 0), seat(copy.getPresentations().get(0), 0));
	}

	@Test
	void batchReplayOnCopyMatchesAfterHallSwitchAndNewPresentation() throws IOException {
		Hall big = secondHall(3, 3);
		SeatingPlan bigPlan = big.getSeatingPlans().get(0);
		mine.setHallLookup(lookup(big));
		ObjectMapper mapper = JsonMapperFactory.create();
		Event copy = mapper.readValue(mapper.writeValueAsBytes(event), Event.class);
		Other other = other();
		other.friday().setHallId(big.getId());
		BookingSync.switchPlan(other.friday(), bigPlan);
		Presentation saturday = other.event().addPresentation("Samstag");
		saturday.setHallId(big.getId());
		saturday.setSeatingPlanId(bigPlan.getId());
		BookingSync.sync(saturday, bigPlan);
		saturday.getBookings().get(4).setLastName("Schmitz");
		other.save();
		long before = files();

		mine.applyForeign(allUnits());
		recorder.batches.get(0).replayOn(copy);

		assertEquals(9, friday.getBookings().size());
		assertEquals(9, event.getPresentations().get(1).getBookings().size());
		assertEquals(mapper.valueToTree(event), mapper.valueToTree(copy),
				"auch die vom Abgleich neu angelegten Platzhalter tragen dieselben Kennungen");
		mine.saveEvent(event);
		assertEquals(before, files());
	}

	@Test
	void failureWhileMergingAnUnsavedEventLeavesNoForeignStateInTheModel() throws IOException {
		Presentation saturday = event.addPresentation("Samstag");
		saturday.setHallId(hall.getId());
		saturday.setSeatingPlanId(plan.getId());
		BookingSync.sync(saturday, plan);
		mine.saveEvent(event);
		seat(saturday, 1).setLastName("Eigen");
		Hall other = secondHall(1, 2);
		// 1. Abfrage: Abgleich der Vorstellung (ihre Buchungen fragen erst danach, beim Einarbeiten)
		mine.setHallLookup(failingAt(1, lookup(other)));
		Booking sold = seat(friday, 0);
		Other remote = other();
		remote.event().setDescription("Große Sitzung");
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), other.getSeatingPlans().get(0));
		remote.save();
		UnitKey presentation = UnitKey.presentation(event.getId(), friday.getId());

		ForeignResult result = mine.applyForeign(allUnits());

		assertEquals(1, result.warnings().size(), result.warnings().toString());
		assertSame(event, mine.loadEvents().get(0), "ein ungespeichertes Event wird nicht ausgetauscht");
		assertEquals("Große Sitzung", event.getDescription(), "der Kopf davor bleibt eingearbeitet");
		assertEquals(hall.getId(), friday.getHallId());
		assertEquals(plan.getId(), friday.getSeatingPlanId());
		assertEquals(6, friday.getBookings().size());
		assertSame(sold, seat(friday, 0));
		assertEquals(Set.of(seatUnit(saturday, 1)), mine.locallyChangedUnits(),
				"kein halb eingearbeiteter fremder Stand gilt als eigene Änderung");
		assertTrue(result.deferred().stream().anyMatch(d -> d.unit().equals(presentation)));
		assertEquals(List.of("changing Prunksitzung", "changed Prunksitzung"), recorder.calls);
		assertEquals(Set.of(head()), recorder.batches.get(0).units());

		mine.saveEvent(event);
		assertEquals(2, sync.history(presentation).size(), "die Vorstellung schreibt A nicht");

		assertTrue(mine.retryDeferred().deferred().isEmpty());
		assertEquals(other.getId(), friday.getHallId());
		assertSame(sold, friday.getBookings().get(0));
		assertEquals(other.getSeatingPlans().get(0).getSeats().get(0).getId(), sold.getSeatId());
		assertEquals("Müller", sold.getLastName());
	}

	@Test
	void failureWhileMergingDoesNotRebuildAnEventInEditing() {
		Hall other = secondHall(1, 2);
		// gemeldet ist nur die Vorstellung: Die 1. Abfrage ist ihr Abgleich
		mine.setHallLookup(failingAt(1, lookup(other)));
		mine.setEditingCheck(o -> o == event);
		Other remote = other();
		remote.friday().setHallId(other.getId());
		BookingSync.switchPlan(remote.friday(), other.getSeatingPlans().get(0));
		remote.save();
		UnitKey presentation = UnitKey.presentation(event.getId(), friday.getId());

		ForeignResult result = mine.applyForeign(List.of(presentation));

		assertSame(event, mine.loadEvents().get(0), "die offene Eingabe schreibt weiter ins Objekt im Cache");
		assertEquals(hall.getId(), friday.getHallId());
		assertEquals(List.of(presentation), result.deferred().stream().map(Deferral::unit).toList());
	}

	@Test
	void failureInOneEventDoesNotStopTheOthers() throws IOException {
		StoreEventRepository others = TestRepositories.events(sync);
		others.loadEvents();
		Event children = new Event();
		children.setName("Kinderfasching");
		children.addPresentation("Sonntag");
		others.saveEvent(children);
		mine.applyForeign(sync.eventUnits(children.getId()));
		Event mineChildren = mine.loadEvents().get(0);
		mine.setEditingCheck(o -> {
			if (o == event) {
				throw new IllegalStateException("Registratur kaputt");
			}
			return false;
		});
		Other other = other();
		other.event().setDescription("Große Sitzung");
		other.save();
		children.setDescription("Fasching für Kinder");
		others.saveEvent(children);
		recorder.calls.clear();

		ForeignResult result = mine.applyForeign(List.of(head(), UnitKey.event(children.getId())));

		assertEquals(1, result.warnings().size(), result.warnings().toString());
		assertEquals(null, event.getDescription());
		assertEquals("Fasching für Kinder", mineChildren.getDescription());
		assertEquals(List.of("changing Kinderfasching", "changed Kinderfasching"), recorder.calls);
	}

	@Test
	void locallyChangedUnitsListsUnsavedUnits() {
		seat(friday, 1).setLastName("Eigen");
		seat(friday, 0).clearCustomerData();

		assertEquals(Set.of(seatUnit(friday, 0), seatUnit(friday, 1)), mine.locallyChangedUnits());
	}

	@Test
	void removedListenerHearsNothing() {
		mine.removeListener(recorder);
		Other other = other();
		seat(other.friday(), 0).setLastName("Fremd");
		other.save();

		mine.applyForeign(List.of(seatUnit(friday, 0)));

		assertTrue(recorder.calls.isEmpty());
		assertEquals("Fremd", seat(friday, 0).getLastName());
	}

	@Test
	void hallUnitsAreIgnored() {
		ForeignResult result = mine.applyForeign(List.of(UnitKey.hall(hall.getId())));

		assertTrue(result.deferred().isEmpty());
		assertTrue(recorder.calls.isEmpty());
		assertSame(event, mine.loadEvents().get(0));
	}

	// <-- Regeln
}
