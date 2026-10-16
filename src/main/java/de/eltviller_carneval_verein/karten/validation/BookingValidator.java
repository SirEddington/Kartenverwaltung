package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;

/**
 * Plausibilitätsprüfungen für eine Buchung (Kürzel SEA, siehe {@link SeatIssue}). Die Verkaufsregeln sind dieselben wie
 * beim {@link SeatValidator}; die Meldungen nennen die Buchung ({@link EntityLabels#booking}). Aufbau wie bei
 * {@link SeatValidator}.
 */
public final class BookingValidator {

	private final Booking booking;
	private final ValidationResult result = ValidationResult.ok();

	private BookingValidator(Booking booking) {
		this.booking = booking;
	}

	public static ValidationResult validate(Booking booking) {
		return new BookingValidator(booking).run();
	}

	private ValidationResult run() {
		validatePrice();
		validateCollectedWithoutPayment();
		validateEmail();
		validatePaidWithoutPrice();
		validatePaidWithoutName();
		validateAssigned();
		return result;
	}

	/** Es gilt der Preis, den die Buchung tatsächlich hat: der Einzelpreis oder der Standardpreis der Vorstellung. */
	private void validatePrice() {
		long cents = booking.getEffectivePrice();
		if (cents < 0) {
			result.add(SeatIssue.PRICE_NEGATIVE.toIssue(EntityLabels.booking(booking)));
		} else if (cents > SeatValidator.MAX_PRICE_CENTS) {
			result.add(SeatIssue.PRICE_TOO_HIGH.toIssue(EntityLabels.booking(booking), MoneyFormat.formatCents(cents),
					MoneyFormat.formatCents(SeatValidator.MAX_PRICE_CENTS)));
		}
	}

	private void validateCollectedWithoutPayment() {
		if (booking.isCollected() && !booking.isPaid()) {
			result.add(SeatIssue.COLLECTED_NOT_PAID.toIssue(EntityLabels.booking(booking)));
		}
	}

	/** Nur wenn eine Adresse eingetragen ist; eine leere Adresse ist erlaubt. */
	private void validateEmail() {
		String email = booking.getEMail();
		if (email != null && !email.isBlank() && !SeatValidator.EMAIL.matcher(email.trim()).matches()) {
			result.add(SeatIssue.EMAIL_INVALID.toIssue(EntityLabels.booking(booking), email.trim()));
		}
	}

	private void validatePaidWithoutPrice() {
		if (booking.isPaid() && booking.getEffectivePrice() == 0) {
			result.add(SeatIssue.PAID_WITHOUT_PRICE.toIssue(EntityLabels.booking(booking), MoneyFormat.formatCents(0)));
		}
	}

	/** Wie beim Sitz nur eine Warnung (an der Abendkasse nicht zwingend); es zählt der Nachname. */
	private void validatePaidWithoutName() {
		String lastName = booking.getLastName();
		if (booking.isPaid() && (lastName == null || lastName.isBlank())) {
			result.add(SeatIssue.PAID_WITHOUT_NAME.toIssue(EntityLabels.booking(booking)));
		}
	}

	private void validateAssigned() {
		if (booking.isUnassigned()) {
			result.add(SeatIssue.NO_PLACE.toIssue(EntityLabels.booking(booking)));
		}
	}
}
