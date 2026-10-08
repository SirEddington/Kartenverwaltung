package de.eltviller_carneval_verein.karten.validation;

import java.util.regex.Pattern;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;

/**
 * Plausibilitätsprüfungen für einen Sitz (Kürzel SEA, siehe {@link SeatIssue}).
 *
 * <p>
 * Aufbau aller Validatoren: statischer Einstieg {@link #validate(Seat)}, der für jede Prüfung eine
 * frische Instanz anlegt. Die Instanz hält die Entität und das Ergebnis als Attribute, die einzelnen
 * Regeln sind parameterlose Methoden. Dadurch bleibt kein Zustand zwischen zwei Prüfungen übrig.
 */
public final class SeatValidator {

	/** Höchster erlaubter Kartenpreis in Cent (entspricht der Obergrenze der Preis-Spinner von 1.000 €). */
	public static final long MAX_PRICE_CENTS = 100_000;

	/** Bewusst einfach: etwas vor und nach dem @, danach ein Punkt, keine Leerzeichen. */
	private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

	private final Seat seat;
	private final ValidationResult result = ValidationResult.ok();

	private SeatValidator(Seat seat) {
		this.seat = seat;
	}

	/** Prüft einen Sitz. */
	public static ValidationResult validate(Seat seat) {
		return new SeatValidator(seat).run();
	}

	private ValidationResult run() {
		validatePrice();
		validateCollectedWithoutPayment();
		validateEmail();
		validatePaidWithoutPrice();
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

	private void validateCollectedWithoutPayment() {
		if (seat.isCollected() && !seat.isPaid()) {
			result.add(SeatIssue.COLLECTED_NOT_PAID.toIssue(EntityLabels.seat(seat)));
		}
	}

	/** Nur wenn eine Adresse eingetragen ist; eine leere Adresse ist erlaubt. */
	private void validateEmail() {
		String email = seat.getEMail();
		if (email != null && !email.isBlank() && !EMAIL.matcher(email.trim()).matches()) {
			result.add(SeatIssue.EMAIL_INVALID.toIssue(EntityLabels.seat(seat), email.trim()));
		}
	}

	private void validatePaidWithoutPrice() {
		if (seat.isPaid() && seat.getPrice() == 0) {
			result.add(SeatIssue.PAID_WITHOUT_PRICE.toIssue(EntityLabels.seat(seat), MoneyFormat.formatCents(0)));
		}
	}
}
