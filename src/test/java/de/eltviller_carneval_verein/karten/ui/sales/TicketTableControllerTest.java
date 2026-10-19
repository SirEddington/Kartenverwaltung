package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
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
}
