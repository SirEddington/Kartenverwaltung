package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Collection;
import java.util.List;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/** Die Zusammenfassung der ausgewählten Karten für das Kundenfenster: Anzahl, eine Zeile je Karte und der Gesamtpreis. */
final class SelectionSummary {

	/** Eine Karte der Auswahl: Beschriftung ("Tisch A4, Sitz 2", ohne Platz "Ohne Platz (bisher …)") und effektiver Preis in Cent. */
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
	 * Fasst die ausgewählten Buchungen der gezeigten Vorstellung zusammen, sortiert wie die Kartentabelle
	 * ({@link TableLabel#BOOKING_ORDER}: Buchungen ohne Platz zuerst, dann nach Platz). Buchungen anderer Vorstellungen
	 * (Tabelle mit "Alle Vorstellungen") zählen nicht; ohne gezeigte Vorstellung ist die Zusammenfassung leer.
	 */
	static Summary of(Collection<Booking> selected, Presentation shown) {
		if (shown == null) {
			return new Summary(List.of(), 0);
		}
		List<Line> lines = selected.stream().filter(booking -> shown.equals(booking.getParent())).sorted(TableLabel.BOOKING_ORDER)
				.map(booking -> new Line(EntityLabels.place(booking), booking.getEffectivePrice())).toList();
		return new Summary(lines, lines.stream().mapToLong(Line::cents).sum());
	}

	/** Die Überschrift der Liste: "Keine Karten ausgewählt", "1 Karte" oder "3 Karten". */
	static String countText(int count) {
		if (count == 0) {
			return "Keine Karten ausgewählt";
		}
		return count == 1 ? "1 Karte" : count + " Karten";
	}
}
