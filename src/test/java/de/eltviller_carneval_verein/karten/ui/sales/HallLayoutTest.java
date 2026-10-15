package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

class HallLayoutTest {

	private final Presentation presentation = new Event().addPresentation("P");

	private Table table(double width, double height) {
		Table table = presentation.addTable();
		table.setWidth(width);
		table.setHeight(height);
		return table;
	}

	private Seat seat(Table table, double width) {
		Seat seat = table.addSeat();
		seat.setWidth(width);
		seat.setHeight(width);
		return seat;
	}

	@Test
	void tablesWithoutSizeGetDefaultsAndStartAtFixedOrigin() {
		Table table = table(0, 0);

		HallLayout.apply(presentation, null);

		assertEquals(70, table.getWidth());
		assertEquals(300, table.getHeight());
		assertEquals(60, table.getPosX());
		assertEquals(40, table.getPosY());
	}

	@Test
	void secondTableFollowsBelowTheFirst() {
		table(70, 300);
		Table second = table(70, 300);

		HallLayout.apply(presentation, null);

		assertEquals(60, second.getPosX());
		assertEquals(340, second.getPosY());
	}

	@Test
	void eighthTableStartsANewColumnWithoutHall() {
		Table eighth = null;
		for (int i = 0; i < 8; i++) {
			eighth = table(70, 300);
		}

		HallLayout.apply(presentation, null);

		assertEquals(150, eighth.getPosX());
		assertEquals(40, eighth.getPosY());
	}

	@Test
	void seatsFlankTheTableAndShiftItBySeatMargin() {
		Table table = table(70, 300);
		Seat left = seat(table, 40);
		Seat right = seat(table, 40);

		HallLayout.apply(presentation, null);

		assertEquals(95, table.getPosX());
		assertEquals(80, left.getPosX());
		assertEquals(190, left.getPosY());
		assertEquals(180, right.getPosX());
		assertEquals(190, right.getPosY());
	}

	@Test
	void hallWrapsColumnsAtHallHeight() {
		Hall hall = new Hall();
		hall.setHallWidth(500);
		hall.setHallHeight(700);
		table(70, 300);
		Table second = table(70, 300);
		Table third = table(70, 300);

		HallLayout.apply(presentation, hall);

		assertEquals(50, second.getPosX());
		assertEquals(350, second.getPosY());
		assertEquals(140, third.getPosX());
		assertEquals(50, third.getPosY());
	}

	@Test
	void manualPositionsStayUntouched() {
		Table manualTable = table(70, 300);
		manualTable.setManualPos(true);
		manualTable.setPosX(5);
		manualTable.setPosY(7);
		Seat manualSeat = seat(manualTable, 40);
		manualSeat.setManualPos(true);
		manualSeat.setPosX(11);
		manualSeat.setPosY(13);

		HallLayout.apply(presentation, null);

		assertEquals(5, manualTable.getPosX());
		assertEquals(7, manualTable.getPosY());
		assertEquals(11, manualSeat.getPosX());
		assertEquals(13, manualSeat.getPosY());
	}
}
