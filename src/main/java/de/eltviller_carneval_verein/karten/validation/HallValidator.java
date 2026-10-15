package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;

/**
 * Plausibilitätsprüfungen für eine Halle (Kürzel HLL, siehe {@link HallIssue}) und Weitergabe der Prüfung an die
 * Hallenobjekte. Die Halle ist nicht Teil des Event-Baums und hat deshalb einen eigenen Einstieg. Aufbau wie bei
 * {@link EventValidator}.
 */
public final class HallValidator {

	private final Hall hall;
	private final ValidationResult result = ValidationResult.ok();

	private HallValidator(Hall hall) {
		this.hall = hall;
	}

	public static ValidationResult validate(Hall hall) {
		return new HallValidator(hall).run();
	}

	private ValidationResult run() {
		validateName();
		validateHallObjects();
		return result;
	}

	private void validateName() {
		String name = hall.getName();
		if (name == null || name.isBlank()) {
			result.add(HallIssue.NAME_EMPTY.toIssue());
		}
	}

	private void validateHallObjects() {
		for (HallObject object : hall.getHallObjects()) {
			result.addAll(HallObjectValidator.validate(object, hall));
		}
	}
}
