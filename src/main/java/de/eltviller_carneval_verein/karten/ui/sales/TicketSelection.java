package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import de.eltviller_carneval_verein.karten.model.Booking;

/**
 * Regeln der Auswahl in der Kartentabelle (KARV-193), ohne JavaFX: Die Auswahl ist eine Menge von Buchungen nach
 * Identität, nicht von Zeilen. Die Bedienung überträgt hier, welche Zeile sie meint; die Anzeige liest daraus, was
 * markiert ist und welche zugeklappten Gruppen aufgeklappt werden müssen. Welche Gruppen zugeklappt sind, weiß der
 * Aufrufer.
 */
final class TicketSelection {

	private final Set<Booking> selected = Collections.newSetFromMap(new IdentityHashMap<>());

	/** Die gewählten Buchungen in Reihenfolge der Tabelle, auch die in zugeklappten Gruppen. */
	List<Booking> selected(List<TicketNode> tree) {
		return bookings(tree).stream().filter(selected::contains).toList();
	}

	/** Klick: Menge = diese Buchung bzw. alle Buchungen der Gruppe. */
	void select(TicketNode node) {
		selected.clear();
		add(node);
	}

	/** Strg-Klick: ganz markiert (bei einer Buchung: gewählt) – alle heraus, sonst alle hinein. */
	void toggle(TicketNode node) {
		boolean all = fullySelected(node);
		for (Booking booking : bookings(node)) {
			if (all) {
				selected.remove(booking);
			} else {
				selected.add(booking);
			}
		}
	}

	/** Leertaste: die Buchung bzw. alle Buchungen der Gruppe hinein. */
	void add(TicketNode node) {
		selected.addAll(bookings(node));
	}

	/**
	 * Bereich: nur die Buchungszeilen unter {@code rowsBetween} (Zeilen zwischen Anker und Ziel, wie sie zu sehen sind);
	 * Gruppenzeilen und was in zugeklappten Gruppen steckt, tragen nichts bei. Mit {@code keep} (Strg) kommen sie zur
	 * bisherigen Menge dazu, sonst ersetzen sie sie.
	 */
	void range(List<TicketNode> rowsBetween, boolean keep) {
		if (!keep) {
			selected.clear();
		}
		for (TicketNode node : rowsBetween) {
			if (node.row() instanceof TicketRow.BookingRow row) {
				selected.add(row.dto().getBooking());
			}
		}
	}

	/** Strg+A: alle Buchungen der Tabelle, auch in zugeklappten Gruppen. */
	void selectAll(List<TicketNode> tree) {
		selected.clear();
		selected.addAll(bookings(tree));
	}

	/** {@code restoreSelection}: Menge = die Buchungen aus {@code bookings}, die im Baum stehen. */
	void set(Collection<Booking> bookings, List<TicketNode> tree) {
		Set<Booking> wanted = identitySet(bookings);
		selected.clear();
		bookings(tree).stream().filter(wanted::contains).forEach(selected::add);
	}

	/** {@code deselect}: nimmt die Buchungen aus der Menge. */
	void remove(Collection<Booking> bookings) {
		bookings.forEach(selected::remove); // nach Identität, nicht über equals der übergebenen Sammlung
	}

	void clear() {
		selected.clear();
	}

	/**
	 * Regel 2: Eine Gruppe ist markiert genau dann, wenn alle Buchungen darunter gewählt sind; eine Gruppe ohne
	 * Buchungen nie. Für eine Buchungszeile: ob die Buchung gewählt ist.
	 */
	boolean fullySelected(TicketNode node) {
		List<Booking> under = bookings(node);
		return !under.isEmpty() && selected.containsAll(under);
	}

	/**
	 * Regel 3: die Pfade der zugeklappten Gruppen (auf jeder Ebene), unter denen nur ein Teil der Buchungen gewählt
	 * ist. Sie werden aufgeklappt.
	 */
	Set<List<Object>> pathsToExpand(List<TicketNode> tree, Set<List<Object>> collapsed) {
		Set<List<Object>> paths = new HashSet<>();
		collectPartlySelected(tree, collapsed, paths);
		return paths;
	}

	private void collectPartlySelected(List<TicketNode> nodes, Set<List<Object>> collapsed, Set<List<Object>> paths) {
		for (TicketNode node : nodes) {
			if (node.row() instanceof TicketRow.GroupRow group) {
				if (collapsed.contains(group.path()) && partlySelected(node)) {
					paths.add(group.path());
				}
				collectPartlySelected(node.children(), collapsed, paths);
			}
		}
	}

	private boolean partlySelected(TicketNode group) {
		List<Booking> under = bookings(group);
		long chosen = under.stream().filter(selected::contains).count();
		return chosen > 0 && chosen < under.size();
	}

	/**
	 * Zuklappen einer Gruppe: ganz gewählt – die Auswahl bleibt; nur teilweise gewählt – deren gewählte Buchungen fallen
	 * heraus. Gilt für jeden Auslöser; was JavaFX beim Zuklappen im Auswahlmodell tut, zählt nicht.
	 */
	void collapsing(TicketNode group) {
		if (!fullySelected(group)) {
			bookings(group).forEach(selected::remove);
		}
	}

	/**
	 * Neuaufbau: Die Menge bleibt, nur Buchungen, die nicht mehr im Baum stehen, fallen heraus. Neu hinzugekommene
	 * Buchungen sind nicht gewählt; eine gewählte Gruppe wächst also nie von selbst.
	 */
	void rebuilt(List<TicketNode> tree) {
		selected.retainAll(identitySet(bookings(tree)));
	}

	private static Set<Booking> identitySet(Collection<Booking> bookings) {
		Set<Booking> set = Collections.newSetFromMap(new IdentityHashMap<>());
		set.addAll(bookings);
		return set;
	}

	private static List<Booking> bookings(TicketNode node) {
		return bookings(List.of(node));
	}

	/** Alle Buchungen unter den Knoten in Reihenfolge der Tabelle. */
	private static List<Booking> bookings(List<TicketNode> nodes) {
		List<Booking> result = new ArrayList<>();
		collect(nodes, result);
		return result;
	}

	private static void collect(List<TicketNode> nodes, List<Booking> result) {
		for (TicketNode node : nodes) {
			if (node.row() instanceof TicketRow.BookingRow row) {
				result.add(row.dto().getBooking());
			} else {
				collect(node.children(), result);
			}
		}
	}
}
