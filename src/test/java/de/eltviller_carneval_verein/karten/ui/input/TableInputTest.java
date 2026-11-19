package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;

/**
 * Hält fest, wie {@link TableInput} eine Tabelle bedient: Klick, Tippen, Enter, Tab und Ziehen. Der Test hält das
 * Verhalten fest, bevor die Kartentabelle auf eine andere Tabellenart umgestellt wird. Die Tabelle entsteht an genau
 * einer Stelle ({@link #createTable()}), und alle Eingaben laufen über die Hilfen {@code click}, {@code ctrlClick},
 * {@code press}, {@code type} und {@code drag}; die Fälle selbst kennen die Tabellenart nicht.
 */
class TableInputTest {

	/** Eine Zeile der Testtabelle. */
	private static final class Person {
		final StringProperty lastName;
		final StringProperty firstName;
		final StringProperty city;
		final String number;

		Person(int index) {
			lastName = new SimpleStringProperty("Nachname" + index);
			firstName = new SimpleStringProperty("Vorname" + index);
			city = new SimpleStringProperty("Ort" + index);
			number = "Nr" + index;
		}
	}

	private static final int LAST_NAME = 0;
	private static final int FIRST_NAME = 1;
	private static final int CITY = 2;
	private static final int NUMBER = 3;
	private static final int ROWS = 6;

	private Stage stage;
	private TableView<Person> table;

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

	/** Wartet, bis alles durch ist, was die Tabelle mit {@code Platform.runLater} nachgeschoben hat. */
	private static void flush() throws Exception {
		onFx(() -> null);
		onFx(() -> null);
	}

	/**
	 * Die einzige Stelle, an der die Tabelle entsteht: drei bearbeitbare Textspalten (Nachname, Vorname, Ort) und eine
	 * gesperrte (Nummer), sechs Zeilen, mehrfache Auswahl, die Bedienung von {@link TableInput}.
	 */
	private static TableView<Person> createTable() {
		TableView<Person> table = new TableView<>();
		table.setEditable(true);
		table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		table.getColumns().add(textColumn("Nachname", p -> p.lastName));
		table.getColumns().add(textColumn("Vorname", p -> p.firstName));
		table.getColumns().add(textColumn("Ort", p -> p.city));
		TableColumn<Person, String> number = new TableColumn<>("Nummer");
		number.setEditable(false);
		number.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().number));
		table.getColumns().add(number);
		for (int i = 0; i < ROWS; i++) {
			table.getItems().add(new Person(i));
		}
		TableInput.install(table);
		return table;
	}

	private static TableColumn<Person, String> textColumn(String title, java.util.function.Function<Person, StringProperty> property) {
		TableColumn<Person, String> column = new TableColumn<>(title);
		column.setEditable(true);
		column.setCellValueFactory(cell -> property.apply(cell.getValue()));
		column.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		return column;
	}

	@BeforeEach
	void showTable() throws Exception {
		onFx(() -> {
			table = createTable();
			stage = new Stage();
			stage.setScene(new Scene(table, 600, 400));
			stage.show();
			table.applyCss();
			table.layout();
			table.requestFocus();
			return null;
		});
		flush();
	}

	@AfterEach
	void closeStage() throws Exception {
		onFx(() -> {
			stage.hide();
			return null;
		});
	}

	// ---- Zugriff auf die Tabelle: hier wird sie in einer anderen Tabellenart anders angesprochen ----

	private TableColumn<Person, ?> column(int index) {
		return table.getColumns().get(index);
	}

	private void select(int... rows) throws Exception {
		onFx(() -> {
			table.getSelectionModel().clearSelection();
			for (int row : rows) {
				table.getSelectionModel().select(row);
			}
			return null;
		});
		flush();
	}

	private void focus(int row, int column) throws Exception {
		onFx(() -> {
			table.getFocusModel().focus(row, column(column));
			table.requestFocus();
			return null;
		});
		flush();
	}

	private void startEditing(int row, int column) throws Exception {
		onFx(() -> {
			table.edit(row, column(column));
			table.layout();
			return null;
		});
		flush();
	}

	private List<Integer> selectedRows() throws Exception {
		return onFx(() -> List.copyOf(table.getSelectionModel().getSelectedIndices()));
	}

	private TablePosition<Person, ?> editingCell() throws Exception {
		return onFx(() -> table.getEditingCell());
	}

	private TablePosition<Person, ?> focusedCell() throws Exception {
		return onFx(() -> table.getFocusModel().getFocusedCell());
	}

	private String editorText(int row, int column) throws Exception {
		return onFx(() -> cell(row, column).getGraphic() instanceof TextField field ? field.getText() : null);
	}

	private TableCell<Person, ?> cell(int row, int column) {
		for (Node node : table.lookupAll(".table-row-cell")) {
			if (node instanceof TableRow<?> tableRow && tableRow.getIndex() == row) {
				for (Node cellNode : tableRow.lookupAll(".table-cell")) {
					if (cellNode instanceof TableCell<?, ?> candidate && candidate.getTableColumn() == column(column)) {
						@SuppressWarnings("unchecked")
						TableCell<Person, ?> found = (TableCell<Person, ?>) candidate;
						return found;
					}
				}
			}
		}
		throw new AssertionError("keine Zelle in Zeile " + row + ", Spalte " + column);
	}

	/** Mitte der Zelle in Szenenkoordinaten. */
	private double[] center(int row, int column) {
		TableCell<Person, ?> cell = cell(row, column);
		Bounds bounds = cell.localToScene(cell.getBoundsInLocal());
		return new double[] { (bounds.getMinX() + bounds.getMaxX()) / 2, (bounds.getMinY() + bounds.getMaxY()) / 2 };
	}

	// ---- Eingaben ----

	private void fireMouse(Node target, javafx.event.EventType<MouseEvent> type, double sceneX, double sceneY, int clickCount, boolean ctrl) {
		PickResult pick = new PickResult(target, sceneX, sceneY);
		Event.fireEvent(target, new MouseEvent(this, target, type, sceneX, sceneY, sceneX, sceneY, MouseButton.PRIMARY, clickCount, false, ctrl,
				false, false, type != MouseEvent.MOUSE_RELEASED, false, false, false, false, true, pick));
	}

	private void clickWith(int row, int column, boolean ctrl) throws Exception {
		onFx(() -> {
			table.layout();
			TableCell<Person, ?> cell = cell(row, column);
			double[] at = center(row, column);
			fireMouse(cell, MouseEvent.MOUSE_PRESSED, at[0], at[1], 1, ctrl);
			fireMouse(cell, MouseEvent.MOUSE_RELEASED, at[0], at[1], 1, ctrl);
			fireMouse(cell, MouseEvent.MOUSE_CLICKED, at[0], at[1], 1, ctrl);
			return null;
		});
		flush();
	}

	private void click(int row, int column) throws Exception {
		clickWith(row, column, false);
	}

	private void ctrlClick(int row, int column) throws Exception {
		clickWith(row, column, true);
	}

	private void press(KeyCode code, boolean shift) throws Exception {
		onFx(() -> {
			Node target = table.getEditingCell() != null && cell(table.getEditingCell().getRow(), indexOf(table.getEditingCell().getTableColumn()))
					.getGraphic() instanceof TextField field ? field : table;
			Event.fireEvent(target, new KeyEvent(this, target, KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, shift, false, false, false));
			return null;
		});
		flush();
	}

	private void press(KeyCode code) throws Exception {
		press(code, false);
	}

	private int indexOf(TableColumn<Person, ?> column) {
		return table.getColumns().indexOf(column);
	}

	private void type(String text) throws Exception {
		onFx(() -> {
			Event.fireEvent(table, new KeyEvent(this, table, KeyEvent.KEY_TYPED, text, "", KeyCode.UNDEFINED, false, false, false, false));
			table.layout();
			return null;
		});
		flush();
	}

	/** Drückt in Zeile {@code fromRow} und zieht bis Zeile {@code toRow}, jeweils Spalte Nachname. */
	private void drag(int fromRow, int toRow, boolean ctrl) throws Exception {
		onFx(() -> {
			table.layout();
			TableCell<Person, ?> start = cell(fromRow, LAST_NAME);
			double[] from = center(fromRow, LAST_NAME);
			double[] to = center(toRow, LAST_NAME);
			fireMouse(start, MouseEvent.MOUSE_PRESSED, from[0], from[1], 1, ctrl);
			int steps = 4;
			for (int i = 1; i <= steps; i++) {
				fireMouse(start, MouseEvent.MOUSE_DRAGGED, from[0] + (to[0] - from[0]) * i / steps, from[1] + (to[1] - from[1]) * i / steps, 1, ctrl);
			}
			fireMouse(start, MouseEvent.MOUSE_RELEASED, to[0], to[1], 1, ctrl);
			return null;
		});
		flush();
	}

	// ---- Fälle ----

	@Test
	void klickAufZelleDerEinzigenGewaehltenZeileStartetBearbeiten() throws Exception {
		select(1);
		focus(1, LAST_NAME);

		click(1, LAST_NAME);

		TablePosition<Person, ?> editing = editingCell();
		assertNotNull(editing, "der Klick auf die einzige gewählte Zeile startet das Bearbeiten");
		assertEquals(1, editing.getRow());
		assertSame(column(LAST_NAME), editing.getTableColumn());
	}

	@Test
	void klickBeiMehrerenGewaehltenStartetNicht() throws Exception {
		select(1, 2);
		focus(1, LAST_NAME);

		click(1, LAST_NAME);

		assertNull(editingCell(), "bei mehreren gewählten Zeilen startet ein Klick das Bearbeiten nicht");
	}

	@Test
	void tippenErsetztInhalt() throws Exception {
		select(1);
		focus(1, LAST_NAME);

		type("X");

		TablePosition<Person, ?> editing = editingCell();
		assertNotNull(editing, "Tippen startet das Bearbeiten");
		assertEquals(1, editing.getRow());
		assertSame(column(LAST_NAME), editing.getTableColumn());
		assertEquals("X", editorText(1, LAST_NAME));
	}

	@Test
	void enterStartetBearbeiten() throws Exception {
		select(1);
		focus(1, FIRST_NAME);

		press(KeyCode.ENTER);

		TablePosition<Person, ?> editing = editingCell();
		assertNotNull(editing, "Enter startet das Bearbeiten");
		assertEquals(1, editing.getRow());
		assertSame(column(FIRST_NAME), editing.getTableColumn());
	}

	@Test
	void tabUndUmschaltTabMitZeilenumbruch() throws Exception {
		select(0);
		focus(0, CITY);

		press(KeyCode.TAB);

		TablePosition<Person, ?> forward = focusedCell();
		assertEquals(1, forward.getRow(), "Tab am Zeilenende springt in die nächste Zeile");
		assertSame(column(LAST_NAME), forward.getTableColumn(), "und dort in die erste bearbeitbare Spalte");
		assertEquals(List.of(1), selectedRows(), "gewählt ist genau die neue Zeile");

		press(KeyCode.TAB, true);

		TablePosition<Person, ?> back = focusedCell();
		assertEquals(0, back.getRow(), "Umschalt+Tab am Zeilenanfang springt zurück");
		assertSame(column(CITY), back.getTableColumn(), "in die letzte bearbeitbare Spalte (die gesperrte wird übersprungen)");
		assertEquals(List.of(0), selectedRows());
	}

	@Test
	void tabAusBearbeitenBearbeitetNaechsteZelle() throws Exception {
		select(0);
		focus(0, LAST_NAME);
		startEditing(0, LAST_NAME);
		onFx(() -> {
			((TextField) cell(0, LAST_NAME).getGraphic()).setText("Neu");
			return null;
		});

		press(KeyCode.TAB);

		assertEquals("Neu", onFx(() -> table.getItems().get(0).lastName.get()), "Tab übernimmt die Eingabe");
		TablePosition<Person, ?> editing = editingCell();
		assertNotNull(editing, "und bearbeitet die nächste Zelle");
		assertEquals(0, editing.getRow());
		assertSame(column(FIRST_NAME), editing.getTableColumn());
	}

	@Test
	void ziehenWaehltBereich() throws Exception {
		drag(0, 2, false);

		assertEquals(List.of(0, 1, 2), selectedRows());
		assertTrue(onFx(() -> table.getEditingCell() == null), "Ziehen startet kein Bearbeiten");
	}

	@Test
	void ziehenMitStrgBehaeltAuswahl() throws Exception {
		select(4);

		drag(0, 1, true);

		assertEquals(List.of(0, 1, 4), onFx(() -> table.getSelectionModel().getSelectedIndices().stream().sorted().toList()));
	}
}
