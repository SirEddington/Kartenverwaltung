package de.eltviller_carneval_verein.karten.ui.input;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import javafx.scene.control.Control;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableView;

/**
 * {@link TableAccess} für eine {@link TreeTableView}. Zeilen sind die sichtbaren Einträge (Index wie
 * {@link TreeTableView#getTreeItem(int)}); bearbeitbar ist eine Zeile, deren Wert die Bedingung erfüllt.
 */
final class TreeTableViewAccess<S> implements TableAccess {

	private final TreeTableView<S> table;
	private final Predicate<S> rowEditable;

	TreeTableViewAccess(TreeTableView<S> table, Predicate<S> rowEditable) {
		this.table = table;
		this.rowEditable = rowEditable;
	}

	@Override
	public Control control() {
		return table;
	}

	@Override
	public boolean isEditable() {
		return table.isEditable();
	}

	@Override
	public void setEditable(boolean editable) {
		table.setEditable(editable);
	}

	@Override
	public boolean isEditing() {
		return table.getEditingCell() != null;
	}

	@Override
	public void edit(int row, TableColumnBase<?, ?> column) {
		table.edit(row, cast(column));
	}

	@Override
	public int focusedRow() {
		TreeTablePosition<S, ?> focused = table.getFocusModel().getFocusedCell();
		return focused == null ? -1 : focused.getRow();
	}

	@Override
	public TableColumnBase<?, ?> focusedColumn() {
		TreeTablePosition<S, ?> focused = table.getFocusModel().getFocusedCell();
		return focused == null ? null : focused.getTableColumn();
	}

	@Override
	public void focus(int row, TableColumnBase<?, ?> column) {
		table.getFocusModel().focus(row, cast(column));
	}

	@Override
	public List<Integer> selectedIndices() {
		return table.getSelectionModel().getSelectedIndices();
	}

	@Override
	public int selectedIndex() {
		return table.getSelectionModel().getSelectedIndex();
	}

	@Override
	public void clearAndSelect(int row) {
		table.getSelectionModel().clearAndSelect(row);
	}

	@Override
	public void clearSelection() {
		table.getSelectionModel().clearSelection();
	}

	@Override
	public void selectIndices(int[] rows) {
		table.getSelectionModel().selectIndices(rows[0], Arrays.copyOfRange(rows, 1, rows.length));
	}

	@Override
	public List<? extends TableColumnBase<?, ?>> visibleLeafColumns() {
		return table.getVisibleLeafColumns();
	}

	@Override
	public int rowCount() {
		return table.getExpandedItemCount();
	}

	@Override
	public boolean rowEditable(int row) {
		TreeItem<S> item = table.getTreeItem(row);
		return item != null && item.getValue() != null && rowEditable.test(item.getValue());
	}

	@Override
	public void scrollTo(int row) {
		table.scrollTo(row);
	}

	@Override
	public String rowStyleClass() {
		return "tree-table-row-cell";
	}

	@Override
	public String cellStyleClass() {
		return "tree-table-cell";
	}

	@Override
	public TableColumnBase<?, ?> columnOf(IndexedCell<?> cell) {
		return cell instanceof TreeTableCell<?, ?> treeCell ? treeCell.getTableColumn() : null;
	}

	@SuppressWarnings("unchecked")
	private TreeTableColumn<S, ?> cast(TableColumnBase<?, ?> column) {
		return (TreeTableColumn<S, ?>) column;
	}
}
