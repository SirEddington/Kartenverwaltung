package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BookingTest {

	private static Booking bookingAt(String rowLabel, int tableNumber, int seatNumber) {
		Booking booking = new Booking();
		booking.setRowLabel(rowLabel);
		booking.setTableNumber(tableNumber);
		booking.setSeatNumber(seatNumber);
		return booking;
	}

	@Test
	void hasData_ist_wahr_bei_Nachname() {
		Booking booking = new Booking();
		booking.setLastName("Müller");
		assertTrue(booking.hasData());
	}

	@Test
	void hasData_ist_wahr_bei_Vorname_und_EMail() {
		Booking first = new Booking();
		first.setFirstName("Anna");
		assertTrue(first.hasData());

		Booking mail = new Booking();
		mail.setEMail("a@b.de");
		assertTrue(mail.hasData());
	}

	@Test
	void hasData_ist_wahr_bei_Kommentar() {
		Booking booking = new Booking();
		booking.setComment("Rollstuhl");
		assertTrue(booking.hasData());
	}

	@Test
	void hasData_ist_wahr_bei_Zahlstatus_und_bei_abgeholt() {
		Booking paid = new Booking();
		paid.setPaymentStatus(PaymentStatus.CASH);
		assertTrue(paid.hasData());

		Booking collected = new Booking();
		collected.setCollected(true);
		assertTrue(collected.hasData());
	}

	@Test
	void hasData_ist_falsch_bei_leerer_Buchung_und_bei_blanken_Texten() {
		assertFalse(new Booking().hasData());

		Booking blank = new Booking();
		blank.setLastName("  ");
		blank.setComment("");
		assertFalse(blank.hasData());
	}

	@Test
	void hasData_ist_falsch_bei_bloszem_Einzelpreis() {
		Booking booking = new Booking();
		booking.setPrice(1800);
		assertFalse(booking.hasData());
	}

	@Test
	void getStatus_ist_FREE_RESERVED_oder_SOLD_wie_bei_Seat() {
		Booking booking = new Booking();
		assertEquals(SeatStatus.FREE, booking.getStatus());
		assertFalse(booking.isReserved());
		assertFalse(booking.isPaid());

		booking.setLastName("Müller");
		assertEquals(SeatStatus.RESERVED, booking.getStatus());
		assertTrue(booking.isReserved());

		booking.setPaymentStatus(PaymentStatus.CARD);
		assertEquals(SeatStatus.SOLD, booking.getStatus());
		assertTrue(booking.isPaid());
	}

	@Test
	void getStatus_bezahlt_ohne_Nachnamen_ist_SOLD() {
		Booking booking = new Booking();
		booking.setPaymentStatus(PaymentStatus.TRANSFER);
		assertEquals(SeatStatus.SOLD, booking.getStatus());
	}

	@Test
	void Zahlstatus_ist_standardmaessig_NONE() {
		Booking booking = new Booking();
		assertEquals(PaymentStatus.NONE, booking.getPaymentStatus());
		assertEquals(PaymentStatus.NONE, booking.getPaymentStatusProperty().get());
	}

	@Test
	void takeSnapshot_mit_Reihe_liefert_Tisch_A3_Sitz_2() {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		Table table = plan.addTable(row);
		table.setTableNumber(3);
		table.setCategory("Premium");
		Seat seat = table.addSeat(2);
		seat.setWheelchairAccessible(true);

		Booking booking = new Booking();
		booking.takeSnapshot(table, seat);

		assertEquals(seat.getId(), booking.getSeatId());
		assertEquals("A", booking.getRowLabel());
		assertEquals(3, booking.getTableNumber());
		assertEquals(2, booking.getSeatNumber());
		assertEquals("Premium", booking.getCategory());
		assertTrue(booking.isWheelchairAccessible());
		assertEquals("Tisch A3", booking.getTableLabel());
		assertEquals("Tisch A3, Sitz 2", booking.getPlaceLabel());
	}

	@Test
	void takeSnapshot_ohne_Reihe_liefert_Tisch_3_Sitz_2() {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("");
		Table table = plan.addTable(row);
		table.setTableNumber(3);
		Seat seat = table.addSeat(2);

		Booking booking = new Booking();
		booking.takeSnapshot(table, seat);

		assertEquals("", booking.getRowLabel());
		assertEquals("Tisch 3, Sitz 2", booking.getPlaceLabel());
	}

	@Test
	void takeSnapshot_ohne_Bestuhlung_am_Tisch_hat_keinen_Reihenbuchstaben() {
		Table table = new Table();
		table.setTableNumber(5);
		Seat seat = new Seat();
		seat.setSeatNumber(1);

		Booking booking = new Booking();
		booking.takeSnapshot(table, seat);

		assertEquals("", booking.getRowLabel());
		assertEquals("Tisch 5, Sitz 1", booking.getPlaceLabel());
	}

	@Test
	void getEffectivePrice_nimmt_Einzelpreis_sonst_Standardpreis_sonst_0() {
		Booking loose = new Booking();
		assertEquals(0, loose.getEffectivePrice());

		Presentation presentation = new Presentation();
		presentation.setPrice(2500);
		Booking booking = presentation.addBooking();
		assertEquals(2500, booking.getEffectivePrice());

		booking.setPrice(1800);
		assertEquals(1800, booking.getEffectivePrice());
	}

	@Test
	void setEffectivePrice_auf_Standardpreis_setzt_price_auf_null() {
		Presentation presentation = new Presentation();
		presentation.setPrice(2500);
		Booking booking = presentation.addBooking();
		booking.setPrice(1800);

		booking.setEffectivePrice(2500);

		assertNull(booking.getPrice());
		assertEquals(2500, booking.getEffectivePrice());
	}

	@Test
	void setEffectivePrice_auf_anderen_Wert_setzt_price() {
		Presentation presentation = new Presentation();
		presentation.setPrice(2500);
		Booking booking = presentation.addBooking();

		booking.setEffectivePrice(1800);

		assertEquals(Integer.valueOf(1800), booking.getPrice());
	}

	@Test
	void PLACE_ORDER_sortiert_A2_vor_A10_und_Tische_ohne_Buchstaben_vor_A() {
		Booking a10 = bookingAt("A", 10, 1);
		Booking a2 = bookingAt("A", 2, 1);
		Booking none3 = bookingAt("", 3, 1);
		Booking noneNull = bookingAt(null, 1, 2);
		Booking b1 = bookingAt("B", 1, 1);
		Booking a2s2 = bookingAt("A", 2, 2);

		List<Booking> sorted = new ArrayList<>(List.of(b1, a10, a2s2, none3, a2, noneNull));
		sorted.sort(Booking.PLACE_ORDER);

		assertEquals(List.of(noneNull, none3, a2, a2s2, a10, b1), sorted);
	}

	@Test
	void PLACE_ORDER_sortiert_Reihen_wie_Excel_Spalten() {
		Booking aa = bookingAt("AA", 1, 1);
		Booking z = bookingAt("Z", 1, 1);
		Booking none = bookingAt("", 1, 1);
		Booking b = bookingAt("B", 1, 1);
		Booking a = bookingAt("A", 1, 1);

		List<Booking> sorted = new ArrayList<>(List.of(aa, z, none, b, a));
		sorted.sort(Booking.PLACE_ORDER);

		assertEquals(List.of(none, a, b, z, aa), sorted);
	}

	@Test
	void PLACE_ORDER_vergleicht_Reihenbuchstaben_ohne_Gross_und_Kleinschreibung() {
		Booking lowerA = bookingAt("a", 2, 1);
		Booking upperB = bookingAt("B", 1, 1);
		Booking upperA = bookingAt("A", 1, 1);

		List<Booking> sorted = new ArrayList<>(List.of(upperB, lowerA, upperA));
		sorted.sort(Booking.PLACE_ORDER);

		assertEquals(List.of(upperA, lowerA, upperB), sorted);
	}

	@Test
	void unassigned_ist_standardmaessig_falsch_und_aenderbar() {
		Booking booking = new Booking();
		assertFalse(booking.isUnassigned());
		booking.setUnassigned(true);
		assertTrue(booking.isUnassigned());
	}

	@Test
	void copy_uebernimmt_alle_Felder_in_ein_neues_Objekt() {
		Booking booking = bookingAt("A", 3, 2);
		booking.setSeatId("s-1");
		booking.setCategory("Premium");
		booking.setWheelchairAccessible(true);
		booking.setPrice(1800);
		booking.setPaymentStatus(PaymentStatus.CASH);
		booking.setCollected(true);
		booking.setLastName("Müller");
		booking.setFirstName("Anna");
		booking.setEMail("a@b.de");
		booking.setComment("Hinweis");

		Booking copy = booking.copy();

		assertNotSame(booking, copy);
		assertEquals("s-1", copy.getSeatId());
		assertEquals("A", copy.getRowLabel());
		assertEquals(3, copy.getTableNumber());
		assertEquals(2, copy.getSeatNumber());
		assertEquals("Premium", copy.getCategory());
		assertTrue(copy.isWheelchairAccessible());
		assertEquals(Integer.valueOf(1800), copy.getPrice());
		assertEquals(PaymentStatus.CASH, copy.getPaymentStatus());
		assertTrue(copy.isCollected());
		assertEquals("Müller", copy.getLastName());
		assertEquals("Anna", copy.getFirstName());
		assertEquals("a@b.de", copy.getEMail());
		assertEquals("Hinweis", copy.getComment());

		copy.setLastName("Meier");
		assertEquals("Müller", booking.getLastName());
	}
}
