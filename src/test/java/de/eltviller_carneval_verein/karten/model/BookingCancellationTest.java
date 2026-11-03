package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.BookingCancellation.Summary;

/** Stornieren einer Buchung (KARV-121) und die Frage, ob das Löschen des Nachnamens Daten zurücklässt. */
class BookingCancellationTest {

	private static Booking onSeat(Presentation presentation, String lastName) {
		Booking booking = presentation.addBooking();
		booking.setSeatId("s-" + presentation.getBookings().size());
		booking.setRowLabel("A");
		booking.setTableNumber(1);
		booking.setSeatNumber(presentation.getBookings().size());
		booking.setLastName(lastName);
		return booking;
	}

	@Test
	void cancel_macht_die_Buchung_auf_einem_Sitz_zum_leeren_Platzhalter_mit_neuer_Kennung() {
		Presentation presentation = new Presentation();
		Booking booking = onSeat(presentation, "Müller");
		booking.setComment("Fensterplatz");
		booking.setPaymentStatus(PaymentStatus.CASH);
		booking.setPrice(1800);
		String idBefore = booking.getId();

		BookingCancellation.cancel(List.of(booking));

		assertTrue(presentation.getBookings().contains(booking));
		assertFalse(booking.hasData());
		assertEquals(PaymentStatus.NONE, booking.getPaymentStatus());
		assertEquals(null, booking.getPrice(), "Einzelpreis gehört zur stornierten Buchung");
		assertEquals("s-1", booking.getSeatId());
		assertEquals("Tisch A1, Sitz 1", booking.getPlaceLabel());
		assertNotEquals(idBefore, booking.getId());
	}

	@Test
	void cancel_entfernt_eine_Buchung_ohne_Platz_aus_der_Vorstellung() {
		Presentation presentation = new Presentation();
		Booking booking = onSeat(presentation, "Schmidt");
		booking.setUnassigned(true);

		BookingCancellation.cancel(List.of(booking));

		assertFalse(presentation.getBookings().contains(booking));
	}

	@Test
	void cancel_laesst_eine_Buchung_ohne_Daten_unberuehrt() {
		Presentation presentation = new Presentation();
		Booking empty = onSeat(presentation, null);
		empty.setPrice(1800);
		String idBefore = empty.getId();

		BookingCancellation.cancel(List.of(empty));

		assertEquals(idBefore, empty.getId());
		assertEquals(Integer.valueOf(1800), empty.getPrice());
	}

	@Test
	void empty_laesst_eine_Buchung_ohne_Platz_leer_und_ohne_Platz_in_der_Liste() {
		Presentation presentation = new Presentation();
		Booking booking = onSeat(presentation, "Schmidt");
		booking.setUnassigned(true);

		BookingCancellation.empty(booking);

		assertTrue(presentation.getBookings().contains(booking));
		assertFalse(booking.hasData());
		assertTrue(booking.isUnassigned());
		assertTrue(BookingSync.removeIfCancelled(booking));
	}

	@Test
	void leavesData_wenn_der_Name_geht_und_ein_Kommentar_bleibt() {
		Booking booking = new Booking();
		booking.setLastName("Müller");
		booking.setComment("storniert");

		assertTrue(BookingCancellation.leavesData(booking, ""));
		assertTrue(BookingCancellation.leavesData(booking, null));
		assertTrue(BookingCancellation.leavesData(booking, "  "));
	}

	@Test
	void leavesData_ist_falsch_wenn_nichts_uebrig_bleibt() {
		Booking booking = new Booking();
		booking.setLastName("Müller");
		booking.setPrice(1800);
		booking.setReservationDate(java.time.LocalDate.of(2026, 10, 1));

		assertFalse(BookingCancellation.leavesData(booking, ""));
	}

	@Test
	void leavesData_ist_falsch_ohne_bisherigen_Namen_oder_mit_neuem_Namen() {
		Booking noName = new Booking();
		noName.setComment("Notiz");
		assertFalse(BookingCancellation.leavesData(noName, ""), "Name war schon leer");

		Booking renamed = new Booking();
		renamed.setLastName("Müller");
		renamed.setComment("Notiz");
		assertFalse(BookingCancellation.leavesData(renamed, "Meier"), "neuer Name");
	}

	@Test
	void leavesData_bei_bezahlter_Buchung() {
		Booking booking = new Booking();
		booking.setLastName("Müller");
		booking.setPaymentStatus(PaymentStatus.CARD);

		assertTrue(BookingCancellation.leavesData(booking, ""));
	}

	@Test
	void Summary_zaehlt_nur_Buchungen_mit_Daten_samt_bezahlt_Betrag_und_abgeholt() {
		Presentation presentation = new Presentation();
		presentation.changePrice(2000);
		Booking reserved = onSeat(presentation, "Müller");
		Booking paid = onSeat(presentation, "Meier");
		paid.setPaymentStatus(PaymentStatus.CASH);
		Booking paidCollected = onSeat(presentation, "Schulz");
		paidCollected.setPaymentStatus(PaymentStatus.CARD);
		paidCollected.setPaidAmount(1500);
		paidCollected.setCollected(true);
		Booking empty = onSeat(presentation, null);

		Summary summary = Summary.of(List.of(reserved, paid, paidCollected, empty));

		assertEquals(new Summary(3, 2, 3500, 1), summary);
	}
}
