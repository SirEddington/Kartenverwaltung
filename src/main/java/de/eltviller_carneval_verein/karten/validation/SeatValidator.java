package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;

/** Plausibilitätsprüfungen für einen Sitz (Kürzel SEA, siehe {@link SeatIssue}). */
public final class SeatValidator {

	/** Höchster erlaubter Kartenpreis in Cent (entspricht der Obergrenze der Preis-Spinner von 1.000 €). */
	public static final long MAX_PRICE_CENTS = 100_000;

	private SeatValidator() {
	}

	/** Prüft einen Sitz (derzeit nur den Preis). */
	public static ValidationResult validate(Seat seat) {
		return validatePriceCents(seat, seat.getPrice());
	}

	/**
	 * Prüft einen frisch eingegebenen Preis in Euro, bevor er in den Sitz übernommen wird.
	 *
	 * @param euros der Preis; {@code null} steht für nicht lesbaren Text (SEA-002)
	 */
	public static ValidationResult validatePriceInput(Seat seat, Double euros) {
		if (euros == null || euros.isNaN() || euros.isInfinite()) {
			return ValidationResult.of(SeatIssue.PRICE_NOT_A_NUMBER.toIssue(EntityLabels.seat(seat)));
		}
		return validatePriceCents(seat, Math.round(euros * 100));
	}

	private static ValidationResult validatePriceCents(Seat seat, long cents) {
		if (cents < 0) {
			return ValidationResult.of(SeatIssue.PRICE_NEGATIVE.toIssue(EntityLabels.seat(seat)));
		}
		if (cents > MAX_PRICE_CENTS) {
			return ValidationResult.of(SeatIssue.PRICE_TOO_HIGH.toIssue(EntityLabels.seat(seat), MoneyFormat.formatCents(cents),
					MoneyFormat.formatCents(MAX_PRICE_CENTS)));
		}
		return ValidationResult.ok();
	}
}
