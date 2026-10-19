package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
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

	@Test
	void modelPlan_liefert_die_Bestuhlung_der_Vorstellung_und_ohne_Halle_null() {
		Hall hall = new Hall();
		hall.addSeatingPlan();
		SeatingPlan plan = hall.addSeatingPlan();
		Presentation presentation = new Presentation();
		presentation.setSeatingPlanId(plan.getId());

		assertSame(plan, EventEditController.modelPlan(presentation, hall));
		assertNull(EventEditController.modelPlan(presentation, null));
		presentation.setSeatingPlanId(null);
		assertNull(EventEditController.modelPlan(presentation, hall));
	}

	@Test
	void hallenwechsel_ohne_Buchungen_mit_Daten_fragt_nicht() {
		Presentation presentation = new Presentation();
		presentation.addBooking();
		presentation.addBooking().setPrice(2000);

		assertNull(EventEditController.hallChangeMessage(presentation));
	}

	@Test
	void hallenwechsel_mit_Buchungen_mit_Daten_nennt_ihre_Anzahl() {
		Presentation presentation = new Presentation();
		presentation.addBooking().setLastName("Müller");
		presentation.addBooking();

		assertEquals("1 Buchung mit Daten bleibt erhalten, hat aber keinen Platz mehr, bis du eine Bestuhlung der neuen Halle wählst."
				+ " Hallenwechsel fortsetzen?", EventEditController.hallChangeMessage(presentation));

		presentation.addBooking().setPaymentStatus(PaymentStatus.CASH);
		presentation.addBooking().setComment("Fenster");

		assertEquals("3 Buchungen mit Daten bleiben erhalten, haben aber keinen Platz mehr, bis du eine Bestuhlung der neuen Halle wählst."
				+ " Hallenwechsel fortsetzen?", EventEditController.hallChangeMessage(presentation));
	}
}
