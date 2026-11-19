package de.eltviller_carneval_verein.karten.ui.input;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;

import javafx.animation.KeyFrame;
import javafx.application.Platform;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

/**
 * Bedienung einer Tabelle wie in Excel, gemeinsam für alle Tabellen mit Eingabe ({@link TableView} und
 * {@link TreeTableView}):
 *
 * <ul>
 * <li>Ein Klick wählt die Zeile und setzt den Fokus auf die geklickte Zelle. Ein weiterer Klick auf eine Zelle der
 * einzigen gewählten Zeile startet das Bearbeiten (wie beim Umbenennen im Explorer). Es beginnt außerdem mit Tippen
 * (bei genau einer gewählten Zeile; das Zeichen ersetzt den Inhalt), Enter, F2 oder einem Doppelklick. Bei mehreren
 * gewählten Zeilen, mit Strg/Umschalt und nach dem Ziehen startet ein Klick es nie.</li>
 * <li>Tab und Umschalt+Tab setzen den Fokus auf die nächste bzw. vorherige bearbeitbare Zelle, am Zeilenende in die
 * nächste Zeile. Zeilen, die die Bedingung der Tabelle nicht zulässt (die Gruppenzeilen einer Baumtabelle), werden
 * übersprungen, ebenso zugeklappte Einträge, ohne sie aufzuklappen; Enter startet dort kein Bearbeiten. Beim Bearbeiten
 * übernimmt Tab erst die Eingabe und bearbeitet dann gleich die neue Zelle, siehe {@link ParsingTextFieldTableCell} und
 * {@link ParsingTextFieldTreeTableCell}.</li>
 * <li>Drücken und Ziehen über mehrere Zeilen wählt sie aus; mit Strg bleibt die bisherige Auswahl erhalten. Am oberen
 * und unteren Rand scrollt die Tabelle mit.</li>
 * </ul>
 *
 * Bearbeitet werden nur Zellen, die {@link ParsingCell} umsetzen; Auswahllisten und Checkboxen behalten ihr eigenes
 * Verhalten. Die Logik kennt die Tabellenart nicht, sie arbeitet über {@link TableAccess}.
 */
public final class TableInput {

	private static final double DRAG_THRESHOLD = 4;
	private static final double MAX_SCROLL_STEP = 30;

	private TableInput() {
	}

	/** Für eine {@link TableView}: Jede Zeile lässt sich bearbeiten. */
	public static <S> void install(TableView<S> table) {
		install(new TableViewAccess<>(table));
	}

	/**
	 * Für eine {@link TreeTableView}: Bearbeiten und Tab gelten nur in Zeilen, deren Wert {@code rowEditable} erfüllt;
	 * Tab überspringt die anderen.
	 */
	public static <S> void install(TreeTableView<S> table, Predicate<S> rowEditable) {
		install(new TreeTableViewAccess<>(table, rowEditable));
	}

	private static void install(TableAccess access) {
		Control control = access.control();
		// Die Zellen finden darüber zur Zeilenbedingung, wenn sie mit Tab weiterspringen
		control.getProperties().put(TableAccess.class, access);
		DragSelection dragSelection = new DragSelection(access);
		boolean[] clickMayEdit = { false };
		control.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
			clickMayEdit[0] = pressedOnlySelectedRow(access, event);
			dragSelection.onPressed(event);
		});
		control.addEventFilter(MouseEvent.MOUSE_DRAGGED, dragSelection::onDragged);
		control.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
			if (dragSelection.onReleased(event)) {
				event.consume();
			} else if (!clickMayEdit[0]) {
				suppressClickEdit(access, event);
			}
		});
		control.addEventFilter(KeyEvent.KEY_TYPED, event -> onKeyTyped(access, event));
		control.addEventFilter(KeyEvent.KEY_PRESSED, event -> onKeyPressed(access, event));
	}

	/** Der bei {@link #install} hinterlegte Zugriff, sonst einer ohne Zeilenbedingung. */
	private static TableAccess accessOf(Control control, TableAccess fallback) {
		return control.getProperties().get(TableAccess.class) instanceof TableAccess access ? access : fallback;
	}

	/**
	 * Ob der Druck eine Zeile trifft, die schon die einzige gewählte ist (ohne Umschalt oder Strg): Ein weiterer Klick
	 * auf eine ihrer Zellen startet dann das Bearbeiten, wie beim Umbenennen im Explorer.
	 */
	private static boolean pressedOnlySelectedRow(TableAccess access, MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY || event.isShiftDown() || event.isControlDown() || event.isMetaDown()
				|| insideControl(access, event.getTarget())) {
			return false;
		}
		IndexedCell<?> row = access.rowOf(event.getTarget());
		return row != null && !row.isEmpty() && access.selectedIndices().size() == 1 && access.selectedIndex() == row.getIndex();
	}

	/**
	 * JavaFX startet das Bearbeiten schon bei einem einzelnen Klick auf eine gewählte Zelle, auch wenn mehrere Zeilen
	 * gewählt sind oder Strg/Umschalt gedrückt waren. Das soll nur gelten, wenn die Zeile schon die einzige gewählte war
	 * ({@link #pressedOnlySelectedRow}); sonst startet nur ein Doppelklick (oder Tippen, Enter, F2) das Bearbeiten. Das
	 * Loslassen darf dafür nicht verworfen werden: JavaFX verschiebt bei einem Klick auf eine schon gewählte Zeile die
	 * Auswahl auf das Loslassen. Stattdessen ist die Tabelle für die Dauer dieses Loslassens nicht bearbeitbar (die
	 * Zelle fragt beim Start danach).
	 */
	private static void suppressClickEdit(TableAccess access, MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 1 || access.isEditing()
				|| !(access.cellOf(event.getTarget()) instanceof ParsingCell) || !access.isEditable()) {
			return;
		}
		access.setEditable(false);
		// Das Loslassen ist verarbeitet, sobald dieses Ereignis durch ist
		Platform.runLater(() -> access.setEditable(true));
	}

	private static void onKeyTyped(TableAccess access, KeyEvent event) {
		String typed = event.getCharacter();
		if (typed.isEmpty() || Character.isISOControl(typed.charAt(0)) || event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
			return;
		}
		IndexedCell<?> cell = editableCellInFocus(access);
		if (cell == null) {
			return;
		}
		access.edit(cell.getIndex(), access.columnOf(cell));
		if (cell.getGraphic() instanceof TextField field) {
			field.setText(typed);
			field.positionCaret(typed.length());
		}
		event.consume();
	}

	private static void onKeyPressed(TableAccess access, KeyEvent event) {
		if (access.isEditing() || event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
			return;
		}
		if (event.getCode() == KeyCode.ENTER) {
			IndexedCell<?> cell = editableCellInFocus(access);
			if (cell != null) {
				access.edit(cell.getIndex(), access.columnOf(cell));
				event.consume();
			} else if (access.focusedRow() >= 0 && !access.rowEditable(access.focusedRow())) {
				// Eine Zeile, die die Bedingung nicht zulässt (Gruppenzeile): JavaFX startete das Bearbeiten sonst selbst
				event.consume();
			}
		} else if (event.getCode() == KeyCode.TAB) {
			int row = access.focusedRow();
			if (row >= 0 && moveFocus(access, row, access.focusedColumn(), !event.isShiftDown())) {
				event.consume();
			}
		}
	}

	/** Die Zelle mit dem Fokus, wenn sie ein Textfeld bearbeiten lässt und genau diese eine Zeile gewählt ist. */
	private static IndexedCell<?> editableCellInFocus(TableAccess access) {
		if (!access.isEditable() || access.isEditing() || access.selectedIndices().size() != 1) {
			return null;
		}
		int row = access.focusedRow();
		TableColumnBase<?, ?> column = access.focusedColumn();
		if (column == null || !column.isEditable() || row != access.selectedIndex() || !access.rowEditable(row)) {
			return null;
		}
		IndexedCell<?> cell = access.cellAt(row, column);
		// die Zelle selbst kann gesperrt sein (etwa der gezahlte Betrag einer offenen Buchung)
		return cell instanceof ParsingCell parsing && parsing.canEdit() ? cell : null;
	}

	/** Wie {@link #moveFocus(TableAccess, int, TableColumnBase, boolean)} für eine {@link TableView}. */
	static <S> boolean moveFocus(TableView<S> table, int row, TableColumn<S, ?> column, boolean forward) {
		return moveFocus(accessOf(table, new TableViewAccess<>(table)), row, column, forward);
	}

	/**
	 * Setzt Auswahl und Fokus auf die nächste (oder vorherige) bearbeitbare Zelle, am Zeilenende in die nächste Zeile.
	 * Zeilen, die sich nicht bearbeiten lassen, werden übersprungen.
	 *
	 * @return {@code false}, wenn es keine weitere Zelle gibt (der Fokus bleibt, die Taste kann den Fokus weitergeben)
	 */
	static boolean moveFocus(TableAccess access, int row, TableColumnBase<?, ?> column, boolean forward) {
		List<TableColumnBase<?, ?>> columns = new ArrayList<>();
		for (TableColumnBase<?, ?> candidate : access.visibleLeafColumns()) {
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
		int rowCount = access.rowCount();
		while (nextRow >= 0 && nextRow < rowCount && !access.rowEditable(nextRow)) {
			// in der übersprungenen Richtung beginnt die nächste Zeile mit ihrer ersten bzw. letzten Spalte
			nextRow += forward ? 1 : -1;
			nextColumn = forward ? 0 : columns.size() - 1;
		}
		if (nextRow < 0 || nextRow >= rowCount) {
			return false;
		}
		access.clearAndSelect(nextRow);
		access.focus(nextRow, columns.get(nextColumn));
		// Nur so weit scrollen, dass die Zeile ganz zu sehen ist: TableView.scrollTo setzte sie an den oberen Rand, und die
		// Zeile sprang bei jedem Tab nach oben
		if (access.control().lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
			flow.scrollTo(nextRow);
		} else {
			access.scrollTo(nextRow);
		}
		access.control().requestFocus();
		return true;
	}

	/**
	 * Wie {@link #moveEditing(TableAccess, int, TableColumnBase, boolean)} für eine {@link TableView}. Eine Tabelle ohne
	 * {@link #install(TableView)} behandelt jede Zeile als bearbeitbar.
	 */
	static <S> boolean moveEditing(TableView<S> table, int row, TableColumn<S, ?> column, boolean forward) {
		return moveEditing(accessOf(table, new TableViewAccess<>(table)), row, column, forward);
	}

	/**
	 * Wie {@link #moveEditing(TableAccess, int, TableColumnBase, boolean)} für eine {@link TreeTableView}; es gilt die
	 * Zeilenbedingung aus {@link #install(TreeTableView, Predicate)}. Eine Tabelle ohne {@code install} behandelt jede
	 * Zeile als bearbeitbar.
	 */
	static <S> boolean moveEditing(TreeTableView<S> table, int row, TreeTableColumn<S, ?> column, boolean forward) {
		return moveEditing(accessOf(table, new TreeTableViewAccess<>(table, value -> true)), row, column, forward);
	}

	/**
	 * Wie {@link #moveFocus}, für Tab aus dem Bearbeiten: Ist die neue Zelle eine Textzelle, die sich bearbeiten lässt,
	 * beginnt dort gleich das Bearbeiten (mit markiertem Inhalt). Auswahllisten, Checkboxen und gesperrte Zellen bekommen
	 * nur den Fokus.
	 */
	static boolean moveEditing(TableAccess access, int row, TableColumnBase<?, ?> column, boolean forward) {
		if (!moveFocus(access, row, column, forward)) {
			return false;
		}
		// Nach dem Scrollen gibt es die Zellen der neuen Zeile erst nach einem Layout
		access.control().layout();
		IndexedCell<?> cell = editableCellInFocus(access);
		if (cell != null) {
			access.edit(cell.getIndex(), access.columnOf(cell));
		}
		return true;
	}

	/** Eine Zelle mit eigenem Bedienelement (Checkbox, Auswahlliste, Textfeld) startet kein Ziehen. */
	private static boolean insideControl(TableAccess access, Object target) {
		for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
			if (node instanceof ButtonBase || node instanceof ComboBoxBase<?> || node instanceof TextInputControl) {
				return true;
			}
			if (access.isRow(node)) {
				return false;
			}
		}
		return false;
	}

	/** Auswahl durch Drücken und Ziehen über mehrere Zeilen. */
	private static final class DragSelection {

		private final TableAccess access;
		private final Timeline autoScroll;
		private int anchor = -1;
		private TableColumnBase<?, ?> pressedColumn;
		private boolean dragging;
		private double pressX;
		private double pressY;
		private double lastSceneY;
		private int lastCurrent = -1;
		private List<Integer> base = List.of();

		DragSelection(TableAccess access) {
			this.access = access;
			this.autoScroll = new Timeline(new KeyFrame(Duration.millis(40), event -> autoScrollStep()));
			this.autoScroll.setCycleCount(Timeline.INDEFINITE);
		}

		void onPressed(MouseEvent event) {
			anchor = -1;
			dragging = false;
			if (event.getButton() != MouseButton.PRIMARY || access.isEditing() || insideControl(access, event.getTarget())) {
				return;
			}
			IndexedCell<?> row = access.rowOf(event.getTarget());
			if (row == null || row.isEmpty()) {
				return;
			}
			anchor = row.getIndex();
			IndexedCell<?> pressedCell = access.cellOf(event.getTarget());
			pressedColumn = pressedCell == null ? null : access.columnOf(pressedCell);
			pressX = event.getSceneX();
			pressY = event.getSceneY();
			lastCurrent = -1;
			base = event.isControlDown() ? new ArrayList<>(access.selectedIndices()) : List.of();
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
			access.clearSelection();
			access.selectIndices(wanted.stream().mapToInt(Integer::intValue).toArray());
			access.focus(current, pressedColumn);
		}

		private Bounds rowArea() {
			Node flow = access.control().lookup(".virtual-flow");
			Node area = flow == null ? access.control() : flow;
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
			for (Node node : access.control().lookupAll("." + access.rowStyleClass())) {
				if (node instanceof IndexedCell<?> row && !row.isEmpty() && row.isVisible()) {
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
			if (access.control().lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
				flow.scrollPixels(delta);
			}
			lastCurrent = -1; // nach dem Scrollen steht eine andere Zeile unter dem Mauszeiger
			selectUpTo(lastSceneY);
		}
	}
}
