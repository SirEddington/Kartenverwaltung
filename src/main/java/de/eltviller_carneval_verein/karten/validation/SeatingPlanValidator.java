package de.eltviller_carneval_verein.karten.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

/**
 * Plausibilitätsprüfungen für eine Bestuhlung (Kürzel SPL, siehe {@link SeatingPlanIssue}) und Weitergabe der
 * Prüfung an die Reihen und Tische. Aufbau wie bei {@link SeatValidator}.
 */
public final class SeatingPlanValidator {

	private final SeatingPlan plan;
	private final Hall hall;
	private final ValidationResult result = ValidationResult.ok();
	/** Umrisse der Tische (Platte und Stühle) parallel zu {@code plan.getTables()}, je Lauf einmal berechnet. */
	private List<Optional<Footprint>> footprints = List.of();

	private SeatingPlanValidator(SeatingPlan plan, Hall hall) {
		this.plan = plan;
		this.hall = hall;
	}

	public static ValidationResult validate(SeatingPlan plan) {
		return validate(plan, plan.getParent());
	}

	/**
	 * @param hall die Halle für die Lageprüfung der Tische (TBL-007) und der Hallenobjekte (SPL-005); darf
	 *             {@code null} sein
	 */
	static ValidationResult validate(SeatingPlan plan, Hall hall) {
		return new SeatingPlanValidator(plan, hall).run();
	}

	private ValidationResult run() {
		footprints = plan.getTables().stream().map(Footprint::of).toList();
		validateName();
		validateRowLabelsAreUnique();
		validateRows();
		validateTableNamesAreUnique();
		validateTableRows();
		validateTables();
		validateTablesDoNotOverlap();
		validateTablesDoNotOverlapObjects();
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
		List<Table> tables = plan.getTables();
		for (int i = 0; i < tables.size(); i++) {
			result.addAll(TableValidator.validate(tables.get(i), hall, footprints.get(i).orElse(null)));
		}
	}

	/**
	 * Warnt je Paar von Tischen, deren Platten oder Stühle sich überlappen (die Meldung nennt sie in Listenreihenfolge).
	 * Tische ohne Umriss nehmen nicht teil.
	 */
	private void validateTablesDoNotOverlap() {
		List<Table> tables = plan.getTables();
		for (int i = 0; i < tables.size(); i++) {
			if (footprints.get(i).isEmpty()) {
				continue;
			}
			Footprint a = footprints.get(i).get();
			for (int j = i + 1; j < tables.size(); j++) {
				if (footprints.get(j).isPresent() && a.overlaps(footprints.get(j).get())) {
					result.add(SeatingPlanIssue.TABLES_OVERLAP.toIssue(EntityLabels.plan(plan), tables.get(i).getDisplayName(),
							tables.get(j).getDisplayName()));
				}
			}
		}
	}

	/**
	 * Warnt je Tisch und Hallenobjekt, die sich nur teilweise überlappen. Liegt der Tisch (Platte und Stühle) ganz auf
	 * dem Objekt, etwa auf einer Tanzfläche, ist das gewollt. Ohne Halle gibt es nichts zu prüfen; Tische und Objekte
	 * ohne Umriss nehmen nicht teil.
	 */
	private void validateTablesDoNotOverlapObjects() {
		if (hall == null) {
			return;
		}
		List<Optional<Footprint>> objects = hall.getHallObjects().stream().map(Footprint::of).toList();
		List<Table> tables = plan.getTables();
		for (int i = 0; i < tables.size(); i++) {
			if (footprints.get(i).isEmpty()) {
				continue;
			}
			Footprint tableFootprint = footprints.get(i).get();
			for (int j = 0; j < objects.size(); j++) {
				if (objects.get(j).isPresent() && tableFootprint.overlaps(objects.get(j).get())
						&& !tableFootprint.within(objects.get(j).get())) {
					result.add(SeatingPlanIssue.TABLE_OVERLAPS_OBJECT.toIssue(EntityLabels.table(tables.get(i)),
							EntityLabels.hallObjectName(hall.getHallObjects().get(j))));
				}
			}
		}
	}
}
