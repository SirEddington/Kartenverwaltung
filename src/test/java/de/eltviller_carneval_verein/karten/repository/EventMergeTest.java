package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.store.Snapshots;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.VersionId;

class EventMergeTest {

	private static final ObjectMapper MAPPER = JsonMapperFactory.create();

	/** Merkt sich, was das Einarbeiten meldet. */
	private static final class Recording implements EventMerge.Sink {
		final Map<UnitKey, Boolean> adopted = new HashMap<>();
		final List<UnitKey> held = new ArrayList<>();
		final List<UnitKey> left = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		final List<UnitKey> displacedUnits = new ArrayList<>();
		final List<String> displaced = new ArrayList<>();
		final List<UnitKey> reconciled = new ArrayList<>();

		@Override
		public void reconciled(UnitKey unit) {
			reconciled.add(unit);
		}

		@Override
		public void displaced(UnitSnapshot snapshot, String hint) {
			displacedUnits.add(snapshot.unit());
			displaced.add(hint);
		}

		@Override
		public void adopted(UnitSnapshot snapshot, boolean shown) {
			adopted.put(snapshot.unit(), shown);
		}

		@Override
		public void held(UnitSnapshot snapshot) {
			held.add(snapshot.unit());
		}

		@Override
		public void left(UnitKey presentation) {
			left.add(presentation);
		}

		@Override
		public void warning(String text) {
			warnings.add(text);
		}
	}

	private int versions;

	// Bauteile -->

	private VersionId nextVersion() {
		versions++;
		return VersionId.create(1_000L * versions, "abcdefgh", versions);
	}

	/** Der Stand einer Einheit, wie ihn ein anderer Rechner geschrieben hat: genau der Inhalt des Zerlegens. */
	private UnitSnapshot foreign(Event state, UnitKey unit) {
		return Snapshots.snapshot(unit, nextVersion(), Status.OK, false, UnitMapper.payloads(state).get(unit));
	}

	private UnitSnapshot deleted(UnitKey unit) {
		return Snapshots.snapshot(unit, nextVersion(), Status.OK, true, JsonNodeFactory.instance.objectNode());
	}

	/** Eine unabhängige Kopie über JSON, wie sie der Tracker aus seinen Bytes liest. */
	private static Event copyOf(Event event) throws IOException {
		return MAPPER.readValue(MAPPER.writeValueAsBytes(event), Event.class);
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

	/** Ein Event mit einer Vorstellung "Freitag", einer Buchung "Müller" auf s1 und einem leeren Platzhalter auf s2. */
	private static Event event() {
		Event event = new Event();
		event.setName("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		friday.setDate(LocalDate.of(2027, 2, 12));
		booking(friday, "s1", 1, "Müller");
		booking(friday, "s2", 2, null);
		return event;
	}

	private static UnitKey seat(Event event, String seatId) {
		return UnitKey.booking(event.getId(), event.getPresentations().get(0).getId(), seatId);
	}

	/** Ein Plan für Stände ohne Buchungen unter einer Vorstellung des Modells: Er kennt keine Index-Stände von Vorstellungen. */
	private static EventMerge.Plan plan(List<UnitSnapshot> units, List<UnitSnapshot> index) {
		return new EventMerge.Plan(units, index, id -> null, Map.of());
	}

	/** Die Stände der Vorstellungen des Modells, wie sie der Index hat, wenn er ihnen nichts voraus hat. */
	private Map<UnitKey, UnitSnapshot> indexOf(Event model) {
		Map<UnitKey, UnitSnapshot> states = new HashMap<>();
		for (Presentation presentation : model.getPresentations()) {
			UnitKey key = UnitKey.presentation(model.getId(), presentation.getId());
			states.put(key, foreign(model, key));
		}
		return states;
	}

	/** Ein Plan, dessen Index die Vorstellungen des Modells unverändert hat. */
	private EventMerge.Plan plan(Event model, List<UnitSnapshot> units, List<UnitSnapshot> index) {
		return new EventMerge.Plan(units, index, id -> null, indexOf(model));
	}

	// <-- Bauteile

	@Test
	void headChangeIsCopiedIntoTheSameEvent() {
		Event event = event();
		Event state = copyOfUnchecked(event);
		state.setDescription("Große Sitzung");
		state.setArchived(true);
		Recording sink = new Recording();
		List<Presentation> presentations = event.getPresentations();

		EventMerge.apply(event, plan(List.of(foreign(state, UnitKey.event(event.getId()))), List.of()), sink);

		assertEquals("Große Sitzung", event.getDescription());
		assertTrue(event.isArchived());
		assertSame(presentations, event.getPresentations());
		assertEquals(Map.of(UnitKey.event(event.getId()), true), sink.adopted);
	}

	@Test
	void bookingOnSeatFillsThePlaceholderObject() {
		Event event = event();
		Booking placeholder = event.getPresentations().get(0).getBookings().get(1);
		Event state = copyOfUnchecked(event);
		state.getPresentations().get(0).getBookings().get(1).setLastName("Fremd");
		Recording sink = new Recording();

		EventMerge.apply(event, plan(event, List.of(foreign(state, seat(event, "s2"))), List.of()), sink);

		assertSame(placeholder, event.getPresentations().get(0).getBookings().get(1));
		assertEquals("Fremd", placeholder.getLastName());
		assertEquals(state.getPresentations().get(0).getBookings().get(1).getId(), placeholder.getId());
		assertEquals(2, event.getPresentations().get(0).getBookings().size());
		assertEquals(Map.of(seat(event, "s2"), true), sink.adopted);
	}

	@Test
	void deletedSeatBookingKeepsThePlaceAndTheReplayGetsTheSameNewId() throws IOException {
		Event event = event();
		Event copy = copyOf(event);
		Booking sold = event.getPresentations().get(0).getBookings().get(0);
		String oldId = sold.getId();
		EventMerge.Plan plan = plan(event, List.of(deleted(seat(event, "s1"))), List.of());
		Recording sink = new Recording();

		EventMerge.apply(event, plan, sink);
		EventMerge.apply(copy, plan, EventMerge.NONE);

		assertSame(sold, event.getPresentations().get(0).getBookings().get(0));
		assertFalse(sold.hasData());
		assertNotEquals(oldId, sold.getId());
		assertEquals("s1", sold.getSeatId());
		assertEquals(1, sold.getSeatNumber());
		assertEquals(MAPPER.valueToTree(event), MAPPER.valueToTree(copy));
		assertEquals(Map.of(seat(event, "s1"), false), sink.adopted);
	}

	@Test
	void unassignedBookingIsUpdatedByIdOrAppendedAndRemovedWhenDeleted() {
		Event event = event();
		Presentation friday = event.getPresentations().get(0);
		Event state = copyOfUnchecked(event);
		Booking unassigned = state.getPresentations().get(0).addBooking();
		unassigned.setUnassigned(true);
		unassigned.setLastName("Ohne");
		UnitKey unit = UnitKey.unassignedBooking(event.getId(), friday.getId(), unassigned.getId());

		EventMerge.apply(event, plan(event, List.of(foreign(state, unit)), List.of()), new Recording());
		Booking added = friday.getBookings().get(2);
		unassigned.setLastName("Ohne Platz");
		EventMerge.apply(event, plan(event, List.of(foreign(state, unit)), List.of()), new Recording());

		assertEquals(3, friday.getBookings().size());
		assertSame(added, friday.getBookings().get(2));
		assertEquals("Ohne Platz", added.getLastName());
		assertSame(friday, added.getParent());

		EventMerge.apply(event, plan(event, List.of(deleted(unit)), List.of()), new Recording());

		assertEquals(2, friday.getBookings().size());
	}

	@Test
	void bookingOnUnknownSeatUnderPresentationWithoutHallIsAdoptedWithoutPlace() {
		Event event = event();
		Presentation friday = event.getPresentations().get(0);
		Event state = copyOfUnchecked(event);
		Booking foreign = booking(state.getPresentations().get(0), "s9", 9, "Fremd");
		Recording sink = new Recording();

		EventMerge.apply(event, plan(event, List.of(foreign(state, seat(event, "s9"))), List.of()), sink);

		// keine Halle, auf die zu warten wäre: Die Buchung kommt herein, der Abgleich ohne Bestuhlung stellt sie ohne Platz
		Booking result = friday.getBookings().stream().filter(b -> foreign.getId().equals(b.getId())).findFirst().orElseThrow();
		assertTrue(result.isUnassigned());
		assertEquals("Fremd", result.getLastName());
		assertTrue(sink.held.isEmpty());
		assertTrue(sink.displaced.isEmpty(), "ohne Halle gibt es keine Bestuhlung, die der Hinweis nennen könnte");
		assertEquals(Map.of(seat(event, "s9"), true), sink.adopted);
	}

	@Test
	void failureAfterTheSyncOfADisplacedBookingRestoresAllBookingsOfThePresentation() {
		Hall hall = hallWithOneSeat();
		Event event = eventInHall(hall);
		Presentation friday = event.getPresentations().get(0);
		// eine Buchung des Modells, deren Sitz die Bestuhlung nicht hat: Der Abgleich ändert sie (ohne Platz)
		Booking old = booking(friday, "alter-sitz", 5, "Alt");
		Event state = copyOfUnchecked(event);
		// Reihe A, Tisch 1, Sitz 1 sind die Beschriftung des Sitzes der Bestuhlung, den im Modell der Platzhalter trägt
		booking(state.getPresentations().get(0), "fremder-sitz", 1, "Müller");
		UnitKey unit = UnitKey.booking(event.getId(), friday.getId(), "fremder-sitz");
		Event control = copyOfUnchecked(event);
		EventMerge.apply(control, displacedPlan(control, hall, state, unit), EventMerge.NONE);
		assertNotEquals(MAPPER.valueToTree(event), MAPPER.valueToTree(control), "ohne Fehler ändert der Abgleich die Vorstellung");
		List<Booking> bookings = new ArrayList<>(friday.getBookings());
		var before = MAPPER.valueToTree(event);
		// das Melden nach dem Abgleich scheitert
		EventMerge.Sink failing = new EventMerge.Sink() {
			@Override
			public void adopted(UnitSnapshot snapshot, boolean shown) {
				throw new IllegalStateException("Melden scheitert");
			}
		};

		assertThrows(IllegalStateException.class,
				() -> EventMerge.apply(event, displacedPlan(event, hall, state, unit), failing));

		assertEquals(bookings, friday.getBookings());
		assertFalse(old.isUnassigned());
		assertEquals(before, MAPPER.valueToTree(event));
	}

	@Test
	void failingUnitLeavesItsPartOfTheModelAsBefore() {
		Event event = event();
		Presentation friday = event.getPresentations().get(0);
		List<Booking> bookings = new ArrayList<>(friday.getBookings());
		Event state = copyOfUnchecked(event);
		state.setDescription("Große Sitzung");
		state.getPresentations().get(0).setHallId("andere-halle");
		state.getPresentations().get(0).setSeatingPlanId("andere-bestuhlung");
		UnitKey head = UnitKey.event(event.getId());
		UnitKey presentation = UnitKey.presentation(event.getId(), friday.getId());
		EventMerge.Plan plan = new EventMerge.Plan(List.of(foreign(state, head), foreign(state, presentation)),
				List.of(), id -> {
					throw new IllegalStateException("Halle nicht lesbar");
				}, Map.of());
		Recording sink = new Recording();

		assertThrows(IllegalStateException.class, () -> EventMerge.apply(event, plan, sink));

		assertEquals("Große Sitzung", event.getDescription(), "die Einheit davor bleibt eingearbeitet");
		assertNull(friday.getHallId());
		assertNull(friday.getSeatingPlanId());
		assertEquals(bookings, friday.getBookings());
		assertEquals("Müller", bookings.get(0).getLastName());
		assertEquals(Map.of(head, true), sink.adopted);
	}

	@Test
	void placeholdersCreatedByTheSyncGetTheSameIdsOnTheCopy() throws IOException {
		Hall hall = new Hall();
		SeatingPlan seating = hall.addSeatingPlan();
		TableRow row = seating.addRow();
		row.setLabel("A");
		seating.fillRow(row, 2, 3);
		Event event = event();
		Event copy = copyOf(event);
		Event state = copyOfUnchecked(event);
		state.getPresentations().get(0).setHallId(hall.getId());
		state.getPresentations().get(0).setSeatingPlanId(seating.getId());
		UnitKey presentation = UnitKey.presentation(event.getId(), event.getPresentations().get(0).getId());
		EventMerge.Plan plan = plan(List.of(foreign(state, presentation)), List.of());
		EventMerge.Plan withHall = new EventMerge.Plan(plan.units(), List.of(), id -> hall, Map.of());

		EventMerge.apply(event, withHall, new Recording());
		EventMerge.apply(copy, withHall, EventMerge.NONE);

		assertEquals(6, event.getPresentations().get(0).getBookings().size());
		assertEquals(MAPPER.valueToTree(event), MAPPER.valueToTree(copy));
	}

	@Test
	void newPresentationIsAssembledWithItsBookingsAndSortedIn() {
		Event event = event();
		Presentation sunday = event.addPresentation("Sonntag");
		sunday.setDate(LocalDate.of(2027, 2, 14));
		Event state = copyOfUnchecked(event);
		Hall hall = hallWithOneSeat();
		Presentation saturday = inHall(state.addPresentation("Samstag"), hall);
		saturday.setDate(LocalDate.of(2027, 2, 13));
		String seatId = seatOf(hall);
		booking(saturday, seatId, 1, "Schmitz");
		UnitKey presentationUnit = UnitKey.presentation(event.getId(), saturday.getId());
		UnitKey bookingUnit = UnitKey.booking(event.getId(), saturday.getId(), seatId);
		UnitSnapshot bookingState = foreign(state, bookingUnit);
		Recording sink = new Recording();

		// die Buchung steht schon im Index (sie kam vor ihrer Vorstellung), gemeldet ist nur die Vorstellung
		EventMerge.apply(event,
				new EventMerge.Plan(List.of(foreign(state, presentationUnit)), List.of(bookingState), id -> hall, Map.of()), sink);

		assertEquals(List.of("Freitag", "Samstag", "Sonntag"),
				event.getPresentations().stream().map(Presentation::getName).toList());
		Presentation added = event.getPresentations().get(1);
		assertSame(event, added.getParent());
		assertEquals(List.of("Schmitz"), added.getBookings().stream().map(Booking::getLastName).toList());
		assertSame(added, added.getBookings().get(0).getParent());
		assertEquals(Map.of(presentationUnit, true, bookingUnit, true), sink.adopted);
	}

	@Test
	void deletedPresentationLeavesTheModel() {
		Event event = event();
		UnitKey unit = UnitKey.presentation(event.getId(), event.getPresentations().get(0).getId());
		Recording sink = new Recording();

		EventMerge.apply(event, plan(List.of(deleted(unit)), List.of()), sink);

		assertTrue(event.getPresentations().isEmpty());
		assertEquals(List.of(unit), sink.left);
		assertEquals(Map.of(unit, false), sink.adopted);
	}

	@Test
	void bookingOfAnInvisiblePresentationWaits() {
		Event event = event();
		UnitKey unit = UnitKey.booking(event.getId(), "fehlt", "s1");
		Event state = copyOfUnchecked(event);
		UnitSnapshot snapshot = Snapshots.snapshot(unit, nextVersion(), Status.OK, false,
				UnitMapper.payloads(state).get(seat(event, "s1")));
		Recording sink = new Recording();

		EventMerge.apply(event, plan(List.of(snapshot), List.of()), sink);

		assertEquals(List.of(unit), sink.held, "sie wartet auf ihre Vorstellung, statt in den Vergleichsstand zu gehen");
		assertEquals(Map.of(), sink.adopted);
		assertEquals(1, event.getPresentations().size());
	}

	@Test
	void bookingOfAGoneInvisiblePresentationIsOnlyAdopted() {
		Event event = event();
		UnitKey unit = UnitKey.booking(event.getId(), "fehlt", "s1");
		Event state = copyOfUnchecked(event);
		UnitSnapshot snapshot = Snapshots.snapshot(unit, nextVersion(), Status.OK, false,
				UnitMapper.payloads(state).get(seat(event, "s1")));
		Recording sink = new Recording();
		// die Vorstellung ist gelöscht oder unlesbar: Auf einen Platz wartet nichts
		UnitKey presentation = UnitKey.presentation(event.getId(), "fehlt");
		EventMerge.Plan plan = new EventMerge.Plan(List.of(snapshot), List.of(), id -> null,
				Map.of(presentation, deleted(presentation)));

		EventMerge.apply(event, plan, sink);

		assertEquals(Map.of(unit, false), sink.adopted);
		assertEquals(List.of(), sink.held);
	}

	@Test
	void bookingOfAPresentationDeletedInTheSameBatchIsOnlyAdopted() {
		Event event = event();
		Event state = copyOfUnchecked(event);
		UnitKey presentation = UnitKey.presentation(event.getId(), event.getPresentations().get(0).getId());
		UnitKey unit = seat(event, "s1");
		UnitSnapshot gone = deleted(presentation);
		Recording sink = new Recording();
		// die Vorstellung ist im Modell, verlässt es aber mit diesem Schub; ihre Buchung kommt im selben
		EventMerge.Plan plan = new EventMerge.Plan(List.of(gone, foreign(state, unit)), List.of(), id -> null,
				Map.of(presentation, gone));

		EventMerge.apply(event, plan, sink);

		assertTrue(event.getPresentations().isEmpty());
		assertEquals(Map.of(presentation, false, unit, false), sink.adopted);
		assertEquals(List.of(), sink.held, "nichts wartet auf einen Platz");
	}

	@Test
	void deletedBookingOfAnInvisiblePresentationIsOnlyAdopted() {
		Event event = event();
		UnitKey unit = UnitKey.booking(event.getId(), "fehlt", "s1");
		UnitSnapshot snapshot = Snapshots.snapshot(unit, nextVersion(), Status.OK, true, null);
		Recording sink = new Recording();

		EventMerge.apply(event, plan(List.of(snapshot), List.of()), sink);

		assertEquals(Map.of(unit, false), sink.adopted, "nichts wartet auf einen Platz");
		assertEquals(List.of(), sink.held);
	}

	@Test
	void unitsAreMergedHeadFirstThenPresentationsThenBookings() {
		Event event = event();
		Event state = copyOfUnchecked(event);
		Hall hall = hallWithOneSeat();
		Presentation saturday = inHall(state.addPresentation("Samstag"), hall);
		booking(saturday, seatOf(hall), 1, "Schmitz");
		UnitKey presentationUnit = UnitKey.presentation(event.getId(), saturday.getId());
		UnitKey bookingUnit = UnitKey.booking(event.getId(), saturday.getId(), seatOf(hall));
		UnitSnapshot bookingState = foreign(state, bookingUnit);

		// gemeldet in umgekehrter Reihenfolge: die Buchung kommt mit ihrer Vorstellung aus dem Index
		EventMerge.apply(event, new EventMerge.Plan(List.of(bookingState, foreign(state, presentationUnit)),
				List.of(bookingState), id -> hall, Map.of()), new Recording());

		assertEquals(List.of("Schmitz"),
				event.getPresentations().get(1).getBookings().stream().map(Booking::getLastName).toList());
	}

	@Test
	void newPresentationLeavesOutBookingsOnSeatsItsPlanDoesNotKnow() {
		Event event = event();
		Event state = copyOfUnchecked(event);
		Hall hall = hallWithOneSeat();
		Presentation saturday = inHall(state.addPresentation("Samstag"), hall);
		booking(saturday, "anderer-sitz", 2, "Schmitz");
		UnitKey presentationUnit = UnitKey.presentation(event.getId(), saturday.getId());
		UnitKey bookingUnit = UnitKey.booking(event.getId(), saturday.getId(), "anderer-sitz");
		UnitSnapshot bookingState = foreign(state, bookingUnit);
		Recording sink = new Recording();

		EventMerge.apply(event,
				new EventMerge.Plan(List.of(foreign(state, presentationUnit)), List.of(bookingState), id -> hall, Map.of()), sink);

		Presentation added = event.getPresentations().get(1);
		assertEquals(1, added.getBookings().size(), "nur der leere Platzhalter der Bestuhlung");
		assertFalse(added.getBookings().get(0).hasData());
		assertEquals(List.of(bookingUnit), sink.held);
		assertEquals(Map.of(presentationUnit, true), sink.adopted);
	}

	@Test
	void unassignedBookingWhoseSeatIsInThePlanIsHeld() throws IOException {
		Event event = event();
		Hall hall = hallWithOneSeat();
		Presentation friday = inHall(event.getPresentations().get(0), hall);
		Event before = copyOf(event);
		Event state = copyOfUnchecked(event);
		// "ohne Platz" in einer anderen Bestuhlung, sein alter Sitz ist aber einer der Bestuhlung im Modell
		Booking unassigned = booking(state.getPresentations().get(0), seatOf(hall), 1, "Ohne");
		unassigned.setUnassigned(true);
		UnitKey unit = UnitKey.unassignedBooking(event.getId(), friday.getId(), unassigned.getId());
		Recording sink = new Recording();

		EventMerge.apply(event,
				new EventMerge.Plan(List.of(foreign(state, unit)), List.of(), id -> hall, indexOf(event)), sink);

		assertEquals(List.of(unit), sink.held);
		assertEquals(MAPPER.valueToTree(before), MAPPER.valueToTree(event));
	}

	@Test
	void bookingIsNotPlaceableWhilePresentationModelLagsBehindIndex() {
		Event event = event();
		Hall hall = hallWithOneSeat();
		Hall other = hallWithOneSeat();
		Presentation friday = inHall(event.getPresentations().get(0), hall);
		// der Index-Stand der Vorstellung steht schon in der anderen Halle, das Modell noch in der ersten
		Event state = copyOfUnchecked(event);
		inHall(state.getPresentations().get(0), other);
		booking(state.getPresentations().get(0), seatOf(hall), 1, "Fremd");
		UnitSnapshot presentation = foreign(state, UnitKey.presentation(event.getId(), friday.getId()));
		UnitSnapshot booking = foreign(state, UnitKey.booking(event.getId(), friday.getId(), seatOf(hall)));

		assertFalse(EventMerge.placeable(event, booking, presentation, lookup(hall, other), id -> false));
	}

	@Test
	void bookingIsPlaceableWhenModelMatchesIndexAndSeatExists() {
		Event event = event();
		Hall hall = hallWithOneSeat();
		Presentation friday = inHall(event.getPresentations().get(0), hall);
		Event state = copyOfUnchecked(event);
		booking(state.getPresentations().get(0), seatOf(hall), 1, "Fremd");
		UnitSnapshot presentation = foreign(state, UnitKey.presentation(event.getId(), friday.getId()));
		UnitSnapshot booking = foreign(state, UnitKey.booking(event.getId(), friday.getId(), seatOf(hall)));

		assertTrue(EventMerge.placeable(event, booking, presentation, lookup(hall), id -> false));
		assertFalse(EventMerge.placeable(event, booking, null, lookup(hall), id -> false),
				"ohne Index-Stand der Vorstellung nicht");
		assertFalse(EventMerge.placeable(event, booking, deleted(presentation.unit()), lookup(hall), id -> false),
				"mit gelöschter Vorstellung nicht");
		assertFalse(EventMerge.placeable(event, booking, presentation, lookup(), id -> false), "ohne die Halle nicht");
	}

	@Test
	void unassignedBookingMatchingAFreeSeatByLabelIsNotPlaceable() {
		Event event = event();
		Hall hall = hallWithOneSeat();
		Presentation friday = inHall(event.getPresentations().get(0), hall);
		Event state = copyOfUnchecked(event);
		// "ohne Platz" ohne Sitzkennung, aber Reihe A, Tisch 1, Sitz 1 sind die Beschriftung des Sitzes der Bestuhlung, den im
		// Modell keine Buchung trägt: Der Abgleich setzte sie dorthin (Schritt 2), das nächste Speichern überschriebe den Stand
		Booking matching = booking(state.getPresentations().get(0), null, 1, "Ohne");
		matching.setUnassigned(true);
		Booking elsewhere = booking(state.getPresentations().get(0), null, 7, "Anderswo");
		elsewhere.setUnassigned(true);
		UnitSnapshot presentation = foreign(state, UnitKey.presentation(event.getId(), friday.getId()));
		UnitSnapshot matchingUnit = foreign(state,
				UnitKey.unassignedBooking(event.getId(), friday.getId(), matching.getId()));
		UnitSnapshot elsewhereUnit = foreign(state,
				UnitKey.unassignedBooking(event.getId(), friday.getId(), elsewhere.getId()));

		assertFalse(EventMerge.placeable(event, matchingUnit, presentation, lookup(hall), id -> false));
		assertTrue(EventMerge.placeable(event, elsewhereUnit, presentation, lookup(hall), id -> false),
				"trifft sie keinen Sitz, bleibt sie ohne Platz");
	}

	@Test
	void unassignedBookingMatchingASeatThatAnotherBookingTakesIsPlaceable() {
		Event event = event();
		Hall hall = hallWithOneSeat();
		Presentation friday = inHall(event.getPresentations().get(0), hall);
		// der Sitz der Bestuhlung ist im Modell belegt: Der Abgleich setzte die Buchung nicht um
		booking(friday, seatOf(hall), 1, "Müller");
		Event state = copyOfUnchecked(event);
		Booking matching = booking(state.getPresentations().get(0), null, 1, "Ohne");
		matching.setUnassigned(true);
		UnitSnapshot presentation = foreign(state, UnitKey.presentation(event.getId(), friday.getId()));
		UnitSnapshot unit = foreign(state, UnitKey.unassignedBooking(event.getId(), friday.getId(), matching.getId()));

		assertTrue(EventMerge.placeable(event, unit, presentation, lookup(hall), id -> false));
	}

	/** Ein Event mit einer Vorstellung "Freitag" in der Halle, abgeglichen (der Platzhalter ihres einen Sitzes steht da). */
	private static Event eventInHall(Hall hall) {
		Event event = new Event();
		event.setName("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		friday.setDate(LocalDate.of(2027, 2, 12));
		inHall(friday, hall);
		BookingSync.sync(friday, hall.getSeatingPlans().get(0));
		return event;
	}

	@Test
	void bookingOnASeatOfAnotherPlanIsPlaceableWhenTheHallIsCurrent() {
		Hall hall = hallWithOneSeat();
		Event event = eventInHall(hall);
		Presentation friday = event.getPresentations().get(0);
		Event state = copyOfUnchecked(event);
		booking(state.getPresentations().get(0), "fremder-sitz", 7, "Fremd");
		UnitSnapshot presentation = foreign(state, UnitKey.presentation(event.getId(), friday.getId()));
		UnitSnapshot booking = foreign(state, UnitKey.booking(event.getId(), friday.getId(), "fremder-sitz"));

		assertFalse(EventMerge.placeable(event, booking, presentation, lookup(hall), id -> false));
		assertTrue(EventMerge.placeable(event, booking, presentation, lookup(hall), id -> true));
		assertTrue(EventMerge.waits(event, booking, presentation, lookup(hall), id -> false));
		assertFalse(EventMerge.waits(event, booking, presentation, lookup(hall), id -> true));
	}

	@Test
	void presentationBehindTheIndexStillWaitsEvenWithCurrentHall() {
		Event event = event();
		Hall hall = hallWithOneSeat();
		Hall other = hallWithOneSeat();
		Presentation friday = inHall(event.getPresentations().get(0), hall);
		// der Index-Stand der Vorstellung steht schon in der anderen Halle, das Modell noch in der ersten
		Event state = copyOfUnchecked(event);
		inHall(state.getPresentations().get(0), other);
		booking(state.getPresentations().get(0), "fremder-sitz", 7, "Fremd");
		UnitSnapshot presentation = foreign(state, UnitKey.presentation(event.getId(), friday.getId()));
		UnitSnapshot booking = foreign(state, UnitKey.booking(event.getId(), friday.getId(), "fremder-sitz"));

		assertFalse(EventMerge.placeable(event, booking, presentation, lookup(hall, other), id -> true));
	}

	@Test
	void bookingUnderPresentationWithoutHallIsPlaceable() {
		Event event = event();
		Presentation friday = event.getPresentations().get(0);
		Event state = copyOfUnchecked(event);
		booking(state.getPresentations().get(0), "fremder-sitz", 7, "Fremd");
		UnitSnapshot presentation = foreign(state, UnitKey.presentation(event.getId(), friday.getId()));
		UnitSnapshot booking = foreign(state, UnitKey.booking(event.getId(), friday.getId(), "fremder-sitz"));

		assertTrue(EventMerge.placeable(event, booking, presentation, lookup(), id -> false),
				"keine Halle, auf die zu warten wäre");
	}

	/** Das Einarbeiten einer Buchung auf einem Sitz, den die Bestuhlung nicht hat, in einer aktuellen Halle. */
	private EventMerge.Plan displacedPlan(Event event, Hall hall, Event state, UnitKey unit) {
		return new EventMerge.Plan(List.of(foreign(state, unit)), List.of(), lookup(hall), id -> true, indexOf(event),
				Map.of());
	}

	@Test
	void displacedBookingEndsWithoutPlaceAndIsReported() {
		Hall hall = hallWithOneSeat();
		Event event = eventInHall(hall);
		Presentation friday = event.getPresentations().get(0);
		Event state = copyOfUnchecked(event);
		Booking foreign = booking(state.getPresentations().get(0), "fremder-sitz", 7, "Müller");
		UnitKey unit = UnitKey.booking(event.getId(), friday.getId(), "fremder-sitz");
		Recording sink = new Recording();

		EventMerge.apply(event, displacedPlan(event, hall, state, unit), sink);

		Booking result = friday.getBookings().stream().filter(b -> foreign.getId().equals(b.getId())).findFirst().orElseThrow();
		assertTrue(result.isUnassigned());
		assertEquals("Müller", result.getLastName());
		assertEquals(List.of("Müller steht jetzt ohne Platz: Tisch A1, Sitz 7 gibt es in der Bestuhlung von Vorstellung Freitag nicht."),
				sink.displaced);
		assertEquals(List.of(unit), sink.displacedUnits);
		assertEquals(Map.of(unit, true), sink.adopted);
		assertTrue(sink.held.isEmpty());
	}

	private static Booking byId(Presentation presentation, String id) {
		return presentation.getBookings().stream().filter(b -> id.equals(b.getId())).findFirst().orElseThrow();
	}

	@Test
	void displacedBookingWithoutNameIsReportedAsABooking() {
		Hall hall = hallWithOneSeat();
		Event event = eventInHall(hall);
		Presentation friday = event.getPresentations().get(0);
		Event state = copyOfUnchecked(event);
		// nur ein Kommentar: Die Buchung hat Daten, aber keinen Kunden
		Booking foreign = booking(state.getPresentations().get(0), "fremder-sitz", 7, null);
		foreign.setComment("Rollstuhl");
		UnitKey unit = UnitKey.booking(event.getId(), friday.getId(), "fremder-sitz");
		Recording sink = new Recording();

		EventMerge.apply(event, displacedPlan(event, hall, state, unit), sink);

		assertTrue(byId(friday, foreign.getId()).isUnassigned());
		assertEquals(List.of("Eine Buchung steht jetzt ohne Platz: Tisch A1, Sitz 7 gibt es in der Bestuhlung von Vorstellung Freitag nicht."),
				sink.displaced);
	}

	@Test
	void displacedBookingSetOntoASeatWithTheSameLabelIsNotReported() {
		Hall hall = hallWithOneSeat();
		Event event = eventInHall(hall);
		Presentation friday = event.getPresentations().get(0);
		// der Sitz der Bestuhlung ist im Modell frei (keine Buchung trägt ihn): Nur dann trifft Schritt 2 ihn
		friday.getBookings().clear();
		Event state = copyOfUnchecked(event);
		// unbekannte Sitzkennung, aber Reihe A, Tisch 1, Sitz 1 sind die Beschriftung des Sitzes der Bestuhlung: Schritt 2 des
		// Abgleichs setzt sie dorthin, und das kann nur auf einen Sitz mit derselben Beschriftung geschehen: Der Nutzer sieht
		// keinen Unterschied
		Booking foreign = booking(state.getPresentations().get(0), "fremder-sitz", 1, "Müller");
		UnitKey unit = UnitKey.booking(event.getId(), friday.getId(), "fremder-sitz");
		Recording sink = new Recording();

		EventMerge.apply(event, displacedPlan(event, hall, state, unit), sink);

		Booking result = byId(friday, foreign.getId());
		assertFalse(result.isUnassigned());
		assertEquals(seatOf(hall), result.getSeatId());
		assertEquals("Müller", result.getLastName());
		assertTrue(sink.displaced.isEmpty());
		assertTrue(sink.held.isEmpty());
		assertEquals(Map.of(unit, true), sink.adopted);
	}

	@Test
	void unassignedBookingThatDoesNotFitIsAdoptedAndSyncedWithoutAHint() {
		Hall hall = hallWithOneSeat();
		Event event = eventInHall(hall);
		Presentation friday = event.getPresentations().get(0);
		Event state = copyOfUnchecked(event);
		// "ohne Platz", nennt aber einen Sitz, den die Bestuhlung kennt: Schritt 1 des Abgleichs setzt sie dorthin zurück
		Booking foreign = booking(state.getPresentations().get(0), seatOf(hall), 1, "Müller");
		foreign.setUnassigned(true);
		UnitKey unit = UnitKey.unassignedBooking(event.getId(), friday.getId(), foreign.getId());
		Recording sink = new Recording();

		EventMerge.apply(event, displacedPlan(event, hall, state, unit), sink);

		Booking result = byId(friday, foreign.getId());
		assertFalse(result.isUnassigned(), "der Abgleich hat sie auf ihren Sitz gesetzt");
		assertEquals(seatOf(hall), result.getSeatId());
		assertEquals("Müller", result.getLastName());
		assertTrue(sink.displaced.isEmpty(), "ihr Platz fehlt nicht");
		assertTrue(sink.held.isEmpty());
		assertEquals(Map.of(unit, true), sink.adopted);
	}

	@Test
	void displacedBookingWithTheIdOfAnUnassignedOneIsTheSameBooking() {
		Hall hall = hallWithOneSeat();
		Event event = eventInHall(hall);
		Presentation friday = event.getPresentations().get(0);
		// Müller steht im Modell schon ohne Platz (der Bestuhlungswechsel hat ihn dorthin gestellt) ...
		Booking known = booking(friday, null, 3, "Müller");
		known.setUnassigned(true);
		Event state = copyOfUnchecked(event);
		// ... und der andere Rechner hat ihn gleichzeitig auf einen Sitz umgesetzt, den die Bestuhlung nicht hat
		Booking moved = byId(state.getPresentations().get(0), known.getId());
		moved.setUnassigned(false);
		moved.setSeatId("fremder-sitz");
		moved.setSeatNumber(7);
		UnitKey unit = UnitKey.booking(event.getId(), friday.getId(), "fremder-sitz");
		Recording sink = new Recording();

		EventMerge.apply(event, displacedPlan(event, hall, state, unit), sink);

		assertEquals(1, friday.getBookings().stream().filter(b -> known.getId().equals(b.getId())).count(), "keine zweite daneben");
		Booking result = byId(friday, known.getId());
		assertTrue(result.isUnassigned());
		assertEquals("Müller", result.getLastName());
		assertEquals(List.of("Müller steht jetzt ohne Platz: Tisch A1, Sitz 7 gibt es in der Bestuhlung von Vorstellung Freitag nicht."),
				sink.displaced);
		assertEquals(List.of(unit), sink.reconciled);
	}

	@Test
	void displacedBookingDoesNotOverwriteAValidlySeatedBookingWithTheSameId() {
		Hall hall = hallWithOneSeat();
		Event event = eventInHall(hall);
		Presentation friday = event.getPresentations().get(0);
		// Müller sitzt gültig auf dem Sitz der Bestuhlung
		Booking seated = friday.getBookings().get(0);
		seated.setLastName("Müller");
		String seatId = seated.getSeatId();
		Event state = copyOfUnchecked(event);
		Booking moved = booking(state.getPresentations().get(0), "fremder-sitz", 7, "Müller");
		moved.setId(seated.getId());
		UnitKey unit = UnitKey.booking(event.getId(), friday.getId(), "fremder-sitz");

		EventMerge.apply(event, displacedPlan(event, hall, state, unit), new Recording());

		assertSame(seated, friday.getBookings().stream().filter(b -> b == seated).findFirst().orElseThrow());
		assertFalse(seated.isUnassigned(), "ein gültig sitzender Stand wird nicht überschrieben und nicht entsetzt");
		assertEquals(seatId, seated.getSeatId());
	}

	/** Müller steht ohne Platz im Modell; seine Einheit ist fremd gelöscht, und eine andere Einheit trägt ihn (nach {@code carried}). */
	private Booking cancelledUnassigned(boolean carried, boolean twice, Recording sink) {
		Hall hall = hallWithOneSeat();
		Event event = eventInHall(hall);
		Presentation friday = event.getPresentations().get(0);
		Booking known = booking(friday, null, 3, "Müller");
		known.setUnassigned(true);
		if (twice) {
			Booking duplicate = booking(friday, "anderer-sitz", 4, "Müller");
			duplicate.setId(known.getId());
		}
		UnitKey unit = UnitKey.unassignedBooking(event.getId(), friday.getId(), known.getId());
		EventMerge.Plan plan = new EventMerge.Plan(List.of(deleted(unit)), List.of(), lookup(hall), id -> true, indexOf(event),
				Map.of());
		EventMerge.apply(event, carried ? plan.withCarried(Set.of(unit)) : plan, sink);
		assertEquals(Map.of(unit, false), sink.adopted);
		return known;
	}

	@Test
	void cancelOfAnUnassignedUnitThatAnotherUnitCarriesKeepsTheBooking() {
		Recording sink = new Recording();

		Booking known = cancelledUnassigned(true, false, sink);

		assertTrue(known.getParent().getBookings().contains(known));
		assertEquals(1, sink.reconciled.size());
	}

	@Test
	void cancelOfAnUnassignedUnitNobodyCarriesRemovesTheBooking() {
		Recording sink = new Recording();

		Booking known = cancelledUnassigned(false, false, sink);

		assertFalse(known.getParent().getBookings().contains(known));
		assertTrue(sink.reconciled.isEmpty());
	}

	@Test
	void cancelOfAnUnassignedUnitRemovesTheCopyWhenTheBookingStandsTwice() {
		Recording sink = new Recording();

		Booking known = cancelledUnassigned(true, true, sink);

		assertFalse(known.getParent().getBookings().contains(known), "die zweite trägt die Buchung, diese ist überzählig");
		assertTrue(sink.reconciled.isEmpty());
	}

	@Test
	void displacedBookingIsReplayedLikeTheOriginal() {
		Hall hall = hallWithOneSeat();
		Event event = eventInHall(hall);
		Presentation friday = event.getPresentations().get(0);
		Event copy = copyOfUnchecked(event);
		Event state = copyOfUnchecked(event);
		booking(state.getPresentations().get(0), "fremder-sitz", 7, "Müller");
		UnitKey unit = UnitKey.booking(event.getId(), friday.getId(), "fremder-sitz");
		EventMerge.Plan plan = displacedPlan(event, hall, state, unit);

		EventMerge.apply(event, plan, EventMerge.NONE);
		EventMerge.apply(copy, plan, EventMerge.NONE);

		assertEquals(UnitMapper.payloads(event), UnitMapper.payloads(copy));
	}

	private static Function<String, Hall> lookup(Hall... halls) {
		return id -> {
			for (Hall hall : halls) {
				if (hall.getId().equals(id)) {
					return hall;
				}
			}
			return null;
		};
	}

	/** Eine Halle mit einer Bestuhlung aus einem Tisch mit einem Sitz. */
	private static Hall hallWithOneSeat() {
		Hall hall = new Hall();
		SeatingPlan seating = hall.addSeatingPlan();
		TableRow row = seating.addRow();
		row.setLabel("A");
		seating.fillRow(row, 1, 1);
		return hall;
	}

	private static String seatOf(Hall hall) {
		return hall.getSeatingPlans().get(0).getSeats().get(0).getId();
	}

	private static Presentation inHall(Presentation presentation, Hall hall) {
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId(hall.getSeatingPlans().get(0).getId());
		return presentation;
	}

	private static Event copyOfUnchecked(Event event) {
		try {
			return copyOf(event);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
