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

	private ValidationResult run() {
		validateName();
		return result;
	}

	private void validateName() {
		String name = object.getName();
		if (name == null || name.isBlank()) {
			result.add(HallObjectIssue.NAME_EMPTY.toIssue(EntityLabels.hall(hall)));
		}
	}
}
