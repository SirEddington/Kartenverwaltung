package de.eltviller_carneval_verein.karten.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;

class BookingTotalsTest {

	private final Presentation presentation = new Event().addPresentation("Samstag");

	private Booking booking(int seatNumber) {
		Booking booking = presentation.addBooking();
		booking.setRowLabel("A");
		booking.setTableNumber(1);
		booking.setSeatNumber(seatNumber);
		return booking;
	}

	@Test
	void countPaidReservedAndAllWithEffectivePrice() {
		presentation.changePrice(1000);
		Booking paid = booking(1);
		paid.setLastName("A");
		paid.setPaymentStatus(PaymentStatus.CASH);
		Booking reserved = booking(2);
		reserved.setLastName("B");
		reserved.setEffectivePrice(500);
		booking(3);
		Booking withoutPlace = booking(4);
		withoutPlace.setUnassigned(true);
		withoutPlace.setLastName("C");
		withoutPlace.setPaymentStatus(PaymentStatus.CARD);
		withoutPlace.setEffectivePrice(2500);

		BookingTotals totals = BookingTotals.of(presentation.getBookings());

		assertEquals(2, totals.paid());
		assertEquals(3, totals.reserved());
		assertEquals(4, totals.total());
		assertEquals(1000 + 2500, totals.revenueCents());
		assertEquals(1000 + 500 + 2500, totals.expectedCents());
		assertEquals(1000 + 500 + 1000 + 2500, totals.potentialCents());
		assertEquals("2 / 3 / 4", totals.countText());
		assertEquals("35,00 € / 40,00 € / 50,00 €", totals.revenueText());
	}

	@Test
	void totalsOfNothingAreZero() {
		BookingTotals totals = BookingTotals.of(List.of());

		assertEquals(0, totals.total());
		assertEquals("0 / 0 / 0", totals.countText());
		assertEquals("0,00 € / 0,00 € / 0,00 €", totals.revenueText());
	}

	@Test
	void paidTotalsSplitByPaymentMethod() {
		presentation.changePrice(1000);
		booking(1).setPaymentStatus(PaymentStatus.CASH);
		booking(2).setPaymentStatus(PaymentStatus.CASH);
		Booking card = booking(3);
		card.setPaymentStatus(PaymentStatus.CARD);
		card.setEffectivePrice(1500);
		booking(4);
		List<Booking> bookings = presentation.getBookings();

		assertEquals(2, BookingTotals.paidCount(bookings, PaymentStatus.CASH));
		assertEquals(2000, BookingTotals.paidCents(bookings, PaymentStatus.CASH));
		assertEquals(1500, BookingTotals.paidCents(bookings, PaymentStatus.CARD));
		assertEquals(0, BookingTotals.paidCount(bookings, PaymentStatus.TRANSFER));
		assertEquals(0, BookingTotals.paidCount(bookings, PaymentStatus.NONE));
	}
}
