package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

class PlanImpactTest {

	private static final String STAYS = "\nSie bleiben erhalten und stehen danach unter „Ohne Platz“, bis ihnen ein neuer Platz zugewiesen wird.";

	private final SeatingPlan plan = new Hall().addSeatingPlan();
	private final TableRow row = plan.addRow();
	private final Table table = plan.addTable(row);
	private final Seat seat1 = table.addSeat();
	private final Seat seat2 = table.addSeat();
	private final Seat seat3 = table.addSeat();

	private final Event event = event("Kampagne 2027");

	private static Event event(String name) {
		Event event = new Event();
		event.setName(name);
		return event;
	}

	private Presentation presentation(Event owner, String name, String planId) {
		Presentation presentation = owner.addPresentation(name);
		presentation.setSeatingPlanId(planId);
		return presentation;
	}

	private static Booking booked(Presentation presentation, Seat seat) {
		Booking booking = presentation.addBooking();
		booking.setSeatId(seat.getId());
		booking.setLastName("Meier");
		return booking;
	}

	private static Booking empty(Presentation presentation, Seat seat) {
		Booking booking = presentation.addBooking();
		booking.setSeatId(seat.getId());
		return booking;
	}

	private PlanImpact impactOn(Set<String> seatIds, List<Event> events) {
		return PlanImpact.of(plan.getId(), seatIds, events);
	}

	// --- Zählen -->

	@Test
	void noEventsNoImpact() {
		PlanImpact impact = impactOn(Set.of(seat1.getId()), List.of());

		assertTrue(impact.isEmpty());
		assertEquals(0, impact.total());
		assertEquals("", impact.deleteNote());
		assertNull(impact.renumberMessage());
	}

	@Test
	void nullListsAreNoImpact() {
		assertTrue(PlanImpact.of(plan.getId(), null, List.of(event)).isEmpty());
		assertTrue(PlanImpact.of(plan.getId(), Set.of(seat1.getId()), null).isEmpty());
		assertTrue(PlanImpact.of(null, Set.of(seat1.getId()), List.of(event)).isEmpty());

		Event withoutPresentations = event("Leer");
		withoutPresentations.setPresentations(null);
		Presentation withoutBookings = presentation(event, "Prunksitzung", plan.getId());
		withoutBookings.setBookings(null);
		List<Event> events = new ArrayList<>();
		events.add(null);
		events.add(withoutPresentations);
		events.add(event);

		assertTrue(impactOn(Set.of(seat1.getId()), events).isEmpty());
	}

	@Test
	void presentationsOfOtherPlansAreIgnored() {
		Presentation other = presentation(event, "Prunksitzung", "andere-bestuhlung");
		booked(other, seat1);

		assertTrue(impactOn(Set.of(seat1.getId()), List.of(event)).isEmpty());
	}

	@Test
	void bookingsWithoutDataAreIgnored() {
		Presentation presentation = presentation(event, "Prunksitzung", plan.getId());
		empty(presentation, seat1);
		// Ein bloßer Einzelpreis ist keine Datenbuchung
		empty(presentation, seat2).setPrice(2500);

		assertTrue(impactOn(Set.of(seat1.getId(), seat2.getId()), List.of(event)).isEmpty());
	}

	@Test
	void bookingsWithDataOnOtherSeatsAreIgnored() {
		Presentation presentation = presentation(event, "Prunksitzung", plan.getId());
		booked(presentation, seat2);
		booked(presentation, seat3);

		assertTrue(impactOn(Set.of(seat1.getId()), List.of(event)).isEmpty());
	}

	@Test
	void countsBookingsWithDataPerPresentation() {
		Presentation first = presentation(event, "Prunksitzung", plan.getId());
		booked(first, seat1);
		empty(first, seat2);
		first.addBooking().setPaymentStatus(PaymentStatus.CASH); // Daten, aber auf keinem der Sitze
		Booking paid = empty(first, seat3);
		paid.setPaymentStatus(PaymentStatus.CARD);
		presentation(event, "Kindersitzung", plan.getId()); // nutzt die Bestuhlung, aber ohne betroffene Buchung
		Event later = event("Kampagne 2028");
		Presentation second = presentation(later, "Fremdensitzung", plan.getId());
		booked(second, seat2);

		PlanImpact impact = impactOn(Set.of(seat1.getId(), seat2.getId(), seat3.getId()), List.of(event, later));

		assertEquals(List.of(new PlanImpact.Entry("Kampagne 2027", "Prunksitzung", 2), new PlanImpact.Entry("Kampagne 2028", "Fremdensitzung", 1)),
				impact.entries());
		assertEquals(3, impact.total());
	}

	@Test
	void emptyNamesGetThePlaceholder() {
		Event unnamed = event(" ");
		booked(presentation(unnamed, null, plan.getId()), seat1);

		assertEquals(List.of(new PlanImpact.Entry("(ohne Name)", "(ohne Name)", 1)), impactOn(Set.of(seat1.getId()), List.of(unnamed)).entries());
	}
	// <-- Zählen

	// --- Texte -->

	@Test
	void deleteNoteForOneBooking() {
		booked(presentation(event, "Prunksitzung", plan.getId()), seat1);

		assertEquals("\n\nBetroffen: 1 Buchung mit Daten in Vorstellung Prunksitzung (Event Kampagne 2027)."
				+ "\nSie bleibt erhalten und steht danach unter „Ohne Platz“, bis ihr ein neuer Platz zugewiesen wird.",
				impactOn(Set.of(seat1.getId()), List.of(event)).deleteNote());
	}

	@Test
	void deleteNoteForSeveralBookingsInOnePresentation() {
		Presentation presentation = presentation(event, "Prunksitzung", plan.getId());
		booked(presentation, seat1);
		booked(presentation, seat2);
		booked(presentation, seat3);

		assertEquals("\n\nBetroffen: 3 Buchungen mit Daten in Vorstellung Prunksitzung (Event Kampagne 2027)." + STAYS,
				impactOn(Set.of(seat1.getId(), seat2.getId(), seat3.getId()), List.of(event)).deleteNote());
	}

	@Test
	void deleteNoteForSeveralPresentationsHasOneLineEach() {
		booked(presentation(event, "Prunksitzung", plan.getId()), seat1);
		Presentation second = presentation(event, "Kindersitzung", plan.getId());
		booked(second, seat1);
		booked(second, seat2);

		assertEquals("\n\nBetroffen:\n- 1 Buchung mit Daten in Vorstellung Prunksitzung (Event Kampagne 2027)"
				+ "\n- 2 Buchungen mit Daten in Vorstellung Kindersitzung (Event Kampagne 2027)" + STAYS,
				impactOn(Set.of(seat1.getId(), seat2.getId()), List.of(event)).deleteNote());
	}

	@Test
	void renumberMessageForOneBooking() {
		booked(presentation(event, "Prunksitzung", plan.getId()), seat1);

		assertEquals("Nummern neu vergeben: 1 Buchung mit Daten in Vorstellung Prunksitzung (Event Kampagne 2027) folgt ihrem Tisch"
				+ " und trägt danach eine andere Tischnummer.", impactOn(Set.of(seat1.getId()), List.of(event)).renumberMessage());
	}

	@Test
	void renumberMessageForSeveralBookingsInOnePresentation() {
		Presentation presentation = presentation(event, "Prunksitzung", plan.getId());
		booked(presentation, seat1);
		booked(presentation, seat2);

		assertEquals("Nummern neu vergeben: 2 Buchungen mit Daten in Vorstellung Prunksitzung (Event Kampagne 2027) folgen ihren Tischen"
				+ " und tragen danach andere Tischnummern.", impactOn(Set.of(seat1.getId(), seat2.getId()), List.of(event)).renumberMessage());
	}

	@Test
	void renumberMessageForSeveralPresentationsHasOneLineEach() {
		booked(presentation(event, "Prunksitzung", plan.getId()), seat1);
		booked(presentation(event, "Kindersitzung", plan.getId()), seat1);

		assertEquals("Nummern neu vergeben: Diese Buchungen folgen ihren Tischen und tragen danach andere Tischnummern:"
				+ "\n- 1 Buchung mit Daten in Vorstellung Prunksitzung (Event Kampagne 2027)"
				+ "\n- 1 Buchung mit Daten in Vorstellung Kindersitzung (Event Kampagne 2027)",
				impactOn(Set.of(seat1.getId()), List.of(event)).renumberMessage());
	}
	// <-- Texte

	// --- Betroffene Sitze -->

	@Test
	void seatIdsOfTables() {
		Table other = plan.addTable(row);
		Seat otherSeat = other.addSeat();

		assertEquals(Set.of(seat1.getId(), seat2.getId(), seat3.getId(), otherSeat.getId()), PlanImpact.seatIds(List.of(table, other)));
		assertEquals(Set.of(), PlanImpact.seatIds(null));
	}

	@Test
	void renumberWithoutChangesAffectsNoSeat() {
		plan.addTable(row).addSeat();

		assertEquals(Set.of(), PlanImpact.seatIdsRenumbered(plan, row));
	}

	@Test
	void renumberAffectsOnlyTablesWithANewNumber() {
		Table gap = plan.addTable(row);
		gap.setTableNumber(5);
		Seat moved = gap.addSeat();

		assertEquals(Set.of(moved.getId()), PlanImpact.seatIdsRenumbered(plan, row));
		// Das Modell bleibt unverändert
		assertEquals(1, table.getTableNumber());
		assertEquals(5, gap.getTableNumber());
	}

	@Test
	void renumberOfAnUnlabeledRowCoversAllUnlabeledRows() {
		table.setTableNumber(2); // Reihe 1: Tisch 2 wird 1
		TableRow second = plan.addRow();
		Table later = plan.addTable(second);
		later.setTableNumber(7); // Reihe 2 ohne Buchstaben: Tisch 7 wird 2
		Seat laterSeat = later.addSeat();
		TableRow labeled = plan.addRow();
		labeled.setLabel("A");
		Table own = plan.addTable(labeled);
		own.setTableNumber(9); // eigener Geltungsbereich, bleibt
		own.addSeat();

		assertEquals(Set.of(seat1.getId(), seat2.getId(), seat3.getId(), laterSeat.getId()), PlanImpact.seatIdsRenumbered(plan, second));
	}

	@Test
	void renumberOfAnUnknownRowAffectsNoSeat() {
		table.setTableNumber(4);

		assertEquals(Set.of(), PlanImpact.seatIdsRenumbered(plan, new TableRow()));
		assertEquals(Set.of(), PlanImpact.seatIdsRenumbered(null, row));
	}
	// <-- Betroffene Sitze
}
