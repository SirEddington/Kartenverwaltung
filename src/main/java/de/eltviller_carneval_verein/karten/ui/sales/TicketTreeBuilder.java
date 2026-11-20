package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Booking;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;

/**
 * Baut die {@link TreeItem}s der Kartentabelle aus dem Baum von {@link TicketGrouping} und kennt zu jedem Element seinen
 * Knoten, zu jeder Buchung und jedem Pfad das Element. Dazu der Strukturvergleich vor einem Neuaufbau und die
 * Sortierung der Spalten als {@code Comparator<BookingDTO>}. Teil von {@link TicketTreeTable}.
 */
final class TicketTreeBuilder {

	private final Map<TreeItem<?>, TicketNode> nodeOf = new IdentityHashMap<>();
	private final Map<Booking, TreeItem<TicketRow>> itemOfBooking = new IdentityHashMap<>();
	private final Map<List<Object>, TreeItem<TicketRow>> itemOfPath = new HashMap<>();

	/**
	 * Baut die Elemente neu und vergisst die alten. Gruppen sind aufgeklappt, außer ihr Pfad steht in {@code collapsed};
	 * {@code onToggle} erfährt danach jedes Auf- und Zuklappen einer Gruppe (erst nach dem Klappen beim Aufbau).
	 */
	List<TreeItem<TicketRow>> build(List<TicketNode> nodes, Set<List<Object>> collapsed, BiConsumer<TreeItem<TicketRow>, Boolean> onToggle) {
		nodeOf.clear();
		itemOfBooking.clear();
		itemOfPath.clear();
		return items(nodes, collapsed, onToggle);
	}

	private List<TreeItem<TicketRow>> items(List<TicketNode> list, Set<List<Object>> collapsed, BiConsumer<TreeItem<TicketRow>, Boolean> onToggle) {
		List<TreeItem<TicketRow>> items = new ArrayList<>(list.size());
		for (TicketNode node : list) {
			TreeItem<TicketRow> item = new TreeItem<>(node.row());
			nodeOf.put(item, node);
			if (node.row() instanceof TicketRow.GroupRow group) {
				item.getChildren().setAll(items(node.children(), collapsed, onToggle));
				// vor dem Listener: Das Klappen beim Aufbau ist keine Bedienung
				item.setExpanded(!collapsed.contains(group.path()));
				item.expandedProperty().addListener((property, was, now) -> onToggle.accept(item, now));
				itemOfPath.put(group.path(), item);
			} else if (node.row() instanceof TicketRow.BookingRow booking) {
				itemOfBooking.put(booking.dto().getBooking(), item);
			}
			items.add(item);
		}
		return items;
	}

	/** Der Knoten eines Elements dieses Baums, sonst {@code null}. */
	TicketNode node(Object item) {
		return item == null ? null : nodeOf.get(item);
	}

	/** Das Element mit seinem Typ, wenn es zu diesem Baum gehört, sonst {@code null}. */
	@SuppressWarnings("unchecked")
	TreeItem<TicketRow> typed(Object item) {
		return item != null && nodeOf.containsKey(item) ? (TreeItem<TicketRow>) item : null;
	}

	TreeItem<TicketRow> item(Booking booking) {
		return itemOfBooking.get(booking);
	}

	TreeItem<TicketRow> item(List<Object> path) {
		return itemOfPath.get(path);
	}

	Collection<TreeItem<TicketRow>> groupItems() {
		return itemOfPath.values();
	}

	/** Schlüssel einer Zeile über einen Neuaufbau: die Buchung bzw. der Pfad der Gruppe. */
	Object keyOf(TreeItem<?> item) {
		TicketNode node = node(item);
		if (node == null) {
			return null;
		}
		return node.row() instanceof TicketRow.GroupRow group ? group.path() : ((TicketRow.BookingRow) node.row()).dto().getBooking();
	}

	/** Das Element zu einem Schlüssel aus {@link #keyOf}, im neuen Baum. */
	TreeItem<TicketRow> itemOf(Object key) {
		if (key instanceof Booking booking) {
			return itemOfBooking.get(booking);
		}
		return key instanceof List<?> path ? itemOfPath.get(path) : null;
	}

	/** Gleiche Pfade, gleiche Buchungen je Gruppe (nach Identität), gleiche Reihenfolge, gleiche Kopftexte. */
	static boolean sameStructure(List<TicketNode> a, List<TicketNode> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			TicketRow left = a.get(i).row();
			TicketRow right = b.get(i).row();
			if (left instanceof TicketRow.BookingRow x && right instanceof TicketRow.BookingRow y) {
				if (x.dto().getBooking() != y.dto().getBooking()) {
					return false;
				}
			} else if (left instanceof TicketRow.GroupRow x && right instanceof TicketRow.GroupRow y) {
				if (x.column() != y.column() || !x.path().equals(y.path()) || !x.text().equals(y.text()) || x.count() != y.count()
						|| !sameStructure(a.get(i).children(), b.get(i).children())) {
					return false;
				}
			} else {
				return false;
			}
		}
		return true;
	}

	/**
	 * Ordnet Buchungen nach den Spalten {@code order}: Zellwert über die Wert-Fabrik der Spalte, Comparator und Richtung
	 * der Spalte; Gleichstand bleibt dem Aufrufer (Grundordnung). {@code null} ohne Spalten. Der Comparator liest den Wert
	 * jeder Buchung je Spalte nur einmal; er gilt deshalb nur für einen Neuaufbau.
	 */
	static Comparator<BookingDTO> sortComparator(List<TreeTableColumn<TicketRow, ?>> order) {
		if (order.isEmpty()) {
			return null;
		}
		Map<BookingDTO, TreeItem<TicketRow>> items = new IdentityHashMap<>();
		Comparator<BookingDTO> result = null;
		for (TreeTableColumn<TicketRow, ?> column : order) {
			Comparator<BookingDTO> byColumn = byColumn(column, items);
			result = result == null ? byColumn : result.thenComparing(byColumn);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static <T> Comparator<BookingDTO> byColumn(TreeTableColumn<TicketRow, T> column, Map<BookingDTO, TreeItem<TicketRow>> items) {
		Comparator<T> comparator = column.getComparator() != null ? column.getComparator() : (Comparator<T>) TableColumnBase.DEFAULT_COMPARATOR;
		Map<BookingDTO, T> values = new IdentityHashMap<>();
		Function<BookingDTO, T> valueOf = dto -> {
			if (!values.containsKey(dto)) { // auch ein fehlender Wert (null) wird nur einmal gelesen
				values.put(dto, cellValue(column, dto, items));
			}
			return values.get(dto);
		};
		Comparator<BookingDTO> byValue = (a, b) -> comparator.compare(valueOf.apply(a), valueOf.apply(b));
		return column.getSortType() == TreeTableColumn.SortType.DESCENDING ? byValue.reversed() : byValue;
	}

	private static <T> T cellValue(TreeTableColumn<TicketRow, T> column, BookingDTO dto, Map<BookingDTO, TreeItem<TicketRow>> items) {
		TreeItem<TicketRow> item = items.computeIfAbsent(dto, row -> new TreeItem<>(new TicketRow.BookingRow(row)));
		ObservableValue<T> value = column.getCellObservableValue(item);
		return value == null ? null : value.getValue();
	}
}
