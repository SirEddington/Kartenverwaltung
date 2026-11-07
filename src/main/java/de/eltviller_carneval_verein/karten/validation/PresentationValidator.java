package de.eltviller_carneval_verein.karten.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Presentation;

/**
 * Plausibilitätsprüfungen für eine Vorstellung (Kürzel PRS, siehe {@link PresentationIssue}) und
 * Weitergabe der Prüfung an die Buchungen. Tische und Sitze gehören zur Bestuhlung der Halle und werden dort
 * geprüft ({@link SeatingPlanValidator}). Aufbau wie bei {@link SeatValidator}.
 */
public final class PresentationValidator {

	private static final Logger LOG = Logger.getLogger(PresentationValidator.class.getName());

	private static final Function<String, String> NO_HALL_LOCKS = hallId -> null;

	/** Siehe {@link #useHallLocks}. */
	private static volatile Function<String, String> hallLocks = NO_HALL_LOCKS;

	private final Presentation presentation;
	private final ValidationResult result = ValidationResult.ok();

	private PresentationValidator(Presentation presentation) {
		this.presentation = presentation;
	}

	public static ValidationResult validate(Presentation presentation) {
		return new PresentationValidator(presentation).run();
	}

	/**
	 * Setzt die Abfrage, ob die Halle einer Vorstellung gesperrt ist (KARV-135; in der App
	 * {@code StoreHallRepository.lockTextOfHall}): Hallen-ID → Sperrtext in der Form „Code: Text“ (SYS-009 aus
	 * {@code store.StoreMessages}), oder {@code null}, wenn sie nicht gesperrt ist. Eine gesperrte Halle fehlt im Modell
	 * und friert ihre Vorstellungen ein ({@link Presentation#isPlanMissing()}); die Prüfung meldet dann die Sperre statt
	 * {@link PresentationIssue#PLAN_GONE}. Ohne Einrichtung, nach {@code null} oder wenn die Abfrage scheitert, ist keine
	 * Halle gesperrt.
	 */
	public static void useHallLocks(Function<String, String> lockTextOfHall) {
		hallLocks = lockTextOfHall != null ? lockTextOfHall : NO_HALL_LOCKS;
	}

	/** Größter erlaubter Ist-Betrag im Kassensturz in Cent: 1.000.000 € (wie bisher die Grenze des Spinners). */
	static final long MAX_CASH_CENTS = 100_000_000L;

	private ValidationResult run() {
		validateName();
		validateActualCash();
		validateDateAndTime();
		validateHall();
		validateSeatingPlan();
		validateDefaultPrice();
		validateBookings();
		validateSeatsAreBookedOnce();
		return result;
	}

	/** Nur mit Halle: ohne Halle gibt es nichts auszuwählen, dort meldet {@code PRS-003} das Fehlende. */
	private void validateSeatingPlan() {
		String hallId = presentation.getHallId();
		String planId = presentation.getSeatingPlanId();
		boolean hasHall = hallId != null && !hallId.isBlank();
		if (hasHall && (planId == null || planId.isBlank())) {
			result.add(PresentationIssue.PLAN_MISSING.toIssue(EntityLabels.presentation(presentation)));
		}
		if (presentation.isPlanMissing()) {
			String lock = hasHall ? hallLock(hallId) : null;
			result.add(lock != null ? lockIssue(lock) : PresentationIssue.PLAN_GONE.toIssue(EntityLabels.presentation(presentation)));
		}
	}

	/** Der Sperrtext der Halle ({@link #useHallLocks}), oder {@code null}. */
	private static String hallLock(String hallId) {
		try {
			return hallLocks.apply(hallId);
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Keine Auskunft über die Sperre der Halle " + hallId, e);
			return null;
		}
	}

	/** Die Sperre der Halle statt PRS-007, mit dessen Schwere; Code und Text aus „Code: Text“. */
	private static ValidationIssue lockIssue(String lock) {
		int separator = lock.indexOf(": ");
		String code = separator < 0 ? "" : lock.substring(0, separator);
		String message = separator < 0 ? lock : lock.substring(separator + 2);
		return new ValidationIssue(code, PresentationIssue.PLAN_GONE.getSeverity(), message);
	}

	private void validateDefaultPrice() {
		RangeCheck.checkCents(result, PresentationIssue.PRICE_OUT_OF_RANGE, "Standardpreis", EntityLabels.presentation(presentation),
				presentation.getPrice(), 0, BookingValidator.MAX_PRICE_CENTS);
	}

	private void validateBookings() {
		if (presentation.getBookings() == null) {
			return;
		}
		for (Booking booking : presentation.getBookings()) {
			result.addAll(BookingValidator.validate(booking));
		}
	}

	/** Vergleich unter Geschwistern: eine Meldung je Sitz, den mehr als eine Buchung trägt (leere Sitzkennungen zählen nicht). */
	private void validateSeatsAreBookedOnce() {
		if (presentation.getBookings() == null) {
			return;
		}
		Map<String, Booking> firstBookings = new LinkedHashMap<>();
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Booking booking : presentation.getBookings()) {
			String seatId = booking.getSeatId();
			if (seatId == null || seatId.isBlank()) {
				continue;
			}
			firstBookings.putIfAbsent(seatId, booking);
			counts.merge(seatId, 1, Integer::sum);
		}
		counts.forEach((seatId, count) -> {
			if (count > 1) {
				result.add(SeatIssue.SEAT_DOUBLE.toIssue(EntityLabels.booking(firstBookings.get(seatId))));
			}
		});
	}

	private void validateActualCash() {
		RangeCheck.checkCents(result, PresentationIssue.CASH_OUT_OF_RANGE, "Ist-Betrag", EntityLabels.presentation(presentation),
				presentation.getActualCashAmount(), 0, MAX_CASH_CENTS);
	}

	private void validateName() {
		String name = presentation.getName();
		if (name == null || name.isBlank()) {
			result.add(PresentationIssue.NAME_EMPTY.toIssue());
		}
	}

	private void validateDateAndTime() {
		if (presentation.getDate() == null || presentation.getTime() == null) {
			result.add(PresentationIssue.DATE_OR_TIME_MISSING.toIssue(EntityLabels.presentation(presentation)));
		}
	}

	/** Prüft nur, ob eine Halle zugeordnet ist, nicht ob sie existiert (dafür wäre das Hallen-Repository nötig). */
	private void validateHall() {
		String hallId = presentation.getHallId();
		if (hallId == null || hallId.isBlank()) {
			result.add(PresentationIssue.HALL_MISSING.toIssue(EntityLabels.presentation(presentation)));
		}
	}
}
