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

	// Wertebereiche, bisher die Grenzen der Spinner (Breite und Höhe 0 = noch ohne Maße, siehe Presentation.addTable)
	static final double MAX_SIZE = 1_000;
	static final double MAX_POSITION = 10_000;

	private ValidationResult run() {
		validateNumber();
		validateSizeAndPosition();
		validateSeatNumbersAreUnique();
		validateSeats();
		return result;
	}

	private void validateSizeAndPosition() {
		String owner = EntityLabels.table(table);
		RangeCheck.check(result, TableIssue.SIZE_OUT_OF_RANGE, "Breite", owner, table.getWidth(), 0, MAX_SIZE);
		RangeCheck.check(result, TableIssue.SIZE_OUT_OF_RANGE, "Höhe", owner, table.getHeight(), 0, MAX_SIZE);
		RangeCheck.check(result, TableIssue.POSITION_OUT_OF_RANGE, "Position X", owner, table.getPosX(), -MAX_POSITION, MAX_POSITION);
		RangeCheck.check(result, TableIssue.POSITION_OUT_OF_RANGE, "Position Y", owner, table.getPosY(), -MAX_POSITION, MAX_POSITION);
	}

	private void validateNumber() {
		if (table.getTableNumber() < 1) {
			result.add(TableIssue.NUMBER_TOO_LOW.toIssue(EntityLabels.table(table)));
		}
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
