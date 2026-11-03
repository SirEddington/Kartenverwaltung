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

/** Texte und Sperre des Menüpunkts "Stornieren" (KARV-121). */
class CancelActionTest {

	private final Presentation presentation = new Event().addPresentation("Prunksitzung");
	private final TestBookings bookings = new TestBookings();

	private Booking seat(int number, String firstName, String lastName) {
		Booking booking = bookings.booking(presentation, "A", 1, number);
		booking.setFirstName(firstName);
		booking.setLastName(lastName);
		return booking;
	}

	@Test
	void menuTextNamesOneBookingOrTheCount() {
		assertEquals("Buchung stornieren…", CancelAction.menuText(1));
		assertEquals("Ausgewählte Buchungen stornieren… (3)", CancelAction.menuText(3));
	}

	@Test
	void onlyBookingsWithDataCanBeCancelled() {
		Booking empty = seat(1, null, null);
		Booking withComment = seat(2, null, null);
		withComment.setComment("Notiz");

		assertFalse(CancelAction.anyWithData(List.of(empty)));
		assertTrue(CancelAction.anyWithData(List.of(empty, withComment)));
	}

	@Test
	void questionNamesCustomerAndPlaceOfASingleBooking() {
		Booking booking = seat(2, "Anna", "Müller");

		assertEquals("Buchung von Anna Müller (Tisch A1, Sitz 2) stornieren?\n"
				+ "Name, Zahlung und Kommentar werden gelöscht, der Platz ist wieder frei. Zurück nur über die Sicherungen des Events.",
				CancelAction.question(List.of(booking)));
	}

	@Test
	void questionForABookingWithoutPlaceOrWithoutName() {
		Booking unassigned = seat(1, null, "Schmidt");
		unassigned.setUnassigned(true);
		Booking commentOnly = seat(2, null, null);
		commentOnly.setComment("storniert");

		assertTrue(CancelAction.question(List.of(unassigned)).startsWith("Buchung von Schmidt (ohne Platz) stornieren?\n"));
		assertTrue(CancelAction.question(List.of(commentOnly)).startsWith("Buchung (Tisch A1, Sitz 2) stornieren?\n"));
	}

	@Test
	void questionCountsOnlyBookingsWithDataAndWarnsAboutPayment() {
		presentation.changePrice(2000);
		Booking reserved = seat(1, null, "Müller");
		Booking paid = seat(2, null, "Meier");
		paid.setPaymentStatus(PaymentStatus.CASH);
		Booking collected = seat(3, null, "Schulz");
		collected.setPaymentStatus(PaymentStatus.CARD);
		collected.setPaidAmount(2500);
		collected.setCollected(true);
		Booking empty = seat(4, null, null);

		assertEquals("3 Buchungen stornieren?\n"
				+ "Name, Zahlung und Kommentar werden gelöscht, die Plätze sind wieder frei. Zurück nur über die Sicherungen des Events.\n"
				+ "Davon 2 bezahlt (zusammen 45,00 €) und 1 abgeholt – an die Rückzahlung denken.",
				CancelAction.question(List.of(reserved, paid, collected, empty)));
	}

	@Test
	void paymentHintForASingleBooking() {
		presentation.changePrice(2000);
		Booking paid = seat(1, null, "Meier");
		paid.setPaymentStatus(PaymentStatus.CASH);
		Booking collectedOnly = seat(2, null, "Schulz");
		collectedOnly.setCollected(true);

		assertEquals("Die Buchung ist bezahlt (20,00 €) – an die Rückzahlung denken.", CancelAction.paymentHint(List.of(paid)));
		assertEquals("Die Buchung ist abgeholt.", CancelAction.paymentHint(List.of(collectedOnly)));
		assertEquals("", CancelAction.paymentHint(List.of(seat(3, null, "Becker"))));
	}
}
