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
