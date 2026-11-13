package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.model.TextAlignH;
import de.eltviller_carneval_verein.karten.model.TextAlignV;

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

	// --- KARV-128: Maße und Beschriftung der Tische und Sitze aller gewählten Reihen ---

	private Table table(TableRow row, double width, int seats, double seatWidth) {
		Table table = plan.addTable(row);
		table.setWidth(width);
		table.setHeight(320);
		for (int i = 0; i < seats; i++) {
			table.addSeat().setWidth(seatWidth);
		}
		return table;
	}

	@Test
	void tablesAreTheTablesOfAllRowsInOrder() {
		TableRow a = row("A", 0, 0);
		TableRow b = row("B", 0, 0);
		Table a1 = table(a, 50, 0, 0);
		Table b1 = table(b, 50, 0, 0);
		Table a2 = table(a, 50, 0, 0);

		assertEquals(List.of(a1, a2, b1), new TableRowBulkEdit(List.of(a, b)).tables());
	}

	@Test
	void tableSizeIsCommonOnlyWhenAllTablesAgree() {
		TableRow a = row("A", 0, 0);
		TableRow b = row("B", 0, 0);
		table(a, 50, 0, 0);
		table(b, 50, 0, 0);
		assertEquals(Optional.of(50.0), new TableRowBulkEdit(List.of(a, b)).tableWidth().common());
		assertEquals(Optional.of(320.0), new TableRowBulkEdit(List.of(a, b)).tableHeight().common());

		assertFalse(new TableRowBulkEdit(List.of(a, b)).tableWidth().mixed());

		table(b, 60, 0, 0);
		assertEquals(Optional.empty(), new TableRowBulkEdit(List.of(a, b)).tableWidth().common());
		assertTrue(new TableRowBulkEdit(List.of(a, b)).tableWidth().mixed());
	}

	@Test
	void fieldWithoutTargetsIsNotMixed() {
		TableRow a = row("A", 0, 0);
		table(a, 50, 0, 0);

		assertFalse(new TableRowBulkEdit(List.of(a)).seatWidth().mixed(), "Tische ohne Sitze haben keinen verschiedenen Durchmesser");
		assertFalse(new TableRowBulkEdit(List.of(row("B", 0, 0))).tableWidth().mixed());
	}

	@Test
	void changedTableSizeGoesToEveryTableAlsoManualOnesWhichKeepTheirCorner() {
		TableRow a = row("A", 0, 0);
		Table automatic = table(a, 50, 0, 0);
		Table manual = table(a, 60, 0, 0);
		manual.setManualPos(true);
		manual.setPosX(7);
		manual.setPosY(8);
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(a));

		edit.tableWidth().change(70.0);
		edit.apply();

		assertEquals(70, automatic.getWidth());
		assertEquals(70, manual.getWidth());
		assertEquals(7, manual.getPosX());
		assertEquals(8, manual.getPosY());
	}

	@Test
	void seatWidthCountsOnlyTablesWithSeatsAndGoesToEverySeat() {
		TableRow a = row("A", 0, 0);
		Table withSeats = table(a, 50, 3, 45);
		table(a, 50, 0, 0);
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(a));
		assertEquals(Optional.of(45.0), edit.seatWidth().common());

		edit.seatWidth().change(40.0);
		edit.apply();

		withSeats.getSeats().forEach(seat -> assertEquals(40, seat.getWidth()));
	}

	@Test
	void rowWithoutTablesHasNoCommonValuesAndChangesNothing() {
		TableRow empty = row("A", 0, 0);
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(empty));
		assertEquals(Optional.empty(), edit.tableWidth().common());
		assertEquals(Optional.empty(), edit.seatWidth().common());
		assertEquals(Optional.empty(), edit.textAuto().common());

		edit.tableWidth().change(70.0);
		edit.textSize().change(30.0);

		assertFalse(edit.hasChanges(), "ohne Tische gibt es nichts zu ändern");
		edit.apply();
		assertTrue(plan.getTablesOf(empty).isEmpty());
	}

	@Test
	void textFieldsWorkLikeTheSizes() {
		TableRow a = row("A", 0, 0);
		Table first = table(a, 50, 0, 0);
		Table second = table(a, 50, 0, 0);
		second.setTextAuto(false);
		TableRowBulkEdit edit = new TableRowBulkEdit(List.of(a));
		assertEquals(Optional.empty(), edit.textAuto().common());
		assertEquals(Optional.of(TextAlignH.CENTER), edit.textAlignH().common());

		edit.textAlignH().change(TextAlignH.LEFT);
		edit.textAlignV().change(TextAlignV.TOP);
		edit.textSize().change(30.0);
		edit.textRotation().change(90.0);
		edit.apply();

		for (Table table : List.of(first, second)) {
			assertEquals(TextAlignH.LEFT, table.getTextAlignH());
			assertEquals(TextAlignV.TOP, table.getTextAlignV());
			assertEquals(30, table.getTextSize());
			assertEquals(90, table.getTextRotation());
		}
	}
}
