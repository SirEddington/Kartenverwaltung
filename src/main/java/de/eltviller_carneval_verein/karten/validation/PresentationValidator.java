package de.eltviller_carneval_verein.karten.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Plausibilitätsprüfungen für eine Vorstellung (Kürzel PRS, siehe {@link PresentationIssue}) und
 * Weitergabe der Prüfung an die Tische und Buchungen. Aufbau wie bei {@link SeatValidator}.
 */
public final class PresentationValidator {

	private final Presentation presentation;
	private final ValidationResult result = ValidationResult.ok();

	private PresentationValidator(Presentation presentation) {
		this.presentation = presentation;
	}

	public static ValidationResult validate(Presentation presentation) {
		return new PresentationValidator(presentation).run();
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
		validateTableNumbersAreUnique();
		validateTables();
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
			result.add(PresentationIssue.PLAN_GONE.toIssue(EntityLabels.presentation(presentation)));
		}
	}

	private void validateDefaultPrice() {
		RangeCheck.checkCents(result, PresentationIssue.PRICE_OUT_OF_RANGE, "Standardpreis", EntityLabels.presentation(presentation),
				presentation.getPrice(), 0, SeatValidator.MAX_PRICE_CENTS);
	}

	private void validateBookings() {
		for (Booking booking : presentation.getBookings()) {
			result.addAll(BookingValidator.validate(booking));
		}
	}

	/** Vergleich unter Geschwistern: eine Meldung je Sitz, den mehr als eine Buchung trägt (leere Sitzkennungen zählen nicht). */
	private void validateSeatsAreBookedOnce() {
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

	/** Vergleich unter Geschwistern: eine Meldung je mehrfach vergebener Tischnummer. */
	private void validateTableNumbersAreUnique() {
		Map<Integer, Integer> counts = new TreeMap<>();
		for (Table table : presentation.getTables()) {
			counts.merge(table.getTableNumber(), 1, Integer::sum);
		}
		counts.forEach((number, count) -> {
			if (count > 1) {
				result.add(PresentationIssue.TABLE_NUMBER_DUPLICATE.toIssue(EntityLabels.presentation(presentation), number));
			}
		});
	}

	private void validateTables() {
		for (Table table : presentation.getTables()) {
			result.addAll(TableValidator.validate(table));
		}
	}
}
