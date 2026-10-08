package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Plausibilitätsprüfungen für eine Vorstellung (Kürzel PRS). Eigene Regeln gibt es noch nicht, der
 * Validator reicht die Prüfung an die Tische weiter. Aufbau wie bei {@link SeatValidator}.
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

	private ValidationResult run() {
		validateTables();
		return result;
	}

	private void validateTables() {
		for (Table table : presentation.getTables()) {
			result.addAll(TableValidator.validate(table));
		}
	}
}
