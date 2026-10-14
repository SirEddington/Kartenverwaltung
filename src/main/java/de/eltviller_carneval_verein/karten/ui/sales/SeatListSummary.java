package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;

/**
 * Beschreibt eine Auswahl von Sitzen in Worten: kurz für die Kopfzeile des Sitz-Dialogs ("Prunksitzung; Tisch 3;
 * Sitz 3-12; Tisch 4; Sitz 1, 3") und ausführlich mit einer Zeile je Sitz für den Tooltip. Gruppiert wird nach
 * Vorstellung (in der Reihenfolge des ersten Auftretens), Tisch und Sitznummer (jeweils aufsteigend).
 */
public final class SeatListSummary {

	/** Ab so vielen aufeinanderfolgenden Sitznummern wird "3-12" statt "3, 4, 5" geschrieben. */
	private static final int MIN_RANGE_LENGTH = 3;

	private static final int MAX_TOOLTIP_LINES = 40;

	private SeatListSummary() {
	}

	/** Kurzfassung mit zusammengefassten Sitznummern; leer bei einer leeren Liste. */
	public static String headline(List<Seat> seats) {
		List<String> parts = new ArrayList<>();
		group(seats).forEach((presentation, tables) -> {
			parts.add(nameOf(presentation));
			tables.forEach((tableNumber, seatNumbers) -> {
				parts.add("Tisch " + tableNumber);
				parts.add("Sitz " + formatSeatNumbers(new ArrayList<>(seatNumbers)));
			});
		});
		return String.join("; ", parts);
	}

	/**
	 * Jeder Sitz in einer eigenen Zeile ("Prunksitzung, Tisch 3, Sitz 3"); leer bei einer leeren Liste. Bei sehr vielen
	 * Sitzen (z. B. der ganzen Halle) endet die Liste nach {@value #MAX_TOOLTIP_LINES} Zeilen mit der Anzahl der übrigen:
	 * Ein Tooltip mit Hunderten Zeilen wäre höher als der Bildschirm.
	 */
	public static String tooltip(List<Seat> seats) {
		List<String> lines = new ArrayList<>();
		group(seats).forEach((presentation, tables) -> tables.forEach(
				(tableNumber, seatNumbers) -> seatNumbers.forEach(seatNumber -> lines.add(nameOf(presentation) + ", Tisch " + tableNumber + ", Sitz " + seatNumber))));
		if (lines.size() > MAX_TOOLTIP_LINES) {
			int remaining = lines.size() - MAX_TOOLTIP_LINES;
			lines.subList(MAX_TOOLTIP_LINES, lines.size()).clear();
			lines.add("… und " + remaining + " weitere Sitze");
		}
		return String.join("\n", lines);
	}

	private static Map<Presentation, TreeMap<Integer, TreeSet<Integer>>> group(List<Seat> seats) {
		Map<Presentation, TreeMap<Integer, TreeSet<Integer>>> grouped = new LinkedHashMap<>();
		for (Seat seat : seats) {
			Presentation presentation = seat.getParent().getParent();
			grouped.computeIfAbsent(presentation, key -> new TreeMap<>()).computeIfAbsent(seat.getParent().getTableNumber(), key -> new TreeSet<>())
					.add(seat.getSeatNumber());
		}
		return grouped;
	}

	private static String nameOf(Presentation presentation) {
		return presentation.getName() == null ? "" : presentation.getName();
	}

	/** Aufsteigende, doppelfreie Nummern als "1-3, 5". */
	private static String formatSeatNumbers(List<Integer> numbers) {
		List<String> parts = new ArrayList<>();
		int start = 0;
		while (start < numbers.size()) {
			int end = start;
			while (end + 1 < numbers.size() && numbers.get(end + 1) == numbers.get(end) + 1) {
				end++;
			}
			if (end - start + 1 >= MIN_RANGE_LENGTH) {
				parts.add(numbers.get(start) + "-" + numbers.get(end));
			} else {
				for (int index = start; index <= end; index++) {
					parts.add(String.valueOf(numbers.get(index)));
				}
			}
			start = end + 1;
		}
		return String.join(", ", parts);
	}
}
