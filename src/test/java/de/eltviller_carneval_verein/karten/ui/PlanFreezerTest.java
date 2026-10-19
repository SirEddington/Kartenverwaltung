package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;

class PlanFreezerTest {

	private ChangeTracker<Event> tracker;
	private Hall hall;
	private SeatingPlan plan;
	private SeatingPlan otherPlan;
	private Function<String, Hall> hallLookup;
	private final List<Event> saved = new ArrayList<>();
	private final Consumer<Event> save = saved::add;

	@BeforeEach
	void setUp() {
		tracker = new ChangeTracker<>(Event::getId);
		hall = new Hall();
		plan = planWithThreeTables();
		otherPlan = planWithThreeTables();
		hallLookup = id -> Map.of(hall.getId(), hall).get(id);
	}

	private SeatingPlan planWithThreeTables() {
		SeatingPlan seatingPlan = hall.addSeatingPlan();
		TableRow row = seatingPlan.addRow();
		seatingPlan.fillRow(row, 3, 2);
		return seatingPlan;
	}

	/** Event mit einer Vorstellung auf der Bestuhlung, abgeglichen und verfolgt, mit einer bezahlten Buchung an Tisch 3, Sitz 1. */
	private Event eventOn(SeatingPlan seatingPlan, String name) {
		Event event = new Event();
		event.setName(name);
		Presentation presentation = event.addPresentation("Samstag");
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId(seatingPlan.getId());
		BookingSyncService.prepare(event, hallLookup, tracker);
		Booking paid = paidBooking(event);
		paid.setLastName("Müller");
		paid.setPaymentStatus(PaymentStatus.CASH);
		tracker.markSaved(event);
		return event;
	}

	private static Booking paidBooking(Event event) {
		return event.getPresentations().get(0).getBookings().stream()
				.filter(b -> b.getTableNumber() == 3 && b.getSeatNumber() == 1).findFirst().orElseThrow();
	}

	private static Table table3(SeatingPlan seatingPlan) {
		return seatingPlan.getTables().stream().filter(t -> t.getTableNumber() == 3).findFirst().orElseThrow();
	}

	private Predicate<Presentation> usesPlan() {
		return presentation -> plan.getId().equals(presentation.getSeatingPlanId());
	}

	@Test
	void sauberes_Event_wird_abgeglichen_und_einmal_gespeichert() {
		Event event = eventOn(plan, "Prunksitzung");
		Booking paid = paidBooking(event);
		// Tisch 3 wurde aus der Bestuhlung entfernt, ohne das Event zu öffnen
		plan.deleteTable(table3(plan));
		assertFalse(paid.isUnassigned());

		List<Event> result = PlanFreezer.freeze(List.of(event), usesPlan(), hallLookup, tracker, save);

		assertEquals(List.of(event), saved);
		assertEquals(List.of(event), result);
		assertTrue(paid.isUnassigned());
		assertTrue(event.getPresentations().get(0).getBookings().contains(paid));
		assertFalse(tracker.isDirty(event));
	}

	@Test
	void nicht_verfolgtes_Event_gilt_als_sauber_und_wird_gespeichert() {
		Event event = eventOn(plan, "Prunksitzung");
		tracker.untrack(event);
		plan.deleteTable(table3(plan));

		PlanFreezer.freeze(List.of(event), usesPlan(), hallLookup, tracker, save);

		assertEquals(List.of(event), saved);
		assertTrue(paidBooking(event).isUnassigned());
	}

	@Test
	void geaendertes_Event_wird_nicht_gespeichert() {
		Event event = eventOn(plan, "Prunksitzung");
		// ungespeicherte Änderung, die der Tracker noch nicht verglichen hat
		event.getPresentations().get(0).setDescription("Noch nicht gespeichert");
		plan.deleteTable(table3(plan));

		List<Event> result = PlanFreezer.freeze(List.of(event), usesPlan(), hallLookup, tracker, save);

		assertTrue(saved.isEmpty());
		assertTrue(result.isEmpty());
		tracker.check(event);
		assertTrue(tracker.isDirty(event));
	}

	@Test
	void Event_einer_anderen_Bestuhlung_bleibt_unberuehrt() {
		Event other = eventOn(otherPlan, "Kinderfasching");
		Booking otherPaid = paidBooking(other);
		otherPlan.deleteTable(table3(otherPlan));
		int bookingsBefore = other.getPresentations().get(0).getBookings().size();

		PlanFreezer.freeze(List.of(other), usesPlan(), hallLookup, tracker, save);

		assertTrue(saved.isEmpty());
		assertFalse(otherPaid.isUnassigned());
		assertEquals(bookingsBefore, other.getPresentations().get(0).getBookings().size());
		assertFalse(tracker.isDirty(other));
	}

	@Test
	void nur_betroffene_Events_werden_gespeichert() {
		Event affected = eventOn(plan, "Prunksitzung");
		Event other = eventOn(otherPlan, "Kinderfasching");
		Event withoutPresentations = new Event();
		withoutPresentations.setName("Leer");
		withoutPresentations.setPresentations(null);

		PlanFreezer.freeze(List.of(affected, other, withoutPresentations), usesPlan(), hallLookup, tracker, save);

		assertEquals(List.of(affected), saved);
	}

	@Test
	void ein_fehlgeschlagenes_Speichern_haelt_die_uebrigen_nicht_auf() {
		Event broken = eventOn(plan, "Kaputt");
		Event fine = eventOn(plan, "Prunksitzung");
		Consumer<Event> failingForBroken = event -> {
			if (event == broken) {
				throw new IllegalArgumentException("Speichern fehlgeschlagen");
			}
			saved.add(event);
		};

		List<Event> result = PlanFreezer.freeze(List.of(broken, fine), usesPlan(), hallLookup, tracker, failingForBroken);

		assertEquals(List.of(fine), saved);
		assertEquals(List.of(fine), result);
	}
}
