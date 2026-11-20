package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.eltviller_carneval_verein.karten.model.Presentation;

/** Gruppiert die Buchungen der Kartentabelle zu einem Baum, ohne JavaFX. */
public final class TicketGrouping {

	private TicketGrouping() {
	}

	/**
	 * Baut den Baum und liefert die Kinder der Wurzel.
	 *
	 * @param rows              die Buchungen in Grundordnung
	 * @param grouping          die Spalten der Gruppierung von oben nach unten, ohne Doppelte
	 * @param order             die Ordnung der Gruppen auf allen Ebenen
	 * @param sort              ordnet die Buchungen innerhalb der untersten Gruppen (ohne Gruppierung alle); {@code null}
	 *                          oder Gleichstand: Grundordnung
	 * @param presentationOrder die Reihenfolge der Vorstellungen
	 */
	public static List<TicketNode> group(List<BookingDTO> rows, List<TicketColumn> grouping, GroupOrder order,
			Comparator<BookingDTO> sort, List<Presentation> presentationOrder) {
		List<Comparator<Object>> keyOrders = new ArrayList<>();
		for (TicketColumn column : grouping) {
			keyOrders.add(column.keyOrder(presentationOrder));
		}
		return build(rows, grouping, keyOrders, 0, List.of(), order, sort);
	}

	private static List<TicketNode> build(List<BookingDTO> rows, List<TicketColumn> grouping, List<Comparator<Object>> keyOrders,
			int level, List<Object> parentPath, GroupOrder order, Comparator<BookingDTO> sort) {
		if (level == grouping.size()) {
			List<BookingDTO> sorted = new ArrayList<>(rows);
			if (sort != null) {
				sorted.sort(sort); // stabil: Gleichstand bleibt in Grundordnung
			}
			List<TicketNode> leaves = new ArrayList<>(sorted.size());
			for (BookingDTO dto : sorted) {
				leaves.add(new TicketNode(new TicketRow.BookingRow(dto), List.of()));
			}
			return leaves;
		}
		TicketColumn column = grouping.get(level);
		Map<Object, List<BookingDTO>> groups = new LinkedHashMap<>();
		for (BookingDTO dto : rows) {
			groups.computeIfAbsent(column.keyOf(dto), key -> new ArrayList<>()).add(dto);
		}
		List<TicketNode> nodes = new ArrayList<>(groups.size());
		for (Map.Entry<Object, List<BookingDTO>> entry : groups.entrySet()) {
			List<Object> path = new ArrayList<>(parentPath);
			path.add(entry.getKey());
			List<BookingDTO> members = entry.getValue();
			String text = column.textOf(entry.getKey(), members.get(0));
			TicketRow.GroupRow row = new TicketRow.GroupRow(column, text, members.size(), List.copyOf(path));
			nodes.add(new TicketNode(row, build(members, grouping, keyOrders, level + 1, path, order, sort)));
		}
		Comparator<Object> keyOrder = keyOrders.get(level);
		Comparator<TicketNode> byValue = (a, b) -> keyOrder.compare(((TicketRow.GroupRow) a.row()).key(), ((TicketRow.GroupRow) b.row()).key());
		if (order == GroupOrder.SIZE) {
			nodes.sort(Comparator.comparingInt((TicketNode node) -> ((TicketRow.GroupRow) node.row()).count()).reversed().thenComparing(byValue));
		} else {
			nodes.sort(byValue);
		}
		return nodes;
	}
}
