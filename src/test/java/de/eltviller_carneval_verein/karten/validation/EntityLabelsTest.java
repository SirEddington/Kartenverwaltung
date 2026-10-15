package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

class EntityLabelsTest {

	@Test
	void seatLabelContainsPresentationTableAndSeat() {
		Event event = new Event();
		event.setName("Kampagne");
		Seat seat = event.addPresentation("Prunksitzung").addTable(4).addSeat(2);

		assertEquals("Prunksitzung, Tisch 4, Sitz 2", EntityLabels.seat(seat));
	}

	@Test
	void seatWithoutParentsIsDescribedByNumberOnly() {
		Seat seat = new Seat();
		seat.setSeatNumber(7);

		assertEquals("Sitz 7", EntityLabels.seat(seat));
	}

	@Test
	void hallLabelContainsTheNameOrSaysItIsMissing() {
		Hall hall = new Hall();
		hall.setName("Festhalle");
		assertEquals("Halle Festhalle", EntityLabels.hall(hall));

		hall.setName("  ");
		assertEquals("Halle ohne Namen", EntityLabels.hall(hall));
		assertEquals("Halle ohne Namen", EntityLabels.hall(null));
	}

	@Test
	void nameOrPlaceholderReplacesEmptyNames() {
		assertEquals("(ohne Name)", EntityLabels.nameOrPlaceholder(null));
		assertEquals("(ohne Name)", EntityLabels.nameOrPlaceholder(""));
		assertEquals("(ohne Name)", EntityLabels.nameOrPlaceholder("  "));
		assertEquals("Bar", EntityLabels.nameOrPlaceholder("Bar"));
	}

	@Test
	void seatAtTableWithoutPresentationOmitsPresentation() {
		Table table = new Table();
		table.setTableNumber(3);
		Seat seat = new Seat();
		seat.setSeatNumber(1);
		seat.setParent(table);

		assertEquals("Tisch 3, Sitz 1", EntityLabels.seat(seat));
	}
}
