package de.eltviller_carneval_verein.karten.ui.input;

import java.util.Arrays;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableSelectionModel;

/**
 * Was {@link TableInput} von einer Tabelle braucht, unabhängig von der Tabellenart: Zeilen als Index der sichtbaren
 * Zeilen, Spalten als {@link TableColumnBase}. Umgesetzt für {@code TableView} ({@link TableViewAccess}) und
 * {@code TreeTableView} ({@link TreeTableViewAccess}).
 */
interface TableAccess {

	Control control();

	boolean isEditable();

	void setEditable(boolean editable);

	/** Ob gerade eine Zelle bearbeitet wird. */
	boolean isEditing();

	void edit(int row, TableColumnBase<?, ?> column);

	/** Zeile der Zelle mit dem Fokus, {@code -1}: keine. */
	int focusedRow();

	/** Spalte der Zelle mit dem Fokus, {@code null}: keine. */
	TableColumnBase<?, ?> focusedColumn();

	void focus(int row, TableColumnBase<?, ?> column);

	/** Das Auswahlmodell der Tabelle; Zeilen darin wie hier als Index der sichtbaren Zeilen. */
	TableSelectionModel<?> selectionModel();

	default List<Integer> selectedIndices() {
		return selectionModel().getSelectedIndices();
	}

	default int selectedIndex() {
		return selectionModel().getSelectedIndex();
	}

	default void clearAndSelect(int row) {
		selectionModel().clearAndSelect(row);
	}

	default void clearSelection() {
		selectionModel().clearSelection();
	}

	/** Wählt die Zeilen zusätzlich aus; {@code rows} ist nicht leer. */
	default void selectIndices(int[] rows) {
		selectionModel().selectIndices(rows[0], Arrays.copyOfRange(rows, 1, rows.length));
	}

	List<? extends TableColumnBase<?, ?>> visibleLeafColumns();

	/** Anzahl der sichtbaren Zeilen (in einer Baumtabelle ohne die Kinder zugeklappter Einträge). */
	int rowCount();

	/** Ob die Zeile sich bearbeiten lässt; Tab überspringt die anderen. */
	boolean rowEditable(int row);

	/** Scrollt die Zeile in den sichtbaren Bereich, wenn die Tabelle keinen {@code VirtualFlow} hat. */
	void scrollTo(int row);

	/** Style-Klasse der Zeilen ({@code table-row-cell} bzw. {@code tree-table-row-cell}). */
	String rowStyleClass();

	/** Style-Klasse der Zellen ({@code table-cell} bzw. {@code tree-table-cell}). */
	String cellStyleClass();

	/** Die Spalte einer Zelle dieser Tabelle. */
	TableColumnBase<?, ?> columnOf(IndexedCell<?> cell);

	/** Die angezeigte Zelle in Zeile {@code row} und Spalte {@code column}, {@code null}: nicht angezeigt. */
	default IndexedCell<?> cellAt(int row, TableColumnBase<?, ?> column) {
		for (Node node : control().lookupAll("." + rowStyleClass())) {
			if (node instanceof IndexedCell<?> tableRow && tableRow.getIndex() == row) {
				for (Node cellNode : tableRow.lookupAll("." + cellStyleClass())) {
					if (cellNode instanceof IndexedCell<?> cell && columnOf(cell) == column) {
						return cell;
					}
				}
			}
		}
		return null;
	}

	/** Die Zeile, in der das Ereignisziel liegt; {@code null} außerhalb der Zeilen (auch auf einer Bildlaufleiste). */
	default IndexedCell<?> rowOf(Object target) {
		return (IndexedCell<?>) TableGestures.rowOf(target, this::isRow);
	}

	/** Die Zelle, in der das Ereignisziel liegt, {@code null}: keine. */
	default IndexedCell<?> cellOf(Object target) {
		return (IndexedCell<?>) TableGestures.ancestor(target,
				node -> node instanceof IndexedCell<?> && node.getStyleClass().contains(cellStyleClass()));
	}

	default boolean isRow(Node node) {
		return node instanceof IndexedCell<?> && node.getStyleClass().contains(rowStyleClass());
	}
}
