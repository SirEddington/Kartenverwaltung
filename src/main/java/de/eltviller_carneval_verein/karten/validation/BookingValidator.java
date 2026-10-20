package de.eltviller_carneval_verein.karten.validation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

	private static final String RESERVATION_DATE = "Reservierungsdatum";
	private static final String PAYMENT_DATE = "Bezahldatum";
	private static final String COLLECTION_DATE = "Abholdatum";
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

	private final Booking booking;
	private final LocalDate today;
	private final ValidationResult result = ValidationResult.ok();

	private BookingValidator(Booking booking, LocalDate today) {
		this.booking = booking;
		this.today = today;
	}

	public static ValidationResult validate(Booking booking) {
		return validate(booking, LocalDate.now());
	}

	/** Wie {@link #validate(Booking)}, mit einem festen "heute" für die Prüfung auf Daten in der Zukunft (für Tests). */
	public static ValidationResult validate(Booking booking, LocalDate today) {
		return new BookingValidator(booking, today).run();
	}

	private ValidationResult run() {
		validatePrice();
		validateCollectedWithoutPayment();
		validateEmail();
		validatePaidWithoutPrice();
		validatePaidWithoutName();
		validateDates();
		validateAssigned();
		return result;
	}

	/**
	 * Die Daten der Verkaufsaktionen: ein Datum ohne Aktion und eine Aktion ohne Datum (beides Warnungen; fehlt die
	 * Aktion und das Datum, ist alles in Ordnung), ein Datum in der Zukunft und die Reihenfolge Reservierung,
	 * Bezahlung, Abholung (nur für Paare, bei denen beide Daten da sind).
	 */
	private void validateDates() {
		checkDate(booking.getReservationDate(), booking.isReserved(), SeatIssue.RESERVATION_DATE_WITHOUT_ACTION, SeatIssue.RESERVATION_DATE_MISSING);
		checkDate(booking.getPaymentDate(), booking.isPaid(), SeatIssue.PAYMENT_DATE_WITHOUT_ACTION, SeatIssue.PAYMENT_DATE_MISSING);
		checkDate(booking.getCollectionDate(), booking.isCollected(), SeatIssue.COLLECTION_DATE_WITHOUT_ACTION, SeatIssue.COLLECTION_DATE_MISSING);

		checkFuture(RESERVATION_DATE, booking.getReservationDate());
		checkFuture(PAYMENT_DATE, booking.getPaymentDate());
		checkFuture(COLLECTION_DATE, booking.getCollectionDate());

		checkOrder(PAYMENT_DATE, booking.getPaymentDate(), RESERVATION_DATE, booking.getReservationDate());
		checkOrder(COLLECTION_DATE, booking.getCollectionDate(), PAYMENT_DATE, booking.getPaymentDate());
		checkOrder(COLLECTION_DATE, booking.getCollectionDate(), RESERVATION_DATE, booking.getReservationDate());
	}

	private void checkDate(LocalDate date, boolean actionDone, SeatIssue withoutAction, SeatIssue missing) {
		if (date != null && !actionDone) {
			result.add(withoutAction.toIssue(EntityLabels.booking(booking)));
		} else if (date == null && actionDone) {
			result.add(missing.toIssue(EntityLabels.booking(booking)));
		}
	}

	private void checkFuture(String name, LocalDate date) {
		if (date != null && date.isAfter(today)) {
			result.add(SeatIssue.DATE_IN_FUTURE.toIssue(EntityLabels.booking(booking), name, DATE_FORMAT.format(date)));
		}
	}

	/** Meldet, wenn {@code later} vor {@code earlier} liegt; ein fehlendes Datum eines Paares ist hier kein Befund. */
	private void checkOrder(String laterName, LocalDate later, String earlierName, LocalDate earlier) {
		if (later != null && earlier != null && later.isBefore(earlier)) {
			result.add(SeatIssue.DATE_ORDER.toIssue(EntityLabels.booking(booking), laterName, earlierName));
		}
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
