package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
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
		assertEquals("Tisch 1 in Bestuhlung Prunksitzung in Halle Festhalle verweist auf eine Reihe, die es nicht gibt.",
				issue.message());
	}

	@Test
	void tableWithoutRowIdIsLeftAlone() {
		SeatingPlan plan = plan();
		plan.getTables().get(0).setRowId(null);

		assertTrue(SeatingPlanValidator.validate(plan).isEmpty());
	}

	@Test
	void planValidationIncludesTheTableRules() {
		SeatingPlan plan = plan();
		plan.getTables().get(0).setWidth(5_000);

		ValidationIssue issue = only(SeatingPlanValidator.validate(plan));

		assertEquals("TBL-003", issue.code());
		assertTrue(issue.message().startsWith("Breite von Tisch A1"), issue.message());
	}
}
