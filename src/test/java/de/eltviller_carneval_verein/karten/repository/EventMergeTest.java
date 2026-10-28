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
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
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
		return new UnitSnapshot(unit, nextVersion(), Status.OK, false, UnitMapper.payloads(state).get(unit));
	}

	private UnitSnapshot deleted(UnitKey unit) {
		return new UnitSnapshot(unit, nextVersion(), Status.OK, true, JsonNodeFactory.instance.objectNode());
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
	void bookingOnUnknownSeatIsHeldAndLeavesTheModelAlone() throws IOException {
		Event event = event();
		Event before = copyOf(event);
		Event state = copyOfUnchecked(event);
		booking(state.getPresentations().get(0), "s9", 9, "Fremd");
		Recording sink = new Recording();

		EventMerge.apply(event, plan(event, List.of(foreign(state, seat(event, "s9"))), List.of()), sink);

		assertEquals(List.of(seat(event, "s9")), sink.held);
		assertTrue(sink.adopted.isEmpty());
		assertEquals(MAPPER.valueToTree(before), MAPPER.valueToTree(event));
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
		UnitSnapshot snapshot = new UnitSnapshot(unit, nextVersion(), Status.OK, false,
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
		UnitSnapshot snapshot = new UnitSnapshot(unit, nextVersion(), Status.OK, false,
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
		UnitSnapshot snapshot = new UnitSnapshot(unit, nextVersion(), Status.OK, true, null);
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

		assertFalse(EventMerge.placeable(event, booking, presentation, lookup(hall, other)));
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

		assertTrue(EventMerge.placeable(event, booking, presentation, lookup(hall)));
		assertFalse(EventMerge.placeable(event, booking, null, lookup(hall)), "ohne Index-Stand der Vorstellung nicht");
		assertFalse(EventMerge.placeable(event, booking, deleted(presentation.unit()), lookup(hall)),
				"mit gelöschter Vorstellung nicht");
		assertFalse(EventMerge.placeable(event, booking, presentation, lookup()), "ohne die Halle nicht");
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

		assertFalse(EventMerge.placeable(event, matchingUnit, presentation, lookup(hall)));
		assertTrue(EventMerge.placeable(event, elsewhereUnit, presentation, lookup(hall)),
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

		assertTrue(EventMerge.placeable(event, unit, presentation, lookup(hall)));
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
