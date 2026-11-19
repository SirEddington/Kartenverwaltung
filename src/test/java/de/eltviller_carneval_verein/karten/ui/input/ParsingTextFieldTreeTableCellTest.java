package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.ui.StatusLabel;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.converter.DefaultStringConverter;

/**
 * Dieselben Fälle wie {@link ParsingTextFieldTableCellTest}, mit {@link ParsingTextFieldTreeTableCell} in einer flachen
 * {@link TreeTableView} (ohne sichtbare Wurzel, die Zeilen direkt darunter); dazu die zusätzliche Zeilenbedingung, die in
 * jeder Fabrik der Baumzelle gilt.
 */
class ParsingTextFieldTreeTableCellTest {

	/** Eine Zeile der Testtabelle: ein Wert und ob er sich bearbeiten lässt. */
	private record Row(String value, boolean editable) {
	}

	@BeforeAll
	static void startFx() {
		try {
			Platform.startup(() -> {
			});
		} catch (IllegalStateException alreadyStarted) {
			// der Toolkit läuft schon (anderer Test im selben Lauf)
		}
		// Ein Test zeigt ein Fenster: Schließt es, bliebe JavaFX sonst für alle folgenden Tests beendet
		Platform.setImplicitExit(false);
	}

	private static <T> T onFx(Supplier<T> action) throws Exception {
		CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.get());
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		});
		return result.get(10, TimeUnit.SECONDS);
	}

	/** Eine Baumtabelle mit den Zeilen direkt unter einer unsichtbaren Wurzel. */
	@SafeVarargs
	private static <S> TreeTableView<S> treeTable(S... rows) {
		TreeTableView<S> table = new TreeTableView<>();
		table.setEditable(true);
		TreeItem<S> root = new TreeItem<>();
		root.setExpanded(true);
		for (S row : rows) {
			root.getChildren().add(new TreeItem<>(row));
		}
		table.setRoot(root);
		table.setShowRoot(false);
		return table;
	}

	private static void layOut(TreeTableView<?> table) {
		new Scene(table, 300, 200);
		table.applyCss();
		table.layout();
	}

	private static <S> S row(TreeTableView<S> table, int index) {
		return table.getRoot().getChildren().get(index).getValue();
	}

	/** Eine bearbeitbare Tabelle mit einer gesperrten und einer freien Zeile, ausgelegt wie auf dem Bildschirm. */
	private static TreeTableView<Row> table() {
		TreeTableView<Row> table = treeTable(new Row("gesperrt", false), new Row("frei", true));
		TreeTableColumn<Row, String> column = new TreeTableColumn<>("Wert");
		column.setEditable(true);
		column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getValue().value()));
		column.setCellFactory(ParsingTextFieldTreeTableCell.forTableColumn(new DefaultStringConverter(), InputIssue.NOT_A_NUMBER, "Wert",
				row -> null, Row::editable));
		table.getColumns().add(column);
		layOut(table);
		return table;
	}

	@Test
	void editOfALockedRowLeavesNoEditingCellBehind() throws Exception {
		TreeTablePosition<Row, ?> editing = onFx(() -> {
			TreeTableView<Row> table = table();
			table.edit(0, table.getColumns().get(0));
			return table.getEditingCell();
		});

		assertNull(editing, "eine gesperrte Zeile darf die Tabelle nicht im Bearbeiten festhalten");
	}

	@Test
	void editOfAFreeRowStillStarts() throws Exception {
		Integer editingRow = onFx(() -> {
			TreeTableView<Row> table = table();
			table.edit(1, table.getColumns().get(0));
			return table.getEditingCell() == null ? null : table.getEditingCell().getRow();
		});

		assertEquals(1, editingRow);
	}

	/** Eine Zeile mit änderbarem Text, wie eine Buchung: Der Commit-Handler der Spalte schreibt hinein. */
	private static final class TextRow {
		String text;
		String second = "";
		/** Ob sich die zweite Spalte dieser Zeile bearbeiten lässt (wie der gezahlte Betrag nur bei bezahlten Buchungen). */
		final boolean secondEditable;

		TextRow(String text, boolean secondEditable) {
			this.text = text;
			this.secondEditable = secondEditable;
		}
	}

	/**
	 * Zwei bearbeitbare Textspalten über zwei Zeilen, ausgelegt wie auf dem Bildschirm; die zweite Spalte der ersten Zeile
	 * lässt sich bearbeiten, die der zweiten nicht.
	 */
	private static TreeTableView<TextRow> textTable() {
		TreeTableView<TextRow> table = treeTable(new TextRow("alt", true), new TextRow("zwei", false));
		TreeTableColumn<TextRow, String> column = new TreeTableColumn<>("Text");
		column.setEditable(true);
		column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getValue().text));
		column.setCellFactory(ParsingTextFieldTreeTableCell.forTextColumn());
		column.setOnEditCommit(edit -> edit.getRowValue().getValue().text = edit.getNewValue());
		TreeTableColumn<TextRow, String> second = new TreeTableColumn<>("Zweite");
		second.setEditable(true);
		second.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getValue().second));
		second.setCellFactory(ParsingTextFieldTreeTableCell.forTableColumn(new DefaultStringConverter(), InputIssue.NOT_A_NUMBER, "Zweite",
				row -> null, row -> row.secondEditable));
		second.setOnEditCommit(edit -> edit.getRowValue().getValue().second = edit.getNewValue());
		table.getColumns().add(column);
		table.getColumns().add(second);
		layOut(table);
		return table;
	}

	/** Tab bzw. Umschalt+Tab im Feld der Zelle, die gerade bearbeitet wird. */
	private static void pressTab(ParsingTextFieldTreeTableCell<?, ?> cell, boolean shift) {
		Event.fireEvent(cell.getGraphic(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, shift, false, false, false));
	}

	private static String editingCellOf(TreeTableView<TextRow> table) {
		TreeTablePosition<TextRow, ?> editing = table.getEditingCell();
		return editing == null ? "keine" : editing.getRow() + ":" + editing.getTableColumn().getText();
	}

	/** Wie {@link #textTable()}, mit 50 Zeilen, von denen nur einige auf einmal zu sehen sind; die Tabelle ist nach unten gescrollt. */
	private static TreeTableView<TextRow> scrolledTextTable() {
		TreeTableView<TextRow> table = textTable();
		for (int row = 2; row < 50; row++) {
			table.getRoot().getChildren().add(new TreeItem<>(new TextRow("Zeile " + row, true)));
		}
		table.scrollTo(20);
		table.layout();
		return table;
	}

	private static VirtualFlow<?> flowOf(TreeTableView<?> table) {
		return (VirtualFlow<?>) table.lookup(".virtual-flow");
	}

	@Test
	void tabWithinARowLeavesTheViewWhereItIs() throws Exception {
		int[] tops = onFx(() -> {
			TreeTableView<TextRow> table = scrolledTextTable();
			int before = flowOf(table).getFirstVisibleCell().getIndex();
			pressTab(typeInto(table, before + 2, "neu"), false);
			table.layout();
			return new int[] { before, flowOf(table).getFirstVisibleCell().getIndex() };
		});

		assertEquals(tops[0], tops[1], "die bearbeitete Zeile springt nicht nach oben");
	}

	@Test
	void tabIntoTheRowBelowTheViewScrollsOnlyAsFarAsNeeded() throws Exception {
		int[] result = onFx(() -> {
			TreeTableView<TextRow> table = scrolledTextTable();
			int before = flowOf(table).getFirstVisibleCell().getIndex();
			int last = flowOf(table).getLastVisibleCell().getIndex();
			table.edit(last, table.getColumns().get(1));
			for (Node node : table.lookupAll(".tree-table-cell")) {
				if (node instanceof ParsingTextFieldTreeTableCell<?, ?> cell && cell.isEditing()) {
					pressTab(cell, false);
					break;
				}
			}
			table.layout();
			return new int[] { before, last, flowOf(table).getFirstVisibleCell().getIndex(), flowOf(table).getLastVisibleCell().getIndex(),
					table.getEditingCell() == null ? -1 : table.getEditingCell().getRow() };
		});

		assertEquals(result[1] + 1, result[4], "Tab am Zeilenende bearbeitet die nächste Zeile");
		assertTrue(result[3] >= result[1] + 1, "die nächste Zeile ist zu sehen");
		assertTrue(result[2] <= result[0] + 2, "die Tabelle scrollt nur so weit wie nötig, oben stand " + result[0] + ", jetzt " + result[2]);
	}

	@Test
	void tabCommitsAndStartsEditingTheNextCell() throws Exception {
		String[] result = onFx(() -> {
			TreeTableView<TextRow> table = textTable();
			pressTab(typeInto(table, 0, "neu"), false);
			return new String[] { row(table, 0).text, editingCellOf(table) };
		});

		assertEquals("neu", result[0]);
		assertEquals("0:Zweite", result[1]);
	}

	@Test
	void shiftTabStartsEditingThePreviousCellInTheRowAbove() throws Exception {
		String editing = onFx(() -> {
			TreeTableView<TextRow> table = textTable();
			pressTab(typeInto(table, 1, "neu"), true);
			return editingCellOf(table);
		});

		assertEquals("0:Zweite", editing);
	}

	@Test
	void tabOntoALockedCellOnlyMovesTheFocus() throws Exception {
		String[] result = onFx(() -> {
			TreeTableView<TextRow> table = textTable();
			pressTab(typeInto(table, 1, "neu"), false);
			TreeTablePosition<TextRow, ?> focused = table.getFocusModel().getFocusedCell();
			return new String[] { editingCellOf(table), focused.getRow() + ":" + focused.getTableColumn().getText() };
		});

		assertEquals("keine", result[0]);
		assertEquals("1:Zweite", result[1]);
	}

	/** Startet das Bearbeiten der Zeile und tippt den Text ins Feld (ohne Enter). */
	private static ParsingTextFieldTreeTableCell<?, ?> typeInto(TreeTableView<TextRow> table, int row, String text) {
		table.edit(row, table.getColumns().get(0));
		for (Node node : table.lookupAll(".tree-table-cell")) {
			if (node instanceof ParsingTextFieldTreeTableCell<?, ?> cell && cell.isEditing() && cell.getGraphic() instanceof TextField field) {
				field.setText(text);
				return cell;
			}
		}
		throw new AssertionError("keine Zelle im Bearbeiten");
	}

	@Test
	void editEndedByTheTableKeepsTheInput() throws Exception {
		// So beendet JavaFX das Bearbeiten beim Klick auf eine andere Zeile (TreeTableCellBehavior.edit)
		String text = onFx(() -> {
			TreeTableView<TextRow> table = textTable();
			typeInto(table, 0, "neu");
			table.edit(-1, null);
			return row(table, 0).text;
		});

		assertEquals("neu", text);
	}

	@Test
	void editMovedToAnotherRowKeepsTheInputOfTheFirst() throws Exception {
		String[] result = onFx(() -> {
			TreeTableView<TextRow> table = textTable();
			typeInto(table, 0, "neu");
			table.edit(1, table.getColumns().get(0));
			return new String[] { row(table, 0).text, row(table, 1).text,
					table.getEditingCell() == null ? "keine" : String.valueOf(table.getEditingCell().getRow()) };
		});

		assertEquals("neu", result[0]);
		assertEquals("zwei", result[1]);
		assertEquals("1", result[2], "die neue Zelle bleibt im Bearbeiten");
	}

	/** Eine Zeile mit einem Datum, wie das Reservierungsdatum einer Buchung. */
	private static final class DateRow {
		LocalDate date;

		DateRow(LocalDate date) {
			this.date = date;
		}
	}

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

	@Test
	void unreadableDateEndedByTheTableTakesTodayInAnEmptyFieldAndKeepsAFilledOne() throws Exception {
		LocalDate[] dates = onFx(() -> {
			TreeTableView<DateRow> table = treeTable(new DateRow(null), new DateRow(LocalDate.of(2026, 1, 1)));
			TreeTableColumn<DateRow, LocalDate> column = new TreeTableColumn<>("Datum");
			column.setEditable(true);
			column.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getValue().date));
			column.setCellFactory(ParsingTextFieldTreeTableCell.forDateColumn("Datum", row -> null, () -> TODAY));
			column.setOnEditCommit(edit -> edit.getRowValue().getValue().date = edit.getNewValue());
			table.getColumns().add(column);
			// Die Meldung INP-002 braucht eine sichtbare Fußzeile, sonst öffnet StatusMessage einen Dialog und wartet
			Stage stage = new Stage();
			stage.setOpacity(0);
			stage.setScene(new Scene(new VBox(table, new StatusLabel()), 300, 200));
			stage.show();
			try {
				for (int row = 0; row < 2; row++) {
					table.edit(row, column);
					for (Node node : table.lookupAll(".tree-table-cell")) {
						if (node instanceof ParsingTextFieldTreeTableCell<?, ?> cell && cell.isEditing() && cell.getGraphic() instanceof TextField field) {
							field.setText("kein Datum");
						}
					}
					table.edit(-1, null);
				}
			} finally {
				stage.hide();
			}
			return new LocalDate[] { row(table, 0).date, row(table, 1).date };
		});

		assertEquals(TODAY, dates[0]);
		assertEquals(LocalDate.of(2026, 1, 1), dates[1]);
	}

	@Test
	void escapeDiscardsTheInput() throws Exception {
		// Die Tabelle beendet bei Esc selbst mit table.edit(-1, null): Das darf nicht als Übernahme gelten
		String text = onFx(() -> {
			TreeTableView<TextRow> table = textTable();
			Node field = typeInto(table, 0, "neu").getGraphic();
			Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
			Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.ESCAPE, false, false, false, false));
			return row(table, 0).text + "|" + editingCellOf(table);
		});

		assertEquals("alt|keine", text);
	}

	@Test
	void cancelOfTheCellItselfStillDiscards() throws Exception {
		// Esc bricht über die Zelle selbst ab, während die Tabelle sie noch als bearbeitet führt
		String text = onFx(() -> {
			TreeTableView<TextRow> table = textTable();
			typeInto(table, 0, "neu").cancelEdit();
			return row(table, 0).text;
		});

		assertEquals("alt", text);
	}

	/**
	 * Die zusätzliche Zeilenbedingung ({@code rowEditable}) gilt in jeder Fabrik: Eine Zeile, die sie nicht erfüllt (in der
	 * Kartentabelle eine Gruppenzeile), lässt sich nicht bearbeiten, eine andere schon.
	 */
	@Test
	void zeileOhneBedingungNichtBearbeitbar() throws Exception {
		String[] result = onFx(() -> {
			TreeTableView<Row> table = treeTable(new Row("Gruppe", false), new Row("Buchung", true));
			TreeTableColumn<Row, String> text = new TreeTableColumn<>("Text");
			text.setCellFactory(ParsingTextFieldTreeTableCell.forTextColumn(Row::editable));
			TreeTableColumn<Row, String> parsed = new TreeTableColumn<>("Gelesen");
			parsed.setCellFactory(ParsingTextFieldTreeTableCell.forTableColumn(new DefaultStringConverter(), InputIssue.NOT_A_NUMBER, "Gelesen",
					row -> null, null, Row::editable));
			TreeTableColumn<Row, String> both = new TreeTableColumn<>("Beide");
			// die Bedingung der Spalte lässt beide Zeilen zu, die der Zeile nur die zweite
			both.setCellFactory(ParsingTextFieldTreeTableCell.forTableColumn(new DefaultStringConverter(), InputIssue.NOT_A_NUMBER, "Beide",
					row -> null, row -> true, Row::editable));
			TreeTableColumn<Row, LocalDate> date = new TreeTableColumn<>("Datum");
			date.setCellValueFactory(cell -> new SimpleObjectProperty<>());
			date.setCellFactory(ParsingTextFieldTreeTableCell.forDateColumn("Datum", row -> null, () -> TODAY, Row::editable));
			for (TreeTableColumn<Row, String> column : List.of(text, parsed, both)) {
				column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getValue().value()));
			}
			table.getColumns().add(text);
			table.getColumns().add(parsed);
			table.getColumns().add(both);
			table.getColumns().add(date);
			layOut(table);
			StringBuilder locked = new StringBuilder();
			StringBuilder free = new StringBuilder();
			for (TreeTableColumn<Row, ?> column : table.getColumns()) {
				table.edit(0, column);
				locked.append(table.getEditingCell() == null ? "-" : "x");
				table.edit(-1, null);
				table.edit(1, column);
				free.append(table.getEditingCell() == null ? "-" : "x");
				table.edit(-1, null);
			}
			return new String[] { locked.toString(), free.toString() };
		});

		assertEquals("----", result[0], "die Zeile ohne Bedingung lässt sich in keiner Spalte bearbeiten");
		assertEquals("xxxx", result[1], "die Zeile mit Bedingung in jeder");
	}
}
