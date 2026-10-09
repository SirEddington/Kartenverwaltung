package de.eltviller_carneval_verein.karten.validation;

import java.util.Map;
import java.util.TreeMap;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Plausibilitätsprüfungen für einen Tisch (Kürzel TBL, siehe {@link TableIssue}) und Weitergabe der
 * Prüfung an die Sitze. Aufbau wie bei {@link SeatValidator}.
 */
public final class TableValidator {

	private final Table table;
	private final ValidationResult result = ValidationResult.ok();

	private TableValidator(Table table) {
		this.table = table;
	}

	public static ValidationResult validate(Table table) {
		return new TableValidator(table).run();
	}

	private ValidationResult run() {
		validateSeatNumbersAreUnique();
		validateSeats();
		return result;
	}

	/** Vergleich unter Geschwistern: eine Meldung je mehrfach vergebener Sitznummer. */
	private void validateSeatNumbersAreUnique() {
		Map<Integer, Integer> counts = new TreeMap<>();
		for (Seat seat : table.getSeats()) {
			counts.merge(seat.getSeatNumber(), 1, Integer::sum);
		}
		counts.forEach((number, count) -> {
			if (count > 1) {
				result.add(TableIssue.SEAT_NUMBER_DUPLICATE.toIssue(EntityLabels.table(table), number));
			}
		});
	}

	private void validateSeats() {
		for (Seat seat : table.getSeats()) {
			result.addAll(SeatValidator.validate(seat));
		}
	}
}
