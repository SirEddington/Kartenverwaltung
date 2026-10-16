package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

class SeatingPlanLayoutTest {

	private static final double EPS = 1e-6;

	private final SeatingPlan plan = new SeatingPlan();
	private final TableRow row = plan.addRow();

	SeatingPlanLayoutTest() {
		plan.setDefaultTableWidth(70);
		plan.setDefaultTableHeight(300);
		plan.setDefaultSeatWidth(20);
		plan.setDefaultSeatHeight(20);
		row.setPosX(100);
		row.setPosY(50);
	}

	/** Neuer Tisch 70 x 300 mit zwei Sitzen der Breite 20 hinten in der Reihe (also Rand m = 25). */
	private Table table() {
		Table table = plan.addTable(row);
		table.addSeat();
		table.addSeat();
		return table;
	}

	private static void assertSeat(Seat seat, double x, double y) {
		assertEquals(x, seat.getPosX(), EPS, "Sitz posX");
		assertEquals(y, seat.getPosY(), EPS, "Sitz posY");
	}

	@Test
	void quer_ohne_Abstand() {
		Table first = table();
		Table second = table();

		SeatingPlanLayout.apply(plan);

		assertEquals(125, first.getPosX(), EPS);
		assertEquals(-100, first.getPosY(), EPS);
		assertEquals(0, first.getRotation(), EPS);
		assertEquals(245, second.getPosX(), EPS);
		assertEquals(-100, second.getPosY(), EPS);
		// Mittelpunkt (160, 50), Stuhl 15 cm neben der Tischkante: 160 -/+ (35 + 15)
		assertSeat(first.getSeats().get(0), 110, 50);
		assertSeat(first.getSeats().get(1), 210, 50);
	}

	@Test
	void reihenlaenge_mit_abstand_und_mittelgang() {
		table();
		table();
		row.setTableGap(10);
		row.setAisleAfter(1);
		row.setAisleWidth(100);

		// je Tisch 70 + 2 * 25 = 120, dazwischen Abstand 10 und Gang 100; kein Abstand nach dem letzten
		assertEquals(350, SeatingPlanLayout.rowLength(plan, row), EPS);
		assertEquals(0, SeatingPlanLayout.rowLength(plan, plan.addRow()), EPS);
	}

	@Test
	void tischabstand_und_mittelgang() {
		table();
		Table second = table();

		row.setTableGap(10);
		SeatingPlanLayout.apply(plan);
		assertEquals(290, second.getPosX() + 35, EPS);

		row.setTableGap(0);
		row.setAisleAfter(1);
		row.setAisleWidth(100);
		SeatingPlanLayout.apply(plan);
		assertEquals(380, second.getPosX() + 35, EPS);

		row.setAisleAfter(5);
		SeatingPlanLayout.apply(plan);
		assertEquals(280, second.getPosX() + 35, EPS);
	}

	@Test
	void laengs() {
		Table first = table();
		row.setTableRotation(90);

		SeatingPlanLayout.apply(plan);

		assertEquals(250, first.getPosX() + 35, EPS);
		assertEquals(50, first.getPosY() + 150, EPS);
		assertEquals(90, first.getRotation(), EPS);
		// Stühle liegen jetzt oben und unten: 50 cm über bzw. unter dem Mittelpunkt (250, 50)
		assertSeat(first.getSeats().get(0), 250, 0);
		assertSeat(first.getSeats().get(1), 250, 100);
	}

	@Test
	void reihe_gedreht_um_90_grad() {
		Table first = table();
		row.setAngle(90);

		SeatingPlanLayout.apply(plan);

		assertEquals(100, first.getPosX() + 35, EPS);
		assertEquals(110, first.getPosY() + 150, EPS);
		assertEquals(90, first.getRotation(), EPS);
	}

	@Test
	void manuelle_Tische_behalten_Position_und_Platz() {
		Table first = table();
		Table second = table();
		first.setManualPos(true);
		first.setPosX(7);
		first.setPosY(8);
		first.setRotation(33);

		SeatingPlanLayout.apply(plan);

		assertEquals(7, first.getPosX(), EPS);
		assertEquals(8, first.getPosY(), EPS);
		assertEquals(33, first.getRotation(), EPS);
		assertEquals(280, second.getPosX() + 35, EPS);
	}

	@Test
	void idempotent() {
		row.setAngle(30);
		row.setTableRotation(45);
		row.setTableGap(12.5);
		row.setAisleAfter(2);
		row.setAisleWidth(80);
		for (int i = 0; i < 4; i++) {
			table();
		}
		plan.getTables().get(1).addSeat();

		SeatingPlanLayout.apply(plan);
		List<Double> before = snapshot();
		SeatingPlanLayout.apply(plan);

		assertEquals(before, snapshot());
	}

	@Test
	void unbekannte_Reihe_reihe_ohne_Tische_und_Maße_0_werfen_nicht() {
		plan.addRow(); // leere Reihe

		Table stray = plan.addTable(row);
		stray.setRowId("gibt-es-nicht");
		stray.setPosX(1);
		stray.setPosY(2);
		stray.setRotation(3);
		stray.setWidth(0);
		stray.setHeight(0);

		Table zero = plan.addTable(row);
		zero.setWidth(0);
		zero.setHeight(0);
		Seat noSize = zero.addSeat();
		noSize.setWidth(0);
		noSize.setHeight(0);
		zero.addSeat().setWidth(0);

		assertDoesNotThrow(() -> SeatingPlanLayout.apply(plan));

		assertEquals(1, stray.getPosX(), EPS);
		assertEquals(2, stray.getPosY(), EPS);
		assertEquals(3, stray.getRotation(), EPS);
		assertEquals(0, stray.getWidth(), EPS);
		assertEquals(0, stray.getHeight(), EPS);
		// Maße 0: Rückfall auf 70 x 300; Rand m = 15, Mittelpunkt x = 100 + (70 + 30) / 2
		assertEquals(70, zero.getWidth(), EPS);
		assertEquals(300, zero.getHeight(), EPS);
		assertEquals(150, zero.getPosX() + 35, EPS);
		assertSeat(noSize, 150 - 50, 50);
	}

	@Test
	void reihen_ohne_buchstaben_werden_nach_nummer_gelegt() {
		Table nine = table();
		nine.setTableNumber(9);
		Table one = table();
		one.setTableNumber(1);
		Table five = table();
		five.setTableNumber(5);

		SeatingPlanLayout.apply(plan);

		assertEquals(125, one.getPosX(), EPS);
		assertEquals(245, five.getPosX(), EPS);
		assertEquals(365, nine.getPosX(), EPS);
	}

	private List<Double> snapshot() {
		List<Double> values = new ArrayList<>();
		for (Table table : plan.getTables()) {
			values.add(table.getPosX());
			values.add(table.getPosY());
			values.add(table.getWidth());
			values.add(table.getHeight());
			values.add(table.getRotation());
			for (Seat seat : table.getSeats()) {
				values.add(seat.getPosX());
				values.add(seat.getPosY());
			}
		}
		return values;
	}
}
