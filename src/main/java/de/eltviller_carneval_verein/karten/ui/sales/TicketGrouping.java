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
	 * @param nested            {@code true}: eine Ebene je Spalte; {@code false}: bei zwei oder mehr Spalten nur eine Ebene
	 *                          mit einer Gruppe je vorkommender Kombination der Schlüssel (bei einer Spalte ohne Wirkung)
	 * @param sort              ordnet die Buchungen innerhalb der untersten Gruppen (ohne Gruppierung alle); {@code null}
	 *                          oder Gleichstand: Grundordnung
	 * @param presentationOrder die Reihenfolge der Vorstellungen
	 */
	public static List<TicketNode> group(List<BookingDTO> rows, List<TicketColumn> grouping, GroupOrder order, boolean nested,
			Comparator<BookingDTO> sort, List<Presentation> presentationOrder) {
		List<Comparator<Object>> keyOrders = new ArrayList<>();
		for (TicketColumn column : grouping) {
			keyOrders.add(column.keyOrder(presentationOrder));
		}
		if (!nested && grouping.size() >= 2) {
			return buildOneLevel(rows, grouping, keyOrders, order, sort);
		}
		return build(rows, grouping, keyOrders, 0, List.of(), order, sort);
	}

	private static List<TicketNode> buildOneLevel(List<BookingDTO> rows, List<TicketColumn> grouping, List<Comparator<Object>> keyOrders,
			GroupOrder order, Comparator<BookingDTO> sort) {
		Map<List<Object>, List<BookingDTO>> groups = new LinkedHashMap<>();
		for (BookingDTO dto : rows) {
			List<Object> keys = new ArrayList<>(grouping.size());
			for (TicketColumn column : grouping) {
				keys.add(column.keyOf(dto));
			}
			groups.computeIfAbsent(keys, key -> new ArrayList<>()).add(dto);
		}
		List<TicketNode> nodes = new ArrayList<>(groups.size());
		for (Map.Entry<List<Object>, List<BookingDTO>> entry : groups.entrySet()) {
			List<BookingDTO> members = entry.getValue();
			List<String> texts = new ArrayList<>(grouping.size());
			for (int i = 0; i < grouping.size(); i++) {
				texts.add(grouping.get(i).textOf(entry.getKey().get(i), members.get(0)));
			}
			TicketRow.GroupRow row = new TicketRow.GroupRow(List.copyOf(grouping), String.join(", ", texts), members.size(),
					List.of(List.copyOf(entry.getKey())));
			nodes.add(new TicketNode(row, build(members, grouping, keyOrders, grouping.size(), row.path(), order, sort)));
		}
		Comparator<TicketNode> byValue = (a, b) -> {
			List<?> left = (List<?>) ((TicketRow.GroupRow) a.row()).key();
			List<?> right = (List<?>) ((TicketRow.GroupRow) b.row()).key();
			for (int i = 0; i < keyOrders.size(); i++) {
				int result = keyOrders.get(i).compare(left.get(i), right.get(i));
				if (result != 0) {
					return result;
				}
			}
			return 0;
		};
		sortNodes(nodes, order, byValue);
		return nodes;
	}

	private static void sortNodes(List<TicketNode> nodes, GroupOrder order, Comparator<TicketNode> byValue) {
		if (order == GroupOrder.SIZE) {
			nodes.sort(Comparator.comparingInt((TicketNode node) -> ((TicketRow.GroupRow) node.row()).count()).reversed().thenComparing(byValue));
		} else {
			nodes.sort(byValue);
		}
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
			TicketRow.GroupRow row = new TicketRow.GroupRow(List.of(column), text, members.size(), List.copyOf(path));
			nodes.add(new TicketNode(row, build(members, grouping, keyOrders, level + 1, path, order, sort)));
		}
		Comparator<Object> keyOrder = keyOrders.get(level);
		Comparator<TicketNode> byValue = (a, b) -> keyOrder.compare(((TicketRow.GroupRow) a.row()).key(), ((TicketRow.GroupRow) b.row()).key());
		sortNodes(nodes, order, byValue);
		return nodes;
	}
}
