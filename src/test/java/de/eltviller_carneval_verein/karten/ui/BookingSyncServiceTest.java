package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Syncs;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.Version;
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
		BookingSyncService.prepare(event, id -> Map.of(hall.getId(), hall).get(id), tracker, p -> Set.of());
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

		assertDoesNotThrow(() -> BookingSyncService.prepare(event, id -> null, tracker, p -> Set.of()));

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

		assertDoesNotThrow(() -> BookingSyncService.prepare(event, id -> null, tracker, p -> Set.of()));

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

		assertDoesNotThrow(() -> BookingSyncService.prepare(withoutPresentations, id -> null, tracker, p -> Set.of()));
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

	/**
	 * KARV-135: Ein neueres Programm sperrt die Buchung auf dem ersten Sitz (unbekannter Zahlstatus), im Modell steht eine
	 * Buchung ohne Platz mit Reihe, Tisch und Nummer genau dieses Sitzes. Das Öffnen (etwa der Abrechnung) setzt sie nicht
	 * auf den gesperrten Sitz: Das Event bleibt sauber, Speichern gelingt ohne zu schreiben, und der Platzhalter des
	 * gesperrten Sitzes behält beim nächsten Öffnen seine Kennung.
	 */
	@Test
	void openingAccountingWithBookingWithoutSeatMatchingLockedSeatKeepsEventClean(@TempDir Path appData)
			throws IOException {
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		hall.setName("Stadthalle");
		TestRepositories.halls(sync).saveHall(hall);
		Syncs.sync(presentation, plan);
		Seat first = plan.getSeats().get(0);
		Booking sold = bookingOn(presentation, first);
		sold.setLastName("Müller");
		Booking lost = presentation.addBooking();
		lost.setLastName("Schulz");
		lost.setRowLabel(sold.getRowLabel());
		lost.setTableNumber(sold.getTableNumber());
		lost.setSeatNumber(sold.getSeatNumber());
		lost.setUnassigned(true);
		TestRepositories.events(sync).saveEvent(event);
		UnitKey lockedUnit = UnitKey.booking(event.getId(), presentation.getId(), first.getId());
		new ForeignVersions(sync, appData).write(lockedUnit, "Fremd", payload -> payload.put("paymentStatus", "x-test-voucher"));

		// ein anderer Rechner startet neu und öffnet das Event
		StoreEventRepository events = TestRepositories.events(sync);
		Event loaded = events.loadEvents().get(0);
		Presentation shown = loaded.getPresentations().get(0);
		ChangeTracker<Event> opened = new ChangeTracker<>(Event::getId);
		BookingSyncService.prepare(loaded, id -> Map.of(hall.getId(), hall).get(id), opened, events);
		Booking placeholder = bookingOn(shown, first);
		String placeholderId = placeholder.getId();
		Booking shownLost = shown.getBookings().stream().filter(b -> "Schulz".equals(b.getLastName())).findFirst()
				.orElseThrow();
		long files = new ForeignVersions(sync, appData).files();

		BookingSyncService.prepare(loaded, id -> Map.of(hall.getId(), hall).get(id), opened, events);

		assertFalse(placeholder.hasData(), "auf dem gesperrten Sitz liegt nur der Platzhalter");
		assertSame(placeholder, bookingOn(shown, first));
		assertEquals(placeholderId, placeholder.getId(), "der Platzhalter behält seine Kennung");
		assertTrue(shownLost.isUnassigned(), "Schulz bleibt ohne Platz");
		assertNull(shownLost.getSeatId());
		assertFalse(opened.check(loaded), "keine Änderung");
		assertFalse(opened.isDirty(loaded));
		assertEquals(Set.of(), events.locallyChangedUnits());
		assertDoesNotThrow(() -> events.saveEvent(loaded));
		assertEquals(files, new ForeignVersions(sync, appData).files(), "Speichern schreibt nichts");
		assertTrue(sync.unitState(lockedUnit).contentLocked(), "die gesperrte Buchung bleibt unberührt");
	}

	/**
	 * Die gesperrten Sitze ergeben sich aus der Vorstellung, die abgeglichen wird, nicht aus dem Event im Cache des
	 * Repositorys: Müllers Buchung ist beschädigt, ihr älterer Stand wird in diesem Event angezeigt und behält ihren Sitz,
	 * auch wenn das Event im Cache den Sitz leer zeigt (KARV-135). Sonst käme Müller zu den Resten, und jedes Speichern
	 * scheiterte mit SYS-008.
	 */
	@Test
	void lockedSeatsComeFromThePreparedPresentation(@TempDir Path appData) throws IOException {
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		hall.setName("Stadthalle");
		TestRepositories.halls(sync).saveHall(hall);
		Syncs.sync(presentation, plan);
		Seat first = plan.getSeats().get(0);
		Booking sold = bookingOn(presentation, first);
		sold.setLastName("Müller");
		TestRepositories.events(sync).saveEvent(event);
		UnitKey unit = UnitKey.booking(event.getId(), presentation.getId(), first.getId());
		Version damaged = new ForeignVersions(sync, appData).write(unit, "Fremd", payload -> payload.put("lastName", "Neu"));
		Files.writeString(appData.resolve("data/" + unit.relativePath() + "/" + damaged.id() + ".json"), "kaputt");
		assertEquals(UnitState.Status.LOCKED_DAMAGED, sync.unitState(unit).status());
		StoreEventRepository events = TestRepositories.events(sync);
		// das Event im Cache zeigt den Sitz leer, das abgeglichene Event zeigt Müller
		bookingOn(events.findEvent(event.getId()).getPresentations().get(0), first).setLastName(null);

		BookingSyncService.prepare(event, id -> Map.of(hall.getId(), hall).get(id), tracker, events);

		assertSame(sold, bookingOn(presentation, first), "Müller bleibt auf seinem Sitz");
		assertFalse(sold.isUnassigned());
		assertEquals("Müller", sold.getLastName());
	}

	/** Die Buchung auf dem Sitz (auch der leere Platzhalter). */
	private static Booking bookingOn(Presentation presentation, Seat seat) {
		return presentation.getBookings().stream().filter(b -> seat.getId().equals(b.getSeatId())).findFirst()
				.orElseThrow();
	}
}
