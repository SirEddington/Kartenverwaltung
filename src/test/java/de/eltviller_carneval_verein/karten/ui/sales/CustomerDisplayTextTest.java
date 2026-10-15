package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;

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
	void summaryGoesBelowThePlanOnlyInPortrait() {
		assertTrue(CustomerDisplayText.summaryBelow(1080, 1920));
		assertFalse(CustomerDisplayText.summaryBelow(1920, 1080));
		assertFalse(CustomerDisplayText.summaryBelow(1000, 1000));
	}

	@Test
	void freeSeatIsNotTaken() {
		Seat seat = presentation.addTable(1).addSeat(1);

		assertFalse(CustomerDisplayText.isTaken(seat));
	}

	@Test
	void reservedSeatIsTaken() {
		Seat seat = presentation.addTable(1).addSeat(1);
		seat.setLastName("Meier");

		assertTrue(CustomerDisplayText.isTaken(seat));
	}

	@Test
	void paidSeatIsTaken() {
		Seat seat = presentation.addTable(1).addSeat(1);
		seat.setPaymentStatus(PaymentStatus.CASH);

		assertTrue(CustomerDisplayText.isTaken(seat));
	}
}
