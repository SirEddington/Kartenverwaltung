package de.eltviller_carneval_verein.karten.ui.sales;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.ui.input.DateTimeInput;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/** Texte und Regeln des Kundenfensters, die ohne JavaFX-Oberfläche prüfbar sind. */
final class CustomerDisplayText {

	/** Steht anstelle des Plans, solange keine Vorstellung gewählt ist oder es keinen Plan zu zeigen gibt. */
	static final String WAITING_HINT = "Bitte haben Sie einen Moment Geduld.";

	private CustomerDisplayText() {
	}

	/**
	 * Ob das Kundenfenster anstelle des Plans den Wartetext {@value #WAITING_HINT} zeigt: ohne Vorstellung und immer
	 * dann, wenn die Verkaufsansicht statt der Bestuhlung einen internen Hinweis zeigt ({@link SalesPlan#hint}, etwa
	 * "Bestuhlung nicht mehr vorhanden"). Diesen Hinweis sieht das Publikum nie.
	 *
	 * @param plan die Bestuhlung ({@link SalesPlan#of}), {@code null} ohne Bestuhlung
	 */
	static boolean showsWaitingHint(Presentation presentation, SeatingPlan plan) {
		return presentation == null || SalesPlan.hint(presentation, plan) != null;
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

	/** Im Hochformat steht die Kartenübersicht unter dem Plan, sonst rechts daneben (dort wäre im Hochformat zu wenig Breite). */
	static boolean summaryBelow(double windowWidth, double windowHeight) {
		return windowHeight > windowWidth;
	}

	/** Rot für Kunden: Jeder Platz, der nicht frei ist (reserviert, verkauft, blockiert). */
	static boolean isTaken(Booking booking) {
		return booking.getStatus() != SeatStatus.FREE;
	}
}
