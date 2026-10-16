package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.BookingSync.Report;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;

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
		BookingSync.switchPlan(presentation, plan);
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

		assertTrue(PlanSwitchDialog.confirm(presentation, plan, NEVER_ASKED));

		assertEquals(plan.getId(), presentation.getSeatingPlanId());
		assertEquals(before, presentation.getBookings());
	}

	@Test
	void erste_Zuordnung_ohne_Buchungen_kommt_ohne_Dialog_aus() {
		Hall hall = new Hall();
		SeatingPlan plan = plan(hall, 2, 3);
		Presentation presentation = new Presentation();
		presentation.setHallId(hall.getId());

		assertTrue(PlanSwitchDialog.confirm(presentation, plan, NEVER_ASKED));

		assertEquals(plan.getId(), presentation.getSeatingPlanId());
		assertEquals(6, presentation.getBookings().size());
	}

	@Test
	void wechsel_auf_eine_gleich_nummerierte_Bestuhlung_ohne_Daten_kommt_ohne_Dialog_aus() {
		Hall hall = new Hall();
		SeatingPlan oldPlan = plan(hall, 2, 2);
		SeatingPlan newPlan = plan(hall, 2, 2);
		Presentation presentation = presentationOn(oldPlan);

		assertTrue(PlanSwitchDialog.confirm(presentation, newPlan, NEVER_ASKED));

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

		Report shown = PlanSwitchDialog.dialogReport(presentation, BookingSync.preview(presentation, newPlan));

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

		boolean result = PlanSwitchDialog.confirm(presentation, newPlan, report -> {
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

		assertTrue(PlanSwitchDialog.confirm(presentation, newPlan, report -> true));

		assertEquals(newPlan.getId(), presentation.getSeatingPlanId());
		assertSame(newPlan.findSeat(mueller.getSeatId()).getParent(), newPlan.getTables().get(0));
		assertTrue(meier.isUnassigned());
		// 4 Sitze der neuen Bestuhlung plus Meier ohne Platz
		assertEquals(5, presentation.getBookings().size());
	}

	@Test
	void ohne_neue_Bestuhlung_bleiben_die_Buchungen() {
		Hall hall = new Hall();
		SeatingPlan oldPlan = plan(hall, 1, 2);
		Presentation presentation = presentationOn(oldPlan);
		bookingAt(presentation, 1, 1).setLastName("Müller");
		List<Booking> before = new ArrayList<>(presentation.getBookings());

		assertTrue(PlanSwitchDialog.confirm(presentation, null, NEVER_ASKED));

		assertNull(presentation.getSeatingPlanId());
		assertEquals(before, presentation.getBookings());
	}
}
