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

	/** Reihe nach rechts (270 Grad), Tische quer nebeneinander (Tischdrehung 90 Grad). */
	private void rowToTheRightWithTablesSideBySide() {
		row.setAngle(270);
		row.setTableRotation(90);
	}

	// --- Richtung der Reihe ---

	@Test
	void richtung_0_grad_nach_unten_im_uhrzeigersinn() {
		assertDirection(SeatingPlanLayout.direction(0), 0, 1);
		assertDirection(SeatingPlanLayout.direction(90), -1, 0);
		assertDirection(SeatingPlanLayout.direction(180), 0, -1);
		assertDirection(SeatingPlanLayout.direction(270), 1, 0);
	}

	@Test
	void naechste_reihe_liegt_rechts_bzw_darunter() {
		assertDirection(SeatingPlanLayout.besideDirection(0), 1, 0);
		assertDirection(SeatingPlanLayout.besideDirection(180), 1, 0);
		assertDirection(SeatingPlanLayout.besideDirection(270), 0, 1);
		assertDirection(SeatingPlanLayout.besideDirection(90), 0, 1);
	}

	private static void assertDirection(double[] direction, double x, double y) {
		assertEquals(x, direction[0], EPS, "Richtung x");
		assertEquals(y, direction[1], EPS, "Richtung y");
	}

	// --- Standardreihe: 0 Grad nach unten, Tische längs hintereinander ---

	@Test
	void standardreihe_tische_laengs_nach_unten() {
		Table first = table();
		Table second = table();

		SeatingPlanLayout.apply(plan);

		// Ausdehnung entlang der Reihe = Tischhöhe 300; Mittelpunkte (100, 200) und (100, 500)
		assertEquals(65, first.getPosX(), EPS);
		assertEquals(50, first.getPosY(), EPS);
		assertEquals(0, first.getRotation(), EPS);
		assertEquals(65, second.getPosX(), EPS);
		assertEquals(350, second.getPosY(), EPS);
		assertEquals(0, second.getRotation(), EPS);
		// Sitze links und rechts des stehenden Tischs
		assertSeat(first.getSeats().get(0), 50, 200);
		assertSeat(first.getSeats().get(1), 150, 200);
		assertEquals(600, SeatingPlanLayout.rowLength(plan, row), EPS);
	}

	@Test
	void reihe_90_grad_laeuft_nach_links_mit_gedrehten_tischen() {
		Table first = table();
		Table second = table();
		row.setAngle(90);

		SeatingPlanLayout.apply(plan);

		// Mittelpunkte (-50, 50) und (-350, 50)
		assertEquals(-50, first.getPosX() + 35, EPS);
		assertEquals(50, first.getPosY() + 150, EPS);
		assertEquals(90, first.getRotation(), EPS);
		assertEquals(-350, second.getPosX() + 35, EPS);
		assertEquals(50, second.getPosY() + 150, EPS);
		// Gedreht liegen die Sitze über und unter dem Tisch
		assertSeat(first.getSeats().get(0), -50, 0);
		assertSeat(first.getSeats().get(1), -50, 100);
	}

	@Test
	void tischdrehung_90_grad_quer_in_reihe_nach_unten() {
		Table first = table();
		Table second = table();
		row.setTableRotation(90);

		SeatingPlanLayout.apply(plan);

		// Ausdehnung entlang der Reihe = 70 + 2 * 25 = 120; Mittelpunkte (100, 110) und (100, 230)
		assertEquals(100, first.getPosX() + 35, EPS);
		assertEquals(110, first.getPosY() + 150, EPS);
		assertEquals(90, first.getRotation(), EPS);
		assertEquals(100, second.getPosX() + 35, EPS);
		assertEquals(230, second.getPosY() + 150, EPS);
		// Die Stühle stehen zwischen den Tischen
		assertSeat(first.getSeats().get(0), 100, 60);
		assertSeat(first.getSeats().get(1), 100, 160);
	}

	@Test
	void abstand_und_mittelgang_in_reihe_nach_unten() {
		table();
		Table second = table();

		row.setTableGap(10);
		SeatingPlanLayout.apply(plan);
		assertEquals(50 + 300 + 10 + 150, second.getPosY() + 150, EPS);

		row.setTableGap(0);
		row.setAisleAfter(1);
		row.setAisleWidth(100);
		SeatingPlanLayout.apply(plan);
		assertEquals(50 + 300 + 100 + 150, second.getPosY() + 150, EPS);
		assertEquals(100, second.getPosX() + 35, EPS);

		row.setTableGap(10);
		assertEquals(300 + 10 + 100 + 300, SeatingPlanLayout.rowLength(plan, row), EPS);
	}

	@Test
	void manueller_tisch_behaelt_seinen_platz_in_reihe_nach_unten() {
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
		assertEquals(350, second.getPosY(), EPS);
		assertEquals(0, second.getRotation(), EPS);
	}

	@Test
	void idempotent_in_reihe_nach_unten() {
		row.setTableRotation(90);
		row.setTableGap(5);
		row.setAisleAfter(1);
		row.setAisleWidth(60);
		for (int i = 0; i < 3; i++) {
			table();
		}

		SeatingPlanLayout.apply(plan);
		List<Double> before = snapshot();
		SeatingPlanLayout.apply(plan);

		assertEquals(before, snapshot());
	}

	@Test
	void drehung_aus_winkel_und_tischdrehung_wird_normalisiert() {
		Table first = table();
		row.setAngle(350);
		row.setTableRotation(20);
		SeatingPlanLayout.apply(plan);
		assertEquals(10, first.getRotation(), EPS);

		row.setAngle(270);
		row.setTableRotation(180);
		SeatingPlanLayout.apply(plan);
		assertEquals(90, first.getRotation(), EPS);
	}

	// --- Umgerechnete Fälle der früheren Zählweise (alter Winkel - 90, alte Tischdrehung + 90) ---

	@Test
	void quer_ohne_Abstand() {
		Table first = table();
		Table second = table();
		rowToTheRightWithTablesSideBySide();

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
		rowToTheRightWithTablesSideBySide();
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
		rowToTheRightWithTablesSideBySide();

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
	void reihe_nach_rechts_tische_laengs() {
		Table first = table();
		row.setAngle(270);
		row.setTableRotation(180);

		SeatingPlanLayout.apply(plan);

		assertEquals(250, first.getPosX() + 35, EPS);
		assertEquals(50, first.getPosY() + 150, EPS);
		assertEquals(90, first.getRotation(), EPS);
		// Stühle liegen jetzt oben und unten: 50 cm über bzw. unter dem Mittelpunkt (250, 50)
		assertSeat(first.getSeats().get(0), 250, 0);
		assertSeat(first.getSeats().get(1), 250, 100);
	}

	@Test
	void reihe_nach_unten_tische_quer() {
		Table first = table();
		row.setAngle(0);
		row.setTableRotation(90);

		SeatingPlanLayout.apply(plan);

		assertEquals(100, first.getPosX() + 35, EPS);
		assertEquals(110, first.getPosY() + 150, EPS);
		assertEquals(90, first.getRotation(), EPS);
	}

	@Test
	void tischdrehung_liegt_zwischen_0_und_unter_360() {
		Table first = table();
		row.setAngle(210);
		row.setTableRotation(180);
		SeatingPlanLayout.apply(plan);
		assertEquals(30, first.getRotation(), EPS);

		row.setAngle(-180);
		row.setTableRotation(90);
		SeatingPlanLayout.apply(plan);
		assertEquals(270, first.getRotation(), EPS);

		row.setAngle(180);
		row.setTableRotation(180);
		SeatingPlanLayout.apply(plan);
		assertEquals(0, first.getRotation(), EPS);
	}

	@Test
	void manuelle_Tische_behalten_Position_und_Platz() {
		Table first = table();
		Table second = table();
		rowToTheRightWithTablesSideBySide();
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
		row.setAngle(300);
		row.setTableRotation(135);
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
		rowToTheRightWithTablesSideBySide();
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
		zero.addSeat().setWidth(0);

		assertDoesNotThrow(() -> SeatingPlanLayout.apply(plan));

		assertEquals(1, stray.getPosX(), EPS);
		assertEquals(2, stray.getPosY(), EPS);
		assertEquals(3, stray.getRotation(), EPS);
		assertEquals(0, stray.getWidth(), EPS);
		assertEquals(0, stray.getHeight(), EPS);
		// Maße 0: Rückfall auf 50 x 320; Sitze ohne Breite mit Ersatzradius 10, Rand m = 5 + 20 = 25,
		// Mittelpunkt x = 100 + (50 + 50) / 2
		assertEquals(50, zero.getWidth(), EPS);
		assertEquals(320, zero.getHeight(), EPS);
		assertEquals(150, zero.getPosX() + 25, EPS);
		assertSeat(noSize, 150 - 25 - 10 - 5, 50);
	}

	@Test
	void tische_liegen_in_der_reihenfolge_des_anlegens_nicht_nach_nummer() {
		Table nine = table();
		nine.setTableNumber(9);
		Table one = table();
		one.setTableNumber(1);
		Table five = table();
		five.setTableNumber(5);
		rowToTheRightWithTablesSideBySide();

		SeatingPlanLayout.apply(plan);

		assertEquals(125, nine.getPosX(), EPS);
		assertEquals(245, one.getPosX(), EPS);
		assertEquals(365, five.getPosX(), EPS);
	}

	@Test
	void eine_neue_nummer_verschiebt_den_tisch_nicht() {
		Table first = table();
		Table second = table();
		Table third = table();
		SeatingPlanLayout.apply(plan);
		List<Double> before = snapshot();

		// auf die Nummer eines anderen Tischs (doppelt, meldet der Validator) und auf eine größere
		first.setTableNumber(third.getTableNumber());
		SeatingPlanLayout.apply(plan);
		assertEquals(before, snapshot());
		second.setTableNumber(99);
		SeatingPlanLayout.apply(plan);
		assertEquals(before, snapshot());
	}

	// --- Nächste Reihe ("+ Reihe"): neben der vorigen, Abstand = deren Querausdehnung + 20 cm ---

	/** Tisch 70 x 300 mit zwei Sitzen der Breite 30 (Rand m = 5 + 30 = 35). */
	private Table tableWithWideSeats() {
		Table table = table();
		table.getSeats().forEach(seat -> seat.setWidth(30));
		return table;
	}

	private static void assertPoint(double[] point, double x, double y) {
		assertEquals(x, point[0], EPS, "x");
		assertEquals(y, point[1], EPS, "y");
	}

	@Test
	void querausdehnung_stehender_tische_ist_breite_plus_stuehle() {
		tableWithWideSeats();
		tableWithWideSeats();

		// 70 + 2 * 35
		assertEquals(140, SeatingPlanLayout.crossExtent(plan, row), EPS);
	}

	@Test
	void querausdehnung_zaehlt_den_breitesten_tisch() {
		tableWithWideSeats();
		tableWithWideSeats().setWidth(90);

		assertEquals(160, SeatingPlanLayout.crossExtent(plan, row), EPS);
	}

	@Test
	void naechste_reihe_neben_stehenden_tischen() {
		tableWithWideSeats();
		tableWithWideSeats();

		// Reihe nach unten: die nächste rechts daneben, 140 + 20 weiter, auf gleicher Höhe
		assertPoint(SeatingPlanLayout.nextRowStart(plan, row), 100 + 140 + 20, 50);
	}

	@Test
	void naechste_reihe_neben_querer_reihe() {
		tableWithWideSeats();
		tableWithWideSeats();
		row.setTableRotation(90);

		// Quer: Ausdehnung quer zur Reihe = Tischhöhe 300
		assertEquals(300, SeatingPlanLayout.crossExtent(plan, row), EPS);
		assertPoint(SeatingPlanLayout.nextRowStart(plan, row), 100 + 300 + 20, 50);
	}

	@Test
	void naechste_reihe_unter_reihe_nach_links() {
		tableWithWideSeats();
		tableWithWideSeats();
		row.setAngle(90);

		// Reihe nach links: die nächste darunter, gleicher Start entlang der Achse
		assertPoint(SeatingPlanLayout.nextRowStart(plan, row), 100, 50 + 140 + 20);
	}

	@Test
	void naechste_reihe_bei_schraeg_gedrehten_tischen() {
		tableWithWideSeats();
		row.setTableRotation(45);

		// Projektion des Rechtecks 140 x 300 quer zur Achse: (140 + 300) * cos 45
		double extent = (140 + 300) * Math.sqrt(0.5);
		assertEquals(extent, SeatingPlanLayout.crossExtent(plan, row), EPS);
		assertPoint(SeatingPlanLayout.nextRowStart(plan, row), 100 + extent + 20, 50);
	}

	@Test
	void naechste_reihe_nach_reihe_ohne_tische_150_daneben() {
		assertEquals(0, SeatingPlanLayout.crossExtent(plan, row), EPS);
		assertPoint(SeatingPlanLayout.nextRowStart(plan, row), 100 + 150, 50);
	}

	@Test
	void erste_reihe_am_standardstart() {
		assertPoint(SeatingPlanLayout.nextRowStart(plan, null), 100, 100);
	}

	// --- KARV-128: Sitze in gleichen Abschnitten, Abstand zur Tischkante nach dem Durchmesser ---

	/** Tisch 50 x 320 mit {@code seats} Sitzen vom Durchmesser 45 hinten in der Reihe (Reihe nach unten, Tische längs). */
	private Table standardTable(int seats) {
		plan.setDefaultTableWidth(50);
		plan.setDefaultTableHeight(320);
		plan.setDefaultSeatWidth(45);
		Table table = plan.addTable(row);
		for (int i = 0; i < seats; i++) {
			table.addSeat();
		}
		return table;
	}

	@Test
	void sitze_stehen_in_der_mitte_gleicher_abschnitte_auch_an_der_stossstelle() {
		Table first = standardTable(12);
		Table second = standardTable(12);

		SeatingPlanLayout.apply(plan);

		double step = 320.0 / 6;
		// Tisch 1 von y = 50 bis 370: erster Sitz einen halben Abschnitt unter der Oberkante
		assertEquals(50 + step / 2, first.getSeats().get(0).getPosY(), EPS);
		for (int i = 2; i < 12; i += 2) {
			assertEquals(step, first.getSeats().get(i).getPosY() - first.getSeats().get(i - 2).getPosY(), EPS, "Abstand innen");
		}
		assertEquals(step, second.getSeats().get(0).getPosY() - first.getSeats().get(10).getPosY(), EPS, "Abstand an der Stoßstelle");
	}

	@Test
	void an_der_stossstelle_kommt_der_tischabstand_dazu() {
		Table first = standardTable(12);
		Table second = standardTable(12);
		row.setTableGap(20);

		SeatingPlanLayout.apply(plan);

		assertEquals(320.0 / 6 + 20, second.getSeats().get(0).getPosY() - first.getSeats().get(10).getPosY(), EPS);
	}

	@Test
	void sitzmittelpunkt_liegt_radius_plus_5_neben_der_tischkante() {
		Table table = standardTable(2);

		SeatingPlanLayout.apply(plan);

		// Tischmitte x = 100, Kante bei 75 bzw. 125, Radius 22,5
		assertSeat(table.getSeats().get(0), 100 - 25 - 22.5 - 5, 210);
		assertSeat(table.getSeats().get(1), 100 + 25 + 22.5 + 5, 210);
	}

	@Test
	void ungerade_zahl_der_letzte_sitz_steht_allein_links() {
		Table table = standardTable(3);

		SeatingPlanLayout.apply(plan);

		// zwei Abschnitte zu 160: Mitten bei 210 - 80 und 210 + 80
		assertSeat(table.getSeats().get(0), 47.5, 130);
		assertSeat(table.getSeats().get(1), 152.5, 130);
		assertSeat(table.getSeats().get(2), 47.5, 290);
	}

	@Test
	void sitz_ohne_breite_liegt_mit_ersatzradius_neben_dem_tisch() {
		Table table = standardTable(2);
		table.getSeats().forEach(seat -> seat.setWidth(0));

		SeatingPlanLayout.apply(plan);

		assertSeat(table.getSeats().get(0), 100 - 25 - 10 - 5, 210);
		assertEquals(0, table.getSeats().get(0).getWidth(), EPS, "das Modell bleibt unverändert");
		assertEquals(10, table.getSeats().get(0).radius(), EPS);
		assertEquals(22.5, standardTable(1).getSeats().get(0).radius(), EPS);
	}

	@Test
	void reihenbreite_und_reihenlaenge_folgen_dem_durchmesser() {
		standardTable(12);
		standardTable(12);

		// 50 + 2 * (5 + 45)
		assertEquals(150, SeatingPlanLayout.crossExtent(plan, row), EPS);
		row.setTableRotation(90);
		assertEquals(150 + 150, SeatingPlanLayout.rowLength(plan, row), EPS);
	}

	@Test
	void manueller_sitz_bleibt_stehen() {
		Table table = standardTable(2);
		Seat manual = table.getSeats().get(0);
		manual.setManualPos(true);
		manual.setPosX(1);
		manual.setPosY(2);

		SeatingPlanLayout.apply(plan);

		assertSeat(manual, 1, 2);
		assertSeat(table.getSeats().get(1), 152.5, 210);
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
