package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.converter.DefaultStringConverter;

/**
 * Die Zeilenbedingung der Textzelle ({@link ParsingTextFieldTableCell#forTableColumn} mit {@code editableRow}) und die
 * Übernahme, wenn die Tabelle das Bearbeiten beendet.
 */
class ParsingTextFieldTableCellTest {

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

	/** Eine bearbeitbare Tabelle mit einer gesperrten und einer freien Zeile, ausgelegt wie auf dem Bildschirm. */
	private static TableView<Row> table() {
		TableView<Row> table = new TableView<>();
		table.setEditable(true);
		TableColumn<Row, String> column = new TableColumn<>("Wert");
		column.setEditable(true);
		column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().value()));
		column.setCellFactory(ParsingTextFieldTableCell.forTableColumn(new DefaultStringConverter(), InputIssue.NOT_A_NUMBER, "Wert",
				row -> null, Row::editable));
		table.getColumns().add(column);
		table.getItems().addAll(new Row("gesperrt", false), new Row("frei", true));
		new Scene(table, 300, 200);
		table.applyCss();
		table.layout();
		return table;
	}

	@Test
	void editOfALockedRowLeavesNoEditingCellBehind() throws Exception {
		TablePosition<Row, ?> editing = onFx(() -> {
			TableView<Row> table = table();
			table.edit(0, table.getColumns().get(0));
			return table.getEditingCell();
		});

		assertNull(editing, "eine gesperrte Zeile darf die Tabelle nicht im Bearbeiten festhalten");
	}

	@Test
	void editOfAFreeRowStillStarts() throws Exception {
		Integer editingRow = onFx(() -> {
			TableView<Row> table = table();
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
	private static TableView<TextRow> textTable() {
		TableView<TextRow> table = new TableView<>();
		table.setEditable(true);
		TableColumn<TextRow, String> column = new TableColumn<>("Text");
		column.setEditable(true);
		column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().text));
		column.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		column.setOnEditCommit(edit -> edit.getRowValue().text = edit.getNewValue());
		TableColumn<TextRow, String> second = new TableColumn<>("Zweite");
		second.setEditable(true);
		second.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().second));
		second.setCellFactory(ParsingTextFieldTableCell.forTableColumn(new DefaultStringConverter(), InputIssue.NOT_A_NUMBER, "Zweite",
				row -> null, row -> row.secondEditable));
		second.setOnEditCommit(edit -> edit.getRowValue().second = edit.getNewValue());
		table.getColumns().add(column);
		table.getColumns().add(second);
		table.getItems().addAll(new TextRow("alt", true), new TextRow("zwei", false));
		new Scene(table, 300, 200);
		table.applyCss();
		table.layout();
		return table;
	}

	/** Tab bzw. Umschalt+Tab im Feld der Zelle, die gerade bearbeitet wird. */
	private static void pressTab(ParsingTextFieldTableCell<?, ?> cell, boolean shift) {
		Event.fireEvent(cell.getGraphic(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, shift, false, false, false));
	}

	private static String editingCellOf(TableView<TextRow> table) {
		TablePosition<TextRow, ?> editing = table.getEditingCell();
		return editing == null ? "keine" : editing.getRow() + ":" + editing.getTableColumn().getText();
	}

	@Test
	void tabCommitsAndStartsEditingTheNextCell() throws Exception {
		String[] result = onFx(() -> {
			TableView<TextRow> table = textTable();
			pressTab(typeInto(table, 0, "neu"), false);
			return new String[] { table.getItems().get(0).text, editingCellOf(table) };
		});

		assertEquals("neu", result[0]);
		assertEquals("0:Zweite", result[1]);
	}

	@Test
	void shiftTabStartsEditingThePreviousCellInTheRowAbove() throws Exception {
		String editing = onFx(() -> {
			TableView<TextRow> table = textTable();
			pressTab(typeInto(table, 1, "neu"), true);
			return editingCellOf(table);
		});

		assertEquals("0:Zweite", editing);
	}

	@Test
	void tabOntoALockedCellOnlyMovesTheFocus() throws Exception {
		String[] result = onFx(() -> {
			TableView<TextRow> table = textTable();
			pressTab(typeInto(table, 1, "neu"), false);
			TablePosition<TextRow, ?> focused = table.getFocusModel().getFocusedCell();
			return new String[] { editingCellOf(table), focused.getRow() + ":" + focused.getTableColumn().getText() };
		});

		assertEquals("keine", result[0]);
		assertEquals("1:Zweite", result[1]);
	}

	/** Startet das Bearbeiten der Zeile und tippt den Text ins Feld (ohne Enter). */
	private static ParsingTextFieldTableCell<?, ?> typeInto(TableView<TextRow> table, int row, String text) {
		table.edit(row, table.getColumns().get(0));
		for (Node node : table.lookupAll(".table-cell")) {
			if (node instanceof ParsingTextFieldTableCell<?, ?> cell && cell.isEditing() && cell.getGraphic() instanceof TextField field) {
				field.setText(text);
				return cell;
			}
		}
		throw new AssertionError("keine Zelle im Bearbeiten");
	}

	@Test
	void editEndedByTheTableKeepsTheInput() throws Exception {
		// So beendet JavaFX das Bearbeiten beim Klick auf eine andere Zeile (TableCellBehavior.edit)
		String text = onFx(() -> {
			TableView<TextRow> table = textTable();
			typeInto(table, 0, "neu");
			table.edit(-1, null);
			return table.getItems().get(0).text;
		});

		assertEquals("neu", text);
	}

	@Test
	void editMovedToAnotherRowKeepsTheInputOfTheFirst() throws Exception {
		String[] result = onFx(() -> {
			TableView<TextRow> table = textTable();
			typeInto(table, 0, "neu");
			table.edit(1, table.getColumns().get(0));
			return new String[] { table.getItems().get(0).text, table.getItems().get(1).text,
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
			TableView<DateRow> table = new TableView<>();
			table.setEditable(true);
			TableColumn<DateRow, LocalDate> column = new TableColumn<>("Datum");
			column.setEditable(true);
			column.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().date));
			column.setCellFactory(ParsingTextFieldTableCell.forDateColumn("Datum", row -> null, () -> TODAY));
			column.setOnEditCommit(edit -> edit.getRowValue().date = edit.getNewValue());
			table.getColumns().add(column);
			table.getItems().addAll(new DateRow(null), new DateRow(LocalDate.of(2026, 1, 1)));
			// Die Meldung INP-002 braucht eine sichtbare Fußzeile, sonst öffnet StatusMessage einen Dialog und wartet
			Stage stage = new Stage();
			stage.setOpacity(0);
			stage.setScene(new Scene(new VBox(table, new StatusLabel()), 300, 200));
			stage.show();
			try {
				for (int row = 0; row < 2; row++) {
					table.edit(row, column);
					for (Node node : table.lookupAll(".table-cell")) {
						if (node instanceof ParsingTextFieldTableCell<?, ?> cell && cell.isEditing() && cell.getGraphic() instanceof TextField field) {
							field.setText("kein Datum");
						}
					}
					table.edit(-1, null);
				}
			} finally {
				stage.hide();
			}
			return new LocalDate[] { table.getItems().get(0).date, table.getItems().get(1).date };
		});

		assertEquals(TODAY, dates[0]);
		assertEquals(LocalDate.of(2026, 1, 1), dates[1]);
	}

	@Test
	void escapeDiscardsTheInput() throws Exception {
		// Die Tabelle beendet bei Esc selbst mit table.edit(-1, null): Das darf nicht als Übernahme gelten
		String text = onFx(() -> {
			TableView<TextRow> table = textTable();
			Node field = typeInto(table, 0, "neu").getGraphic();
			Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
			Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.ESCAPE, false, false, false, false));
			return table.getItems().get(0).text + "|" + editingCellOf(table);
		});

		assertEquals("alt|keine", text);
	}

	@Test
	void cancelOfTheCellItselfStillDiscards() throws Exception {
		// Esc bricht über die Zelle selbst ab, während die Tabelle sie noch als bearbeitet führt
		String text = onFx(() -> {
			TableView<TextRow> table = textTable();
			typeInto(table, 0, "neu").cancelEdit();
			return table.getItems().get(0).text;
		});

		assertEquals("alt", text);
	}
}
