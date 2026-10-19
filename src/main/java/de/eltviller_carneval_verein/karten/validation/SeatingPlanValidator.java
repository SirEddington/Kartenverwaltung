package de.eltviller_carneval_verein.karten.validation;

import java.util.LinkedHashMap;
import java.util.Map;

import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

/**
 * Plausibilitätsprüfungen für eine Bestuhlung (Kürzel SPL, siehe {@link SeatingPlanIssue}) und Weitergabe der
 * Prüfung an die Reihen und Tische. Aufbau wie bei {@link SeatValidator}.
 */
public final class SeatingPlanValidator {

	private final SeatingPlan plan;
	private final ValidationResult result = ValidationResult.ok();

	private SeatingPlanValidator(SeatingPlan plan) {
		this.plan = plan;
	}

	public static ValidationResult validate(SeatingPlan plan) {
		return new SeatingPlanValidator(plan).run();
	}

	private ValidationResult run() {
		validateName();
		validateRowLabelsAreUnique();
		validateRows();
		validateTableNamesAreUnique();
		validateTableRows();
		validateTables();
		return result;
	}

	private void validateName() {
		String name = plan.getName();
		if (name == null || name.isBlank()) {
			result.add(SeatingPlanIssue.NAME_EMPTY.toIssue(EntityLabels.hall(plan.getParent())));
		}
	}

	/**
	 * Vergleich unter Geschwistern: eine Meldung je mehrfach verwendetem Buchstaben (Groß-/Kleinschreibung und
	 * Leerzeichen am Rand zählen nicht, die Meldung nennt die erste Schreibweise); Reihen ohne Buchstaben zählen nicht.
	 */
	private void validateRowLabelsAreUnique() {
		for (String label : DuplicateNames.of(plan.getRows().stream().map(TableRow::getLabel))) {
			result.add(TableRowIssue.LABEL_DUPLICATE.toIssue(EntityLabels.plan(plan), label));
		}
	}

	private void validateRows() {
		for (TableRow row : plan.getRows()) {
			result.addAll(TableRowValidator.validate(row));
		}
	}

	/**
	 * Vergleich unter Geschwistern: eine Meldung je mehrfach vorkommendem Tischnamen (Buchstabe und Nummer). Tische in
	 * Reihen ohne Buchstaben tragen nur die Nummer und teilen sich damit einen Geltungsbereich.
	 */
	private void validateTableNamesAreUnique() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Table table : plan.getTables()) {
			counts.merge(table.getDisplayName(), 1, Integer::sum);
		}
		counts.forEach((name, count) -> {
			if (count > 1) {
				result.add(SeatingPlanIssue.TABLE_DUPLICATE.toIssue(EntityLabels.plan(plan), name));
			}
		});
	}

	/** Jeder Tisch gehört zu einer vorhandenen Reihe; ohne Reihenkennung oder mit unbekannter Kennung bleibt er unplatziert. */
	private void validateTableRows() {
		for (Table table : plan.getTables()) {
			String rowId = table.getRowId();
			if (rowId == null || rowId.isBlank() || plan.findRow(rowId) == null) {
				result.add(TableIssue.ROW_MISSING.toIssue(EntityLabels.table(table)));
			}
		}
	}

	private void validateTables() {
		for (Table table : plan.getTables()) {
			result.addAll(TableValidator.validate(table));
		}
	}
}
