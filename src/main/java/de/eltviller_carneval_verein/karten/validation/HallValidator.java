package de.eltviller_carneval_verein.karten.validation;

import java.util.List;
import java.util.Optional;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;

/**
 * Plausibilitätsprüfungen für eine Halle (Kürzel HLL, siehe {@link HallIssue}) und Weitergabe der Prüfung an die
 * Hallenobjekte und Bestuhlungen. Die Halle ist nicht Teil des Event-Baums und hat deshalb einen eigenen Einstieg. Aufbau wie bei
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

	/** Größte erlaubte Breite und Höhe der Halle (wie bisher die Grenze der Spinner); die kleinste ist 0 ("noch nicht festgelegt"). */
	static final double MAX_SIZE = 100_000;

	private ValidationResult run() {
		validateName();
		validateSize();
		validateHallObjects();
		validateObjectsDoNotOverlap();
		validateSeatingPlans();
		return result;
	}

	/**
	 * Warnt je Paar von Hallenobjekten, die sich nur teilweise überlappen; liegt eines ganz auf dem anderen (Pult auf
	 * der Bühne), ist das gewollt. Objekte ohne Fläche nehmen nicht teil.
	 */
	private void validateObjectsDoNotOverlap() {
		List<HallObject> objects = hall.getHallObjects();
		List<Optional<Footprint>> footprints = objects.stream().map(Footprint::of).toList();
		for (int i = 0; i < objects.size(); i++) {
			if (footprints.get(i).isEmpty()) {
				continue;
			}
			Footprint a = footprints.get(i).get();
			for (int j = i + 1; j < objects.size(); j++) {
				if (footprints.get(j).isEmpty()) {
					continue;
				}
				Footprint b = footprints.get(j).get();
				if (a.overlaps(b) && !a.within(b) && !b.within(a)) {
					result.add(HallIssue.OBJECTS_OVERLAP.toIssue(EntityLabels.hall(hall),
							EntityLabels.hallObjectName(objects.get(i)), EntityLabels.hallObjectName(objects.get(j))));
				}
			}
		}
	}

	private void validateSize() {
		String owner = EntityLabels.hall(hall);
		RangeCheck.check(result, HallIssue.SIZE_OUT_OF_RANGE, "Breite", owner, hall.getHallWidth(), 0, MAX_SIZE);
		RangeCheck.check(result, HallIssue.SIZE_OUT_OF_RANGE, "Höhe", owner, hall.getHallHeight(), 0, MAX_SIZE);
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

	/**
	 * Vergleich unter Geschwistern: eine Meldung je mehrfach vorkommendem Namen einer Bestuhlung (Groß-/Kleinschreibung
	 * und Leerzeichen am Rand zählen nicht). Die Meldungen und Auswahlen nennen eine Bestuhlung nur über ihren Namen.
	 */
	private void validatePlanNamesAreUnique() {
		for (String name : DuplicateNames.of(hall.getSeatingPlans().stream().map(SeatingPlan::getName))) {
			result.add(SeatingPlanIssue.NAME_DUPLICATE.toIssue(EntityLabels.hall(hall), name));
		}
	}

	private void validateSeatingPlans() {
		validatePlanNamesAreUnique();
		for (SeatingPlan plan : hall.getSeatingPlans()) {
			result.addAll(SeatingPlanValidator.validate(plan, hall));
		}
	}
}
