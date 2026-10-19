package de.eltviller_carneval_verein.karten.ui.hall;

/**
 * Welche Beschriftungen und Ebenen der Hallenplan zeigt (Menü "Anzeige"). Eine Momentaufnahme der
 * {@link PlanLabelOptions}, mit der gezeichnet wird.
 *
 * @param rows        in der Saalansicht die Reihen (Linie mit Buchstabe), im Hallen-Editor der Buchstabe der Reihe
 * @param tables      der Name auf dem Tisch
 * @param seatNumbers die Nummer im Sitz (ab einer Mindestgröße auf dem Bildschirm)
 * @param hallObjects der Name auf dem Hallenobjekt
 */
public record PlanLabels(boolean rows, boolean tables, boolean seatNumbers, boolean hallObjects) {

	/** Alles sichtbar, so beginnt jede Ansicht. */
	public static final PlanLabels ALL = new PlanLabels(true, true, true, true);
}
