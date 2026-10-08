package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Table;

class PresentationAndTableValidatorTest {

	private static Presentation presentation() {
		Event event = new Event();
		event.changeName("Kampagne");
		return event.addPresentation("Prunksitzung");
	}

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

		ValidationResult result = PresentationValidator.validate(presentation);

		assertEquals(2, result.getIssues().size());
	}

	@Test
	void emptyTableAndPresentationAreValid() {
		Presentation presentation = presentation();
		Table table = presentation.addTable(1);

		assertTrue(TableValidator.validate(table).isValid());
		assertTrue(PresentationValidator.validate(presentation).isValid());
	}
}
