package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;

class TableRowBulkEditTest {

	private final SeatingPlan plan = new SeatingPlan();

	private TableRow row(String label, double angle, double tableGap) {
		TableRow row = plan.addRow();
		row.setLabel(label);
		row.setAngle(angle);
		row.setTableGap(tableGap);
		return row;
	}

	// --- gemeinsamer Wert ---

	@Test
	void commonValueIsEmptyWhenRowsDifferAndPresentWhenTheyAgree() {
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(row("A", 0, 20), row("B", 30, 20)));

		assertEquals(Optional.empty(), edit.angle().common());
		assertEquals(Optional.of(20.0), edit.tableGap().common());
	}

	@Test
	void missingAisleCountsAsZero() {
		TableRow first = row("A", 0, 0);
		TableRow second = row("B", 0, 0);
		second.setAisleAfter(2);

		assertEquals(Optional.empty(), new TableRowBulkEdit(List.of(first, second)).aisleAfter().common());
		second.setAisleAfter(null);
		assertEquals(Optional.of(0), new TableRowBulkEdit(List.of(first, second)).aisleAfter().common());
	}

	// --- Änderungen übernehmen ---

	@Test
	void writesOnlyChangedFields() {
		TableRow first = row("A", 0, 20);
		TableRow second = row("B", 30, 20);
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(first, second));

		edit.aisleWidth().change(150.0);
		edit.apply();

		assertEquals(150.0, first.getAisleWidth());
		assertEquals(150.0, second.getAisleWidth());
		assertEquals(0.0, first.getAngle());
		assertEquals(30.0, second.getAngle());
		assertEquals(20.0, first.getTableGap());
		assertEquals(20.0, second.getTableGap());
	}

	@Test
	void appliesAngleTableRotationAndGapToEveryRow() {
		TableRow first = row("A", 0, 20);
		TableRow second = row("B", 30, 40);
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(first, second));

		edit.angle().change(90.0);
		edit.tableRotation().change(45.0);
		edit.tableGap().change(10.0);
		edit.apply();

		for (TableRow row : List.of(first, second)) {
			assertEquals(90.0, row.getAngle());
			assertEquals(45.0, row.getTableRotation());
			assertEquals(10.0, row.getTableGap());
		}
	}

	@Test
	void labelAndPositionAreNeverTouched() {
		TableRow first = row("A", 0, 20);
		first.setPosX(100);
		first.setPosY(200);
		TableRow second = row("", 30, 20);
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(first, second));

		edit.angle().change(15.0);
		edit.apply();

		assertEquals("A", first.getLabel());
		assertEquals("", second.getLabel());
		assertEquals(100.0, first.getPosX());
		assertEquals(200.0, first.getPosY());
	}

	@Test
	void aisleAfterZeroRemovesTheAisle() {
		TableRow first = row("A", 0, 0);
		first.setAisleAfter(2);
		TableRow second = row("B", 0, 0);
		second.setAisleAfter(3);
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(first, second));

		edit.aisleAfter().change(0);
		edit.apply();

		assertNull(first.getAisleAfter());
		assertNull(second.getAisleAfter());
	}

	@Test
	void aisleAfterIsAppliedToEveryRow() {
		TableRow first = row("A", 0, 0);
		TableRow second = row("B", 0, 0);
		second.setAisleAfter(3);
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(first, second));

		edit.aisleAfter().change(2);
		edit.apply();

		assertEquals(2, first.getAisleAfter());
		assertEquals(2, second.getAisleAfter());
	}

	@Test
	void changingBackToTheCommonValueIsNoChange() {
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(row("A", 0, 20), row("B", 30, 20)));

		edit.tableGap().change(50.0);
		assertTrue(edit.hasChanges());
		edit.tableGap().change(20.0);

		assertFalse(edit.hasChanges());
	}

	@Test
	void nullMeansNoInputAndWithdrawsEarlierInput() {
		TableRow first = row("A", 0, 20);
		TableRow second = row("B", 30, 20);
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(first, second));

		edit.angle().change(45.0);
		edit.angle().change(null);
		edit.apply();

		assertFalse(edit.hasChanges());
		assertEquals(0.0, first.getAngle());
		assertEquals(30.0, second.getAngle());
	}

	@Test
	void rowsAreExposedUnchanged() {
		TableRow only = row("A", 0, 0);

		assertEquals(List.of(only), new TableRowBulkEdit(List.of(only)).rows());
	}
}
