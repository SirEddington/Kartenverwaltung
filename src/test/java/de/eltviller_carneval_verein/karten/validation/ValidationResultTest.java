package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ValidationResultTest {

	private static final ValidationIssue ERROR = new ValidationIssue("TST-001", Severity.ERROR, "Fehler");
	private static final ValidationIssue WARNING = new ValidationIssue("TST-002", Severity.WARNING, "Warnung");

	@Test
	void emptyResultIsValid() {
		ValidationResult result = ValidationResult.ok();

		assertTrue(result.isValid());
		assertTrue(result.isEmpty());
		assertTrue(result.mostSevere().isEmpty());
	}

	@Test
	void warningsDoNotInvalidateResult() {
		ValidationResult result = ValidationResult.of(WARNING);

		assertTrue(result.isValid());
		assertFalse(result.isEmpty());
	}

	@Test
	void errorInvalidatesResult() {
		assertFalse(ValidationResult.of(ERROR).isValid());
	}

	@Test
	void issuesAreSortedBySeverityMostSevereFirst() {
		ValidationResult result = ValidationResult.ok().add(WARNING).add(ERROR);

		assertEquals(ERROR, result.getIssues().get(0));
		assertEquals(WARNING, result.getIssues().get(1));
		assertEquals(ERROR, result.mostSevere().orElseThrow());
	}

	@Test
	void addAllMergesIssues() {
		ValidationResult merged = ValidationResult.of(WARNING).addAll(ValidationResult.of(ERROR));

		assertEquals(2, merged.getIssues().size());
	}

	@Test
	void displayTextContainsCodeAndMessage() {
		assertEquals("TST-001: Fehler", ERROR.toDisplayText());
	}
}
