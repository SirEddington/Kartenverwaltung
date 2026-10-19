package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.ui.sales.OccupiedSeatsDialog.Choice;
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
	 * @param selected   die gewählten Buchungen
	 * @param owner      Fenster, über dem der Dialog erscheint
	 * @param onApplied  wird aufgerufen, nachdem der Dialog mit "Anwenden" geschlossen wurde
	 * @param onDeselect nimmt die belegten Buchungen aus der Auswahl der Ansicht, wenn nur die freien verkauft werden
	 * @return der geöffnete Dialog, {@code null} wenn nichts zu bearbeiten ist oder abgebrochen wurde
	 */
	static SeatDetailPopup edit(List<Booking> selected, Window owner, double screenX, double screenY, Runnable onApplied,
			Consumer<List<Booking>> onDeselect) {
		if (selected.isEmpty()) {
			return null; // Die Menüs gibt es nur für eine Auswahl; ohne Buchungen gäbe es nichts zu zeigen
		}

		List<Booking> bookings = chooseBookings(selected, OccupiedSeatsDialog::ask, onDeselect);
		if (bookings == null) {
			return null;
		}

		SeatDetailPopup popup = new SeatDetailPopup(bookings, true, onApplied);
		popup.show(owner, screenX, screenY);
		return popup;
	}

	/**
	 * Entscheidet, welche Buchungen der Dialog bearbeitet. Sind belegte Plätze dabei, wird nachgefragt. Bei "Nur freie
	 * Sitze" gehören die belegten nicht mehr zur Auswahl: Sonst blieben sie in der Ansicht und im Kundenfenster
	 * mitgezählt, obwohl sie nicht verkauft werden.
	 *
	 * @param ask        beantwortet die Rückfrage (paketsichtbar für Tests ohne Oberfläche)
	 * @param onDeselect bekommt die belegten Buchungen, die bei "Nur freie Sitze" aus der Auswahl fallen
	 * @return die zu bearbeitenden Buchungen, {@code null} bei "Abbrechen"
	 */
	static List<Booking> chooseBookings(List<Booking> selected, Function<SeatSelectionSplit, Choice> ask, Consumer<List<Booking>> onDeselect) {
		SeatSelectionSplit split = SeatSelectionSplit.of(selected);
		if (!split.hasOccupied()) {
			return selected;
		}
		return switch (ask.apply(split)) {
		case FREE_ONLY -> {
			onDeselect.accept(split.occupied());
			yield split.free();
		}
		case CANCEL -> null;
		case OVERWRITE -> selected; // Alle gewählten Buchungen bleiben in der Liste
		};
	}
}
