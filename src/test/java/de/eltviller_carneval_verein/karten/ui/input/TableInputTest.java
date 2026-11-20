package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;

/**
 * Hält fest, wie {@link TableInput} eine Tabelle bedient: Klick, Tippen, Enter, Tab und Ziehen. Jeder Fall läuft gegen
 * eine {@link TableView} und gegen eine flache {@link TreeTableView} (ohne sichtbare Wurzel, die Zeilen direkt darunter)
 * mit denselben Erwartungen. Die Tabelle entsteht an genau einer Stelle ({@link Art#create()}), und alle Eingaben laufen
 * über die Hilfen {@code click}, {@code ctrlClick}, {@code press}, {@code type} und {@code drag}; die Fälle selbst
 * kennen die Tabellenart nicht.
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

	/** Zeile und Spalte einer Zelle, unabhängig von der Tabellenart. */
	private record Position(int row, TableColumnBase<?, ?> column) {
	}

	/** Was die Fälle von der Tabelle brauchen; je Tabellenart einmal umgesetzt. */
	private interface Tabelle {
		Control control();

		List<? extends TableColumnBase<?, ?>> columns();

		void select(int row);

		void clearSelection();

		void focus(int row, TableColumnBase<?, ?> column);

		void edit(int row, TableColumnBase<?, ?> column);

		List<Integer> selectedRows();

		/** Die bearbeitete Zelle, {@code null}: keine. */
		Position editing();

		/** Die Zelle mit dem Fokus, {@code null}: keine. */
		Position focused();

		IndexedCell<?> cell(int row, TableColumnBase<?, ?> column);

		Person person(int row);
	}

	/** Die Tabellenarten, gegen die jeder Fall läuft. */
	enum Art {
		TABLE_VIEW {
			@Override
			Tabelle create() {
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
				return new FlatTable(table);
			}
		},
		TREE_TABLE_VIEW {
			@Override
			Tabelle create() {
				TreeTableView<Person> table = new TreeTableView<>();
				table.setEditable(true);
				table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
				table.getColumns().add(treeTextColumn("Nachname", p -> p.lastName));
				table.getColumns().add(treeTextColumn("Vorname", p -> p.firstName));
				table.getColumns().add(treeTextColumn("Ort", p -> p.city));
				TreeTableColumn<Person, String> number = new TreeTableColumn<>("Nummer");
				number.setEditable(false);
				number.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getValue().number));
				table.getColumns().add(number);
				TreeItem<Person> root = new TreeItem<>();
				root.setExpanded(true);
				for (int i = 0; i < ROWS; i++) {
					root.getChildren().add(new TreeItem<>(new Person(i)));
				}
				table.setRoot(root);
				table.setShowRoot(false);
				TableInput.install(table, person -> true);
				return new TreeTable(table);
			}
		};

		abstract Tabelle create();
	}

	static Stream<Art> tabellen() {
		return Stream.of(Art.values());
	}

	private static TableColumn<Person, String> textColumn(String title, Function<Person, StringProperty> property) {
		TableColumn<Person, String> column = new TableColumn<>(title);
		column.setEditable(true);
		column.setCellValueFactory(cell -> property.apply(cell.getValue()));
		column.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		return column;
	}

	private static TreeTableColumn<Person, String> treeTextColumn(String title, Function<Person, StringProperty> property) {
		TreeTableColumn<Person, String> column = new TreeTableColumn<>(title);
		column.setEditable(true);
		column.setCellValueFactory(cell -> property.apply(cell.getValue().getValue()));
		column.setCellFactory(ParsingTextFieldTreeTableCell.forTextColumn());
		return column;
	}

	@SuppressWarnings("unchecked")
	private static final class FlatTable implements Tabelle {
		private final TableView<Person> table;

		FlatTable(TableView<Person> table) {
			this.table = table;
		}

		@Override
		public Control control() {
			return table;
		}

		@Override
		public List<? extends TableColumnBase<?, ?>> columns() {
			return table.getColumns();
		}

		@Override
		public void select(int row) {
			table.getSelectionModel().select(row);
		}

		@Override
		public void clearSelection() {
			table.getSelectionModel().clearSelection();
		}

		@Override
		public void focus(int row, TableColumnBase<?, ?> column) {
			table.getFocusModel().focus(row, (TableColumn<Person, ?>) column);
		}

		@Override
		public void edit(int row, TableColumnBase<?, ?> column) {
			table.edit(row, (TableColumn<Person, ?>) column);
		}

		@Override
		public List<Integer> selectedRows() {
			return List.copyOf(table.getSelectionModel().getSelectedIndices());
		}

		@Override
		public Position editing() {
			TablePosition<Person, ?> editing = table.getEditingCell();
			return editing == null ? null : new Position(editing.getRow(), editing.getTableColumn());
		}

		@Override
		public Position focused() {
			TablePosition<Person, ?> focused = table.getFocusModel().getFocusedCell();
			return focused == null ? null : new Position(focused.getRow(), focused.getTableColumn());
		}

		@Override
		public IndexedCell<?> cell(int row, TableColumnBase<?, ?> column) {
			for (Node node : table.lookupAll(".table-row-cell")) {
				if (node instanceof TableRow<?> tableRow && tableRow.getIndex() == row) {
					for (Node cellNode : tableRow.lookupAll(".table-cell")) {
						if (cellNode instanceof TableCell<?, ?> candidate && candidate.getTableColumn() == column) {
							return candidate;
						}
					}
				}
			}
			return null;
		}

		@Override
		public Person person(int row) {
			return table.getItems().get(row);
		}
	}

	@SuppressWarnings("unchecked")
	private static final class TreeTable implements Tabelle {
		private final TreeTableView<Person> table;

		TreeTable(TreeTableView<Person> table) {
			this.table = table;
		}

		@Override
		public Control control() {
			return table;
		}

		@Override
		public List<? extends TableColumnBase<?, ?>> columns() {
			return table.getColumns();
		}

		@Override
		public void select(int row) {
			table.getSelectionModel().select(row);
		}

		@Override
		public void clearSelection() {
			table.getSelectionModel().clearSelection();
		}

		@Override
		public void focus(int row, TableColumnBase<?, ?> column) {
			table.getFocusModel().focus(row, (TreeTableColumn<Person, ?>) column);
		}

		@Override
		public void edit(int row, TableColumnBase<?, ?> column) {
			table.edit(row, (TreeTableColumn<Person, ?>) column);
		}

		@Override
		public List<Integer> selectedRows() {
			return List.copyOf(table.getSelectionModel().getSelectedIndices());
		}

		@Override
		public Position editing() {
			TreeTablePosition<Person, ?> editing = table.getEditingCell();
			return editing == null ? null : new Position(editing.getRow(), editing.getTableColumn());
		}

		@Override
		public Position focused() {
			TreeTablePosition<Person, ?> focused = table.getFocusModel().getFocusedCell();
			return focused == null ? null : new Position(focused.getRow(), focused.getTableColumn());
		}

		@Override
		public IndexedCell<?> cell(int row, TableColumnBase<?, ?> column) {
			for (Node node : table.lookupAll(".tree-table-row-cell")) {
				if (node instanceof TreeTableRow<?> tableRow && tableRow.getIndex() == row) {
					for (Node cellNode : tableRow.lookupAll(".tree-table-cell")) {
						if (cellNode instanceof TreeTableCell<?, ?> candidate && candidate.getTableColumn() == column) {
							return candidate;
						}
					}
				}
			}
			return null;
		}

		@Override
		public Person person(int row) {
			return table.getTreeItem(row).getValue();
		}
	}

	private Stage stage;
	private Tabelle table;

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
	 * Zeigt die Tabelle der Art: drei bearbeitbare Textspalten (Nachname, Vorname, Ort) und eine gesperrte (Nummer), sechs
	 * Zeilen, mehrfache Auswahl, die Bedienung von {@link TableInput}.
	 */
	private void show(Art art) throws Exception {
		onFx(() -> {
			table = art.create();
			stage = new Stage();
			stage.setScene(new Scene(table.control(), 600, 400));
			stage.show();
			table.control().applyCss();
			table.control().layout();
			table.control().requestFocus();
			return null;
		});
		flush();
	}

	@AfterEach
	void closeStage() throws Exception {
		if (stage == null) {
			return;
		}
		onFx(() -> {
			stage.hide();
			return null;
		});
	}

	// ---- Zugriff auf die Tabelle ----

	private TableColumnBase<?, ?> column(int index) {
		return table.columns().get(index);
	}

	private void select(int... rows) throws Exception {
		onFx(() -> {
			table.clearSelection();
			for (int row : rows) {
				table.select(row);
			}
			return null;
		});
		flush();
	}

	private void focus(int row, int column) throws Exception {
		onFx(() -> {
			table.focus(row, column(column));
			table.control().requestFocus();
			return null;
		});
		flush();
	}

	private void startEditing(int row, int column) throws Exception {
		onFx(() -> {
			table.edit(row, column(column));
			table.control().layout();
			return null;
		});
		flush();
	}

	private List<Integer> selectedRows() throws Exception {
		return onFx(() -> table.selectedRows());
	}

	private Position editingCell() throws Exception {
		return onFx(() -> table.editing());
	}

	private Position focusedCell() throws Exception {
		return onFx(() -> table.focused());
	}

	private String editorText(int row, int column) throws Exception {
		return onFx(() -> cell(row, column).getGraphic() instanceof TextField field ? field.getText() : null);
	}

	private IndexedCell<?> cell(int row, int column) {
		IndexedCell<?> cell = table.cell(row, column(column));
		if (cell == null) {
			throw new AssertionError("keine Zelle in Zeile " + row + ", Spalte " + column);
		}
		return cell;
	}

	/** Mitte der Zelle in Szenenkoordinaten. */
	private double[] center(int row, int column) {
		IndexedCell<?> cell = cell(row, column);
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
			table.control().layout();
			IndexedCell<?> cell = cell(row, column);
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
			Position editing = table.editing();
			Node target = editing != null && cell(editing.row(), indexOf(editing.column())).getGraphic() instanceof TextField field ? field : table.control();
			Event.fireEvent(target, new KeyEvent(this, target, KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, shift, false, false, false));
			return null;
		});
		flush();
	}

	private void press(KeyCode code) throws Exception {
		press(code, false);
	}

	private int indexOf(TableColumnBase<?, ?> column) {
		return table.columns().indexOf(column);
	}

	private void type(String text) throws Exception {
		onFx(() -> {
			Control control = table.control();
			Event.fireEvent(control, new KeyEvent(this, control, KeyEvent.KEY_TYPED, text, "", KeyCode.UNDEFINED, false, false, false, false));
			control.layout();
			return null;
		});
		flush();
	}

	/** Drückt in Zeile {@code fromRow} und zieht bis Zeile {@code toRow}, jeweils Spalte Nachname. */
	private void drag(int fromRow, int toRow, boolean ctrl) throws Exception {
		onFx(() -> {
			table.control().layout();
			IndexedCell<?> start = cell(fromRow, LAST_NAME);
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

	@ParameterizedTest
	@MethodSource("tabellen")
	void klickAufZelleDerEinzigenGewaehltenZeileStartetBearbeiten(Art art) throws Exception {
		show(art);
		select(1);
		focus(1, LAST_NAME);

		click(1, LAST_NAME);

		Position editing = editingCell();
		assertNotNull(editing, "der Klick auf die einzige gewählte Zeile startet das Bearbeiten");
		assertEquals(1, editing.row());
		assertSame(column(LAST_NAME), editing.column());
	}

	@ParameterizedTest
	@MethodSource("tabellen")
	void klickBeiMehrerenGewaehltenStartetNicht(Art art) throws Exception {
		show(art);
		select(1, 2);
		focus(1, LAST_NAME);

		click(1, LAST_NAME);

		assertNull(editingCell(), "bei mehreren gewählten Zeilen startet ein Klick das Bearbeiten nicht");
	}

	@ParameterizedTest
	@MethodSource("tabellen")
	void tippenErsetztInhalt(Art art) throws Exception {
		show(art);
		select(1);
		focus(1, LAST_NAME);

		type("X");

		Position editing = editingCell();
		assertNotNull(editing, "Tippen startet das Bearbeiten");
		assertEquals(1, editing.row());
		assertSame(column(LAST_NAME), editing.column());
		assertEquals("X", editorText(1, LAST_NAME));
	}

	@ParameterizedTest
	@MethodSource("tabellen")
	void enterStartetBearbeiten(Art art) throws Exception {
		show(art);
		select(1);
		focus(1, FIRST_NAME);

		press(KeyCode.ENTER);

		Position editing = editingCell();
		assertNotNull(editing, "Enter startet das Bearbeiten");
		assertEquals(1, editing.row());
		assertSame(column(FIRST_NAME), editing.column());
	}

	@ParameterizedTest
	@MethodSource("tabellen")
	void tabUndUmschaltTabMitZeilenumbruch(Art art) throws Exception {
		show(art);
		select(0);
		focus(0, CITY);

		press(KeyCode.TAB);

		Position forward = focusedCell();
		assertEquals(1, forward.row(), "Tab am Zeilenende springt in die nächste Zeile");
		assertSame(column(LAST_NAME), forward.column(), "und dort in die erste bearbeitbare Spalte");
		assertEquals(List.of(1), selectedRows(), "gewählt ist genau die neue Zeile");

		press(KeyCode.TAB, true);

		Position back = focusedCell();
		assertEquals(0, back.row(), "Umschalt+Tab am Zeilenanfang springt zurück");
		assertSame(column(CITY), back.column(), "in die letzte bearbeitbare Spalte (die gesperrte wird übersprungen)");
		assertEquals(List.of(0), selectedRows());
	}

	@ParameterizedTest
	@MethodSource("tabellen")
	void tabAusBearbeitenBearbeitetNaechsteZelle(Art art) throws Exception {
		show(art);
		select(0);
		focus(0, LAST_NAME);
		startEditing(0, LAST_NAME);
		onFx(() -> {
			((TextField) cell(0, LAST_NAME).getGraphic()).setText("Neu");
			return null;
		});

		press(KeyCode.TAB);

		assertEquals("Neu", onFx(() -> table.person(0).lastName.get()), "Tab übernimmt die Eingabe");
		Position editing = editingCell();
		assertNotNull(editing, "und bearbeitet die nächste Zelle");
		assertEquals(0, editing.row());
		assertSame(column(FIRST_NAME), editing.column());
	}

	@ParameterizedTest
	@MethodSource("tabellen")
	void ziehenWaehltBereich(Art art) throws Exception {
		show(art);
		drag(0, 2, false);

		assertEquals(List.of(0, 1, 2), selectedRows());
		assertTrue(onFx(() -> table.editing() == null), "Ziehen startet kein Bearbeiten");
	}

	@ParameterizedTest
	@MethodSource("tabellen")
	void ziehenMitStrgBehaeltAuswahl(Art art) throws Exception {
		show(art);
		select(4);

		drag(0, 1, true);

		assertEquals(List.of(0, 1, 4), onFx(() -> table.selectedRows().stream().sorted().toList()));
	}

	/**
	 * Die kurze Sperre des Bearbeitens ({@link TableGestures#suspendEditing}) stellt „bearbeitbar“ nur wieder her, wenn
	 * während der Sperre niemand sonst den Wert geändert hat (etwa ein Wechsel in den Anzeigemodus und zurück).
	 */
	@Test
	void sperreStelltBearbeitbarNichtGegenAenderungVonAussenHer() throws Exception {
		BooleanProperty untouched = new SimpleBooleanProperty(true);
		BooleanProperty switchedOff = new SimpleBooleanProperty(true);
		BooleanProperty switchedOn = new SimpleBooleanProperty(true);
		onFx(() -> {
			TableGestures.suspendEditing(untouched);
			TableGestures.suspendEditing(switchedOff);
			TableGestures.suspendEditing(switchedOn);
			assertFalse(untouched.get(), "für die Dauer des Ereignisses nicht bearbeitbar");
			switchedOff.set(true);
			switchedOff.set(false);
			switchedOn.set(true);
			return null;
		});
		flush();

		assertTrue(untouched.get(), "ohne Änderung von außen wieder bearbeitbar");
		assertFalse(switchedOff.get(), "von außen abgeschaltet: bleibt aus");
		assertTrue(switchedOn.get());
	}
}
