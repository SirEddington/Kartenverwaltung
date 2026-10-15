package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;

/**
 * Plausibilitätsprüfungen für ein Hallenobjekt (Kürzel HOB, siehe {@link HallObjectIssue}). Die Halle kommt als
 * Parameter, weil die Meldungen sie benennen und der Elternverweis des Objekts nicht überall gesetzt ist. Aufbau
 * wie bei {@link SeatValidator}.
 */
public final class HallObjectValidator {

	private final HallObject object;
	private final Hall hall;
	private final ValidationResult result = ValidationResult.ok();

	private HallObjectValidator(HallObject object, Hall hall) {
		this.object = object;
		this.hall = hall;
	}

	/** Prüft ein Hallenobjekt der angegebenen Halle. */
	public static ValidationResult validate(HallObject object, Hall hall) {
		return new HallObjectValidator(object, hall).run();
	}

	// Wertebereiche, bisher die Grenzen der Spinner im Hallenplan
	static final double MIN_SIZE = 1;
	static final double MAX_SIZE = 10_000;
	static final double MIN_TEXT_SIZE = 1;
	static final double MAX_TEXT_SIZE = 1_000;
	static final double MAX_POSITION = 100_000;

	private ValidationResult run() {
		validateName();
		validateSizeAndPosition();
		return result;
	}

	private void validateSizeAndPosition() {
		String owner = EntityLabels.hallObject(object, hall);
		RangeCheck.check(result, HallObjectIssue.SIZE_OUT_OF_RANGE, "Breite", owner, object.getWidth(), MIN_SIZE, MAX_SIZE);
		RangeCheck.check(result, HallObjectIssue.SIZE_OUT_OF_RANGE, "Höhe", owner, object.getHeight(), MIN_SIZE, MAX_SIZE);
		RangeCheck.check(result, HallObjectIssue.TEXT_SIZE_OUT_OF_RANGE, "Schriftgröße", owner, object.getTextSize(), MIN_TEXT_SIZE, MAX_TEXT_SIZE);
		RangeCheck.check(result, HallObjectIssue.POSITION_OUT_OF_RANGE, "Position X", owner, object.getPosX(), -MAX_POSITION, MAX_POSITION);
		RangeCheck.check(result, HallObjectIssue.POSITION_OUT_OF_RANGE, "Position Y", owner, object.getPosY(), -MAX_POSITION, MAX_POSITION);
	}

	private void validateName() {
		String name = object.getName();
		if (name == null || name.isBlank()) {
			result.add(HallObjectIssue.NAME_EMPTY.toIssue(EntityLabels.hall(hall)));
		}
	}
}
