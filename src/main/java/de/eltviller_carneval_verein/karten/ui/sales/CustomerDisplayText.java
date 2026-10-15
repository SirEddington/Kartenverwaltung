package de.eltviller_carneval_verein.karten.ui.sales;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.ui.input.DateTimeInput;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/** Texte und Regeln des Kundenfensters, die ohne JavaFX-Oberfläche prüfbar sind. */
final class CustomerDisplayText {

	/** Steht anstelle des Plans, solange keine Vorstellung gewählt ist. */
	static final String WAITING_HINT = "Bitte haben Sie einen Moment Geduld.";

	private CustomerDisplayText() {
	}

	/**
	 * Die Titelzeile: "Event – Vorstellung, Datum, Uhrzeit". Fehlt die Vorstellung, steht nur das Event; fehlen Datum oder
	 * Uhrzeit der Vorstellung, entfallen sie. Ohne Event ist die Zeile leer.
	 */
	static String title(Event event, Presentation presentation) {
		if (event == null) {
			return "";
		}
		String eventName = EntityLabels.nameOrPlaceholder(event.getName());
		if (presentation == null) {
			return eventName;
		}
		StringBuilder title = new StringBuilder(eventName).append(" – ").append(EntityLabels.nameOrPlaceholder(presentation.getName()));
		if (presentation.getDate() != null) {
			title.append(", ").append(DateTimeInput.formatDate(presentation.getDate()));
		}
		if (presentation.getTime() != null) {
			title.append(", ").append(DateTimeInput.formatTime(presentation.getTime())).append(" Uhr");
		}
		return title.toString();
	}

	/** Rot für Kunden: Jeder Sitz, der nicht frei ist (reserviert, verkauft, blockiert). */
	static boolean isTaken(Seat seat) {
		return seat.getStatus() != SeatStatus.FREE;
	}
}
