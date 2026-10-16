package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;

class SalesPlanTest {

	private final Hall hall = new Hall();
	private final SeatingPlan plan = hall.addSeatingPlan();
	private final Presentation presentation = new Event().addPresentation("Samstag");

	@Test
	void findsThePlanOfThePresentationInItsHall() {
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId(plan.getId());

		assertSame(plan, SalesPlan.of(presentation, hall));
		assertNull(SalesPlan.hint(presentation, plan));
	}

	@Test
	void withoutSeatingPlanThereIsNoPlanAndTheHintSaysSo() {
		presentation.setHallId(hall.getId());

		assertNull(SalesPlan.of(presentation, hall));
		assertEquals("Keine Bestuhlung ausgewählt", SalesPlan.hint(presentation, null));
	}

	@Test
	void aDeletedPlanIsReportedAsMissing() {
		presentation.setSeatingPlanId("gibt-es-nicht");

		assertNull(SalesPlan.of(presentation, hall));
		assertEquals("Bestuhlung nicht mehr vorhanden", SalesPlan.hint(presentation, null));
	}

	@Test
	void aDeletedHallLeavesNoPlan() {
		presentation.setSeatingPlanId(plan.getId());

		assertNull(SalesPlan.of(presentation, null));
		assertEquals("Bestuhlung nicht mehr vorhanden", SalesPlan.hint(presentation, null));
	}

	@Test
	void aFrozenPresentationShowsNoPlanEvenIfItWasFound() {
		presentation.setSeatingPlanId(plan.getId());
		presentation.setPlanMissing(true);

		assertNull(SalesPlan.of(presentation, hall));
		assertEquals("Bestuhlung nicht mehr vorhanden", SalesPlan.hint(presentation, null));
	}

	@Test
	void withoutPresentationThereIsNeitherPlanNorHint() {
		assertNull(SalesPlan.of(null, hall));
		assertNull(SalesPlan.hint(null, null));
	}
}
