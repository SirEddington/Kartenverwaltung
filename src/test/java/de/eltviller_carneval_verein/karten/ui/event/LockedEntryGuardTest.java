package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;

/** Löschen einer Vorstellung mit gesperrten Einträgen darunter (KARV-135), ohne Oberfläche. */
class LockedEntryGuardTest {

	@TempDir
	Path appData;

	private SyncService sync;
	private StoreEventRepository repository;
	private Event event;
	private Presentation friday;
	private Presentation saturday;
	private final List<String> rejections = new ArrayList<>();

	/** Event "Prunksitzung" mit "Freitag" (Müller ohne Platz) und "Samstag" (Meier ohne Platz), gespeichert. */
	@BeforeEach
	void setUp() {
		sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		repository = TestRepositories.events(sync);
		event = new Event();
		event.setName("Prunksitzung");
		friday = event.addPresentation("Freitag");
		saturday = event.addPresentation("Samstag");
		sold(friday, "Müller");
		sold(saturday, "Meier");
		repository.saveEvent(event);
	}

	private static Booking sold(Presentation presentation, String lastName) {
		Booking booking = presentation.addBooking();
		booking.setUnassigned(true);
		booking.setLastName(lastName);
		return booking;
	}

	/** Ein neueres Programm setzt einen unbekannten Zahlstatus auf die Buchung der Vorstellung. */
	private void lockBookingOf(Presentation presentation) throws IOException {
		Booking booking = presentation.getBookings().get(0);
		UnitKey unit = UnitKey.unassignedBooking(event.getId(), presentation.getId(), booking.getId());
		new ForeignVersions(sync, appData).write(unit, "Fremd", payload -> payload.put("paymentStatus", "x-test-voucher"));
		assertTrue(sync.unitState(unit).contentLocked());
	}

	@Test
	void deletingPresentationWithLockedBookingIsRejectedBeforeModelChange() throws IOException {
		lockBookingOf(friday);

		boolean deleted = LockedEntryGuard.deletePresentation(friday, repository, rejections::add);

		assertFalse(deleted);
		assertEquals(List.of(friday, saturday), event.getPresentations(), "das Modell bleibt unverändert");
		assertEquals(List.of(StoreMessages.lockedBelow("Freitag", UnitState.Status.LOCKED_FORMAT)), rejections);
	}

	@Test
	void presentationWithoutLockedEntriesIsDeleted() throws IOException {
		lockBookingOf(friday);

		boolean deleted = LockedEntryGuard.deletePresentation(saturday, repository, rejections::add);

		assertTrue(deleted);
		assertEquals(List.of(friday), event.getPresentations());
		assertEquals(List.of(), rejections);
	}

	/**
	 * Der Hallenwechsel im Event-Editor: Ist die einzige verkaufte Buchung gesperrt, steht im Modell keine Buchung mit
	 * Daten, der Wechsel liefe ohne Rückfrage. Er wird trotzdem abgelehnt; dieselbe Halle ist kein Wechsel.
	 */
	@Test
	void hallChangeWithOnlyALockedBookingIsRejected() throws IOException {
		friday.setHallId("halle-1");
		repository.saveEvent(event);
		lockBookingOf(friday);

		assertTrue(LockedEntryGuard.rejectsHallChange(friday, "halle-2", repository, rejections::add));
		assertTrue(LockedEntryGuard.rejectsHallChange(friday, null, repository, rejections::add), "auch ohne Halle");
		assertFalse(LockedEntryGuard.rejectsHallChange(friday, "halle-1", repository, rejections::add), "kein Wechsel");
		assertFalse(LockedEntryGuard.rejectsHallChange(saturday, "halle-2", repository, rejections::add));

		String message = StoreMessages.lockedBelow("Freitag", UnitState.Status.LOCKED_FORMAT);
		assertEquals(List.of(message, message), rejections);
		assertEquals("halle-1", friday.getHallId(), "das Modell bleibt unverändert");
	}

	/** Eine nie gespeicherte Vorstellung hat keine Einheiten im Versionsspeicher, also nichts Gesperrtes. */
	@Test
	void neverSavedPresentationIsDeleted() {
		Presentation added = event.addPresentation("Sonntag");

		assertTrue(LockedEntryGuard.deletePresentation(added, repository, rejections::add));

		assertEquals(List.of(friday, saturday), event.getPresentations());
		assertEquals(List.of(), rejections);
	}
}
