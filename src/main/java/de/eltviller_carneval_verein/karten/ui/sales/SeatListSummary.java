package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Presentation;

/**
 * Beschreibt eine Auswahl von Buchungen in Worten: kurz für die Kopfzeile des Sitz-Dialogs ("Prunksitzung; Tisch A3;
 * Sitz 3-12; Tisch A4; Sitz 1, 3") und ausführlich mit einer Zeile je Platz für den Tooltip. Gruppiert wird nach
 * Vorstellung (in der Reihenfolge des ersten Auftretens), Tisch (Reihenbuchstabe und Nummer, so dass "Tisch A3" und
 * "Tisch 3" getrennt bleiben) und Sitznummer, sortiert nach {@link Booking#PLACE_ORDER}.
 */
public final class SeatListSummary {

	/** Ab so vielen aufeinanderfolgenden Sitznummern wird "3-12" statt "3, 4, 5" geschrieben. */
	private static final int MIN_RANGE_LENGTH = 3;

	private static final int MAX_TOOLTIP_LINES = 40;

	private SeatListSummary() {
	}

	/** Kurzfassung mit zusammengefassten Sitznummern; leer bei einer leeren Liste. */
	public static String headline(List<Booking> bookings) {
		List<String> parts = new ArrayList<>();
		group(bookings).forEach((presentation, tables) -> {
			parts.add(nameOf(presentation));
			tables.forEach((tableLabel, seatNumbers) -> {
				parts.add(tableLabel);
				parts.add("Sitz " + formatSeatNumbers(new ArrayList<>(seatNumbers)));
			});
		});
		return String.join("; ", parts);
	}

	/**
	 * Jeder Platz in einer eigenen Zeile ("Prunksitzung, Tisch A3, Sitz 3"); leer bei einer leeren Liste. Bei sehr vielen
	 * Plätzen (z. B. der ganzen Halle) endet die Liste nach {@value #MAX_TOOLTIP_LINES} Zeilen mit der Anzahl der übrigen:
	 * Ein Tooltip mit Hunderten Zeilen wäre höher als der Bildschirm.
	 */
	public static String tooltip(List<Booking> bookings) {
		List<String> lines = new ArrayList<>();
		group(bookings).forEach((presentation, tables) -> tables.forEach(
				(tableLabel, seatNumbers) -> seatNumbers.forEach(seatNumber -> lines.add(nameOf(presentation) + ", " + tableLabel + ", Sitz " + seatNumber))));
		if (lines.size() > MAX_TOOLTIP_LINES) {
			int remaining = lines.size() - MAX_TOOLTIP_LINES;
			lines.subList(MAX_TOOLTIP_LINES, lines.size()).clear();
			lines.add("… und " + remaining + " weitere Sitze");
		}
		return String.join("\n", lines);
	}

	/** Vorstellung → Tischbezeichnung ("Tisch A3") → Sitznummern, Tische in der Reihenfolge der Plätze. */
	private static Map<Presentation, Map<String, TreeSet<Integer>>> group(List<Booking> bookings) {
		// Erst die Vorstellungen in der Reihenfolge des ersten Auftretens, dann innerhalb jeder nach Platz sortiert
		Map<Presentation, List<Booking>> byPresentation = new LinkedHashMap<>();
		for (Booking booking : bookings) {
			byPresentation.computeIfAbsent(booking.getParent(), key -> new ArrayList<>()).add(booking);
		}
		Map<Presentation, Map<String, TreeSet<Integer>>> grouped = new LinkedHashMap<>();
		byPresentation.forEach((presentation, list) -> {
			Map<String, TreeSet<Integer>> tables = new LinkedHashMap<>();
			list.stream().sorted(Booking.PLACE_ORDER)
					.forEach(booking -> tables.computeIfAbsent(booking.getTableLabel(), key -> new TreeSet<>()).add(booking.getSeatNumber()));
			grouped.put(presentation, tables);
		});
		return grouped;
	}

	private static String nameOf(Presentation presentation) {
		return presentation == null || presentation.getName() == null ? "" : presentation.getName();
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
