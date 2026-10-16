package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

class SeatListSummaryTest {

	private final Event event = new Event();
	private final TestBookings bookings = new TestBookings();

	private Presentation presentation(String name) {
		return event.getPresentations().stream().filter(p -> name.equals(p.getName())).findFirst().orElseGet(() -> event.addPresentation(name));
	}

	private Booking seat(String presentationName, int tableNumber, int seatNumber) {
		return bookings.booking(presentation(presentationName), tableNumber, seatNumber);
	}

	private Booking seat(String presentationName, String rowLabel, int tableNumber, int seatNumber) {
		return bookings.booking(presentation(presentationName), rowLabel, tableNumber, seatNumber);
	}

	@Test
	void joinsThreeOrMoreConsecutiveSeatsToARange() {
		List<Booking> seats = new ArrayList<>();
		for (int number = 3; number <= 12; number++) {
			seats.add(seat("Prunksitzung", 3, number));
		}

		assertEquals("Prunksitzung; Tisch 3; Sitz 3-12", SeatListSummary.headline(seats));
	}

	@Test
	void listsSeparateSeatsAndPairsWithCommas() {
		List<Booking> seats = List.of(seat("Prunksitzung", 4, 1), seat("Prunksitzung", 4, 3), seat("Prunksitzung", 4, 7), seat("Prunksitzung", 4, 8));

		assertEquals("Prunksitzung; Tisch 4; Sitz 1, 3, 7, 8", SeatListSummary.headline(seats));
	}

	@Test
	void mixesRangesAndSingleSeatsOfOneTable() {
		List<Booking> seats = List.of(seat("Prunksitzung", 3, 1), seat("Prunksitzung", 3, 2), seat("Prunksitzung", 3, 3), seat("Prunksitzung", 3, 5));

		assertEquals("Prunksitzung; Tisch 3; Sitz 1-3, 5", SeatListSummary.headline(seats));
	}

	@Test
	void groupsByTableAndSortsAscendingRegardlessOfInputOrder() {
		List<Booking> seats = List.of(seat("Prunksitzung", 4, 3), seat("Prunksitzung", 3, 5), seat("Prunksitzung", 4, 1), seat("Prunksitzung", 3, 3),
				seat("Prunksitzung", 3, 4));

		assertEquals("Prunksitzung; Tisch 3; Sitz 3-5; Tisch 4; Sitz 1, 3", SeatListSummary.headline(seats));
	}

	@Test
	void tablesOfARowAreGroupedApartFromTheTableWithTheSameNumberWithoutRow() {
		List<Booking> seats = List.of(seat("Prunksitzung", "A", 3, 5), seat("Prunksitzung", 3, 1), seat("Prunksitzung", "A", 3, 4), seat("Prunksitzung", "B", 3, 1));

		assertEquals("Prunksitzung; Tisch 3; Sitz 1; Tisch A3; Sitz 4, 5; Tisch B3; Sitz 1", SeatListSummary.headline(seats));
	}

	@Test
	void namesEachPresentationOnceInOrderOfFirstAppearance() {
		List<Booking> seats = List.of(seat("Prunksitzung", 3, 2), seat("Damensitzung", 1, 7), seat("Prunksitzung", 3, 1));

		assertEquals("Prunksitzung; Tisch 3; Sitz 1, 2; Damensitzung; Tisch 1; Sitz 7", SeatListSummary.headline(seats));
	}

	@Test
	void tooltipListsEverySeatOnItsOwnLine() {
		List<Booking> seats = List.of(seat("Prunksitzung", 4, 1), seat("Prunksitzung", 3, 4), seat("Prunksitzung", 3, 3));

		assertEquals("Prunksitzung, Tisch 3, Sitz 3\nPrunksitzung, Tisch 3, Sitz 4\nPrunksitzung, Tisch 4, Sitz 1", SeatListSummary.tooltip(seats));
	}

	@Test
	void tooltipNamesTheRowLetter() {
		List<Booking> seats = List.of(seat("Prunksitzung", "A", 3, 2), seat("Prunksitzung", 3, 1));

		assertEquals("Prunksitzung, Tisch 3, Sitz 1\nPrunksitzung, Tisch A3, Sitz 2", SeatListSummary.tooltip(seats));
	}

	@Test
	void tooltipOfAHugeSelectionShowsTheFirstSeatsAndTheRemainingCount() {
		List<Booking> seats = new ArrayList<>();
		for (int number = 1; number <= 60; number++) {
			seats.add(seat("Prunksitzung", 1, number));
		}

		String[] lines = SeatListSummary.tooltip(seats).split("\n");

		assertEquals(41, lines.length);
		assertEquals("Prunksitzung, Tisch 1, Sitz 1", lines[0]);
		assertEquals("Prunksitzung, Tisch 1, Sitz 40", lines[39]);
		assertEquals("… und 20 weitere Sitze", lines[40]);
	}

	@Test
	void tooltipWithExactlyTheLimitIsNotCut() {
		List<Booking> seats = new ArrayList<>();
		for (int number = 1; number <= 40; number++) {
			seats.add(seat("Prunksitzung", 1, number));
		}

		String[] lines = SeatListSummary.tooltip(seats).split("\n");

		assertEquals(40, lines.length);
		assertEquals("Prunksitzung, Tisch 1, Sitz 40", lines[39]);
	}

	@Test
	void emptyListGivesEmptyTexts() {
		assertEquals("", SeatListSummary.headline(List.of()));
		assertEquals("", SeatListSummary.tooltip(List.of()));
	}
}
