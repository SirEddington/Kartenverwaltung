package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

class TableLabelTest {

	private final Presentation presentation = new Event().addPresentation("Samstag");
	private final TestBookings bookings = new TestBookings();

	private Booking unassigned(String rowLabel, int tableNumber, int seatNumber) {
		Booking booking = bookings.booking(presentation, rowLabel, tableNumber, seatNumber);
		booking.setUnassigned(true);
		return booking;
	}

	@Test
	void showsRowLetterAndTableNumber() {
		assertEquals("A3", TableLabel.of(bookings.booking(presentation, "A", 3, 1)).toString());
		assertEquals("3", TableLabel.of(bookings.booking(presentation, 3, 1)).toString());
	}

	@Test
	void allBookingsWithoutPlaceShareOneGroup() {
		TableLabel first = TableLabel.of(unassigned("A", 3, 1));
		TableLabel second = TableLabel.of(unassigned("B", 7, 2));

		assertEquals(first, second);
		assertEquals("Ohne Platz", first.toString());
		assertNotEquals(TableLabel.of(bookings.booking(presentation, "A", 3, 1)), first);
	}

	@Test
	void placeOfNamesTheFormerTableOfABookingWithoutPlace() {
		assertEquals("A3", TableLabel.placeOf(unassigned(" A ", 3, 1)));
		assertEquals("7", TableLabel.placeOf(bookings.booking(presentation, 7, 1)));
	}

	@Test
	void sortsWithoutPlaceFirstThenLikeThePlaces() {
		List<TableLabel> labels = new ArrayList<>(List.of(TableLabel.of(bookings.booking(presentation, "AA", 1, 1)), TableLabel.of(bookings.booking(presentation, "B", 2, 1)),
				TableLabel.of(bookings.booking(presentation, "A", 10, 1)), TableLabel.of(unassigned("Z", 9, 1)), TableLabel.of(bookings.booking(presentation, 5, 1)),
				TableLabel.of(bookings.booking(presentation, "A", 2, 1))));

		labels.sort(null);

		assertEquals(List.of("Ohne Platz", "5", "A2", "A10", "B2", "AA1"), labels.stream().map(TableLabel::toString).toList());
	}

	@Test
	void bookingOrderPutsBookingsWithoutPlaceFirst() {
		Booking a1 = bookings.booking(presentation, "A", 1, 1);
		Booking lost = unassigned("A", 1, 2);
		Booking none2 = bookings.booking(presentation, 2, 1);

		List<Booking> sorted = new ArrayList<>(List.of(a1, none2, lost));
		sorted.sort(TableLabel.BOOKING_ORDER);

		assertEquals(List.of(lost, none2, a1), sorted);
	}
}
