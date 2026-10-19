package de.eltviller_carneval_verein.karten.validation;

import java.util.regex.Pattern;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;

/**
 * Plausibilitätsprüfungen für eine Buchung (Kürzel SEA, siehe {@link SeatIssue}): die Verkaufsregeln (Preis, abgeholt,
 * E-Mail, bezahlt ohne Preis oder Namen) und die Zuordnung zu einem Platz. Die Meldungen nennen die Buchung
 * ({@link EntityLabels#booking}). Aufbau wie bei {@link SeatValidator}.
 */
public final class BookingValidator {

	/** Höchster erlaubter Kartenpreis in Cent (entspricht der Obergrenze der Preis-Spinner von 1.000 €). */
	public static final long MAX_PRICE_CENTS = 100_000;

	/** Bewusst einfach: etwas vor und nach dem @, danach ein Punkt, keine Leerzeichen. */
	static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

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

	/**
	 * Nur ein eigener Einzelpreis der Buchung wird geprüft. Der Standardpreis der Vorstellung wird einmal von
	 * {@code PRS-008} gemeldet, sonst käme dieselbe Ursache bei jeder Buchung noch einmal.
	 */
	private void validatePrice() {
		Integer own = booking.getPrice();
		if (own == null) {
			return;
		}
		long cents = own;
		if (cents < 0) {
			result.add(SeatIssue.PRICE_NEGATIVE.toIssue(EntityLabels.booking(booking)));
		} else if (cents > MAX_PRICE_CENTS) {
			result.add(SeatIssue.PRICE_TOO_HIGH.toIssue(EntityLabels.booking(booking), MoneyFormat.formatCents(cents),
					MoneyFormat.formatCents(MAX_PRICE_CENTS)));
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
		if (email != null && !email.isBlank() && !EMAIL.matcher(email.trim()).matches()) {
			result.add(SeatIssue.EMAIL_INVALID.toIssue(EntityLabels.booking(booking), email.trim()));
		}
	}

	private void validatePaidWithoutPrice() {
		if (booking.isPaid() && booking.getEffectivePrice() == 0) {
			result.add(SeatIssue.PAID_WITHOUT_PRICE.toIssue(EntityLabels.booking(booking), MoneyFormat.formatCents(0)));
		}
	}

	/**
	 * Eine bezahlte Buchung sollte einen Nachnamen haben (an der Abendkasse nicht zwingend, deshalb nur eine Warnung). Es
	 * zählt der Nachname, weil auch {@link Booking#isReserved()} danach entscheidet.
	 */
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
