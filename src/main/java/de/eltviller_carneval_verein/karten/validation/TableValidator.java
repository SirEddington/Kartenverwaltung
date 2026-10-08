package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Plausibilitätsprüfungen für einen Tisch (Kürzel TBL). Eigene Regeln gibt es noch nicht, der
 * Validator reicht die Prüfung an die Sitze weiter. Aufbau wie bei {@link SeatValidator}.
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
		validateSeats();
		return result;
	}

	private void validateSeats() {
		for (Seat seat : table.getSeats()) {
			result.addAll(SeatValidator.validate(seat));
		}
	}
}
