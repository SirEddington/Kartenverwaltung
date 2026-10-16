package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;

class SeatSelectionSplitTest {

	private final Presentation presentation = new Event().addPresentation("Prunksitzung");
	private final TestBookings bookings = new TestBookings();

	private Booking freeSeat(int number) {
		return bookings.booking(presentation, 1, number);
	}

	private Booking reservedSeat(int number) {
		Booking booking = bookings.booking(presentation, 1, number);
		booking.setLastName("Meier");
		return booking;
	}

	private Booking soldSeat(int number) {
		Booking booking = bookings.booking(presentation, 1, number);
		booking.setPaymentStatus(PaymentStatus.CASH);
		return booking;
	}

	@Test
	void separatesFreeSeatsFromReservedAndSoldOnesKeepingTheOrder() {
		Booking free1 = freeSeat(1);
		Booking reserved = reservedSeat(2);
		Booking free2 = freeSeat(3);
		Booking sold = soldSeat(4);

		SeatSelectionSplit split = SeatSelectionSplit.of(List.of(free1, reserved, free2, sold));

		assertEquals(List.of(free1, free2), split.free());
		assertEquals(List.of(reserved, sold), split.occupied());
		assertTrue(split.hasOccupied());
		assertTrue(split.hasFree());
	}

	@Test
	void onlyFreeSeatsNeedNoQuestion() {
		SeatSelectionSplit split = SeatSelectionSplit.of(List.of(freeSeat(1), freeSeat(2)));

		assertFalse(split.hasOccupied());
		assertTrue(split.hasFree());
	}

	@Test
	void onlyOccupiedSeatsLeaveNothingFree() {
		SeatSelectionSplit split = SeatSelectionSplit.of(List.of(reservedSeat(1), soldSeat(2)));

		assertTrue(split.hasOccupied());
		assertFalse(split.hasFree());
	}
}
