package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.event.BookingTree.TableGroup;

class BookingTreeTest {

	private final Presentation presentation = new Event().addPresentation("Samstag");

	/** Buchung nur mit Momentaufnahme (keine Bestuhlung nötig), wie bei einer eingefrorenen Vorstellung. */
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
	void groupsBookingsByTableInPlaceOrder() {
		Booking a3s2 = booking("A", 3, 2);
		Booking a3s1 = booking("A", 3, 1);
		Booking t3s1 = booking("", 3, 1);
		unassigned("B", 7, 1);

		List<TableGroup> groups = BookingTree.groups(presentation);

		assertEquals(List.of("Tisch 3", "Tisch A3"), groups.stream().map(TableGroup::label).toList());
		assertEquals(List.of(t3s1), groups.get(0).bookings());
		assertEquals(List.of(a3s1, a3s2), groups.get(1).bookings());
	}

	@Test
	void unassignedReturnsOnlyBookingsWithoutPlace() {
		booking("A", 3, 1);
		Booking without = unassigned("A", 4, 2);

		List<Booking> unassigned = BookingTree.unassigned(presentation);

		assertEquals(1, unassigned.size());
		assertSame(without, unassigned.get(0));
	}

	@Test
	void bookingsWithoutPlaceAreNotCountedAsTable() {
		unassigned("A", 3, 1);
		unassigned("A", 3, 2);

		assertTrue(BookingTree.groups(presentation).isEmpty());
		assertEquals(2, BookingTree.unassigned(presentation).size());
	}

	@Test
	void frozenPresentationWithoutPlanGivesTheSameGroups() {
		booking("A", 3, 1);
		booking("A", 3, 2);
		booking("", 3, 1);
		presentation.setSeatingPlanId("gibt-es-nicht");
		presentation.setPlanMissing(true);

		List<TableGroup> groups = BookingTree.groups(presentation);

		assertEquals(List.of("Tisch 3", "Tisch A3"), groups.stream().map(TableGroup::label).toList());
		assertTrue(presentation.getTables().isEmpty());
	}

	@Test
	void presentationWithoutBookingsHasNoGroups() {
		assertTrue(BookingTree.groups(presentation).isEmpty());
		assertTrue(BookingTree.unassigned(presentation).isEmpty());
	}

	@Test
	void tolerantAgainstMissingBookingListAndRowLabel() {
		presentation.setBookings(null);
		assertTrue(BookingTree.groups(presentation).isEmpty());
		assertTrue(BookingTree.unassigned(presentation).isEmpty());

		presentation.setBookings(new java.util.ArrayList<>());
		Booking noRow = presentation.addBooking();
		noRow.setRowLabel(null);
		noRow.setTableNumber(2);
		noRow.setSeatNumber(1);

		assertEquals(List.of("Tisch 2"), BookingTree.groups(presentation).stream().map(TableGroup::label).toList());
	}

	@Test
	void bookingLabelShowsSeatOrFormerPlace() {
		Booking seated = booking("A", 3, 2);
		Booking without = unassigned("A", 3, 2);

		assertEquals("Sitz 2", BookingTree.bookingLabel(seated));
		assertEquals("Ohne Platz (bisher Tisch A3, Sitz 2)", BookingTree.bookingLabel(without));
	}

	@Test
	void bookingSearchTextContainsSeatNameAndCommentButNeverNull() {
		Booking booking = booking("A", 3, 2);
		assertEquals("Sitz 2", BookingTree.bookingSearchText(booking));

		booking.setFirstName("Anna");
		booking.setLastName("Muster");
		booking.setComment("Fenster");
		assertEquals("Sitz 2 Anna Muster Fenster", BookingTree.bookingSearchText(booking));
	}

	@Test
	void statusTextUsesThePaymentWording() {
		Booking booking = booking("A", 3, 1);
		assertEquals("Frei", BookingTree.statusText(booking));

		booking.setLastName("Muster");
		assertEquals("Reserviert", BookingTree.statusText(booking));

		booking.setPaymentStatus(PaymentStatus.CASH);
		assertEquals("Bezahlt (Bar)", BookingTree.statusText(booking));
		booking.setPaymentStatus(PaymentStatus.CARD);
		assertEquals("Bezahlt (Karte)", BookingTree.statusText(booking));
		booking.setPaymentStatus(PaymentStatus.TRANSFER);
		booking.setCollected(true);
		assertEquals("Bezahlt (Überweisung) und abgeholt", BookingTree.statusText(booking));
	}
}
