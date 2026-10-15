package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import de.eltviller_carneval_verein.karten.util.NumberText;

/**
 * Gemeinsame Hilfe für Wertebereiche (Minimum und Maximum von Größen, Positionen und Beträgen). Die Spinner prüfen
 * keinen Bereich mehr; was zu klein oder zu groß ist, meldet der Validator mit dem Text {@link #TEMPLATE}. Ein Wert,
 * der keine Zahl ist ({@code NaN}), liegt außerhalb jedes Bereichs.
 */
final class RangeCheck {

	/** &1 = was ("Breite"), &2 = von wem ("Halle Festhalle"), &3 = der Wert, &4 = Minimum, &5 = Maximum. */
	static final String TEMPLATE = "&1 von &2 liegt mit &3 nicht zwischen &4 und &5.";

	private RangeCheck() {
	}

	static boolean outside(double value, double min, double max) {
		return !(value >= min && value <= max);
	}

	/** Fügt die Meldung hinzu, wenn der Wert außerhalb von {@code min} bis {@code max} liegt. */
	static void check(ValidationResult result, Issue issue, String what, String owner, double value, double min, double max) {
		if (outside(value, min, max)) {
			result.add(issue.toIssue(what, owner, NumberText.format(value), NumberText.format(min), NumberText.format(max)));
		}
	}

	/** Wie {@link #check}, für Beträge in Cent (Anzeige in Euro). */
	static void checkCents(ValidationResult result, Issue issue, String what, String owner, long cents, long minCents, long maxCents) {
		if (cents < minCents || cents > maxCents) {
			result.add(issue.toIssue(what, owner, MoneyFormat.formatCents(cents), MoneyFormat.formatCents(minCents), MoneyFormat.formatCents(maxCents)));
		}
	}
}
