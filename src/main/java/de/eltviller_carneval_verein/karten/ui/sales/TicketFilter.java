package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import de.eltviller_carneval_verein.karten.model.Presentation;

/**
 * Die Spaltenfilter der Kartentabelle, ohne JavaFX: je Spalte eine Menge abgewählter Schlüssel. Eine Buchung besteht
 * den Filter, wenn keiner ihrer Schlüssel in der Menge ihrer Spalte abgewählt ist. Abgewählt statt gewählt, damit ein
 * Wert, der durch eine fremde Änderung neu entsteht, sichtbar ist.
 */
public final class TicketFilter {

	/** Ein Wert der Werteliste einer Spalte: Schlüssel, Text und Anzahl der Buchungen. */
	public record FilterValue(Object key, String text, int count) {
	}

	private final Map<TicketColumn, Set<Object>> deselected = new EnumMap<>(TicketColumn.class);

	/** Besteht die Buchung die Filter aller Spalten? */
	public boolean passes(BookingDTO row) {
		return passesExcept(row, null);
	}

	/** Die abgewählten Schlüssel der Spalte (eine Kopie). */
	public Set<Object> deselected(TicketColumn column) {
		Set<Object> keys = deselected.get(column);
		return keys == null ? Set.of() : Set.copyOf(keys);
	}

	/** Setzt die abgewählten Schlüssel der Spalte; {@code true}, wenn sich dadurch etwas geändert hat. */
	public boolean setDeselected(TicketColumn column, Set<Object> keys) {
		if (deselected(column).equals(keys)) {
			return false;
		}
		if (keys.isEmpty()) {
			deselected.remove(column);
		} else {
			deselected.put(column, new HashSet<>(keys));
		}
		return true;
	}

	/** Ist für die Spalte ein Schlüssel abgewählt? */
	public boolean isActive(TicketColumn column) {
		return deselected.containsKey(column);
	}

	/** Hebt alle Filter auf; {@code true}, wenn einer aktiv war. */
	public boolean clear() {
		boolean changed = !deselected.isEmpty();
		deselected.clear();
		return changed;
	}

	/**
	 * Die Werteliste der Spalte: die Schlüssel aller Buchungen aus {@code afterSearch}, die die Filter der anderen Spalten
	 * bestehen, mit Text und Anzahl, in der Ordnung der Spalte. Abgewählte Schlüssel bleiben darin, solange Buchungen sie
	 * tragen.
	 */
	public List<FilterValue> values(TicketColumn column, List<BookingDTO> afterSearch, List<Presentation> order) {
		return values(column, afterSearch, row -> false, order);
	}

	/**
	 * Wie {@link #values(TicketColumn, List, List)}; Buchungen, für die {@code exempt} gilt (selbst geänderte, Spec
	 * Abschnitt 11), zählen auch, wenn sie die Filter der anderen Spalten nicht bestehen.
	 */
	public List<FilterValue> values(TicketColumn column, List<BookingDTO> afterSearch, Predicate<BookingDTO> exempt,
			List<Presentation> order) {
		List<BookingDTO> rows = new ArrayList<>();
		for (BookingDTO row : afterSearch) {
			if (exempt.test(row) || passesExcept(row, column)) {
				rows.add(row);
			}
		}
		rows.sort(TicketColumn.baseOrder(order));
		Map<Object, BookingDTO> firsts = new LinkedHashMap<>();
		Map<Object, Integer> counts = new LinkedHashMap<>();
		for (BookingDTO row : rows) {
			Object key = column.keyOf(row);
			firsts.putIfAbsent(key, row);
			counts.merge(key, 1, Integer::sum);
		}
		List<FilterValue> values = new ArrayList<>();
		for (Map.Entry<Object, BookingDTO> entry : firsts.entrySet()) {
			values.add(new FilterValue(entry.getKey(), column.textOf(entry.getKey(), entry.getValue()), counts.get(entry.getKey())));
		}
		Comparator<Object> keyOrder = column.keyOrder(order);
		values.sort((a, b) -> keyOrder.compare(a.key(), b.key()));
		return values;
	}

	private boolean passesExcept(BookingDTO row, TicketColumn skipped) {
		for (Map.Entry<TicketColumn, Set<Object>> entry : deselected.entrySet()) {
			if (entry.getKey() != skipped && entry.getValue().contains(entry.getKey().keyOf(row))) {
				return false;
			}
		}
		return true;
	}
}
