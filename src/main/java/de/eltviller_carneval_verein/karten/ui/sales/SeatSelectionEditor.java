package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.stage.Window;

/**
 * Der Sammelverkauf für die ausgewählten Sitze, gemeinsam für Kartentabelle und Saalansicht: erst nachfragen, wenn
 * belegte Sitze dabei sind, dann der {@link SeatDetailPopup} für die übrig gebliebenen Sitze.
 */
final class SeatSelectionEditor {

	private SeatSelectionEditor() {
	}

	/** Beschriftung des Menüeintrags, der den Dialog öffnet (für einen Sitz ohne Anzahl). */
	static String menuText(int seatCount) {
		return seatCount == 1 ? "Sitz verkaufen…" : "Ausgewählte Sitze verkaufen… (" + seatCount + ")";
	}

	/**
	 * @param selected  die gewählten Sitze
	 * @param owner     Fenster, über dem der Dialog erscheint
	 * @param onApplied wird aufgerufen, nachdem der Dialog mit "Anwenden" geschlossen wurde
	 * @return der geöffnete Dialog, {@code null} wenn nichts zu bearbeiten ist oder abgebrochen wurde
	 */
	static SeatDetailPopup edit(List<Seat> selected, Window owner, double screenX, double screenY, Runnable onApplied) {
		if (selected.isEmpty()) {
			StatusMessage.getInstance().show(Severity.INFO, "Es sind keine Sitze ausgewählt.");
			return null;
		}

		List<Seat> seats = selected;
		SeatSelectionSplit split = SeatSelectionSplit.of(selected);
		if (split.hasOccupied()) {
			switch (OccupiedSeatsDialog.ask(split)) {
			case OVERWRITE -> seats = selected;
			case FREE_ONLY -> seats = split.free();
			case CANCEL -> {
				return null;
			}
			}
		}

		SeatDetailPopup popup = new SeatDetailPopup(seats, true, onApplied);
		popup.show(owner, screenX, screenY);
		return popup;
	}
}
