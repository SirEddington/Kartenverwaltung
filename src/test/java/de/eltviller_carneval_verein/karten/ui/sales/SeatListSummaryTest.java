package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

class SeatListSummaryTest {

	private final Event event = new Event();

	private Table table(String presentationName, int tableNumber) {
		Presentation presentation = event.getPresentations().stream().filter(p -> presentationName.equals(p.getName())).findFirst()
				.orElseGet(() -> event.addPresentation(presentationName));
		return presentation.getTables().stream().filter(t -> t.getTableNumber() == tableNumber).findFirst().orElseGet(() -> presentation.addTable(tableNumber));
	}

	private static Seat seat(Table table, int seatNumber) {
		return table.addSeat(seatNumber);
	}

	@Test
	void joinsThreeOrMoreConsecutiveSeatsToARange() {
		Table table = table("Prunksitzung", 3);
		List<Seat> seats = new ArrayList<>();
		for (int number = 3; number <= 12; number++) {
			seats.add(seat(table, number));
		}

		assertEquals("Prunksitzung; Tisch 3; Sitz 3-12", SeatListSummary.headline(seats));
	}

	@Test
	void listsSeparateSeatsAndPairsWithCommas() {
		Table table = table("Prunksitzung", 4);
		List<Seat> seats = List.of(seat(table, 1), seat(table, 3), seat(table, 7), seat(table, 8));

		assertEquals("Prunksitzung; Tisch 4; Sitz 1, 3, 7, 8", SeatListSummary.headline(seats));
	}

	@Test
	void mixesRangesAndSingleSeatsOfOneTable() {
		Table table = table("Prunksitzung", 3);
		List<Seat> seats = List.of(seat(table, 1), seat(table, 2), seat(table, 3), seat(table, 5));

		assertEquals("Prunksitzung; Tisch 3; Sitz 1-3, 5", SeatListSummary.headline(seats));
	}

	@Test
	void groupsByTableAndSortsAscendingRegardlessOfInputOrder() {
		Table three = table("Prunksitzung", 3);
		Table four = table("Prunksitzung", 4);
		List<Seat> seats = List.of(seat(four, 3), seat(three, 5), seat(four, 1), seat(three, 3), seat(three, 4));

		assertEquals("Prunksitzung; Tisch 3; Sitz 3-5; Tisch 4; Sitz 1, 3", SeatListSummary.headline(seats));
	}

	@Test
	void namesEachPresentationOnceInOrderOfFirstAppearance() {
		Table damen = table("Damensitzung", 1);
		Table prunk = table("Prunksitzung", 3);
		List<Seat> seats = List.of(seat(prunk, 2), seat(damen, 7), seat(prunk, 1));

		assertEquals("Prunksitzung; Tisch 3; Sitz 1, 2; Damensitzung; Tisch 1; Sitz 7", SeatListSummary.headline(seats));
	}

	@Test
	void tooltipListsEverySeatOnItsOwnLine() {
		Table three = table("Prunksitzung", 3);
		Table four = table("Prunksitzung", 4);
		List<Seat> seats = List.of(seat(four, 1), seat(three, 4), seat(three, 3));

		assertEquals("Prunksitzung, Tisch 3, Sitz 3\nPrunksitzung, Tisch 3, Sitz 4\nPrunksitzung, Tisch 4, Sitz 1", SeatListSummary.tooltip(seats));
	}

	@Test
	void emptyListGivesEmptyTexts() {
		assertEquals("", SeatListSummary.headline(List.of()));
		assertEquals("", SeatListSummary.tooltip(List.of()));
	}
}
