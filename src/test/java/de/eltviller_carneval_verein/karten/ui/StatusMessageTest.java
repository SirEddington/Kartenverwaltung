package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.validation.Severity;
import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

class StatusMessageTest {

	private static final ValidationIssue ERROR = new ValidationIssue("TST-001", Severity.ERROR, "Fehler");
	private static final ValidationIssue WARNING = new ValidationIssue("TST-002", Severity.WARNING, "Warnung");

	@Test
	void singleIssueShowsCodeAndMessage() {
		StatusMessage.Entry entry = StatusMessage.describe(ValidationResult.of(ERROR));

		assertEquals(Severity.ERROR, entry.severity());
		assertEquals("TST-001: Fehler", entry.text());
		assertEquals("TST-001: Fehler", entry.details());
	}

	@Test
	void multipleIssuesShowMostSevereAndCountOfOthers() {
		StatusMessage.Entry entry = StatusMessage.describe(ValidationResult.ok().add(WARNING).add(ERROR));

		assertEquals(Severity.ERROR, entry.severity());
		assertEquals("TST-001: Fehler (+1 weitere)", entry.text());
		assertEquals("TST-001: Fehler\nTST-002: Warnung", entry.details());
	}
}
