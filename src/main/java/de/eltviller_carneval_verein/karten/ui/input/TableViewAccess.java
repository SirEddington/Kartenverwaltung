package de.eltviller_carneval_verein.karten.ui.input;

import java.util.List;

import javafx.scene.control.Control;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableSelectionModel;
import javafx.scene.control.TableView;

/** {@link TableAccess} für eine {@link TableView}; jede Zeile lässt sich bearbeiten. */
final class TableViewAccess<S> implements TableAccess {

	private final TableView<S> table;

	TableViewAccess(TableView<S> table) {
		this.table = table;
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
		TablePosition<S, ?> focused = table.getFocusModel().getFocusedCell();
		return focused == null ? -1 : focused.getRow();
	}

	@Override
	public TableColumnBase<?, ?> focusedColumn() {
		TablePosition<S, ?> focused = table.getFocusModel().getFocusedCell();
		return focused == null ? null : focused.getTableColumn();
	}

	@Override
	public void focus(int row, TableColumnBase<?, ?> column) {
		table.getFocusModel().focus(row, cast(column));
	}

	@Override
	public TableSelectionModel<?> selectionModel() {
		return table.getSelectionModel();
	}

	@Override
	public List<? extends TableColumnBase<?, ?>> visibleLeafColumns() {
		return table.getVisibleLeafColumns();
	}

	@Override
	public int rowCount() {
		return table.getItems().size();
	}

	@Override
	public boolean rowEditable(int row) {
		return true;
	}

	@Override
	public void scrollTo(int row) {
		table.scrollTo(row);
	}

	@Override
	public String rowStyleClass() {
		return "table-row-cell";
	}

	@Override
	public String cellStyleClass() {
		return "table-cell";
	}

	@Override
	public TableColumnBase<?, ?> columnOf(IndexedCell<?> cell) {
		return cell instanceof TableCell<?, ?> tableCell ? tableCell.getTableColumn() : null;
	}

	@SuppressWarnings("unchecked")
	private TableColumn<S, ?> cast(TableColumnBase<?, ?> column) {
		return (TableColumn<S, ?>) column;
	}
}
