package de.eltviller_carneval_verein.karten.ui.input;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import javafx.animation.KeyFrame;
import javafx.application.Platform;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

/**
 * Bedienung einer Tabelle wie in Excel, gemeinsam für alle Tabellen mit Eingabe:
 *
 * <ul>
 * <li>Ein Klick wählt die Zeile und setzt den Fokus auf die geklickte Zelle. Ein weiterer Klick auf eine Zelle der
 * einzigen gewählten Zeile startet das Bearbeiten (wie beim Umbenennen im Explorer). Es beginnt außerdem mit Tippen
 * (bei genau einer gewählten Zeile; das Zeichen ersetzt den Inhalt), Enter, F2 oder einem Doppelklick. Bei mehreren
 * gewählten Zeilen, mit Strg/Umschalt und nach dem Ziehen startet ein Klick es nie.</li>
 * <li>Tab und Umschalt+Tab setzen den Fokus auf die nächste bzw. vorherige bearbeitbare Zelle, am Zeilenende in die
 * nächste Zeile. Beim Bearbeiten übernimmt Tab erst die Eingabe, siehe {@link ParsingTextFieldTableCell}.</li>
 * <li>Drücken und Ziehen über mehrere Zeilen wählt sie aus; mit Strg bleibt die bisherige Auswahl erhalten. Am oberen
 * und unteren Rand scrollt die Tabelle mit.</li>
 * </ul>
 *
 * Bearbeitet werden nur Spalten mit {@link ParsingTextFieldTableCell}; Auswahllisten und Checkboxen behalten ihr
 * eigenes Verhalten.
 */
public final class TableInput {

	private static final double DRAG_THRESHOLD = 4;
	private static final double MAX_SCROLL_STEP = 30;

	private TableInput() {
	}

	public static <S> void install(TableView<S> table) {
		DragSelection<S> dragSelection = new DragSelection<>(table);
		boolean[] clickMayEdit = { false };
		table.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
			clickMayEdit[0] = pressedOnlySelectedRow(table, event);
			dragSelection.onPressed(event);
		});
		table.addEventFilter(MouseEvent.MOUSE_DRAGGED, dragSelection::onDragged);
		table.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
			if (dragSelection.onReleased(event)) {
				event.consume();
			} else if (!clickMayEdit[0]) {
				suppressClickEdit(table, event);
			}
		});
		table.addEventFilter(KeyEvent.KEY_TYPED, event -> onKeyTyped(table, event));
		table.addEventFilter(KeyEvent.KEY_PRESSED, event -> onKeyPressed(table, event));
	}

	/**
	 * Ob der Druck eine Zeile trifft, die schon die einzige gewählte ist (ohne Umschalt oder Strg): Ein weiterer Klick
	 * auf eine ihrer Zellen startet dann das Bearbeiten, wie beim Umbenennen im Explorer.
	 */
	private static <S> boolean pressedOnlySelectedRow(TableView<S> table, MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY || event.isShiftDown() || event.isControlDown() || event.isMetaDown() || insideControl(event.getTarget())) {
			return false;
		}
		TableRow<?> row = rowOf(event.getTarget());
		return row != null && !row.isEmpty() && table.getSelectionModel().getSelectedIndices().size() == 1
				&& table.getSelectionModel().getSelectedIndex() == row.getIndex();
	}

	/**
	 * JavaFX startet das Bearbeiten schon bei einem einzelnen Klick auf eine gewählte Zelle, auch wenn mehrere Zeilen
	 * gewählt sind oder Strg/Umschalt gedrückt waren. Das soll nur gelten, wenn die Zeile schon die einzige gewählte war
	 * ({@link #pressedOnlySelectedRow}); sonst startet nur ein Doppelklick (oder Tippen, Enter, F2) das Bearbeiten. Das
	 * Loslassen darf dafür nicht verworfen werden: JavaFX verschiebt bei einem Klick auf eine schon gewählte Zeile die
	 * Auswahl auf das Loslassen. Stattdessen ist die Tabelle für die Dauer dieses Loslassens nicht bearbeitbar (die
	 * Zelle fragt beim Start danach).
	 */
	private static <S> void suppressClickEdit(TableView<S> table, MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 1 || table.getEditingCell() != null
				|| !(cellOf(event.getTarget()) instanceof ParsingTextFieldTableCell<?, ?>) || !table.isEditable()) {
			return;
		}
		table.setEditable(false);
		// Das Loslassen ist verarbeitet, sobald dieses Ereignis durch ist
		Platform.runLater(() -> table.setEditable(true));
	}

	private static <S> void onKeyTyped(TableView<S> table, KeyEvent event) {
		String typed = event.getCharacter();
		if (typed.isEmpty() || Character.isISOControl(typed.charAt(0)) || event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
			return;
		}
		TableCell<S, ?> cell = editableCellInFocus(table);
		if (cell == null) {
			return;
		}
		table.edit(cell.getIndex(), cell.getTableColumn());
		if (cell.getGraphic() instanceof TextField field) {
			field.setText(typed);
			field.positionCaret(typed.length());
		}
		event.consume();
	}

	private static <S> void onKeyPressed(TableView<S> table, KeyEvent event) {
		if (table.getEditingCell() != null || event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
			return;
		}
		if (event.getCode() == KeyCode.ENTER) {
			TableCell<S, ?> cell = editableCellInFocus(table);
			if (cell != null) {
				table.edit(cell.getIndex(), cell.getTableColumn());
				event.consume();
			}
		} else if (event.getCode() == KeyCode.TAB) {
			TablePosition<S, ?> focused = table.getFocusModel().getFocusedCell();
			if (focused != null && focused.getRow() >= 0 && moveFocus(table, focused.getRow(), focused.getTableColumn(), !event.isShiftDown())) {
				event.consume();
			}
		}
	}

	/** Die Zelle mit dem Fokus, wenn sie ein Textfeld bearbeiten lässt und genau diese eine Zeile gewählt ist. */
	private static <S> TableCell<S, ?> editableCellInFocus(TableView<S> table) {
		if (!table.isEditable() || table.getEditingCell() != null || table.getSelectionModel().getSelectedIndices().size() != 1) {
			return null;
		}
		TablePosition<S, ?> focused = table.getFocusModel().getFocusedCell();
		if (focused == null || focused.getTableColumn() == null || !focused.getTableColumn().isEditable() || focused.getRow() != table.getSelectionModel().getSelectedIndex()) {
			return null;
		}
		TableCell<S, ?> cell = cellAt(table, focused.getRow(), focused.getTableColumn());
		return cell instanceof ParsingTextFieldTableCell<?, ?> ? cell : null;
	}

	/**
	 * Setzt Auswahl und Fokus auf die nächste (oder vorherige) bearbeitbare Zelle, am Zeilenende in die nächste Zeile.
	 *
	 * @return {@code false}, wenn es keine weitere Zelle gibt (der Fokus bleibt, die Taste kann den Fokus weitergeben)
	 */
	static <S> boolean moveFocus(TableView<S> table, int row, TableColumn<S, ?> column, boolean forward) {
		List<TableColumn<S, ?>> columns = new ArrayList<>();
		for (TableColumn<S, ?> candidate : table.getVisibleLeafColumns()) {
			if (candidate.isEditable()) {
				columns.add(candidate);
			}
		}
		if (columns.isEmpty()) {
			return false;
		}
		int index = columns.indexOf(column);
		int nextRow = row;
		int nextColumn;
		if (forward) {
			nextColumn = index + 1;
			if (nextColumn >= columns.size()) {
				nextColumn = 0;
				nextRow++;
			}
		} else {
			nextColumn = index < 0 ? columns.size() - 1 : index - 1;
			if (nextColumn < 0) {
				nextColumn = columns.size() - 1;
				nextRow--;
			}
		}
		if (nextRow < 0 || nextRow >= table.getItems().size()) {
			return false;
		}
		table.getSelectionModel().clearAndSelect(nextRow);
		table.getFocusModel().focus(nextRow, columns.get(nextColumn));
		table.scrollTo(nextRow);
		table.requestFocus();
		return true;
	}

	@SuppressWarnings("unchecked")
	private static <S> TableCell<S, ?> cellAt(TableView<S> table, int row, TableColumn<S, ?> column) {
		for (Node node : table.lookupAll(".table-row-cell")) {
			if (node instanceof TableRow<?> tableRow && tableRow.getIndex() == row) {
				for (Node cellNode : tableRow.lookupAll(".table-cell")) {
					if (cellNode instanceof TableCell<?, ?> cell && cell.getTableColumn() == column) {
						return (TableCell<S, ?>) cell;
					}
				}
			}
		}
		return null;
	}

	private static TableCell<?, ?> cellOf(Object target) {
		for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
			if (node instanceof TableCell<?, ?> cell) {
				return cell;
			}
		}
		return null;
	}

	private static TableRow<?> rowOf(Object target) {
		for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
			if (node instanceof TableRow<?> row) {
				return row;
			}
			if (node instanceof ScrollBar) {
				return null;
			}
		}
		return null;
	}

	/** Eine Zelle mit eigenem Bedienelement (Checkbox, Auswahlliste, Textfeld) startet kein Ziehen. */
	private static boolean insideControl(Object target) {
		for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
			if (node instanceof ButtonBase || node instanceof ComboBoxBase<?> || node instanceof TextInputControl) {
				return true;
			}
			if (node instanceof TableRow<?>) {
				return false;
			}
		}
		return false;
	}

	/** Auswahl durch Drücken und Ziehen über mehrere Zeilen. */
	private static final class DragSelection<S> {

		private final TableView<S> table;
		private final Timeline autoScroll;
		private int anchor = -1;
		private TableColumn<S, ?> pressedColumn;
		private boolean dragging;
		private double pressX;
		private double pressY;
		private double lastSceneY;
		private int lastCurrent = -1;
		private List<Integer> base = List.of();

		DragSelection(TableView<S> table) {
			this.table = table;
			this.autoScroll = new Timeline(new KeyFrame(Duration.millis(40), event -> autoScrollStep()));
			this.autoScroll.setCycleCount(Timeline.INDEFINITE);
		}

		@SuppressWarnings("unchecked")
		void onPressed(MouseEvent event) {
			anchor = -1;
			dragging = false;
			if (event.getButton() != MouseButton.PRIMARY || table.getEditingCell() != null || insideControl(event.getTarget())) {
				return;
			}
			TableRow<?> row = rowOf(event.getTarget());
			if (row == null || row.isEmpty()) {
				return;
			}
			anchor = row.getIndex();
			TableCell<?, ?> pressedCell = cellOf(event.getTarget());
			pressedColumn = pressedCell == null ? null : (TableColumn<S, ?>) pressedCell.getTableColumn();
			pressX = event.getSceneX();
			pressY = event.getSceneY();
			lastCurrent = -1;
			base = event.isControlDown() ? new ArrayList<>(table.getSelectionModel().getSelectedIndices()) : List.of();
		}

		void onDragged(MouseEvent event) {
			if (anchor < 0) {
				return;
			}
			if (!dragging && Math.hypot(event.getSceneX() - pressX, event.getSceneY() - pressY) < DRAG_THRESHOLD) {
				return;
			}
			dragging = true;
			lastSceneY = event.getSceneY();
			selectUpTo(lastSceneY);
			if (isOutsideRows(lastSceneY)) {
				autoScroll.play();
			} else {
				autoScroll.stop();
			}
			event.consume();
		}

		/** @return {@code true}, wenn die Maus nach einem Ziehen losgelassen wurde (das Loslassen ist dann kein Klick) */
		boolean onReleased(MouseEvent event) {
			boolean wasDragging = dragging;
			anchor = -1;
			dragging = false;
			autoScroll.stop();
			return wasDragging;
		}

		private void selectUpTo(double sceneY) {
			int current = rowIndexAt(sceneY);
			if (current < 0 || current == lastCurrent) {
				return;
			}
			lastCurrent = current;
			TreeSet<Integer> wanted = new TreeSet<>(base);
			for (int index = Math.min(anchor, current); index <= Math.max(anchor, current); index++) {
				wanted.add(index);
			}
			int[] indices = wanted.stream().mapToInt(Integer::intValue).toArray();
			table.getSelectionModel().clearSelection();
			table.getSelectionModel().selectIndices(indices[0], Arrays.copyOfRange(indices, 1, indices.length));
			table.getFocusModel().focus(current, pressedColumn);
		}

		private Bounds rowArea() {
			Node flow = table.lookup(".virtual-flow");
			Node area = flow == null ? table : flow;
			return area.localToScene(area.getBoundsInLocal());
		}

		private boolean isOutsideRows(double sceneY) {
			Bounds area = rowArea();
			return sceneY < area.getMinY() || sceneY > area.getMaxY();
		}

		/** Die Zeile unter dem Mauszeiger; außerhalb der Zeilen die erste bzw. letzte sichtbare. */
		private int rowIndexAt(double sceneY) {
			Bounds area = rowArea();
			double y = Math.max(area.getMinY() + 1, Math.min(area.getMaxY() - 1, sceneY));
			for (Node node : table.lookupAll(".table-row-cell")) {
				if (node instanceof TableRow<?> row && !row.isEmpty() && row.isVisible()) {
					Bounds bounds = row.localToScene(row.getBoundsInLocal());
					if (y >= bounds.getMinY() && y < bounds.getMaxY()) {
						return row.getIndex();
					}
				}
			}
			return -1;
		}

		private void autoScrollStep() {
			Bounds area = rowArea();
			double delta = 0;
			if (lastSceneY < area.getMinY()) {
				delta = -Math.min(MAX_SCROLL_STEP, (area.getMinY() - lastSceneY) / 2 + 2);
			} else if (lastSceneY > area.getMaxY()) {
				delta = Math.min(MAX_SCROLL_STEP, (lastSceneY - area.getMaxY()) / 2 + 2);
			}
			if (delta == 0) {
				autoScroll.stop();
				return;
			}
			if (table.lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
				flow.scrollPixels(delta);
			}
			lastCurrent = -1; // nach dem Scrollen steht eine andere Zeile unter dem Mauszeiger
			selectUpTo(lastSceneY);
		}
	}
}
