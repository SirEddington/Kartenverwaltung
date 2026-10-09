package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

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

	@Test
	void prefixIsPlacedBeforeTheMostSevereMessageOnly() {
		StatusMessage.Entry entry = StatusMessage.describe(ValidationResult.ok().add(WARNING).add(ERROR), "Nicht gespeichert: ");

		assertEquals(Severity.ERROR, entry.severity());
		assertEquals("Nicht gespeichert: TST-001: Fehler (+1 weitere)", entry.text());
		assertEquals("TST-001: Fehler\nTST-002: Warnung", entry.details());
	}

	@Test
	void tooltipListsAtMostTwentyIssues() {
		ValidationResult result = ValidationResult.ok();
		for (int i = 1; i <= 25; i++) {
			result.add(new ValidationIssue("TST-" + String.format("%03d", i), Severity.WARNING, "Meldung " + i));
		}

		StatusMessage.Entry entry = StatusMessage.describe(result);

		assertEquals("TST-001: Meldung 1 (+24 weitere)", entry.text());
		String[] lines = entry.details().split("\n");
		assertEquals(21, lines.length);
		assertEquals("TST-020: Meldung 20", lines[19]);
		assertEquals("... und 5 weitere", lines[20]);
	}

	@Test
	void eachTooltipLineKeepsTheSeverityOfItsIssue() {
		StatusMessage.Entry entry = StatusMessage.describe(ValidationResult.ok().add(WARNING).add(ERROR));

		assertEquals(2, entry.lines().size());
		// wichtigste Meldung zuerst, jede Zeile mit ihrem eigenen Schweregrad
		assertEquals(new StatusMessage.Line(Severity.ERROR, "TST-001: Fehler"), entry.lines().get(0));
		assertEquals(new StatusMessage.Line(Severity.WARNING, "TST-002: Warnung"), entry.lines().get(1));
	}

	@Test
	void cappedTooltipEndsWithAnInfoLineForTheRest() {
		ValidationResult result = ValidationResult.ok();
		for (int i = 1; i <= 25; i++) {
			result.add(new ValidationIssue("TST-" + String.format("%03d", i), Severity.WARNING, "Meldung " + i));
		}

		StatusMessage.Entry entry = StatusMessage.describe(result);

		assertEquals(21, entry.lines().size());
		assertEquals(new StatusMessage.Line(Severity.INFO, "... und 5 weitere"), entry.lines().get(20));
	}

	@Test
	void singleMessageHasOneTooltipLineInItsOwnSeverity() {
		StatusMessage.Entry entry = new StatusMessage.Entry(Severity.SUCCESS, "Gespeichert", "Gespeichert");

		assertEquals(List.of(new StatusMessage.Line(Severity.SUCCESS, "Gespeichert")), entry.lines());
	}
}
