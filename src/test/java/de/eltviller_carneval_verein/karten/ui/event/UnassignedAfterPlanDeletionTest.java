package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.BookingSyncService;

/**
 * Eine Buchung, die "Ohne Platz" ist, bleibt es auch nach dem Speichern und Laden, wenn es ihre Bestuhlung danach
 * nicht mehr gibt (eingefrorene Vorstellung): Sie erscheint nicht wieder unter ihrem früheren Tisch.
 */
class UnassignedAfterPlanDeletionTest {

	private static Table table(SeatingPlan plan, int tableNumber) {
		return plan.getTables().stream().filter(t -> t.getTableNumber() == tableNumber).findFirst().orElseThrow();
	}

	@Test
	void ohne_Platz_bleibt_nach_Speichern_und_Loeschen_der_Bestuhlung() throws IOException {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		plan.fillRow(row, 3, 2);

		Event event = new Event();
		event.setName("Prunksitzung");
		Presentation presentation = event.addPresentation("Samstag");
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId(plan.getId());
		ChangeTracker<Event> tracker = new ChangeTracker<>(Event::getId);
		BookingSyncService.prepare(event, id -> Map.of(hall.getId(), hall).get(id), tracker);

		Seat seat = table(plan, 3).getSeats().stream().filter(s -> s.getSeatNumber() == 1).findFirst().orElseThrow();
		Booking paid = presentation.getBookings().stream().filter(b -> seat.getId().equals(b.getSeatId())).findFirst().orElseThrow();
		paid.setLastName("Müller");
		paid.setPaymentStatus(PaymentStatus.CASH);

		// Tisch 3 verschwindet aus der Bestuhlung: Der Abgleich macht die Buchung "Ohne Platz"
		plan.deleteTable(table(plan, 3));
		BookingSyncService.prepare(event, id -> Map.of(hall.getId(), hall).get(id), tracker);
		assertTrue(paid.isUnassigned());

		// Speichern und Laden, danach fehlt die Bestuhlung (alte seatingPlanId, Bestuhlung nicht gefunden)
		ObjectMapper mapper = JsonMapperFactory.create();
		Event loaded = mapper.readValue(mapper.writeValueAsString(event), Event.class);
		Presentation loadedPresentation = loaded.getPresentations().get(0);
		assertEquals(plan.getId(), loadedPresentation.getSeatingPlanId());
		BookingSync.sync(loadedPresentation, null);

		assertTrue(loadedPresentation.isPlanMissing());
		Booking loadedPaid = loadedPresentation.getBookings().stream().filter(b -> "Müller".equals(b.getLastName())).findFirst().orElseThrow();
		assertTrue(loadedPaid.isUnassigned());
		assertEquals(PaymentStatus.CASH, loadedPaid.getPaymentStatus());
		assertTrue(BookingTree.unassigned(loadedPresentation).contains(loadedPaid));
		assertFalse(BookingTree.groups(loadedPresentation).stream().anyMatch(group -> group.bookings().contains(loadedPaid)));
		assertEquals(2, BookingTree.groups(loadedPresentation).size(), "nur Tisch 1 und 2");
	}
}
