package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

class SeatSelectionSplitTest {

	private final Table table = new Event().addPresentation("Prunksitzung").addTable(1);

	private Seat freeSeat(int number) {
		return table.addSeat(number);
	}

	private Seat reservedSeat(int number) {
		Seat seat = table.addSeat(number);
		seat.setLastName("Meier");
		return seat;
	}

	private Seat soldSeat(int number) {
		Seat seat = table.addSeat(number);
		seat.setPaymentStatus(PaymentStatus.CASH);
		return seat;
	}

	@Test
	void separatesFreeSeatsFromReservedAndSoldOnesKeepingTheOrder() {
		Seat free1 = freeSeat(1);
		Seat reserved = reservedSeat(2);
		Seat free2 = freeSeat(3);
		Seat sold = soldSeat(4);

		SeatSelectionSplit split = SeatSelectionSplit.of(List.of(free1, reserved, free2, sold));

		assertEquals(List.of(free1, free2), split.free());
		assertEquals(List.of(reserved, sold), split.occupied());
		assertTrue(split.hasOccupied());
		assertTrue(split.hasFree());
	}

	@Test
	void onlyFreeSeatsNeedNoQuestion() {
		SeatSelectionSplit split = SeatSelectionSplit.of(List.of(freeSeat(1), freeSeat(2)));

		assertFalse(split.hasOccupied());
		assertTrue(split.hasFree());
	}

	@Test
	void onlyOccupiedSeatsLeaveNothingFree() {
		SeatSelectionSplit split = SeatSelectionSplit.of(List.of(reservedSeat(1), soldSeat(2)));

		assertTrue(split.hasOccupied());
		assertFalse(split.hasFree());
	}
}
