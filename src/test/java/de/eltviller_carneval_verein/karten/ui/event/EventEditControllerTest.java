package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;

class EventEditControllerTest {

	@Test
	void ohne_Bestuhlung_zeigt_die_Spalte_einen_Strich() {
		assertEquals("–", EventEditController.planDisplayName(new Presentation(), new Hall()));
	}

	@Test
	void die_Spalte_zeigt_den_Namen_der_Bestuhlung() {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Prunksitzung");
		Presentation presentation = new Presentation();
		presentation.setSeatingPlanId(plan.getId());

		assertEquals("Prunksitzung", EventEditController.planDisplayName(presentation, hall));
	}

	@Test
	void bestuhlung_ohne_Namen_heisst_ohne_Name() {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName(" ");
		Presentation presentation = new Presentation();
		presentation.setSeatingPlanId(plan.getId());

		assertEquals("(ohne Name)", EventEditController.planDisplayName(presentation, hall));
	}

	@Test
	void geloeschte_Bestuhlung_oder_Halle_heisst_nicht_mehr_vorhanden() {
		Presentation presentation = new Presentation();
		presentation.setSeatingPlanId("geloescht");

		assertEquals("nicht mehr vorhanden", EventEditController.planDisplayName(presentation, new Hall()));
		assertEquals("nicht mehr vorhanden", EventEditController.planDisplayName(presentation, null));
	}
}
