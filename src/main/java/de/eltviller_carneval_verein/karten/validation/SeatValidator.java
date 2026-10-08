package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;

/**
 * Plausibilitätsprüfungen für einen Sitz (Kürzel SEA, siehe {@link SeatIssue}).
 *
 * <p>Aufbau aller Validatoren: statischer Einstieg {@link #validate(Seat)}, der für jede Prüfung eine
 * frische Instanz anlegt. Die Instanz hält die Entität und das Ergebnis als Attribute, die einzelnen
 * Regeln sind parameterlose Methoden. Dadurch bleibt kein Zustand zwischen zwei Prüfungen übrig.
 */
public final class SeatValidator {

	/** Höchster erlaubter Kartenpreis in Cent (entspricht der Obergrenze der Preis-Spinner von 1.000 €). */
	public static final long MAX_PRICE_CENTS = 100_000;

	private final Seat seat;
	private final ValidationResult result = ValidationResult.ok();

	private SeatValidator(Seat seat) {
		this.seat = seat;
	}

	/** Prüft einen Sitz (derzeit nur den Preis). */
	public static ValidationResult validate(Seat seat) {
		return new SeatValidator(seat).run();
	}

	private ValidationResult run() {
		validatePrice();
		return result;
	}

	private void validatePrice() {
		long cents = seat.getPrice();
		if (cents < 0) {
			result.add(SeatIssue.PRICE_NEGATIVE.toIssue(EntityLabels.seat(seat)));
		} else if (cents > MAX_PRICE_CENTS) {
			result.add(SeatIssue.PRICE_TOO_HIGH.toIssue(EntityLabels.seat(seat), MoneyFormat.formatCents(cents),
					MoneyFormat.formatCents(MAX_PRICE_CENTS)));
		}
	}
}
