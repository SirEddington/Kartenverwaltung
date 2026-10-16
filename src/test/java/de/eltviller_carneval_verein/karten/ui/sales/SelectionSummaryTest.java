package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.sales.SelectionSummary.Line;
import de.eltviller_carneval_verein.karten.ui.sales.SelectionSummary.Summary;

class SelectionSummaryTest {

	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("Samstag");
	private final TestBookings bookings = new TestBookings();

	private Booking seat(Presentation in, int tableNumber, int seatNumber, int priceCents) {
		Booking booking = bookings.booking(in, tableNumber, seatNumber);
		booking.setPrice(priceCents);
		return booking;
	}

	@Test
	void nothingSelectedGivesAnEmptySummary() {
		Summary summary = SelectionSummary.of(List.of(), presentation);

		assertEquals(0, summary.count());
		assertTrue(summary.lines().isEmpty());
		assertEquals(0, summary.totalCents());
	}

	@Test
	void countsSumsAndListsTheSeatsSortedByTableAndSeat() {
		Booking t7s1 = seat(presentation, 7, 1, 1800);
		Booking t4s3 = seat(presentation, 4, 3, 2500);
		Booking t4s2 = seat(presentation, 4, 2, 2500);

		Summary summary = SelectionSummary.of(List.of(t7s1, t4s3, t4s2), presentation);

		assertEquals(3, summary.count());
		assertEquals(6800, summary.totalCents());
		assertEquals(List.of(new Line("Tisch 4, Sitz 2", 2500), new Line("Tisch 4, Sitz 3", 2500), new Line("Tisch 7, Sitz 1", 1800)), summary.lines());
	}

	@Test
	void namesTheRowLetterAndSortsRowsAfterTablesWithoutRow() {
		Booking a3s2 = bookings.booking(presentation, "A", 3, 2);
		Booking t5s1 = bookings.booking(presentation, 5, 1);

		Summary summary = SelectionSummary.of(List.of(a3s2, t5s1), presentation);

		assertEquals(List.of("Tisch 5, Sitz 1", "Tisch A3, Sitz 2"), summary.lines().stream().map(Line::text).toList());
	}

	@Test
	void usesTheStandardPriceForBookingsWithoutSinglePrice() {
		presentation.setPrice(2200);
		Booking standard = bookings.booking(presentation, 1, 1);
		Booking deviating = seat(presentation, 1, 2, 1500);

		Summary summary = SelectionSummary.of(List.of(standard, deviating), presentation);

		assertEquals(3700, summary.totalCents());
		assertEquals(new Line("Tisch 1, Sitz 1", 2200), summary.lines().get(0));
	}

	@Test
	void ignoresSeatsOfOtherPresentations() {
		Presentation other = event.addPresentation("Sonntag");
		Booking mine = seat(presentation, 1, 1, 2000);
		Booking foreign = seat(other, 1, 1, 9900);

		Summary summary = SelectionSummary.of(List.of(mine, foreign), presentation);

		assertEquals(1, summary.count());
		assertEquals(2000, summary.totalCents());
	}

	@Test
	void withoutAShownPresentationNothingIsListed() {
		Booking seat = seat(presentation, 1, 1, 2000);

		Summary summary = SelectionSummary.of(List.of(seat), null);

		assertEquals(0, summary.count());
		assertEquals(0, summary.totalCents());
	}

	@Test
	void countTextUsesSingularAndPlural() {
		assertEquals("Keine Karten ausgewählt", SelectionSummary.countText(0));
		assertEquals("1 Karte", SelectionSummary.countText(1));
		assertEquals("3 Karten", SelectionSummary.countText(3));
	}
}
