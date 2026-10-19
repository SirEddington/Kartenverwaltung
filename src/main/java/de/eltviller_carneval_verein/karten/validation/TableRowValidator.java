package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.TableRow;

/**
 * Plausibilitätsprüfungen für eine Tischreihe (Kürzel ROW, siehe {@link TableRowIssue}). Dass ein Buchstabe nur
 * einer Reihe gehört, prüft der {@link SeatingPlanValidator}, weil dafür alle Reihen nötig sind. Aufbau wie bei
 * {@link SeatValidator}.
 */
public final class TableRowValidator {

	// Wertebereiche
	static final double MAX_ANGLE = 360;
	static final double MAX_POSITION = 10_000;
	static final double MAX_DISTANCE = 1_000;
	static final int MIN_AISLE_AFTER = 1;
	static final int MAX_AISLE_AFTER = 1_000;

	private final TableRow row;
	private final ValidationResult result = ValidationResult.ok();

	private TableRowValidator(TableRow row) {
		this.row = row;
	}

	public static ValidationResult validate(TableRow row) {
		return new TableRowValidator(row).run();
	}

	private ValidationResult run() {
		validateValues();
		return result;
	}

	private void validateValues() {
		String owner = EntityLabels.row(row);
		RangeCheck.check(result, TableRowIssue.VALUE_OUT_OF_RANGE, "Winkel", owner, row.getAngle(), -MAX_ANGLE, MAX_ANGLE);
		RangeCheck.check(result, TableRowIssue.VALUE_OUT_OF_RANGE, "Tischdrehung", owner, row.getTableRotation(), -MAX_ANGLE, MAX_ANGLE);
		RangeCheck.check(result, TableRowIssue.VALUE_OUT_OF_RANGE, "Position X", owner, row.getPosX(), -MAX_POSITION, MAX_POSITION);
		RangeCheck.check(result, TableRowIssue.VALUE_OUT_OF_RANGE, "Position Y", owner, row.getPosY(), -MAX_POSITION, MAX_POSITION);
		RangeCheck.check(result, TableRowIssue.VALUE_OUT_OF_RANGE, "Tischabstand", owner, row.getTableGap(), 0, MAX_DISTANCE);
		RangeCheck.check(result, TableRowIssue.VALUE_OUT_OF_RANGE, "Gangbreite", owner, row.getAisleWidth(), 0, MAX_DISTANCE);
		// Der Gang nach einem Tisch ist optional: ohne Wert gibt es keinen Gang
		if (row.getAisleAfter() != null) {
			RangeCheck.check(result, TableRowIssue.VALUE_OUT_OF_RANGE, "Gang nach Tisch", owner, row.getAisleAfter(), MIN_AISLE_AFTER,
					MAX_AISLE_AFTER);
		}
	}
}
