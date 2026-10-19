package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.sales.OccupiedSeatsDialog.Choice;

class SeatSelectionEditorTest {

	private final Presentation presentation = new Event().addPresentation("Prunksitzung");
	private final TestBookings bookings = new TestBookings();
	private final List<Booking> deselected = new ArrayList<>();

	private Booking freeSeat(int number) {
		return bookings.booking(presentation, 1, number);
	}

	private Booking reservedSeat(int number) {
		Booking booking = bookings.booking(presentation, 1, number);
		booking.setLastName("Meier");
		return booking;
	}

	@Test
	void menuTextForASingleSeatNamesNoCount() {
		assertEquals("Sitz verkaufen…", SeatSelectionEditor.menuText(1));
	}

	@Test
	void menuTextForSeveralSeatsShowsTheCount() {
		assertEquals("Ausgewählte Sitze verkaufen… (6)", SeatSelectionEditor.menuText(6));
	}

	@Test
	void freeOnlyEditsTheFreeSeatsAndDropsTheOccupiedOnesFromTheSelection() {
		Booking free1 = freeSeat(1);
		Booking reserved = reservedSeat(2);
		Booking free2 = freeSeat(3);

		List<Booking> toEdit = SeatSelectionEditor.chooseBookings(List.of(free1, reserved, free2), split -> Choice.FREE_ONLY, deselected::addAll);

		assertEquals(List.of(free1, free2), toEdit);
		assertEquals(List.of(reserved), deselected);
	}

	@Test
	void overwriteEditsEverythingAndKeepsTheSelection() {
		Booking free = freeSeat(1);
		Booking reserved = reservedSeat(2);

		List<Booking> toEdit = SeatSelectionEditor.chooseBookings(List.of(free, reserved), split -> Choice.OVERWRITE, deselected::addAll);

		assertEquals(List.of(free, reserved), toEdit);
		assertTrue(deselected.isEmpty());
	}

	@Test
	void cancelEditsNothingAndKeepsTheSelection() {
		Booking free = freeSeat(1);
		Booking reserved = reservedSeat(2);

		List<Booking> toEdit = SeatSelectionEditor.chooseBookings(List.of(free, reserved), split -> Choice.CANCEL, deselected::addAll);

		assertNull(toEdit);
		assertTrue(deselected.isEmpty());
	}

	@Test
	void onlyFreeSeatsAreEditedWithoutAskingAnything() {
		Booking free1 = freeSeat(1);
		Booking free2 = freeSeat(2);

		List<Booking> toEdit = SeatSelectionEditor.chooseBookings(List.of(free1, free2), split -> {
			throw new AssertionError("Bei freien Sitzen gibt es nichts zu fragen");
		}, deselected::addAll);

		assertEquals(List.of(free1, free2), toEdit);
		assertTrue(deselected.isEmpty());
	}
}
