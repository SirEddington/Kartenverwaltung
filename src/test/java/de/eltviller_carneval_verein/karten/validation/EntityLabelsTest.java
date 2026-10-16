package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

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
	void planLabelContainsPlanAndHall() {
		Hall hall = new Hall();
		hall.setName("Festhalle");
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Prunksitzung");

		assertEquals("Bestuhlung Prunksitzung in Halle Festhalle", EntityLabels.plan(plan));

		plan.setName(" ");
		assertEquals("Bestuhlung ohne Namen in Halle Festhalle", EntityLabels.plan(plan));

		SeatingPlan lonely = new SeatingPlan();
		lonely.setName("Prunksitzung");
		assertEquals("Bestuhlung Prunksitzung in Halle ohne Namen", EntityLabels.plan(lonely));
	}

	@Test
	void rowLabelContainsLetterAndPlan() {
		Hall hall = new Hall();
		hall.setName("Festhalle");
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Prunksitzung");
		TableRow row = plan.addRow();
		row.setLabel("A");

		assertEquals("Reihe A in Bestuhlung Prunksitzung in Halle Festhalle", EntityLabels.row(row));

		row.setLabel("");
		assertEquals("Reihe ohne Buchstaben in Bestuhlung Prunksitzung in Halle Festhalle", EntityLabels.row(row));
	}

	@Test
	void tableOfAPlanIsDescribedWithLetterAndPlan() {
		Hall hall = new Hall();
		hall.setName("Festhalle");
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Prunksitzung");
		TableRow row = plan.addRow();
		row.setLabel("A");
		Table table = plan.addTable(row);

		assertEquals("Tisch A1 in Bestuhlung Prunksitzung in Halle Festhalle", EntityLabels.table(table));
	}

	@Test
	void bookingLabelContainsPresentationAndPlace() {
		Presentation presentation = new Event().addPresentation("Prunksitzung");
		Booking booking = presentation.addBooking();
		booking.setRowLabel("A");
		booking.setTableNumber(3);
		booking.setSeatNumber(2);

		assertEquals("Prunksitzung, Tisch A3, Sitz 2", EntityLabels.booking(booking));

		Booking lonely = new Booking();
		lonely.setTableNumber(3);
		lonely.setSeatNumber(2);
		assertEquals("Tisch 3, Sitz 2", EntityLabels.booking(lonely));

		presentation.setName(" ");
		assertEquals("Tisch A3, Sitz 2", EntityLabels.booking(booking));
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
