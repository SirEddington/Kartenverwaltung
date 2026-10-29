package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;

/** Die Anzeigenamen der Einheiten im Sammel-Dialog. */
class ConflictLabelsTest {

	private final Hall hall = new Hall();
	private final Event event = new Event();
	private final Presentation friday;
	private final Seat seat;
	private final Booking seatBooking;
	private final Booking unassigned;

	ConflictLabelsTest() {
		hall.setName("Stadthalle");
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 1, 2);
		seat = plan.getSeats().get(1);
		event.setName("Prunksitzung");
		friday = event.addPresentation("Freitag");
		friday.setHallId(hall.getId());
		friday.setSeatingPlanId(plan.getId());
		seatBooking = friday.addBooking();
		seatBooking.takeSnapshot(seat.getParent(), seat);
		unassigned = friday.addBooking();
		unassigned.setUnassigned(true);
		unassigned.setFirstName("Anna");
		unassigned.setLastName("Müller");
	}

	private Function<UnitKey, String> labels() {
		return ConflictLabels.of(id -> event.getId().equals(id) ? event : null, unit -> {
			if (unit.type() != UnitType.BOOKING) {
				return null;
			}
			if (unit.bookingDir().equals(seat.getId())) {
				return seatBooking;
			}
			return unit.bookingDir().equals("_" + unassigned.getId()) ? unassigned : null;
		}, id -> hall.getId().equals(id) ? hall : null);
	}

	@Test
	void eventAndPresentationAreNamed() {
		assertEquals("Prunksitzung", labels().apply(UnitKey.event(event.getId())));
		assertEquals("Prunksitzung › Freitag", labels().apply(UnitKey.presentation(event.getId(), friday.getId())));
	}

	@Test
	void hallIsNamedByItsName() {
		assertEquals("Stadthalle", labels().apply(UnitKey.hall(hall.getId())));
	}

	@Test
	void bookingOnASeatEndsWithThePlace() {
		assertEquals("Prunksitzung › Freitag › " + seatBooking.getPlaceLabel(),
				labels().apply(UnitKey.booking(event.getId(), friday.getId(), seat.getId())));
	}

	@Test
	void bookingWithoutPlaceEndsWithTheCustomer() {
		assertEquals("Prunksitzung › Freitag › Anna Müller",
				labels().apply(UnitKey.unassignedBooking(event.getId(), friday.getId(), unassigned.getId())));
	}

	@Test
	void unknownEventGivesNoLabel() {
		assertNull(labels().apply(UnitKey.event("gone")));
		assertNull(labels().apply(UnitKey.presentation("gone", "p1")));
		assertNull(labels().apply(UnitKey.hall("gone")));
	}

	@Test
	void unknownPresentationAndBookingFallBackToWhatIsKnown() {
		assertNull(labels().apply(UnitKey.presentation(event.getId(), "gone")));
		assertEquals("Prunksitzung › Freitag › (Kunde unbekannt)",
				labels().apply(UnitKey.unassignedBooking(event.getId(), friday.getId(), "other")));
		assertEquals("Prunksitzung › Freitag › (Platz unbekannt)",
				labels().apply(UnitKey.booking(event.getId(), friday.getId(), "other-seat")));
	}
}
