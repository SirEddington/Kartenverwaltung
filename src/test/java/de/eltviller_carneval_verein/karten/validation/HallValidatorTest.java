package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Shape;

class HallValidatorTest {

	/** Halle "Halle 1" (500 x 300) mit einem Objekt "Bühne" (100 x 60, Schrift 20) in der Ecke (0|0), ohne jede Meldung. */
	private static Hall validHall() {
		Hall hall = new Hall();
		hall.setName("Halle 1");
		hall.setHallWidth(500);
		hall.setHallHeight(300);
		HallObject stage = hall.addHallObject("Bühne");
		stage.setWidth(100);
		stage.setHeight(60);
		return stage.getParent();
	}

	private static HallObject object(Hall hall) {
		return hall.getHallObjects().get(0);
	}

	private static ValidationIssue only(ValidationResult result) {
		assertEquals(1, result.getIssues().size(), "Genau eine Meldung erwartet: " + result.getIssues());
		return result.getIssues().get(0);
	}

	@Test
	void validHallHasNoIssues() {
		ValidationResult result = HallValidator.validate(validHall());

		assertTrue(result.isValid());
		assertTrue(result.isEmpty(), result.getIssues().toString());
	}

	@Test
	void hallWithoutNameIsAnError() {
		Hall hall = validHall();
		hall.setName("");

		ValidationResult result = HallValidator.validate(hall);

		assertFalse(result.isValid());
		assertEquals("HLL-001", only(result).code());
	}

	@Test
	void hallWithOnlySpacesInTheNameIsAnError() {
		Hall hall = validHall();
		hall.setName("   ");

		assertEquals("HLL-001", only(HallValidator.validate(hall)).code());
	}

	@Test
	void hallObjectWithoutNameIsAnErrorAndNamesTheHall() {
		Hall hall = validHall();
		object(hall).setName(" ");

		ValidationIssue issue = only(HallValidator.validate(hall));

		assertEquals("HOB-001", issue.code());
		assertTrue(issue.message().contains("Halle Halle 1"), issue.message());
	}

	@Test
	void everyUnnamedObjectIsReportedSeparately() {
		Hall hall = validHall();
		HallObject bar = hall.addHallObject("Sektbar");
		bar.setWidth(10);
		bar.setHeight(10);
		bar.setName("");
		HallObject cloakroom = hall.addHallObject("Garderobe");
		cloakroom.setWidth(10);
		cloakroom.setHeight(10);
		cloakroom.setName(null);

		ValidationResult result = HallValidator.validate(hall);

		assertEquals(2, result.getIssues().size());
		assertTrue(result.getIssues().stream().allMatch(i -> i.code().equals("HOB-001")));
	}

	@Test
	void objectInAnUnnamedHallIsDescribedWithoutName() {
		Hall hall = validHall();
		hall.setName("");
		HallObject object = object(hall);
		object.setName("");

		ValidationIssue issue = only(HallObjectValidator.validate(object, hall));

		assertEquals("HOB-001", issue.code());
		assertTrue(issue.message().contains("Halle ohne Namen"), issue.message());
	}

	// --- Wertebereiche (HLL-002, HOB-002 bis HOB-004), früher Grenzen der Spinner ---

	@Test
	void hallSizeMustBeBetweenZeroAndOneHundredThousand() {
		for (double ok : new double[] { 0, 1, 100_000 }) {
			Hall hall = validHall();
			hall.getHallObjects().clear();
			hall.setHallWidth(ok);
			hall.setHallHeight(ok);
			assertTrue(HallValidator.validate(hall).isEmpty(), "Gültig: " + ok);
		}

		Hall tooWide = validHall();
		tooWide.setHallWidth(100_000.5);
		ValidationIssue width = only(HallValidator.validate(tooWide));
		assertEquals("HLL-002", width.code());
		assertEquals("Breite von Halle Halle 1 liegt mit 100000,5 nicht zwischen 0 und 100000.", width.message());

		Hall negative = validHall();
		negative.setHallHeight(-1);
		ValidationIssue height = only(HallValidator.validate(negative));
		assertEquals("HLL-002", height.code());
		assertTrue(height.message().startsWith("Höhe von Halle Halle 1"), height.message());
	}

	@Test
	void hallObjectSizeMustBeBetweenOneAndTenThousand() {
		for (double ok : new double[] { 1, 100, 10_000 }) {
			Hall hall = validHall();
			hall.setHallWidth(0);
			hall.setHallHeight(0);
			object(hall).setWidth(ok);
			object(hall).setHeight(ok);
			assertTrue(HallValidator.validate(hall).isEmpty(), "Gültig: " + ok);
		}

		Hall zero = validHall();
		zero.setHallWidth(0);
		zero.setHallHeight(0);
		object(zero).setWidth(0);
		ValidationIssue width = only(HallValidator.validate(zero));
		assertEquals("HOB-002", width.code());
		assertEquals("Breite von Hallenobjekt Bühne in Halle Halle 1 liegt mit 0 nicht zwischen 1 und 10000.", width.message());

		Hall tall = validHall();
		tall.setHallWidth(0);
		tall.setHallHeight(0);
		object(tall).setHeight(10_001);
		assertEquals("HOB-002", only(HallValidator.validate(tall)).code());
	}

	@Test
	void hallObjectTextSizeMustBeBetweenOneAndOneThousand() {
		for (double ok : new double[] { 1, 20, 1000 }) {
			Hall hall = validHall();
			object(hall).setTextSize(ok);
			assertTrue(HallValidator.validate(hall).isEmpty(), "Gültig: " + ok);
		}
		for (double bad : new double[] { 0, 0.5, 1000.1, -3 }) {
			Hall hall = validHall();
			object(hall).setTextSize(bad);
			ValidationIssue issue = only(HallValidator.validate(hall));
			assertEquals("HOB-003", issue.code(), "Ungültig: " + bad);
			assertTrue(issue.message().startsWith("Schriftgröße von Hallenobjekt Bühne"), issue.message());
		}
	}

	@Test
	void hallObjectPositionMustBeWithinPlusMinusOneHundredThousand() {
		for (double ok : new double[] { -100_000, 0, 100_000 }) {
			Hall hall = validHall();
			hall.setHallWidth(0);
			hall.setHallHeight(0);
			object(hall).setPosX(ok);
			object(hall).setPosY(ok);
			assertTrue(HallValidator.validate(hall).isEmpty(), "Gültig: " + ok);
		}

		Hall far = validHall();
		far.setHallWidth(0);
		far.setHallHeight(0);
		object(far).setPosX(100_001);
		object(far).setPosY(-100_001);
		ValidationResult result = HallValidator.validate(far);
		assertEquals(2, result.getIssues().size(), result.getIssues().toString());
		assertTrue(result.getIssues().stream().allMatch(i -> i.code().equals("HOB-004")));
		assertTrue(result.getIssues().stream().anyMatch(i -> i.message().startsWith("Position X von")));
		assertTrue(result.getIssues().stream().anyMatch(i -> i.message().startsWith("Position Y von")));
	}

	@Test
	void hallValidationIncludesEverySeatingPlan() {
		Hall hall = validHall();
		hall.addSeatingPlan().setName("Prunksitzung");
		SeatingPlan unnamed = hall.addSeatingPlan();
		unnamed.setName("");
		unnamed.addRow().setAngle(999);

		ValidationResult result = HallValidator.validate(hall);

		assertEquals(2, result.getIssues().size(), result.getIssues().toString());
		assertTrue(result.getIssues().stream().anyMatch(i -> i.code().equals("SPL-001")
				&& i.message().equals("Der Name einer Bestuhlung in Halle Halle 1 darf nicht leer sein.")));
		assertTrue(result.getIssues().stream().anyMatch(i -> i.code().equals("ROW-002")));
	}

	// --- Lage in der Halle und Überlappung (HOB-005, HLL-003) ---

	private static HallObject addObject(Hall hall, String name, double width, double height, double x, double y) {
		HallObject object = hall.addHallObject(name);
		object.setWidth(width);
		object.setHeight(height);
		object.setPosX(x);
		object.setPosY(y);
		return object;
	}

	@Test
	void objectOutsideHallIsWarned() {
		Hall hall = validHall();
		addObject(hall, "Sektbar", 100, 50, 450, 0);

		ValidationIssue issue = only(HallValidator.validate(hall));

		assertEquals("HOB-005", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Hallenobjekt Sektbar in Halle Halle 1 ragt über den Rand der Halle hinaus.", issue.message());
	}

	@Test
	void objectInsideHallIsFine() {
		Hall hall = validHall();
		addObject(hall, "Sektbar", 100, 50, 390, 0);

		assertTrue(HallValidator.validate(hall).isEmpty());
	}

	@Test
	void hallWithoutSizeSkipsOutsideCheck() {
		Hall hall = validHall();
		hall.setHallWidth(0);
		hall.setHallHeight(0);
		addObject(hall, "Sektbar", 100, 50, 450, 0);
		assertTrue(HallValidator.validate(hall).isEmpty());

		hall.setHallWidth(500);
		hall.setHallHeight(0);
		assertTrue(HallValidator.validate(hall).isEmpty());
	}

	@Test
	void hallWithInvalidSizeSkipsOutsideChecks() {
		for (double bad : new double[] { Double.NaN, -1 }) {
			Hall hall = validHall();
			hall.setHallWidth(bad);
			addObject(hall, "Sektbar", 100, 50, 450, 0);

			ValidationResult result = HallValidator.validate(hall);

			assertTrue(result.getIssues().stream().noneMatch(i -> i.code().equals("HOB-005")), result.getIssues().toString());
		}
	}

	@Test
	void circleObjectIgnoresHeightForHall() {
		Hall hall = validHall();
		HallObject column = addObject(hall, "Säule", 40, 5000, 200, 100);
		column.setShape(Shape.CIRCLE);

		ValidationResult result = HallValidator.validate(hall);

		assertTrue(result.getIssues().stream().noneMatch(i -> i.code().equals("HOB-005")), result.getIssues().toString());
	}

	@Test
	void partlyOverlappingObjectsWarn() {
		Hall hall = validHall();
		addObject(hall, "Sektbar", 100, 50, 80, 0);

		ValidationIssue issue = only(HallValidator.validate(hall));

		assertEquals("HLL-003", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Hallenobjekt Bühne und Hallenobjekt Sektbar überlappen sich teilweise in Halle Halle 1.", issue.message());
	}

	@Test
	void objectFullyOnStageIsFine() {
		Hall hall = validHall();
		addObject(hall, "Pult", 20, 20, 40, 20);

		assertTrue(HallValidator.validate(hall).isEmpty());
	}

	@Test
	void circleOverStageEdgeWarns() {
		Hall hall = validHall();
		HallObject column = addObject(hall, "Säule", 40, 40, 90, 30);
		column.setShape(Shape.CIRCLE);

		assertEquals("HLL-003", only(HallValidator.validate(hall)).code());
	}

	@Test
	void onlyTheOverlappingPairIsReported() {
		Hall hall = validHall();
		addObject(hall, "Sektbar", 100, 50, 80, 0);
		addObject(hall, "Garderobe", 50, 50, 300, 200);

		ValidationResult result = HallValidator.validate(hall);

		assertEquals(1, result.getIssues().size(), result.getIssues().toString());
		assertEquals("HLL-003", result.getIssues().get(0).code());
	}

	@Test
	void objectWithoutSizeTakesPartInNoGeometry() {
		Hall hall = validHall();
		addObject(hall, "Leer", 0, 0, 0, 0);

		ValidationResult result = HallValidator.validate(hall);

		assertEquals(2, result.getIssues().size(), result.getIssues().toString());
		assertTrue(result.getIssues().stream().allMatch(i -> i.code().equals("HOB-002")));
	}

	@Test
	void newWarningsKeepHallValid() {
		Hall outside = validHall();
		addObject(outside, "Sektbar", 100, 50, 450, 0);
		assertTrue(HallValidator.validate(outside).isValid());

		Hall overlap = validHall();
		addObject(overlap, "Sektbar", 100, 50, 80, 0);
		assertTrue(HallValidator.validate(overlap).isValid());
	}

	@Test
	void notANumberCountsAsOutOfRange() {
		Hall hall = validHall();
		hall.setHallWidth(Double.NaN);

		assertEquals("HLL-002", only(HallValidator.validate(hall)).code());
	}
}
