package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Booking;
import javafx.stage.Window;

/**
 * Der Sammelverkauf für die ausgewählten Buchungen, gemeinsam für Kartentabelle und Saalansicht: erst nachfragen, wenn
 * belegte Plätze dabei sind, dann der {@link SeatDetailPopup} für die übrig gebliebenen.
 */
final class SeatSelectionEditor {

	private SeatSelectionEditor() {
	}

	/** Beschriftung des Menüeintrags, der den Dialog öffnet (für einen Sitz ohne Anzahl). */
	static String menuText(int seatCount) {
		return seatCount == 1 ? "Sitz verkaufen…" : "Ausgewählte Sitze verkaufen… (" + seatCount + ")";
	}

	/**
	 * @param selected  die gewählten Buchungen
	 * @param owner     Fenster, über dem der Dialog erscheint
	 * @param onApplied wird aufgerufen, nachdem der Dialog mit "Anwenden" geschlossen wurde
	 * @return der geöffnete Dialog, {@code null} wenn nichts zu bearbeiten ist oder abgebrochen wurde
	 */
	static SeatDetailPopup edit(List<Booking> selected, Window owner, double screenX, double screenY, Runnable onApplied) {
		if (selected.isEmpty()) {
			return null; // Die Menüs gibt es nur für eine Auswahl; ohne Buchungen gäbe es nichts zu zeigen
		}

		List<Booking> bookings = selected;
		SeatSelectionSplit split = SeatSelectionSplit.of(selected);
		if (split.hasOccupied()) {
			switch (OccupiedSeatsDialog.ask(split)) {
			case FREE_ONLY -> bookings = split.free();
			case CANCEL -> {
				return null;
			}
			case OVERWRITE -> {
				// Alle gewählten Buchungen bleiben in der Liste
			}
			}
		}

		SeatDetailPopup popup = new SeatDetailPopup(bookings, true, onApplied);
		popup.show(owner, screenX, screenY);
		return popup;
	}
}
