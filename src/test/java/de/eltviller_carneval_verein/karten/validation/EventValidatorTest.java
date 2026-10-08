package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Table;

class EventValidatorTest {

	/** Event mit zwei Vorstellungen, je ein Tisch (Nr. 1) mit zwei Sitzen zu 10 Euro. */
	private static Event eventWithTwoPresentations() {
		Event event = new Event();
		event.changeName("Kampagne");
		for (String name : List.of("Prunksitzung", "Damensitzung")) {
			Presentation presentation = event.addPresentation(name);
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
	void repeatedValidationDoesNotAccumulateIssues() {
		Event event = eventWithTwoPresentations();
		event.getPresentations().get(0).getTables().get(0).getSeats().get(0).setPrice(-1);

		assertEquals(1, EventValidator.validate(event).getIssues().size());
		assertEquals(1, EventValidator.validate(event).getIssues().size());
	}

	@Test
	void eventWithoutPresentationsIsValid() {
		assertTrue(EventValidator.validate(new Event()).isValid());
	}
}
