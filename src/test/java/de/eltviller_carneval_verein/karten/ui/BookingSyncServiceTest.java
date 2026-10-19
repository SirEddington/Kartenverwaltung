package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;

class BookingSyncServiceTest {

	private ChangeTracker<Event> tracker;
	private Hall hall;
	private SeatingPlan plan;
	private Event event;
	private Presentation presentation;

	@BeforeEach
	void setUp() {
		tracker = new ChangeTracker<>(Event::getId);

		hall = new Hall();
		plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 2, 2);

		event = new Event();
		event.setName("Prunksitzung");
		presentation = event.addPresentation("Samstag");
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId(plan.getId());
	}

	private void prepare() {
		BookingSyncService.prepare(event, id -> Map.of(hall.getId(), hall).get(id), tracker);
	}

	@Test
	void prepare_gleicht_die_Buchungen_mit_der_Bestuhlung_der_Halle_ab() {
		prepare();

		assertEquals(4, presentation.getBookings().size());
		assertFalse(presentation.isPlanMissing());
	}

	@Test
	void prepare_ohne_gefundene_Halle_wirft_nicht_und_laesst_die_Buchungen() {
		Booking booking = presentation.addBooking();
		booking.setLastName("Müller");
		Booking empty = presentation.addBooking();
		presentation.setHallId("gibt-es-nicht");

		assertDoesNotThrow(() -> BookingSyncService.prepare(event, id -> null, tracker));

		assertTrue(presentation.isPlanMissing());
		assertEquals(2, presentation.getBookings().size());
		assertTrue(presentation.getBookings().contains(booking));
		assertTrue(presentation.getBookings().contains(empty));
		assertFalse(booking.isUnassigned());
	}

	@Test
	void prepare_ohne_Hallen_Id_wirft_nicht() {
		presentation.setHallId(null);
		Booking booking = presentation.addBooking();
		booking.setLastName("Müller");

		assertDoesNotThrow(() -> BookingSyncService.prepare(event, id -> null, tracker));

		assertTrue(presentation.isPlanMissing());
		assertEquals(1, presentation.getBookings().size());
	}

	@Test
	void prepare_mit_geloeschter_Bestuhlung_friert_ein() {
		Booking booking = presentation.addBooking();
		booking.setLastName("Müller");
		hall.getSeatingPlans().remove(plan);

		prepare();

		assertTrue(presentation.isPlanMissing());
		assertEquals(1, presentation.getBookings().size());
	}

	@Test
	void prepare_ohne_Bestuhlung_an_der_Vorstellung_laesst_Buchungen_mit_Daten_ohne_Platz() {
		presentation.setSeatingPlanId(null);
		Booking booking = presentation.addBooking();
		booking.setLastName("Müller");
		presentation.addBooking();

		prepare();

		assertFalse(presentation.isPlanMissing());
		assertEquals(1, presentation.getBookings().size());
		assertTrue(presentation.getBookings().contains(booking));
		assertTrue(booking.isUnassigned());
	}

	@Test
	void prepare_ueberspringt_fehlende_Listen() {
		Event withoutPresentations = new Event();
		withoutPresentations.setPresentations(null);
		presentation.setBookings(null);

		assertDoesNotThrow(() -> BookingSyncService.prepare(withoutPresentations, id -> null, tracker));
		assertDoesNotThrow(() -> prepare());
	}

	@Test
	void sauberes_Event_bleibt_nach_prepare_sauber() {
		tracker.track(event);
		assertFalse(tracker.isDirty(event));

		prepare();

		assertEquals(4, presentation.getBookings().size());
		assertFalse(tracker.check(event));
		assertFalse(tracker.isDirty(event));
	}

	@Test
	void nicht_verfolgtes_Event_ist_nach_prepare_sauber_verfolgt() {
		assertFalse(tracker.anyDirty());

		prepare();

		assertNotNull(tracker.baseline(event));
		assertFalse(tracker.check(event));
		assertFalse(tracker.isDirty(event));
	}

	@Test
	void geaendertes_Event_bleibt_nach_prepare_geaendert() {
		tracker.track(event);
		presentation.setDescription("Geändert");
		tracker.check(event);
		assertTrue(tracker.isDirty(event));

		prepare();

		tracker.check(event);
		assertTrue(tracker.isDirty(event));
		assertEquals(4, presentation.getBookings().size());
	}

	@Test
	void noch_nicht_verglichene_Aenderung_geht_nicht_verloren() {
		tracker.track(event);
		presentation.setDescription("Geändert, aber noch nicht geprüft");

		prepare();

		tracker.check(event);
		assertTrue(tracker.isDirty(event));
	}
}
