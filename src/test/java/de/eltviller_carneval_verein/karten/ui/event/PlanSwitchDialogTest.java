package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync.Report;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Syncs;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;

class PlanSwitchDialogTest {

	/** Darf nicht gefragt werden: Der Test scheitert, wenn der Dialog erscheinen würde. */
	private static final Predicate<Report> NEVER_ASKED = report -> fail("Dialog erwartet keinen Aufruf, Report " + report);

	/** Bestuhlung mit Reihe "A" und {@code tableCount} Tischen zu je {@code seatsPerTable} Sitzen. */
	private static SeatingPlan plan(Hall hall, int tableCount, int seatsPerTable) {
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, tableCount, seatsPerTable);
		return plan;
	}

	/** Vorstellung auf {@code plan} mit einer leeren Buchung je Sitz. */
	private static Presentation presentationOn(SeatingPlan plan) {
		Presentation presentation = new Presentation();
		Syncs.switchPlan(presentation, plan);
		return presentation;
	}

	private static Booking bookingAt(Presentation presentation, int tableNumber, int seatNumber) {
		return presentation.getBookings().stream().filter(b -> b.getTableNumber() == tableNumber && b.getSeatNumber() == seatNumber)
				.findFirst().orElseThrow();
	}

	@Test
	void summary_nennt_uebernommen_ohne_Platz_und_leere_entfernt() {
		assertEquals("12 Buchungen übernommen, 3 ohne Platz, 5 leere entfernt", PlanSwitchDialog.summary(new Report(12, 0, 5, 3)));
	}

	@Test
	void summary_spricht_eine_Buchung_in_der_Einzahl_an() {
		assertEquals("1 Buchung übernommen, 0 ohne Platz, 1 leere entfernt", PlanSwitchDialog.summary(new Report(1, 7, 1, 0)));
	}

	@Test
	void gleiche_Bestuhlung_liefert_true_ohne_Dialog_und_aendert_nichts() {
		Hall hall = new Hall();
		SeatingPlan plan = plan(hall, 2, 2);
		Presentation presentation = presentationOn(plan);
		bookingAt(presentation, 1, 1).setLastName("Müller");
		List<Booking> before = new ArrayList<>(presentation.getBookings());

		assertTrue(PlanSwitchDialog.confirm(presentation, plan, Set::of, NEVER_ASKED));

		assertEquals(plan.getId(), presentation.getSeatingPlanId());
		assertEquals(before, presentation.getBookings());
	}

	@Test
	void erste_Zuordnung_ohne_Buchungen_kommt_ohne_Dialog_aus() {
		Hall hall = new Hall();
		SeatingPlan plan = plan(hall, 2, 3);
		Presentation presentation = new Presentation();
		presentation.setHallId(hall.getId());

		assertTrue(PlanSwitchDialog.confirm(presentation, plan, Set::of, NEVER_ASKED));

		assertEquals(plan.getId(), presentation.getSeatingPlanId());
		assertEquals(6, presentation.getBookings().size());
	}

	@Test
	void wechsel_auf_eine_gleich_nummerierte_Bestuhlung_ohne_Daten_kommt_ohne_Dialog_aus() {
		Hall hall = new Hall();
		SeatingPlan oldPlan = plan(hall, 2, 2);
		SeatingPlan newPlan = plan(hall, 2, 2);
		Presentation presentation = presentationOn(oldPlan);

		assertTrue(PlanSwitchDialog.confirm(presentation, newPlan, Set::of, NEVER_ASKED));

		assertEquals(newPlan.getId(), presentation.getSeatingPlanId());
		assertEquals(4, presentation.getBookings().size());
	}

	@Test
	void dialog_zaehlt_als_uebernommen_nur_Buchungen_mit_Daten() {
		Hall hall = new Hall();
		SeatingPlan oldPlan = plan(hall, 3, 2);
		SeatingPlan newPlan = plan(hall, 2, 2);
		Presentation presentation = presentationOn(oldPlan);
		bookingAt(presentation, 1, 1).setLastName("Müller"); // findet Tisch A1, Sitz 1
		bookingAt(presentation, 3, 2).setLastName("Meier"); // Tisch A3 fehlt: ohne Platz

		Report shown = PlanSwitchDialog.dialogReport(presentation, Syncs.preview(presentation, newPlan));

		// 6 alte Buchungen: 3 leere getroffen (zählen nicht), Müller getroffen, Meier ohne Platz, Tisch A3 Sitz 1 leer entfernt
		assertEquals(new Report(1, 0, 1, 1), shown);
	}

	@Test
	void abbrechen_laesst_Bestuhlung_und_Buchungen_unveraendert() {
		Hall hall = new Hall();
		SeatingPlan oldPlan = plan(hall, 3, 2);
		SeatingPlan newPlan = plan(hall, 2, 2);
		Presentation presentation = presentationOn(oldPlan);
		Booking meier = bookingAt(presentation, 3, 2);
		meier.setLastName("Meier");
		String meierSeatId = meier.getSeatId();
		List<Booking> before = new ArrayList<>(presentation.getBookings());
		List<Report> asked = new ArrayList<>();

		boolean result = PlanSwitchDialog.confirm(presentation, newPlan, Set::of, report -> {
			asked.add(report);
			return false;
		});

		assertFalse(result);
		assertEquals(List.of(new Report(0, 0, 1, 1)), asked);
		assertEquals(oldPlan.getId(), presentation.getSeatingPlanId());
		assertEquals(before, presentation.getBookings());
		assertEquals(meierSeatId, meier.getSeatId());
		assertFalse(meier.isUnassigned());
	}

	@Test
	void bestaetigen_wendet_den_Wechsel_an() {
		Hall hall = new Hall();
		SeatingPlan oldPlan = plan(hall, 3, 2);
		SeatingPlan newPlan = plan(hall, 2, 2);
		Presentation presentation = presentationOn(oldPlan);
		Booking mueller = bookingAt(presentation, 1, 1);
		mueller.setLastName("Müller");
		Booking meier = bookingAt(presentation, 3, 2);
		meier.setLastName("Meier");

		assertTrue(PlanSwitchDialog.confirm(presentation, newPlan, Set::of, report -> true));

		assertEquals(newPlan.getId(), presentation.getSeatingPlanId());
		assertSame(newPlan.findSeat(mueller.getSeatId()).getParent(), newPlan.getTables().get(0));
		assertTrue(meier.isUnassigned());
		// 4 Sitze der neuen Bestuhlung plus Meier ohne Platz
		assertEquals(5, presentation.getBookings().size());
	}

	@Test
	void ohne_neue_Bestuhlung_bleiben_Buchungen_mit_Daten_ohne_Platz() {
		Hall hall = new Hall();
		SeatingPlan oldPlan = plan(hall, 1, 2);
		Presentation presentation = presentationOn(oldPlan);
		Booking mueller = bookingAt(presentation, 1, 1);
		mueller.setLastName("Müller");
		String seatId = mueller.getSeatId();

		assertTrue(PlanSwitchDialog.confirm(presentation, null, Set::of, NEVER_ASKED));

		assertNull(presentation.getSeatingPlanId());
		// die leere Buchung entfällt, Müller bleibt ohne Platz mit seiner bisherigen seatId
		assertEquals(List.of(mueller), presentation.getBookings());
		assertTrue(mueller.isUnassigned());
		assertEquals(seatId, mueller.getSeatId());
	}

	// Gesperrte Einträge (KARV-135) -->

	@TempDir
	Path appData;

	/** Der gestartete Dienst des Tests (einzelner Rechner). */
	private SyncService started() {
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		return sync;
	}

	/**
	 * Halle mit zwei gleich gebauten Bestuhlungen; Event "Prunksitzung" mit der Vorstellung "Freitag" auf der ersten,
	 * "Müller" auf Tisch 1, Sitz 1, gespeichert. Danach setzt ein neueres Programm einen unbekannten Zahlstatus auf
	 * Müllers Buchung.
	 *
	 * @return die Halle; das Event steht im Speicher
	 */
	private Hall savedEventWithLockedBooking(SyncService sync) throws IOException {
		Hall hall = new Hall();
		hall.setName("Stadthalle");
		SeatingPlan oldPlan = plan(hall, 2, 2);
		plan(hall, 2, 2);
		TestRepositories.halls(sync).saveHall(hall);
		Event event = new Event();
		event.setName("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		friday.setHallId(hall.getId());
		Syncs.switchPlan(friday, oldPlan);
		Booking mueller = bookingAt(friday, 1, 1);
		mueller.setLastName("Müller");
		TestRepositories.events(sync).saveEvent(event);
		UnitKey unit = UnitKey.booking(event.getId(), friday.getId(), mueller.getSeatId());
		new ForeignVersions(sync, appData).write(unit, "Fremd", payload -> payload.put("paymentStatus", "x-test-voucher"));
		assertTrue(sync.unitState(unit).contentLocked());
		return hall;
	}

	/** Wechselt mit dem Repository; die Rückfrage darf nicht erscheinen, Ablehnungen landen in {@code rejections}. */
	private static boolean confirmWith(StoreEventRepository repository, Presentation presentation, SeatingPlan newPlan,
			List<String> rejections) {
		return PlanSwitchDialog.confirm(presentation, newPlan, repository, NEVER_ASKED, rejections::add);
	}

	/**
	 * Eine gesperrte Buchung (nur im Index aktuell, das Modell zeigt den älteren Stand) verbietet den Wechsel der
	 * Bestuhlung: Sonst bliebe sie auf dem alten Sitz, und der gleichnamige Sitz der neuen Bestuhlung wäre verkäuflich.
	 */
	@Test
	void planSwitchIsRejectedBeforeTheModelChanges() throws IOException {
		SyncService sync = started();
		StoreEventRepository repository = TestRepositories.events(sync);
		Hall hall = savedEventWithLockedBooking(sync);
		Presentation friday = repository.loadEvents().get(0).getPresentations().get(0);
		String planBefore = friday.getSeatingPlanId();
		List<Booking> before = new ArrayList<>(friday.getBookings());
		List<String> rejections = new ArrayList<>();

		boolean switched = confirmWith(repository, friday, hall.getSeatingPlans().get(1), rejections);
		boolean removed = confirmWith(repository, friday, null, rejections);

		assertFalse(switched);
		assertFalse(removed, "auch das Entfernen der Bestuhlung ist ein Wechsel");
		assertEquals(planBefore, friday.getSeatingPlanId());
		assertEquals(before, friday.getBookings());
		String message = StoreMessages.lockedBelow("Freitag", UnitState.Status.LOCKED_FORMAT);
		assertEquals(List.of(message, message), rejections);
		assertTrue(confirmWith(repository, friday, hall.getSeatingPlans().get(0), rejections), "kein Wechsel, nichts zu prüfen");
		assertEquals(2, rejections.size());
	}

	/**
	 * Direkt nach dem Start, bevor „Gesperrte Einträge“ (und {@code LockedSeats}) gefüllt sind: Geprüft wird der Index, die
	 * Ablehnung greift schon.
	 */
	@Test
	void planSwitchRejectedRightAfterStartBeforeLockedListFilled() throws IOException {
		Hall hall = savedEventWithLockedBooking(started());
		SyncService restarted = started();
		StoreEventRepository repository = TestRepositories.events(restarted);
		Presentation friday = repository.loadEvents().get(0).getPresentations().get(0);
		String planBefore = friday.getSeatingPlanId();
		List<String> rejections = new ArrayList<>();

		assertFalse(confirmWith(repository, friday, hall.getSeatingPlans().get(1), rejections));

		assertEquals(planBefore, friday.getSeatingPlanId());
		assertEquals(List.of(StoreMessages.lockedBelow("Freitag", UnitState.Status.LOCKED_FORMAT)), rejections);
	}

	/** Ohne gesperrten Eintrag läuft der Wechsel über das Repository wie gewohnt. */
	@Test
	void planSwitchWithoutLockedEntriesGoesThrough() {
		SyncService sync = started();
		StoreEventRepository repository = TestRepositories.events(sync);
		Hall hall = new Hall();
		SeatingPlan oldPlan = plan(hall, 2, 2);
		SeatingPlan newPlan = plan(hall, 2, 2);
		Event event = new Event();
		event.setName("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		Syncs.switchPlan(friday, oldPlan);
		repository.saveEvent(event);
		List<String> rejections = new ArrayList<>();

		assertTrue(confirmWith(repository, friday, newPlan, rejections));

		assertEquals(newPlan.getId(), friday.getSeatingPlanId());
		assertEquals(List.of(), rejections);
	}

	// <-- Gesperrte Einträge
}
