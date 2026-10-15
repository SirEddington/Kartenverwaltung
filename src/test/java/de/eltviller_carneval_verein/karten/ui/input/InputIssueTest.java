package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.validation.Severity;
import de.eltviller_carneval_verein.karten.validation.ValidationIssue;

class InputIssueTest {

	@Test
	void notANumberNamesFieldAndText() {
		ValidationIssue issue = InputIssue.NOT_A_NUMBER.toIssue("Preis von Tisch 4, Sitz 2", "abc");

		assertEquals("INP-001", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Preis von Tisch 4, Sitz 2: „abc“ ist keine gültige Zahl.", issue.message());
	}

	@Test
	void notADateGivesAnExample() {
		ValidationIssue issue = InputIssue.NOT_A_DATE.toIssue("Datum von Vorstellung Prunksitzung", "31.02.2026");

		assertEquals("INP-002", issue.code());
		assertEquals("Datum von Vorstellung Prunksitzung: „31.02.2026“ ist kein gültiges Datum, z. B. 31.12.2026.", issue.message());
	}

	@Test
	void notATimeGivesAnExample() {
		ValidationIssue issue = InputIssue.NOT_A_TIME.toIssue("Uhrzeit von Vorstellung Prunksitzung", "25:00");

		assertEquals("INP-003", issue.code());
		assertEquals("Uhrzeit von Vorstellung Prunksitzung: „25:00“ ist keine gültige Uhrzeit, z. B. 19:30.", issue.message());
	}

	@Test
	void outOfRangeNamesTheLimits() {
		ValidationIssue issue = InputIssue.OUT_OF_RANGE.toIssue("Breite", "5000", "1", "1000");

		assertEquals("INP-004", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Breite: 5000 liegt nicht zwischen 1 und 1000.", issue.message());
	}
}
