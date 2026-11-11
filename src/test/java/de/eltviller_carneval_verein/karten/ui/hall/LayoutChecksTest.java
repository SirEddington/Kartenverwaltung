package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.validation.HallValidator;
import de.eltviller_carneval_verein.karten.validation.ValidationIssue;

/**
 * Gegenprobe gegen Fehlalarme: Bestuhlungen, die die automatische Anordnung ({@link SeatingPlanLayout}) erzeugt hat,
 * lösen die Überlappungs- und Randwarnungen (SPL-004, SPL-005, TBL-007) nicht aus; dicht gepackte Stühle dagegen schon.
 */
class LayoutChecksTest {

	private static final Set<String> CODES = Set.of("SPL-004", "SPL-005", "TBL-007");
	private static final int SEATS_PER_TABLE = 12;
	private static final double TABLE_WIDTH = 50;
	private static final double TABLE_HEIGHT = 320;
	private static final double SEAT_WIDTH = 45;

	/** Halle "Festhalle" 4000 x 4000 mit einer leeren Bestuhlung. */
	private static Hall hall() {
		Hall hall = new Hall();
		hall.setName("Festhalle");
		hall.setHallWidth(4_000);
		hall.setHallHeight(4_000);
		hall.addSeatingPlan().setName("Prunksitzung");
		return hall;
	}

	private static SeatingPlan plan(Hall hall) {
		return hall.getSeatingPlans().get(0);
	}

	/** Neue Reihe bei {@code nextRowStart}; Winkel, Tischdrehung und Tischabstand wie in "+ Reihe" von der vorigen. */
	private static TableRow nextRow(SeatingPlan plan, TableRow previous) {
		double[] start = SeatingPlanLayout.nextRowStart(plan, previous);
		TableRow row = plan.addRow();
		row.setPosX(start[0]);
		row.setPosY(start[1]);
		if (previous != null) {
			row.setAngle(previous.getAngle());
			row.setTableRotation(previous.getTableRotation());
			row.setTableGap(previous.getTableGap());
		}
		return row;
	}

	/** Erste Reihe "A" bei (1000|300). */
	private static TableRow firstRow(SeatingPlan plan) {
		TableRow row = plan.addRow();
		row.setLabel("A");
		row.setPosX(1_000);
		row.setPosY(300);
		return row;
	}

	private static void fill(SeatingPlan plan, TableRow row, int tables, int seats) {
		plan.fillRow(row, tables, seats, TABLE_WIDTH, TABLE_HEIGHT, SEAT_WIDTH);
	}

	/** Ordnet jede Bestuhlung an und liefert die Codes der Überlappungs- und Randwarnungen. */
	private static List<String> layoutCodes(Hall hall) {
		hall.getSeatingPlans().forEach(SeatingPlanLayout::apply);
		return HallValidator.validate(hall).getIssues().stream().map(ValidationIssue::code).filter(CODES::contains).toList();
	}

	/** Eine Reihe "A" bei (1000|300) mit 5 Tischen, Tischdrehung {@code rotation}. */
	private static Hall oneRow(double rotation, int seats) {
		Hall hall = hall();
		SeatingPlan plan = plan(hall);
		TableRow row = firstRow(plan);
		row.setTableRotation(rotation);
		fill(plan, row, 5, seats);
		return hall;
	}

	@Test
	void oneRowLengthwise() {
		assertEquals(List.of(), layoutCodes(oneRow(0, SEATS_PER_TABLE)));
	}

	@Test
	void oneRowCrosswise() {
		assertEquals(List.of(), layoutCodes(oneRow(90, SEATS_PER_TABLE)));
	}

	@Test
	void rowWithAisle() {
		Hall hall = hall();
		SeatingPlan plan = plan(hall);
		TableRow row = firstRow(plan);
		row.setAisleAfter(2);
		row.setAisleWidth(100);
		fill(plan, row, 5, SEATS_PER_TABLE);

		assertEquals(List.of(), layoutCodes(hall));
	}

	@Test
	void threeRowsViaNextRowStart() {
		Hall hall = hall();
		SeatingPlan plan = plan(hall);
		TableRow row = firstRow(plan);
		fill(plan, row, 5, SEATS_PER_TABLE);
		SeatingPlanLayout.apply(plan);
		for (String label : List.of("B", "C")) {
			row = nextRow(plan, row);
			row.setLabel(label);
			fill(plan, row, 5, SEATS_PER_TABLE);
			SeatingPlanLayout.apply(plan);
		}

		assertEquals(List.of(), layoutCodes(hall));
	}

	@Test
	void twoEmptyRowsFilledLater() {
		Hall hall = hall();
		SeatingPlan plan = plan(hall);
		TableRow first = firstRow(plan);
		TableRow second = nextRow(plan, first);
		second.setLabel("B");
		TableRow third = nextRow(plan, second);
		third.setLabel("C");
		for (TableRow row : List.of(first, second, third)) {
			fill(plan, row, 5, SEATS_PER_TABLE);
		}

		assertEquals(List.of(), layoutCodes(hall));
	}

	@Test
	void slantedRows() {
		Hall hall = hall();
		SeatingPlan plan = plan(hall);
		TableRow first = firstRow(plan);
		first.setAngle(30);
		fill(plan, first, 5, SEATS_PER_TABLE);
		SeatingPlanLayout.apply(plan);
		TableRow second = nextRow(plan, first);
		second.setLabel("B");
		fill(plan, second, 5, SEATS_PER_TABLE);

		assertEquals(List.of(), layoutCodes(hall));
	}

	@Test
	void oddSeatCount() {
		assertEquals(List.of(), layoutCodes(oneRow(0, 11)));
	}

	@Test
	void withParquetAndStage() {
		Hall hall = hall();
		SeatingPlan plan = plan(hall);
		fill(plan, firstRow(plan), 5, SEATS_PER_TABLE);
		HallObject parquet = hall.addHallObject("Parkett");
		parquet.setWidth(170);
		parquet.setHeight(1_620);
		parquet.setPosX(915);
		parquet.setPosY(290);
		HallObject stage = hall.addHallObject("Bühne");
		stage.setWidth(200);
		stage.setHeight(100);
		stage.setPosX(900);
		stage.setPosY(1_900);

		assertEquals(List.of(), layoutCodes(hall));
	}

	@Test
	void denseSeatsCollideAtJoints() {
		// 16 Sitze je Tisch ohne Tischabstand: an jeder der 4 Tischfugen stoßen Stühle aneinander
		assertEquals(List.of("SPL-004", "SPL-004", "SPL-004", "SPL-004"), layoutCodes(oneRow(0, 16)));
	}

	@Test
	void largeHallIsValidatedQuickly() {
		Hall hall = new Hall();
		hall.setName("Großhalle");
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Prunksitzung");
		TableRow row = null;
		for (int i = 0; i < 10; i++) {
			row = nextRow(plan, row);
			row.setLabel(String.valueOf((char) ('A' + i)));
			fill(plan, row, 10, 16);
		}
		SeatingPlanLayout.apply(plan);

		// Aufwärmlauf, dann gemessen; 1000 ms statt Bruchteilen davon, damit die Suite auf langsamen Build-Rechnern stabil bleibt
		HallValidator.validate(hall);
		long start = System.nanoTime();
		HallValidator.validate(hall);
		long millis = (System.nanoTime() - start) / 1_000_000;

		System.out.println("HallValidator.validate (große Halle): " + millis + " ms");
		assertTrue(millis < 1_000, "Prüfung der großen Halle dauerte " + millis + " ms");
	}
}
