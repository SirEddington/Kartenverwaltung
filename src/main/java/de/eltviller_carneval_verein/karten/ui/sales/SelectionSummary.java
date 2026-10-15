package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

/** Die Zusammenfassung der ausgewählten Karten für das Kundenfenster: Anzahl, eine Zeile je Karte und der Gesamtpreis. */
final class SelectionSummary {

	/** Eine Karte der Auswahl: Beschriftung ("Tisch 4, Sitz 2") und Preis in Cent. */
	record Line(String text, long cents) {
	}

	record Summary(List<Line> lines, long totalCents) {
		int count() {
			return lines.size();
		}
	}

	private SelectionSummary() {
	}

	/**
	 * Fasst die ausgewählten Sitze der gezeigten Vorstellung zusammen, sortiert nach Tisch- und Sitznummer. Sitze anderer
	 * Vorstellungen (Tabelle mit "Alle Vorstellungen") zählen nicht; ohne gezeigte Vorstellung ist die Zusammenfassung leer.
	 */
	static Summary of(Collection<Seat> selected, Presentation shown) {
		if (shown == null) {
			return new Summary(List.of(), 0);
		}
		List<Line> lines = selected.stream().filter(seat -> belongsTo(seat, shown))
				.sorted(Comparator.comparingInt((Seat seat) -> seat.getParent().getTableNumber()).thenComparingInt(Seat::getSeatNumber))
				.map(seat -> new Line("Tisch " + seat.getParent().getTableNumber() + ", Sitz " + seat.getSeatNumber(), seat.getPrice())).toList();
		return new Summary(lines, lines.stream().mapToLong(Line::cents).sum());
	}

	/** Die Überschrift der Liste: "Keine Karten ausgewählt", "1 Karte" oder "3 Karten". */
	static String countText(int count) {
		if (count == 0) {
			return "Keine Karten ausgewählt";
		}
		return count == 1 ? "1 Karte" : count + " Karten";
	}

	private static boolean belongsTo(Seat seat, Presentation presentation) {
		Table table = seat.getParent();
		return table != null && presentation.equals(table.getParent());
	}
}
