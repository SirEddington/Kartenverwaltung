package de.eltviller_carneval_verein.karten.validation;

/** Plausibilitätsprüfungen für einen Sitz (Kürzel SEA, siehe {@link SeatIssue}). */
public final class SeatValidator {

	/** Höchster erlaubter Kartenpreis in Euro (entspricht der Obergrenze der Preis-Spinner). */
	public static final double MAX_PRICE_EUR = 1000.0;

	private SeatValidator() {
	}

	/**
	 * Prüft einen eingegebenen Preis in Euro.
	 *
	 * @param euros der Preis; {@code null} steht für eine nicht lesbare Eingabe
	 */
	public static ValidationResult validatePrice(Double euros) {
		if (euros == null || euros.isNaN() || euros.isInfinite()) {
			return ValidationResult.of(SeatIssue.PRICE_NOT_A_NUMBER.toIssue());
		}
		if (euros < 0) {
			return ValidationResult.of(SeatIssue.PRICE_NEGATIVE.toIssue());
		}
		if (euros > MAX_PRICE_EUR) {
			return ValidationResult.of(SeatIssue.PRICE_TOO_HIGH.toIssue());
		}
		return ValidationResult.ok();
	}
}
