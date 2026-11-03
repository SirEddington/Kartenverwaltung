package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;

/** Der Text der Rückfrage, wenn der Nachname gelöscht wird und andere Daten bleiben (KARV-121). */
class NameRemovalDialogTest {

	private final Presentation presentation = new Event().addPresentation("Prunksitzung");
	private final TestBookings bookings = new TestBookings();

	/** Der Stand nach dem Löschen des Namens. */
	private Booking withoutName(int number) {
		return bookings.booking(presentation, "A", 1, number);
	}

	@Test
	void messageListsWhatRemainsOfASingleBooking() {
		Booking booking = withoutName(1);
		booking.setFirstName("Anna");
		booking.setEMail("a@b.de");
		booking.setComment("storniert");

		assertEquals("Der Name ist gelöscht, aber andere Daten bleiben (Vorname, E-Mail, Kommentar). Buchung stornieren?",
				NameRemovalDialog.message(List.of(booking)));
	}

	@Test
	void messageForASinglePaidBookingWarnsAboutPayment() {
		presentation.changePrice(2000);
		Booking booking = withoutName(1);
		booking.setPaymentStatus(PaymentStatus.CASH);
		booking.setCollected(true);

		assertEquals("Der Name ist gelöscht, aber andere Daten bleiben (Zahlung, abgeholt). Buchung stornieren?\n"
				+ "Die Buchung ist bezahlt (20,00 €) und abgeholt – an die Rückzahlung denken.",
				NameRemovalDialog.message(List.of(booking)));
	}

	@Test
	void messageForSeveralBookings() {
		Booking first = withoutName(1);
		first.setComment("Notiz");
		Booking second = withoutName(2);
		second.setFirstName("Anna");

		assertEquals("Bei 2 Buchungen wird der Name gelöscht, andere Daten bleiben. Buchungen stornieren?",
				NameRemovalDialog.message(List.of(first, second)));
	}
}
