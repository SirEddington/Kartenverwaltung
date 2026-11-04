package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.validation.BookingValidator;

/** Preiseingabe in der Ticket-Tabelle: Euro in Cent, danach prüft der {@link BookingValidator}. */
class TicketTableControllerTest {

	private static Booking booking() {
		Booking booking = new Event().addPresentation("Prunksitzung").addBooking();
		booking.setSeatId("sitz-1");
		booking.setTableNumber(4);
		booking.setSeatNumber(2);
		return booking;
	}

	@Test
	void hugeEnteredPricesAreClampedAndStillRejected() {
		Booking booking = booking();

		booking.setEffectivePrice(TicketTableController.toCents(1e300));
		assertEquals("SEA-003", BookingValidator.validate(booking).mostSevere().orElseThrow().code());

		booking.setEffectivePrice(TicketTableController.toCents(-1e300));
		assertEquals("SEA-001", BookingValidator.validate(booking).mostSevere().orElseThrow().code());
	}

	@Test
	void priceEnteredInEuroIsStoredInCentsAndChecked() {
		Booking booking = booking();

		booking.setEffectivePrice(TicketTableController.toCents(12.5));
		assertEquals(1250, booking.getEffectivePrice());
		assertTrue(BookingValidator.validate(booking).isValid());

		booking.setEffectivePrice(TicketTableController.toCents(-5.0));
		assertFalse(BookingValidator.validate(booking).isValid());
	}

	/** KARV-111: Die Zeile eines gesperrten Sitzes fehlt; die übrigen bleiben in Platzreihenfolge. */
	@Test
	void shownBookingsLeaveOutALockedSeat() {
		SeatingPlan plan = new Hall().addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		Table table = plan.addTable(row);
		Seat first = table.addSeat(1);
		Seat middle = table.addSeat(2);
		Seat last = table.addSeat(3);
		Event event = new Event();
		Presentation presentation = event.addPresentation("Samstag");
		presentation.setSeatingPlanId(plan.getId());
		BookingSync.sync(presentation, plan);

		assertEquals(List.of(first.getId(), middle.getId(), last.getId()), seatIds(TicketTableController.shownBookings(presentation)));

		LockedSeats.install(() -> Set.of(UnitKey.booking(event.getId(), presentation.getId(), middle.getId())), () -> {
		});
		assertEquals(List.of(first.getId(), last.getId()), seatIds(TicketTableController.shownBookings(presentation)));
	}

	private static List<String> seatIds(List<Booking> bookings) {
		return bookings.stream().map(Booking::getSeatId).toList();
	}

	@AfterEach
	void forgetLockedSeats() {
		LockedSeats.reset();
	}
}
