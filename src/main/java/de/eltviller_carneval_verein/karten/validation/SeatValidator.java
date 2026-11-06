package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Seat;

/**
 * Plausibilitätsprüfungen für einen Sitz einer Bestuhlung (Kürzel SEA, siehe {@link SeatIssue}). Geprüft wird nur
 * das Layout (Nummer, Größe); die Verkaufsregeln prüft der {@link BookingValidator}.
 *
 * <p>
 * Aufbau aller Validatoren: statischer Einstieg {@link #validate(Seat)}, der für jede Prüfung eine
 * frische Instanz anlegt. Die Instanz hält die Entität und das Ergebnis als Attribute, die einzelnen
 * Regeln sind parameterlose Methoden. Dadurch bleibt kein Zustand zwischen zwei Prüfungen übrig.
 */
public final class SeatValidator {

	private final Seat seat;
	private final ValidationResult result = ValidationResult.ok();

	private SeatValidator(Seat seat) {
		this.seat = seat;
	}

	/** Prüft einen Sitz. */
	public static ValidationResult validate(Seat seat) {
		return new SeatValidator(seat).run();
	}

	/** Größter erlaubter Durchmesser eines Sitzes (wie bisher die Grenze der Spinner). */
	public static final double MAX_SIZE = 100;

	private ValidationResult run() {
		validateNumber();
		validateSize();
		return result;
	}

	private void validateSize() {
		String owner = EntityLabels.seat(seat);
		// Sitze sind Kreise: Die Breite ist der Durchmesser
		RangeCheck.check(result, SeatIssue.SIZE_OUT_OF_RANGE, "Durchmesser", owner, seat.getWidth(), 0, MAX_SIZE);
	}

	private void validateNumber() {
		if (seat.getSeatNumber() < 1) {
			result.add(SeatIssue.NUMBER_TOO_LOW.toIssue(EntityLabels.seat(seat)));
		}
	}
}
