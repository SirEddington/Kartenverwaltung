package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Table;

class EventValidatorTest {

	private static void complete(Presentation presentation) {
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.setHallId("halle-1");
	}

	/** Event mit zwei vollständigen Vorstellungen, je ein Tisch (Nr. 1) mit zwei Sitzen zu 10 Euro. */
	private static Event eventWithTwoPresentations() {
		Event event = new Event();
		event.changeName("Kampagne");
		for (String name : List.of("Prunksitzung", "Damensitzung")) {
			Presentation presentation = event.addPresentation(name);
			complete(presentation);
			Table table = presentation.addTable(1);
			table.addSeat(1).setPrice(1000);
			table.addSeat(2).setPrice(1000);
		}
		return event;
	}

	@Test
	void validEventHasNoIssues() {
		ValidationResult result = EventValidator.validate(eventWithTwoPresentations());

		assertTrue(result.isValid());
		assertTrue(result.isEmpty());
	}

	@Test
	void collectsIssuesFromAllLevelsOfTheTree() {
		Event event = eventWithTwoPresentations();
		event.getPresentations().get(0).getTables().get(0).getSeats().get(1).setPrice(-100);
		event.getPresentations().get(1).getTables().get(0).getSeats().get(0).setPrice(200_000);

		List<ValidationIssue> issues = EventValidator.validate(event).getIssues();

		assertEquals(2, issues.size());
		assertTrue(issues.stream().anyMatch(i -> i.code().equals("SEA-001") && i.message().contains("Prunksitzung, Tisch 1, Sitz 2")));
		assertTrue(issues.stream().anyMatch(i -> i.code().equals("SEA-003") && i.message().contains("Damensitzung, Tisch 1, Sitz 1")));
	}

	@Test
	void issuesFromEveryEntityLevelAreCollected() {
		Event event = eventWithTwoPresentations();
		event.getPresentations().get(1).changeName("prunksitzung ");                              // EVT-001
		event.getPresentations().get(0).setHallId(null);                                           // PRS-003
		event.getPresentations().get(0).getTables().get(0).getSeats().get(1).changeSeatNumber(1);  // TBL-001
		event.getPresentations().get(0).getTables().get(0).getSeats().get(0).setPrice(-5);         // SEA-001

		List<String> codes = EventValidator.validate(event).getIssues().stream().map(ValidationIssue::code).toList();

		assertEquals(4, codes.size(), codes.toString());
		assertTrue(codes.containsAll(List.of("EVT-001", "PRS-003", "TBL-001", "SEA-001")));
	}

	@Test
	void repeatedValidationDoesNotAccumulateIssues() {
		Event event = eventWithTwoPresentations();
		event.getPresentations().get(0).getTables().get(0).getSeats().get(0).setPrice(-1);

		assertEquals(1, EventValidator.validate(event).getIssues().size());
		assertEquals(1, EventValidator.validate(event).getIssues().size());
	}

	@Test
	void eventWithoutPresentationsIsValid() {
		assertTrue(EventValidator.validate(new Event()).isEmpty());
	}

	// --- gleiche Vorstellungsnamen (EVT-001) ---

	@Test
	void duplicatePresentationNameIsReportedOnceIgnoringCaseAndSpaces() {
		Event event = eventWithTwoPresentations();
		event.getPresentations().get(1).changeName("  PRUNKSITZUNG");
		event.addPresentation("Kehraus").changeName("prunksitzung");
		complete(event.getPresentations().get(2));

		ValidationResult result = EventValidator.validate(event);

		assertEquals(1, result.getIssues().size(), result.getIssues().toString());
		assertEquals("EVT-001", result.getIssues().get(0).code());
		assertEquals(Severity.WARNING, result.getIssues().get(0).severity());
		assertEquals("Es gibt mehrere Vorstellungen mit dem Namen Prunksitzung.", result.getIssues().get(0).message());
		assertTrue(result.isValid(), "Warnungen blockieren nicht");
	}

	@Test
	void differentNamesAreFine() {
		assertTrue(EventValidator.validate(eventWithTwoPresentations()).isEmpty());
	}
}
