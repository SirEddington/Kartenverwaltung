package de.eltviller_carneval_verein.karten.validation;

import java.util.Map;
import java.util.TreeMap;

import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Plausibilitätsprüfungen für eine Vorstellung (Kürzel PRS, siehe {@link PresentationIssue}) und
 * Weitergabe der Prüfung an die Tische. Aufbau wie bei {@link SeatValidator}.
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
		validateDateAndTime();
		validateHall();
		validateTableNumbersAreUnique();
		validateTables();
		return result;
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
