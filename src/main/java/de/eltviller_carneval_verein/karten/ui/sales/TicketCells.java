package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Booking;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.cell.CheckBoxTreeTableCell;
import javafx.scene.control.cell.ComboBoxTreeTableCell;
import javafx.util.Callback;
import javafx.util.StringConverter;

/**
 * Zellen der Kartentabelle als Baumtabelle (KARV-193, Spec Abschnitte 4 und 9): Werte und Bedienelemente gibt es nur in
 * Buchungszeilen; eine Gruppenzeile zeigt keine Checkbox und keine Auswahlliste und lässt sich nicht bearbeiten. Die
 * Buchung einer Zeile kommt immer über ihr {@link TreeItem} ({@code getTreeItem(index)}), nie über einen Listenindex.
 */
final class TicketCells {

	private TicketCells() {
	}

	/** Wert-Fabrik einer Spalte: der Wert der Buchung, in einer Gruppenzeile keiner. */
	static <T> Callback<TreeTableColumn.CellDataFeatures<TicketRow, T>, ObservableValue<T>> value(Function<BookingDTO, ObservableValue<T>> value) {
		return cell -> cell.getValue() != null && cell.getValue().getValue() instanceof TicketRow.BookingRow row ? value.apply(row.dto()) : null;
	}

	/** Die Zeile eines Elements als Buchung, sonst (Gruppe, leer) {@code null}. */
	static BookingDTO dtoOf(TreeItem<TicketRow> item) {
		return item != null && item.getValue() instanceof TicketRow.BookingRow row ? row.dto() : null;
	}

	/** Die Buchung eines Elements, sonst {@code null}. */
	static Booking bookingOf(TreeItem<TicketRow> item) {
		BookingDTO dto = dtoOf(item);
		return dto == null ? null : dto.getBooking();
	}

	/**
	 * Checkbox, deren Zustand {@code state} für die Buchung der Zeile liefert (bearbeitbar über eine {@code BooleanProperty},
	 * nur lesbar bei nicht bearbeitbarer Spalte).
	 */
	static Callback<TreeTableColumn<TicketRow, Boolean>, TreeTableCell<TicketRow, Boolean>> checkBox(
			Function<BookingDTO, ObservableValue<Boolean>> state) {
		return column -> new CheckBoxCell(state);
	}

	/** Auswahlliste mit festen Werten. */
	@SafeVarargs
	static <T> Callback<TreeTableColumn<TicketRow, T>, TreeTableCell<TicketRow, T>> comboBox(StringConverter<T> converter, T... items) {
		return column -> new ComboBoxCell<>(converter, items);
	}

	/** Tisch: "A3" bzw. "3"; ohne Platz "Ohne Platz (bisher A3)". */
	static Callback<TreeTableColumn<TicketRow, TableLabel>, TreeTableCell<TicketRow, TableLabel>> tableLabel() {
		return column -> new TreeTableCell<>() {
			@Override
			protected void updateItem(TableLabel label, boolean empty) {
				super.updateItem(label, empty);
				BookingDTO dto = empty ? null : dtoAt(this);
				if (label == null || dto == null) {
					setText(null);
					return;
				}
				setText(label.unassigned() ? TableLabel.UNASSIGNED_TEXT + " (bisher " + TableLabel.placeOf(dto.getBooking()) + ")" : label.toString());
			}
		};
	}

	private static BookingDTO dtoAt(TreeTableCell<TicketRow, ?> cell) {
		TreeTableView<TicketRow> table = cell.getTreeTableView();
		return table == null || cell.getIndex() < 0 ? null : dtoOf(table.getTreeItem(cell.getIndex()));
	}

	/**
	 * Hat die Tabelle diese Zelle einer Gruppenzeile als bearbeitet vermerkt (etwa {@code table.edit}), endet das gleich:
	 * Sonst hielte sie fremde Änderungen und den Neuaufbau zurück.
	 */
	private static void refuseEdit(TreeTableCell<TicketRow, ?> cell) {
		TreeTableView<TicketRow> table = cell.getTreeTableView();
		TreeTablePosition<TicketRow, ?> editing = table == null ? null : table.getEditingCell();
		if (editing != null && editing.getRow() == cell.getIndex() && editing.getTableColumn() == cell.getTableColumn()) {
			table.edit(-1, null);
		}
	}

	private static void hide(IndexedCell<?> cell) {
		cell.setText(null);
		cell.setGraphic(null);
	}

	/** {@link CheckBoxTreeTableCell}, die in Gruppenzeilen nichts zeigt. */
	private static final class CheckBoxCell extends CheckBoxTreeTableCell<TicketRow, Boolean> {

		CheckBoxCell(Function<BookingDTO, ObservableValue<Boolean>> state) {
			// Der Index kommt von der Zelle; die Buchung dazu über das TreeItem dieser Zeile
			setSelectedStateCallback(index -> {
				TreeTableView<TicketRow> table = getTreeTableView();
				BookingDTO dto = table == null ? null : dtoOf(table.getTreeItem(index));
				return dto == null ? new SimpleBooleanProperty(false) : state.apply(dto);
			});
		}

		@Override
		public void updateItem(Boolean item, boolean empty) {
			super.updateItem(item, empty);
			if (!empty && dtoAt(this) == null) {
				hide(this);
			}
		}

		@Override
		public void startEdit() {
			if (dtoAt(this) == null) {
				refuseEdit(this);
				return;
			}
			super.startEdit();
		}
	}

	/** {@link ComboBoxTreeTableCell}, die in Gruppenzeilen nichts zeigt und sich dort nicht bearbeiten lässt. */
	private static final class ComboBoxCell<T> extends ComboBoxTreeTableCell<TicketRow, T> {

		@SafeVarargs
		ComboBoxCell(StringConverter<T> converter, T... items) {
			super(converter, items);
		}

		@Override
		public void updateItem(T item, boolean empty) {
			super.updateItem(item, empty);
			if (!empty && dtoAt(this) == null) {
				hide(this);
			}
		}

		@Override
		public void startEdit() {
			if (dtoAt(this) == null) {
				refuseEdit(this);
				return;
			}
			super.startEdit();
		}
	}
}
