package de.eltviller_carneval_verein.karten.validation;

/** Plausibilitätsprüfungen für den Kartenverkauf (Kürzel SLS, siehe {@link SalesIssue}). */
public final class SalesValidator {

	/** Höchster erlaubter Kartenpreis in Euro (entspricht der Obergrenze der Preis-Spinner). */
	public static final double MAX_PRICE_EUR = 1000.0;

	private SalesValidator() {
	}

	/**
	 * Prüft einen eingegebenen Preis in Euro.
	 *
	 * @param euros der Preis; {@code null} steht für eine nicht lesbare Eingabe
	 */
	public static ValidationResult validatePrice(Double euros) {
		if (euros == null || euros.isNaN() || euros.isInfinite()) {
			return ValidationResult.of(SalesIssue.PRICE_NOT_A_NUMBER.toIssue());
		}
		if (euros < 0) {
			return ValidationResult.of(SalesIssue.PRICE_NEGATIVE.toIssue());
		}
		if (euros > MAX_PRICE_EUR) {
			return ValidationResult.of(SalesIssue.PRICE_TOO_HIGH.toIssue());
		}
		return ValidationResult.ok();
	}
}
