package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

class TableSeatPanelControllerTest {

	private final SeatingPlan plan = new Hall().addSeatingPlan();
	private final TableRow row = plan.addRow();

	@Test
	void deleteTableMessageNamesTableAndSeats() {
		row.setLabel("A");
		plan.fillRow(row, 3, 6);
		Table third = plan.getTablesOf(row).get(2);

		assertEquals("Soll Tisch A3 mit 6 Sitzen wirklich gelöscht werden?", TableSeatPanelController.deleteTableMessage(third));
	}

	@Test
	void deleteTableMessageForOneAndNoSeat() {
		Table table = plan.addTable(row);

		assertEquals("Soll Tisch 1 wirklich gelöscht werden?", TableSeatPanelController.deleteTableMessage(table));
		table.addSeat();
		assertEquals("Soll Tisch 1 mit 1 Sitz wirklich gelöscht werden?", TableSeatPanelController.deleteTableMessage(table));
	}

	@Test
	void seatTitleAndDeleteMessageNameTheTable() {
		row.setLabel("B");
		Table table = plan.addTable(row);
		table.addSeat();
		Seat second = table.addSeat();

		assertEquals("Sitz 2 an Tisch B1", TableSeatPanelController.seatTitle(second));
		assertEquals("Soll Sitz 2 an Tisch B1 wirklich gelöscht werden?", TableSeatPanelController.deleteSeatMessage(second));
	}

	@Test
	void positionText() {
		assertEquals("Position: manuell", TableSeatPanelController.positionText(true));
		assertEquals("Position: automatisch", TableSeatPanelController.positionText(false));
	}
}
