package de.eltviller_carneval_verein.karten.validation;

import java.util.Map;
import java.util.TreeMap;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Plausibilitätsprüfungen für einen Tisch (Kürzel TBL, siehe {@link TableIssue}) und Weitergabe der
 * Prüfung an die Sitze. Aufbau wie bei {@link SeatValidator}.
 */
public final class TableValidator {

	private final Table table;
	private final Hall hall;
	private final Footprint footprint;
	private final ValidationResult result = ValidationResult.ok();

	private TableValidator(Table table, Hall hall, Footprint footprint) {
		this.table = table;
		this.hall = hall;
		this.footprint = footprint;
	}

	public static ValidationResult validate(Table table) {
		return validate(table, null, null);
	}

	/**
	 * @param hall die Halle der Bestuhlung für die Lageprüfung (TBL-007); {@code null} lässt sie entfallen
	 * @param footprint der Umriss des Tisches (Platte und Stühle), den der Aufrufer schon berechnet hat; {@code null}
	 *            lässt die Lageprüfung entfallen
	 */
	static ValidationResult validate(Table table, Hall hall, Footprint footprint) {
		return new TableValidator(table, hall, footprint).run();
	}

	// Wertebereiche, bisher die Grenzen der Spinner (Breite und Höhe 0 = noch ohne Maße, siehe SeatingPlan.addTable)
	public static final double MAX_SIZE = 1_000;
	static final double MAX_POSITION = 10_000;

	private ValidationResult run() {
		validateNumber();
		validateSizeAndPosition();
		validateInsideHall();
		validateSeatNumbersAreUnique();
		validateSeats();
		return result;
	}

	/**
	 * Warnt, wenn Tischplatte oder Stühle über den Rand der Halle hinausragen. Entfällt ohne Halle, wenn die Halle keine
	 * gültige Größe hat (0 = noch nicht festgelegt) oder ohne Umriss; solche Fälle meldet die Bereichsprüfung.
	 */
	private void validateInsideHall() {
		if (hall == null || footprint == null || !hall.hasSize()) {
			return;
		}
		if (!footprint.insideHall(hall.getHallWidth(), hall.getHallHeight())) {
			result.add(TableIssue.OUTSIDE_HALL.toIssue(EntityLabels.table(table)));
		}
	}

	private void validateSizeAndPosition() {
		String owner = EntityLabels.table(table);
		RangeCheck.check(result, TableIssue.SIZE_OUT_OF_RANGE, "Breite", owner, table.getWidth(), 0, MAX_SIZE);
		RangeCheck.check(result, TableIssue.SIZE_OUT_OF_RANGE, "Höhe", owner, table.getHeight(), 0, MAX_SIZE);
		RangeCheck.check(result, TableIssue.POSITION_OUT_OF_RANGE, "Position X", owner, table.getPosX(), -MAX_POSITION, MAX_POSITION);
		RangeCheck.check(result, TableIssue.POSITION_OUT_OF_RANGE, "Position Y", owner, table.getPosY(), -MAX_POSITION, MAX_POSITION);
		// Grenzen wie beim Text eines Hallenobjekts (HOB-003)
		RangeCheck.check(result, TableIssue.TEXT_SIZE_OUT_OF_RANGE, "Schriftgröße", owner, table.getTextSize(), HallObjectValidator.MIN_TEXT_SIZE,
				HallObjectValidator.MAX_TEXT_SIZE);
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
