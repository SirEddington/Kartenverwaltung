package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.event.PresentationTables.Item;

class PresentationTablesTest {

	private final Presentation presentation = new Event().addPresentation("Samstag");

	private Booking booking(String rowLabel, int tableNumber, int seatNumber) {
		Booking booking = presentation.addBooking();
		booking.setRowLabel(rowLabel);
		booking.setTableNumber(tableNumber);
		booking.setSeatNumber(seatNumber);
		return booking;
	}

	private Booking unassigned(String rowLabel, int tableNumber, int seatNumber) {
		Booking booking = booking(rowLabel, tableNumber, seatNumber);
		booking.setUnassigned(true);
		return booking;
	}

	@Test
	void ohne_Buchungen_gibt_es_keine_Tische() {
		assertTrue(PresentationTables.items(presentation).isEmpty());
	}

	@Test
	void Tische_stehen_in_Reihenfolge_der_Plaetze_und_ihre_Sitze_nach_Nummer() {
		Booking a10s2 = booking("A", 10, 2);
		Booking a10s1 = booking("A", 10, 1);
		Booking a2s1 = booking("A", 2, 1);
		Booking b1s1 = booking("B", 1, 1);
		Booking t5s1 = booking("", 5, 1);

		List<Item> items = PresentationTables.items(presentation);

		assertEquals(List.of("Tisch 5", "Tisch A2", "Tisch A10", "Tisch B1"), items.stream().map(Item::label).toList());
		assertEquals(List.of(t5s1), items.get(0).bookings());
		assertEquals(List.of(a2s1), items.get(1).bookings());
		assertEquals(List.of(a10s1, a10s2), items.get(2).bookings());
		assertEquals(List.of(b1s1), items.get(3).bookings());
		assertTrue(items.stream().noneMatch(Item::unassigned));
	}

	@Test
	void Buchungen_ohne_Platz_stehen_als_erste_Gruppe_und_nicht_bei_den_Tischen() {
		booking("A", 3, 1);
		Booking without2 = unassigned("A", 3, 2);
		Booking without1 = unassigned("A", 3, 1);

		List<Item> items = PresentationTables.items(presentation);

		assertEquals(List.of("Ohne Platz", "Tisch A3"), items.stream().map(Item::label).toList());
		assertTrue(items.get(0).unassigned());
		assertFalse(items.get(1).unassigned());
		assertEquals(List.of(without1, without2), items.get(0).bookings());
		assertEquals(1, items.get(1).seats());
	}

	@Test
	void Belegt_zaehlt_nur_Buchungen_mit_Daten() {
		booking("A", 3, 1).setLastName("Müller");
		booking("A", 3, 2).setCollected(true);
		booking("A", 3, 3).setComment(" ");
		booking("A", 3, 4);

		Item table = PresentationTables.items(presentation).get(0);

		assertEquals(4, table.seats());
		assertEquals(2, table.occupied());
	}

	@Test
	void Sitztext_nennt_bei_einer_Buchung_ohne_Platz_den_bisherigen_Tisch() {
		assertEquals("Sitz 2", PresentationTables.seatText(booking("A", 3, 2)));
		assertEquals("Sitz 2 (bisher Tisch A3)", PresentationTables.seatText(unassigned("A", 3, 2)));
	}
}
