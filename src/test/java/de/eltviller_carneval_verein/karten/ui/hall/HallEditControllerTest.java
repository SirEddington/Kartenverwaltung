package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;

class HallEditControllerTest {

	private final SeatingPlan plan = new Hall().addSeatingPlan();

	@Test
	void deleteMessageWithoutUsers() {
		plan.setName("Prunksitzung");

		assertEquals("Soll die Bestuhlung \"Prunksitzung\" wirklich gelöscht werden?", HallEditController.deletePlanMessage(plan, List.of()));
	}

	@Test
	void deleteMessageNamesThePresentationsUsingThePlan() {
		plan.setName("Prunksitzung");
		Event event = new Event();
		event.setName("Kampagne 2027");
		event.addPresentation("Sitzung 1").setSeatingPlanId(plan.getId());
		event.addPresentation("Sitzung 2").setSeatingPlanId("andere");
		event.addPresentation("Sitzung 3").setSeatingPlanId(plan.getId());

		assertEquals("Soll die Bestuhlung \"Prunksitzung\" wirklich gelöscht werden?\n\nSie wird von diesen Vorstellungen verwendet:"
				+ "\n- Kampagne 2027: Sitzung 1\n- Kampagne 2027: Sitzung 3\n\nDie Buchungen bleiben erhalten.",
				HallEditController.deletePlanMessage(plan, List.of(event)));
	}

	@Test
	void deleteMessageSkipsEventsWithoutPresentationList() {
		plan.setName("Prunksitzung");
		Event event = new Event();
		event.setPresentations(null);

		assertEquals("Soll die Bestuhlung \"Prunksitzung\" wirklich gelöscht werden?", HallEditController.deletePlanMessage(plan, List.of(event)));
	}

	@Test
	void newPlanGetsTheDefaultSizesOfTheFormerEventCreation() {
		Hall hall = new Hall();

		SeatingPlan created = HallEditController.addPlanWithDefaultSizes(hall);

		assertEquals(List.of(created), hall.getSeatingPlans());
		assertEquals(70, created.getDefaultTableWidth());
		assertEquals(300, created.getDefaultTableHeight());
		assertEquals(30, created.getDefaultSeatWidth());
		assertEquals(30, created.getDefaultSeatHeight());
	}

	@Test
	void deleteMessageForOneUserAndEmptyNames() {
		plan.setName("");
		Event event = new Event();
		event.addPresentation("Sitzung 1").setSeatingPlanId(plan.getId());

		assertEquals("Soll die Bestuhlung \"(ohne Name)\" wirklich gelöscht werden?\n\nSie wird von dieser Vorstellung verwendet:"
				+ "\n- (ohne Name): Sitzung 1\n\nDie Buchungen bleiben erhalten.", HallEditController.deletePlanMessage(plan, List.of(event)));
	}
}
