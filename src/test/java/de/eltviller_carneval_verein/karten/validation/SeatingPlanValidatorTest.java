package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

class SeatingPlanValidatorTest {

	/** Bestuhlung "Prunksitzung" in der Halle "Festhalle" mit Reihe "A" und einem Tisch (A1), ohne jede Meldung. */
	private static SeatingPlan plan() {
		Hall hall = new Hall();
		hall.setName("Festhalle");
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Prunksitzung");
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.addTable(row);
		return plan;
	}

	private static TableRow row(SeatingPlan plan) {
		return plan.getRows().get(0);
	}

	private static ValidationIssue only(ValidationResult result) {
		assertEquals(1, result.getIssues().size(), "Genau eine Meldung erwartet: " + result.getIssues());
		return result.getIssues().get(0);
	}

	@Test
	void validPlanHasNoIssues() {
		assertTrue(SeatingPlanValidator.validate(plan()).isEmpty());
	}

	// --- Name (SPL-001) ---

	@Test
	void planWithoutNameIsAnErrorAndNamesTheHall() {
		for (String name : new String[] { null, "", "  " }) {
			SeatingPlan plan = plan();
			plan.setName(name);

			ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

			assertEquals("SPL-001", issue.code());
			assertEquals(Severity.ERROR, issue.severity());
			assertEquals("Der Name einer Bestuhlung in Halle Festhalle darf nicht leer sein.", issue.message());
		}
	}

	// --- doppelte Tische (SPL-002) ---

	@Test
	void sameLetterAndNumberTwiceGivesOneMessagePerName() {
		SeatingPlan plan = plan();
		Table second = plan.addTable(row(plan));
		second.setTableNumber(1);
		Table third = plan.addTable(row(plan));
		third.setTableNumber(1);

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("SPL-002", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Tisch A1 kommt in Bestuhlung Prunksitzung in Halle Festhalle mehrfach vor.", issue.message());
	}

	@Test
	void sameNumberInDifferentLettersIsFine() {
		SeatingPlan plan = plan();
		TableRow rowB = plan.addRow();
		rowB.setLabel("B");
		plan.addTable(rowB);

		assertTrue(SeatingPlanValidator.validate(plan).isEmpty());
	}

	@Test
	void rowsWithoutLetterShareOneNumberScope() {
		SeatingPlan plan = plan();
		TableRow first = plan.addRow();
		TableRow second = plan.addRow();
		plan.addTable(first).setTableNumber(5);
		plan.addTable(second).setTableNumber(5);

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("SPL-002", issue.code());
		assertTrue(issue.message().startsWith("Tisch 5 kommt in"), issue.message());
	}

	// --- Reihen (ROW-001) ---

	@Test
	void sameLetterOnSeveralRowsIsAnError() {
		SeatingPlan plan = plan();
		TableRow other = plan.addRow();
		other.setLabel("A");
		TableRow third = plan.addRow();
		third.setLabel("A");

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("ROW-001", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Der Buchstabe A wird in Bestuhlung Prunksitzung in Halle Festhalle für mehrere Reihen verwendet.",
				issue.message());
	}

	@Test
	void lettersDifferingOnlyInCaseAreADuplicate() {
		SeatingPlan plan = plan();
		plan.addRow().setLabel(" a ");

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("ROW-001", issue.code());
		assertEquals("Der Buchstabe A wird in Bestuhlung Prunksitzung in Halle Festhalle für mehrere Reihen verwendet.",
				issue.message());
	}

	@Test
	void severalRowsWithoutLetterAreNotADuplicate() {
		SeatingPlan plan = plan();
		plan.addRow();
		plan.addRow();

		assertTrue(SeatingPlanValidator.validate(plan).isEmpty());
	}

	// --- Wertebereiche der Reihe (ROW-002) ---

	@Test
	void rowValuesWithinTheirRangesAreFine() {
		SeatingPlan plan = plan();
		TableRow row = row(plan);
		row.setAngle(-360);
		row.setTableRotation(360);
		row.setPosX(-10_000);
		row.setPosY(10_000);
		row.setTableGap(1_000);
		row.setAisleWidth(0);
		row.setAisleAfter(1);

		assertTrue(TableRowValidator.validate(row).isEmpty());

		row.setAisleAfter(1_000);
		row.setTableGap(0);
		assertTrue(TableRowValidator.validate(row).isEmpty());
	}

	@Test
	void rowAngleMustBeBetweenMinus360And360() {
		TableRow row = row(plan());
		row.setAngle(361);

		ValidationIssue issue = only(TableRowValidator.validate(row));

		assertEquals("ROW-002", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Winkel von Reihe A in Bestuhlung Prunksitzung in Halle Festhalle liegt mit 361 nicht zwischen -360 und 360.",
				issue.message());
	}

	@Test
	void rowTableRotationMustBeBetweenMinus360And360() {
		TableRow row = row(plan());
		row.setTableRotation(-361);

		ValidationIssue issue = only(TableRowValidator.validate(row));

		assertEquals("ROW-002", issue.code());
		assertTrue(issue.message().startsWith("Tischdrehung von Reihe A"), issue.message());
	}

	@Test
	void rowPositionMustBeWithinPlusMinusTenThousand() {
		TableRow row = row(plan());
		row.setPosX(10_001);
		row.setPosY(-10_001);

		ValidationResult result = TableRowValidator.validate(row);

		assertEquals(2, result.getIssues().size(), result.getIssues().toString());
		assertTrue(result.getIssues().stream().allMatch(i -> i.code().equals("ROW-002")));
		assertTrue(result.getIssues().stream().anyMatch(i -> i.message().startsWith("Position X von")));
		assertTrue(result.getIssues().stream().anyMatch(i -> i.message().startsWith("Position Y von")));
	}

	@Test
	void rowGapAndAisleWidthMustBeBetweenZeroAndOneThousand() {
		TableRow row = row(plan());
		row.setTableGap(-1);
		row.setAisleWidth(1_000.5);

		ValidationResult result = TableRowValidator.validate(row);

		assertEquals(2, result.getIssues().size(), result.getIssues().toString());
		assertTrue(result.getIssues().stream().anyMatch(i -> i.message().startsWith("Tischabstand von")));
		assertTrue(result.getIssues().stream().anyMatch(i -> i.message().startsWith("Gangbreite von")
				&& i.message().endsWith("liegt mit 1000,5 nicht zwischen 0 und 1000.")));
	}

	@Test
	void aisleAfterIsOnlyCheckedWhenSetAndMustBeBetweenOneAndOneThousand() {
		TableRow row = row(plan());
		row.setAisleAfter(null);
		assertTrue(TableRowValidator.validate(row).isEmpty());

		row.setAisleAfter(0);
		ValidationIssue issue = only(TableRowValidator.validate(row));
		assertEquals("ROW-002", issue.code());
		assertTrue(issue.message().startsWith("Gang nach Tisch von Reihe A"), issue.message());
		assertTrue(issue.message().endsWith("liegt mit 0 nicht zwischen 1 und 1000."), issue.message());

		row.setAisleAfter(1_001);
		assertEquals("ROW-002", only(TableRowValidator.validate(row)).code());
	}

	@Test
	void rowWithoutLetterIsDescribedAsSuch() {
		SeatingPlan plan = plan();
		TableRow row = plan.addRow();
		row.setAngle(999);

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("Winkel von Reihe ohne Buchstaben in Bestuhlung Prunksitzung in Halle Festhalle liegt mit 999 nicht zwischen -360 und 360.",
				issue.message());
	}

	@Test
	void planValidationIncludesTheRowValues() {
		SeatingPlan plan = plan();
		row(plan).setAngle(400);

		assertEquals("ROW-002", only(SeatingPlanValidator.validate(plan)).code());
	}

	// --- Tische verweisen auf eine Reihe (TBL-005) ---

	@Test
	void tableWithUnknownRowIsAnError() {
		SeatingPlan plan = plan();
		plan.getTables().get(0).setRowId("gibt-es-nicht");

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("TBL-005", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Tisch 1 in Bestuhlung Prunksitzung in Halle Festhalle gehört zu keiner vorhandenen Reihe.",
				issue.message());
	}

	@Test
	void tableWithoutOrWithBlankRowIdIsAnError() {
		for (String rowId : new String[] { null, "", "  " }) {
			SeatingPlan plan = plan();
			plan.getTables().get(0).setRowId(rowId);

			ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

			assertEquals("TBL-005", issue.code());
			assertEquals(Severity.ERROR, issue.severity());
			assertEquals("Tisch 1 in Bestuhlung Prunksitzung in Halle Festhalle gehört zu keiner vorhandenen Reihe.",
					issue.message());
		}
	}

	@Test
	void planValidationIncludesTheTableRules() {
		SeatingPlan plan = plan();
		plan.getTables().get(0).setWidth(5_000);

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("TBL-003", issue.code());
		assertTrue(issue.message().startsWith("Breite von Tisch A1"), issue.message());
	}

	// --- Tische in der Halle und ohne Überlappung (TBL-007, SPL-004) ---

	/** Bestuhlung "Prunksitzung" in der Halle "Festhalle" ({@code hallSize} x {@code hallSize}) mit Reihe "A", noch ohne Tische. */
	private static SeatingPlan emptyPlan(double hallSize) {
		Hall hall = new Hall();
		hall.setName("Festhalle");
		hall.setHallWidth(hallSize);
		hall.setHallHeight(hallSize);
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Prunksitzung");
		plan.addRow().setLabel("A");
		return plan;
	}

	/** Tisch 50 x 100 mit der linken oberen Ecke bei ({@code x}|{@code y}) in der ersten Reihe. */
	private static Table addTable(SeatingPlan plan, double x, double y) {
		Table table = plan.addTable(row(plan));
		table.setWidth(50);
		table.setHeight(100);
		table.setPosX(x);
		table.setPosY(y);
		return table;
	}

	private static Seat addSeat(Table table, double x, double y) {
		Seat seat = table.addSeat();
		seat.setWidth(20);
		seat.setPosX(x);
		seat.setPosY(y);
		return seat;
	}

	@Test
	void overlappingTablesAreReportedOnce() {
		SeatingPlan plan = emptyPlan(2_000);
		addTable(plan, 0, 0);
		addTable(plan, 20, 0);
		addTable(plan, 500, 0);

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("SPL-004", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Tisch A1 und Tisch A2 überlappen sich in Bestuhlung Prunksitzung in Halle Festhalle (Tischplatten oder Stühle).",
				issue.message());
	}

	@Test
	void seatReachingIntoNeighbourOverlaps() {
		SeatingPlan plan = emptyPlan(2_000);
		Table first = addTable(plan, 0, 0);
		addTable(plan, 100, 0);
		addSeat(first, 105, 50);

		assertEquals("SPL-004", only(SeatingPlanValidator.validate(plan)).code());
	}

	@Test
	void tableWithoutSeatsAndEmptyPlanAreFine() {
		SeatingPlan plan = emptyPlan(2_000);
		assertTrue(SeatingPlanValidator.validate(plan).isEmpty());

		addTable(plan, 0, 0);
		addTable(plan, 500, 0);
		assertTrue(SeatingPlanValidator.validate(plan).isEmpty());
	}

	@Test
	void seatOutsideHallWarns() {
		SeatingPlan plan = emptyPlan(2_000);
		Table first = addTable(plan, 0, 0);
		addSeat(first, -15, 50);

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("TBL-007", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Tisch A1 in Bestuhlung Prunksitzung in Halle Festhalle ragt mit Tischplatte oder Stühlen über den Rand der Halle hinaus.",
				issue.message());
	}

	@Test
	void rotatedTableCornerOutsideWarns() {
		SeatingPlan plan = emptyPlan(2_000);
		Table table = addTable(plan, 0, 0);
		table.setHeight(320);
		table.setRotation(30);

		assertEquals("TBL-007", only(SeatingPlanValidator.validate(plan)).code());
	}

	@Test
	void hallWithoutSizeSkipsTbl007() {
		SeatingPlan plan = emptyPlan(0);
		Table first = addTable(plan, 0, 0);
		addSeat(first, -15, 50);

		assertTrue(SeatingPlanValidator.validate(plan).isEmpty());
	}

	@Test
	void planWithoutHallStillChecksOverlap() {
		SeatingPlan plan = new SeatingPlan();
		plan.setName("Prunksitzung");
		TableRow row = plan.addRow();
		row.setLabel("A");
		Table first = plan.addTable(row);
		first.setWidth(50);
		first.setHeight(100);
		Table second = plan.addTable(row);
		second.setWidth(50);
		second.setHeight(100);
		second.setPosX(20);

		assertEquals("SPL-004", only(SeatingPlanValidator.validate(plan)).code());
	}

	@Test
	void duplicateTableIdsDoNotInventOverlaps() {
		SeatingPlan plan = emptyPlan(2_000);
		Table first = new Table("t1");
		first.setWidth(50);
		first.setHeight(100);
		Table second = new Table("t1");
		second.setWidth(50);
		second.setHeight(100);
		second.setPosX(500);
		plan.setTables(List.of(first, second));

		assertTrue(SeatingPlanValidator.validate(plan).getIssues().stream().noneMatch(i -> i.code().equals("SPL-004")));
	}

	// --- Tische und Hallenobjekte (SPL-005) ---

	private static HallObject addObject(SeatingPlan plan, String name, double width, double height, double x, double y) {
		HallObject object = plan.getParent().addHallObject(name);
		object.setWidth(width);
		object.setHeight(height);
		object.setPosX(x);
		object.setPosY(y);
		return object;
	}

	@Test
	void tableHalfOnStageWarns() {
		SeatingPlan plan = emptyPlan(2_000);
		addObject(plan, "Bühne", 400, 80, 0, 300);
		addTable(plan, 100, 250);

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("SPL-005", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Tisch A1 in Bestuhlung Prunksitzung in Halle Festhalle überlappt teilweise mit Hallenobjekt Bühne (Tischplatte oder Stühle).",
				issue.message());
	}

	@Test
	void tableFullyOnAreaIsFine() {
		SeatingPlan plan = emptyPlan(2_000);
		addObject(plan, "Tanzfläche", 600, 600, 0, 0);
		Table table = addTable(plan, 100, 100);
		addSeat(table, 60, 150);

		assertTrue(SeatingPlanValidator.validate(plan).isEmpty());
	}

	@Test
	void seatOverAreaEdgeWarns() {
		SeatingPlan plan = emptyPlan(2_000);
		addObject(plan, "Tanzfläche", 600, 600, 0, 0);
		Table table = addTable(plan, 100, 100);
		addSeat(table, 595, 150);

		assertEquals("SPL-005", only(SeatingPlanValidator.validate(plan)).code());
	}

	@Test
	void circleObjectPartialOverlap() {
		SeatingPlan plan = emptyPlan(2_000);
		HallObject circle = addObject(plan, "Rondell", 100, 100, 120, 200);
		circle.setShape(Shape.CIRCLE);
		addTable(plan, 100, 250);

		assertEquals("SPL-005", only(SeatingPlanValidator.validate(plan)).code());
	}

	@Test
	void noHallNoSpl005() {
		SeatingPlan plan = new SeatingPlan();
		plan.setName("Prunksitzung");
		TableRow row = plan.addRow();
		row.setLabel("A");
		Table table = plan.addTable(row);
		table.setWidth(50);
		table.setHeight(100);

		assertTrue(SeatingPlanValidator.validate(plan).getIssues().stream().noneMatch(i -> i.code().equals("SPL-005")));
	}

	@Test
	void tableWithoutRowTakesPartInNoLayoutCheck() {
		SeatingPlan plan = emptyPlan(2_000);
		Table first = addTable(plan, 500, 500);
		first.setRowId("gibt-es-nicht");
		addSeat(first, 0, 0);
		Table second = addTable(plan, 1_000, 500);
		second.setRowId("gibt-es-nicht");
		addSeat(second, 0, 0);

		List<String> codes = SeatingPlanValidator.validate(plan).getIssues().stream().map(ValidationIssue::code).toList();

		assertEquals(2, codes.stream().filter("TBL-005"::equals).count());
		assertTrue(codes.stream().noneMatch(c -> c.equals("TBL-007") || c.equals("SPL-004") || c.equals("SPL-005")));
	}

	@Test
	void seatWithNaNPositionIsIgnoredByGeometry() {
		SeatingPlan plan = emptyPlan(2_000);
		Table first = addTable(plan, 0, 0);
		addSeat(first, Double.NaN, 50);

		assertTrue(SeatingPlanValidator.validate(plan).getIssues().stream()
				.noneMatch(i -> i.code().equals("SPL-004") || i.code().equals("TBL-007")));
	}
}
