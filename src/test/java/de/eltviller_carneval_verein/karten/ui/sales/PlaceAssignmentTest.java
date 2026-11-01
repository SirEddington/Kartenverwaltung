package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

class PlaceAssignmentTest {

	private final SeatingPlan plan = new Hall().addSeatingPlan();
	private final Presentation presentation = new Event().addPresentation("Samstag");

	private TableRow row(String label) {
		TableRow row = plan.addRow();
		row.setLabel(label);
		return row;
	}

	private Booking bookingOf(Seat seat) {
		return presentation.getBookings().stream().filter(b -> seat.getId().equals(b.getSeatId())).findFirst().orElseThrow();
	}

	@Test
	void freeSeatsAreTheSeatsWhoseBookingHasNoDataInPlaceOrder() {
		TableRow b = row("B");
		TableRow a = row("A");
		Table b1 = plan.addTable(b);
		Seat b1s1 = b1.addSeat();
		Table a2 = plan.addTable(a);
		a2.setTableNumber(2);
		Seat a2s2 = a2.addSeat(2);
		Seat a2s1 = a2.addSeat(1);
		Table a1 = plan.addTable(a);
		a1.setTableNumber(1);
		Seat a1s1 = a1.addSeat();
		presentation.setSeatingPlanId(plan.getId());
		BookingSync.sync(presentation, plan);
		bookingOf(a2s1).setLastName("Meier");
		bookingOf(a1s1).setPrice(1800); // ein bloßer Einzelpreis belegt den Sitz nicht

		assertEquals(List.of(a1s1, a2s2, b1s1), PlaceAssignment.freeSeats(presentation, plan));
		assertFalse(PlaceAssignment.isFree(presentation, a2s1));
		assertTrue(PlaceAssignment.isFree(presentation, a1s1), "wie bei freeSeats belegt ein Einzelpreis nicht");
	}

	@Test
	void aSeatWithoutAnyBookingIsFree() {
		Table table = plan.addTable(row(""));
		Seat seat = table.addSeat();

		assertEquals(List.of(seat), PlaceAssignment.freeSeats(presentation, plan));
	}

	@Test
	void labelNamesRowTableAndSeat() {
		Table table = plan.addTable(row("A"));
		table.setTableNumber(3);
		Seat seat = table.addSeat(2);
		Table plain = plan.addTable(row(""));
		plain.setTableNumber(4);
		Seat plainSeat = plain.addSeat(1);

		assertEquals("Tisch A3, Sitz 2", PlaceAssignment.label(seat));
		assertEquals("Tisch 4, Sitz 1", PlaceAssignment.label(plainSeat));
	}

	/** Dieselben Texte für Verkaufstabelle und Konflikt-Dialog (KARV-109). */
	@Test
	void reasonsNameTheSituationLikeTheConflictDialog() {
		Table table = plan.addTable(row("A"));
		table.setTableNumber(3);
		Seat seat = table.addSeat(2);

		assertEquals(EntityLabels.presentation(presentation) + " hat keine Bestuhlung; ein Platz lässt sich nicht zuweisen.",
				PlaceAssignment.noPlanText(presentation));
		assertEquals("Kein Platz frei: In dieser Bestuhlung ist jeder Sitz belegt.", PlaceAssignment.NO_FREE_SEAT);
		assertEquals("Tisch A3, Sitz 2 ist inzwischen belegt. Wähle einen anderen Platz.", PlaceAssignment.takenText(seat));
	}

	/** Die Prüfreihe von Verkaufstabelle und Konflikt-Dialog vor dem Dialog "Platz zuweisen…" (KARV-116). */
	@Test
	void refusalNamesMissingPlanThenNoFreeSeat() {
		assertEquals(PlaceAssignment.noPlanText(presentation), PlaceAssignment.refusal(presentation, null));
		assertEquals(PlaceAssignment.NO_FREE_SEAT, PlaceAssignment.refusal(presentation, plan), "eine Bestuhlung ohne Sitze");

		Seat seat = plan.addTable(row("A")).addSeat();
		presentation.setSeatingPlanId(plan.getId());
		BookingSync.sync(presentation, plan);
		assertEquals(null, PlaceAssignment.refusal(presentation, plan), "ein Sitz ist frei");

		bookingOf(seat).setLastName("Meier");
		assertEquals(PlaceAssignment.NO_FREE_SEAT, PlaceAssignment.refusal(presentation, plan));
	}
}
