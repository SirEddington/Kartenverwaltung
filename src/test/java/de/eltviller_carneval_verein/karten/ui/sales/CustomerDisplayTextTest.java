package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;

class CustomerDisplayTextTest {

	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("Samstag");

	@Test
	void titleWithoutEventIsEmpty() {
		assertEquals("", CustomerDisplayText.title(null, null));
	}

	@Test
	void titleWithoutPresentationIsTheEventName() {
		event.setName("Prunksitzung 2027");

		assertEquals("Prunksitzung 2027", CustomerDisplayText.title(event, null));
	}

	@Test
	void titleListsPresentationDateAndTime() {
		event.setName("Prunksitzung 2027");
		presentation.setDate(LocalDate.of(2027, 2, 6));
		presentation.setTime(LocalTime.of(19, 11));

		assertEquals("Prunksitzung 2027 – Samstag, 06.02.2027, 19:11 Uhr", CustomerDisplayText.title(event, presentation));
	}

	@Test
	void titleOmitsMissingTime() {
		event.setName("Prunksitzung 2027");
		presentation.setDate(LocalDate.of(2027, 2, 6));

		assertEquals("Prunksitzung 2027 – Samstag, 06.02.2027", CustomerDisplayText.title(event, presentation));
	}

	@Test
	void titleOmitsMissingDate() {
		event.setName("Prunksitzung 2027");
		presentation.setTime(LocalTime.of(19, 11));

		assertEquals("Prunksitzung 2027 – Samstag, 19:11 Uhr", CustomerDisplayText.title(event, presentation));
	}

	@Test
	void titleShowsPlaceholderForEmptyNames() {
		event.setName("");
		presentation.setName(" ");

		assertEquals("(ohne Name) – (ohne Name)", CustomerDisplayText.title(event, presentation));
	}

	@Test
	void freeSeatIsNotTaken() {
		Booking seat = new TestBookings().booking(presentation, 1, 1);

		assertFalse(CustomerDisplayText.isTaken(seat));
	}

	@Test
	void reservedSeatIsTaken() {
		Booking seat = new TestBookings().booking(presentation, 1, 1);
		seat.setLastName("Meier");

		assertTrue(CustomerDisplayText.isTaken(seat));
	}

	@Test
	void paidSeatIsTaken() {
		Booking seat = new TestBookings().booking(presentation, 1, 1);
		seat.setPaymentStatus(PaymentStatus.CASH);

		assertTrue(CustomerDisplayText.isTaken(seat));
	}

	@Test
	void withoutPresentationTheCustomerWaits() {
		assertTrue(CustomerDisplayText.showsWaitingHint(null, null));
	}

	@Test
	void withAPlanTheCustomerSeesThePlan() {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId(plan.getId());

		assertFalse(CustomerDisplayText.showsWaitingHint(presentation, SalesPlan.of(presentation, hall)));
	}

	@Test
	void aDeletedPlanShowsTheWaitingTextInsteadOfTheInternalHint() {
		Hall hall = new Hall();
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId("gibt-es-nicht");
		SeatingPlan plan = SalesPlan.of(presentation, hall);
		// die Verkaufsansicht zeigt den internen Hinweis, das Kundenfenster den Wartetext
		assertEquals(SalesPlan.MISSING_HINT, SalesPlan.hint(presentation, plan));

		assertTrue(CustomerDisplayText.showsWaitingHint(presentation, plan));
	}

	@Test
	void aFrozenPresentationShowsTheWaitingText() {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId(plan.getId());
		presentation.setPlanMissing(true);

		assertTrue(CustomerDisplayText.showsWaitingHint(presentation, SalesPlan.of(presentation, hall)));
	}

	@Test
	void withoutSeatingPlanTheCustomerWaits() {
		presentation.setHallId(new Hall().getId());
		assertEquals(SalesPlan.NONE_HINT, SalesPlan.hint(presentation, null));

		assertTrue(CustomerDisplayText.showsWaitingHint(presentation, null));
	}
}
