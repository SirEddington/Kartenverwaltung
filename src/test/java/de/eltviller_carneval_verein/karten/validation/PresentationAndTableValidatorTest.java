package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Table;

class PresentationAndTableValidatorTest {

	/** Vollständig ausgefüllte Vorstellung "Prunksitzung", damit nur die zu prüfenden Fehler auftreten. */
	private static Presentation presentation() {
		Event event = new Event();
		event.changeName("Kampagne");
		Presentation presentation = event.addPresentation("Prunksitzung");
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.setHallId("halle-1");
		return presentation;
	}

	private static ValidationIssue only(ValidationResult result) {
		assertEquals(1, result.getIssues().size(), "Genau eine Meldung erwartet: " + result.getIssues());
		return result.getIssues().get(0);
	}

	// --- Weitergabe an die Kinder ---

	@Test
	void tableValidatorChecksEverySeatOfTheTable() {
		Table table = presentation().addTable(4);
		table.addSeat(1).setPrice(-1);
		table.addSeat(2).setPrice(1000);
		table.addSeat(3).setPrice(-5);

		ValidationResult result = TableValidator.validate(table);

		assertEquals(2, result.getIssues().size());
		assertTrue(result.getIssues().stream().allMatch(i -> i.code().equals("SEA-001")));
	}

	@Test
	void presentationValidatorChecksEveryTable() {
		Presentation presentation = presentation();
		presentation.addTable(1).addSeat(1).setPrice(-1);
		presentation.addTable(2).addSeat(1).setPrice(-1);
		presentation.addTable(3).addSeat(1).setPrice(1000);

		assertEquals(2, PresentationValidator.validate(presentation).getIssues().size());
	}

	@Test
	void emptyTableAndCompletePresentationHaveNoIssues() {
		Presentation presentation = presentation();
		Table table = presentation.addTable(1);

		assertTrue(TableValidator.validate(table).isEmpty());
		assertTrue(PresentationValidator.validate(presentation).isEmpty());
	}

	// --- doppelte Sitznummern (TBL-001) ---

	@Test
	void duplicateSeatNumberIsReportedOncePerNumber() {
		Table table = presentation().addTable(4);
		table.addSeat(1);
		table.addSeat(2).changeSeatNumber(1);
		table.addSeat(3).changeSeatNumber(1);

		ValidationIssue issue = only(TableValidator.validate(table));

		assertEquals("TBL-001", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Sitznummer 1 kommt an Prunksitzung, Tisch 4 mehrfach vor.", issue.message());
	}

	@Test
	void differentSeatNumbersAreFine() {
		Table table = presentation().addTable(4);
		table.addSeat(1);
		table.addSeat(2);

		assertTrue(TableValidator.validate(table).isEmpty());
	}

	// --- doppelte Tischnummern (PRS-001) ---

	@Test
	void duplicateTableNumberIsReportedOncePerNumber() {
		Presentation presentation = presentation();
		presentation.addTable(1);
		presentation.addTable(2).changeTableNumber(1);

		ValidationIssue issue = only(PresentationValidator.validate(presentation));

		assertEquals("PRS-001", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Tischnummer 1 kommt in Vorstellung Prunksitzung mehrfach vor.", issue.message());
	}

	@Test
	void differentTableNumbersAreFine() {
		Presentation presentation = presentation();
		presentation.addTable(1);
		presentation.addTable(2);

		assertTrue(PresentationValidator.validate(presentation).isEmpty());
	}

	// --- Datum/Uhrzeit (PRS-002) ---

	@Test
	void missingDateOrTimeIsAWarning() {
		Presentation noDate = presentation();
		noDate.setDate(null);
		Presentation noTime = presentation();
		noTime.setTime(null);

		for (Presentation presentation : new Presentation[] { noDate, noTime }) {
			ValidationResult result = PresentationValidator.validate(presentation);

			assertTrue(result.isValid(), "Warnungen blockieren nicht");
			assertEquals("PRS-002", only(result).code());
			assertEquals("Für Vorstellung Prunksitzung fehlen Datum oder Uhrzeit.", only(result).message());
		}
	}

	@Test
	void bothDateAndTimeMissingGiveOneMessage() {
		Presentation presentation = presentation();
		presentation.setDate(null);
		presentation.setTime(null);

		assertEquals("PRS-002", only(PresentationValidator.validate(presentation)).code());
	}

	// --- Halle (PRS-003) ---

	@Test
	void missingHallIsAWarning() {
		for (String hallId : new String[] { null, "", "  " }) {
			Presentation presentation = presentation();
			presentation.setHallId(hallId);

			ValidationResult result = PresentationValidator.validate(presentation);

			assertTrue(result.isValid());
			assertEquals("PRS-003", only(result).code());
			assertEquals("Für Vorstellung Prunksitzung ist keine Halle ausgewählt.", only(result).message());
		}
	}

	@Test
	void presentationWithoutNameIsStillDescribed() {
		Presentation presentation = presentation();
		presentation.setHallId(null);
		// Name nachträglich leeren, wie es eine geladene Datei könnte
		try {
			var name = Presentation.class.getDeclaredField("name");
			name.setAccessible(true);
			name.set(presentation, " ");
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}

		assertEquals("Für Vorstellung ohne Namen ist keine Halle ausgewählt.", only(PresentationValidator.validate(presentation)).message());
	}
}
