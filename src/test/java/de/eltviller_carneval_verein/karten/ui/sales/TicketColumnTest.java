package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;

class TicketColumnTest {

	private final Event event = new Event();
	private final Presentation first = event.addPresentation("Freitag");
	private final Presentation second = event.addPresentation("Samstag");
	private final TestBookings bookings = new TestBookings();

	private BookingDTO row(Presentation presentation, int table, int seat) {
		return new BookingDTO(event, presentation, bookings.booking(presentation, table, seat));
	}

	private BookingDTO row() {
		return row(first, 1, 1);
	}

	@Test
	void textSpaltenOhneRandUndGrossKlein() {
		BookingDTO a = row();
		a.getBooking().setLastName(" Müller ");
		BookingDTO b = row();
		b.getBooking().setLastName("müller");
		assertEquals(TicketColumn.LAST_NAME.keyOf(a), TicketColumn.LAST_NAME.keyOf(b));
		assertEquals("Müller", TicketColumn.LAST_NAME.textOf(TicketColumn.LAST_NAME.keyOf(a), a));
	}

	@Test
	void leerStehtZuletzt() {
		BookingDTO empty = row();
		BookingDTO filled = row(first, 2, 2);
		filled.getBooking().setLastName("Abel");
		filled.getBooking().setSeatNumber(5);
		filled.getBooking().setPrice(900);
		filled.getBooking().setPaymentStatus(PaymentStatus.CASH);
		filled.getBooking().setPaidAmount(900);
		filled.getBooking().setReservationDate(LocalDate.of(2026, 1, 2));
		empty.getBooking().setSeatNumber(0);
		for (TicketColumn column : List.of(TicketColumn.LAST_NAME, TicketColumn.SEAT, TicketColumn.PAID_AMOUNT, TicketColumn.RESERVED_ON)) {
			assertSame(TicketColumn.EMPTY, column.keyOf(empty), column.name());
			Comparator<Object> order = column.keyOrder(List.of(first, second));
			assertTrue(order.compare(column.keyOf(filled), TicketColumn.EMPTY) < 0, column.name());
			assertTrue(order.compare(TicketColumn.EMPTY, column.keyOf(filled)) > 0, column.name());
			assertEquals("(leer)", column.textOf(TicketColumn.EMPTY, empty));
		}
		assertEquals("(leer)", TicketColumn.EMPTY.toString());
	}

	@Test
	void ohnePlatzIstEineGruppe() {
		BookingDTO a = row(first, 1, 1);
		a.getBooking().setUnassigned(true);
		BookingDTO b = row(first, 7, 3);
		b.getBooking().setUnassigned(true);
		assertEquals(TicketColumn.TABLE.keyOf(a), TicketColumn.TABLE.keyOf(b));
		assertEquals("Ohne Platz", TicketColumn.TABLE.textOf(TicketColumn.TABLE.keyOf(a), a));
		assertTrue(TicketColumn.TABLE.keyOrder(List.of()).compare(TicketColumn.TABLE.keyOf(a), TicketColumn.TABLE.keyOf(row(first, 2, 1))) < 0);
	}

	@Test
	void sitzNullIstLeer() {
		BookingDTO a = row();
		a.getBooking().setSeatNumber(0);
		assertSame(TicketColumn.EMPTY, TicketColumn.SEAT.keyOf(a));
		a.getBooking().setSeatNumber(4);
		assertEquals(4, TicketColumn.SEAT.keyOf(a));
		assertEquals("4", TicketColumn.SEAT.textOf(4, a));
	}

	@Test
	void vorstellungenInEventReihenfolge() {
		BookingDTO a = row(second, 1, 1);
		BookingDTO b = row(first, 1, 2);
		List<Presentation> order = event.getPresentations();
		Comparator<Object> keys = TicketColumn.PRESENTATION.keyOrder(order);
		assertTrue(keys.compare(TicketColumn.PRESENTATION.keyOf(b), TicketColumn.PRESENTATION.keyOf(a)) < 0);
		assertEquals("Samstag", TicketColumn.PRESENTATION.textOf(TicketColumn.PRESENTATION.keyOf(a), a));
		List<BookingDTO> rows = new ArrayList<>(List.of(a, b));
		rows.sort(TicketColumn.baseOrder(order));
		assertEquals(List.of(b, a), rows);
	}

	@Test
	void zahlungsstatusInEnumReihenfolge() {
		Comparator<Object> order = TicketColumn.PAYMENT_STATUS.keyOrder(List.of());
		assertTrue(order.compare(PaymentStatus.NONE, PaymentStatus.TRANSFER) < 0);
		assertTrue(order.compare(PaymentStatus.CARD, PaymentStatus.CASH) > 0);
		assertEquals("Überweisung", TicketColumn.PAYMENT_STATUS.textOf(PaymentStatus.TRANSFER, row()));
	}

	@Test
	void jaVorNein() {
		BookingDTO a = row();
		a.getBooking().setWheelchairAccessible(true);
		Comparator<Object> order = TicketColumn.WHEELCHAIR.keyOrder(List.of());
		assertTrue(order.compare(TicketColumn.WHEELCHAIR.keyOf(a), TicketColumn.WHEELCHAIR.keyOf(row())) < 0);
		assertEquals("Ja", TicketColumn.WHEELCHAIR.textOf(true, a));
		assertEquals("Nein", TicketColumn.COLLECTED.textOf(false, a));
	}

	@Test
	void preisAlsEuroText() {
		BookingDTO a = row();
		a.getBooking().setPrice(1250);
		Object key = TicketColumn.PRICE.keyOf(a);
		assertEquals(1250, key);
		assertEquals(MoneyFormat.formatCents(1250), TicketColumn.PRICE.textOf(key, a));
		assertTrue(TicketColumn.PRICE.keyOrder(List.of()).compare(900, 1250) < 0);
	}

	@Test
	void datumAlsTTMMJJJJ() {
		BookingDTO a = row();
		a.getBooking().setPaymentDate(LocalDate.of(2026, 2, 3));
		Object key = TicketColumn.PAID_ON.keyOf(a);
		assertEquals("03.02.2026", TicketColumn.PAID_ON.textOf(key, a));
		assertTrue(TicketColumn.PAID_ON.keyOrder(List.of()).compare(LocalDate.of(2026, 1, 1), key) < 0);
	}

	@Test
	void bezahltOhneBetragIstLeer() {
		assertSame(TicketColumn.EMPTY, TicketColumn.PAID_AMOUNT.keyOf(row()));
	}

	@Test
	void namenStabil() {
		assertEquals(TicketColumn.LAST_NAME, TicketColumn.valueOf("LAST_NAME"));
		assertEquals("Nachname", TicketColumn.LAST_NAME.header());
		assertEquals(16, TicketColumn.values().length);
	}

	@Test
	void textOrdnungOhneGrossKlein() {
		Comparator<Object> order = TicketColumn.CATEGORY.keyOrder(List.of());
		assertTrue(order.compare("a", "b") < 0);
		assertTrue(order.compare("ä", "b") < 0);
	}

}
