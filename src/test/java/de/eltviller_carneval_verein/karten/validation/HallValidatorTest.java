package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;

class HallValidatorTest {

	/** Halle "Halle 1" mit einem Objekt "Bühne", ohne jede Meldung. */
	private static Hall validHall() {
		Hall hall = new Hall();
		hall.setName("Halle 1");
		hall.addHallObject("Bühne");
		return hall;
	}

	private static ValidationIssue only(ValidationResult result) {
		assertEquals(1, result.getIssues().size(), "Genau eine Meldung erwartet: " + result.getIssues());
		return result.getIssues().get(0);
	}

	@Test
	void validHallHasNoIssues() {
		ValidationResult result = HallValidator.validate(validHall());

		assertTrue(result.isValid());
		assertTrue(result.isEmpty());
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
		hall.getHallObjects().get(0).setName(" ");

		ValidationIssue issue = only(HallValidator.validate(hall));

		assertEquals("HOB-001", issue.code());
		assertTrue(issue.message().contains("Halle Halle 1"), issue.message());
	}

	@Test
	void everyUnnamedObjectIsReportedSeparately() {
		Hall hall = validHall();
		hall.addHallObject("Sektbar").setName("");
		hall.addHallObject("Garderobe").setName(null);

		ValidationResult result = HallValidator.validate(hall);

		assertEquals(2, result.getIssues().size());
		assertTrue(result.getIssues().stream().allMatch(i -> i.code().equals("HOB-001")));
	}

	@Test
	void objectInAnUnnamedHallIsDescribedWithoutName() {
		Hall hall = validHall();
		hall.setName("");
		HallObject object = hall.getHallObjects().get(0);
		object.setName("");

		ValidationIssue issue = only(HallObjectValidator.validate(object, hall));

		assertEquals("HOB-001", issue.code());
		assertTrue(issue.message().contains("Halle ohne Namen"), issue.message());
	}
}
