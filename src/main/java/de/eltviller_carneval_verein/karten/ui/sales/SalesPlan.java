package de.eltviller_carneval_verein.karten.ui.sales;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;

/**
 * Welche Bestuhlung die Verkaufsansicht für eine Vorstellung zeichnet, und welcher Hinweis ohne Bestuhlung erscheint.
 * Ohne JavaFX prüfbar.
 */
final class SalesPlan {

	/** Hinweis, wenn die Bestuhlung der Vorstellung gelöscht ist (eingefrorene Vorstellung oder gelöschte Halle). */
	static final String MISSING_HINT = "Bestuhlung nicht mehr vorhanden";
	/** Hinweis, wenn die Vorstellung keine Bestuhlung hat. */
	static final String NONE_HINT = "Keine Bestuhlung ausgewählt";

	private SalesPlan() {
	}

	/**
	 * Die Bestuhlung der Vorstellung in ihrer Halle; {@code null} ohne Vorstellung, ohne Halle, ohne Bestuhlung und bei
	 * einer eingefrorenen Vorstellung ({@link Presentation#isPlanMissing()}): Dann zeichnet der Plan nur die Halle.
	 */
	static SeatingPlan of(Presentation presentation, Hall hall) {
		if (presentation == null || hall == null || presentation.isPlanMissing()) {
			return null;
		}
		return hall.findSeatingPlan(presentation.getSeatingPlanId());
	}

	/**
	 * Der Hinweis im Plan, wenn keine Bestuhlung gezeichnet wird: {@value #MISSING_HINT}, wenn die Vorstellung eine
	 * Bestuhlung hatte, sonst {@value #NONE_HINT}. {@code null} mit Bestuhlung oder ohne Vorstellung.
	 */
	static String hint(Presentation presentation, SeatingPlan plan) {
		if (presentation == null || plan != null) {
			return null;
		}
		return presentation.isPlanMissing() || presentation.getSeatingPlanId() != null ? MISSING_HINT : NONE_HINT;
	}
}
