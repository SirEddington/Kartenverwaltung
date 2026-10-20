package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class SaleDatesTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
	private static final LocalDate EARLIER = LocalDate.of(2026, 10, 3);

	@Test
	void neue_reservierung_setzt_heute() {
		Booking booking = new Booking();

		SaleDates.change(booking, b -> b.setLastName("Müller"), TODAY);

		assertEquals(TODAY, booking.getReservationDate());
		assertNull(booking.getPaymentDate());
		assertNull(booking.getCollectionDate());
	}

	@Test
	void namensaenderung_ohne_uebergang_laesst_das_datum_stehen() {
		Booking booking = new Booking();
		booking.setLastName("Müller");
		booking.setReservationDate(EARLIER);

		SaleDates.change(booking, b -> b.setLastName("Meier"), TODAY);

		assertEquals(EARLIER, booking.getReservationDate());
	}

	@Test
	void leerzeichen_als_nachname_ist_keine_reservierung() {
		Booking booking = new Booking();

		SaleDates.change(booking, b -> b.setLastName("  "), TODAY);

		assertNull(booking.getReservationDate());
	}

	@Test
	void vorname_ist_kein_uebergang() {
		Booking booking = new Booking();

		SaleDates.change(booking, b -> b.setFirstName("Anna"), TODAY);

		assertNull(booking.getReservationDate());
	}

	@Test
	void zahlung_setzt_heute_und_zahlartwechsel_aendert_nichts() {
		Booking booking = new Booking();

		SaleDates.change(booking, b -> b.setPaymentStatus(PaymentStatus.CASH), TODAY);
		assertEquals(TODAY, booking.getPaymentDate());

		booking.setPaymentDate(EARLIER);
		SaleDates.change(booking, b -> b.setPaymentStatus(PaymentStatus.CARD), TODAY);
		assertEquals(EARLIER, booking.getPaymentDate());
	}

	@Test
	void zahlung_zuruecknehmen_laesst_das_datum_stehen_und_erneutes_setzen_ueberschreibt() {
		Booking booking = new Booking();
		booking.setPaymentStatus(PaymentStatus.CASH);
		booking.setPaymentDate(EARLIER);

		SaleDates.change(booking, b -> b.setPaymentStatus(PaymentStatus.NONE), TODAY);
		assertEquals(EARLIER, booking.getPaymentDate());

		SaleDates.change(booking, b -> b.setPaymentStatus(PaymentStatus.TRANSFER), TODAY);
		assertEquals(TODAY, booking.getPaymentDate());
	}

	@Test
	void abholung_setzt_heute_und_zuruecknehmen_laesst_stehen() {
		Booking booking = new Booking();

		SaleDates.change(booking, b -> b.setCollected(true), TODAY);
		assertEquals(TODAY, booking.getCollectionDate());

		booking.setCollectionDate(EARLIER);
		SaleDates.change(booking, b -> b.setCollected(false), TODAY);
		assertEquals(EARLIER, booking.getCollectionDate());
	}

	@Test
	void mehrere_uebergaenge_in_einer_aenderung() {
		Booking booking = new Booking();

		SaleDates.change(booking, b -> {
			b.setLastName("Müller");
			b.setPaymentStatus(PaymentStatus.CASH);
		}, TODAY);

		assertEquals(TODAY, booking.getReservationDate());
		assertEquals(TODAY, booking.getPaymentDate());
		assertNull(booking.getCollectionDate());
	}

	@Test
	void ein_im_selben_schritt_gesetztes_datum_gewinnt() {
		Booking booking = new Booking();
		LocalDate typed = LocalDate.of(2026, 10, 1);

		SaleDates.change(booking, b -> {
			b.setPaymentStatus(PaymentStatus.CASH);
			b.setPaymentDate(typed);
		}, TODAY);

		assertEquals(typed, booking.getPaymentDate());
	}

	@Test
	void ohne_datumsangabe_nutzt_change_den_heutigen_tag() {
		Booking booking = new Booking();

		SaleDates.change(booking, b -> b.setCollected(true));

		assertEquals(LocalDate.now(), booking.getCollectionDate());
	}
}
