package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.BookingSync.Report;

class BookingSyncTest {

	/** Bestuhlung mit Reihe "A": Tisch 1 (Sitze 1, 2) und Tisch 2 (Sitze 1, 2) = 4 Sitze. */
	private static SeatingPlan newPlan() {
		Hall hall = new Hall();
		return planWithRowA(hall, 2, 2);
	}

	private static SeatingPlan planWithRowA(Hall hall, int tableCount, int seatsPerTable) {
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, tableCount, seatsPerTable);
		return plan;
	}

	private static Presentation newPresentation(SeatingPlan plan) {
		Presentation presentation = new Presentation();
		presentation.setSeatingPlanId(plan.getId());
		return presentation;
	}

	private static Seat seat(SeatingPlan plan, int tableNumber, int seatNumber) {
		for (Table table : plan.getTables()) {
			if (table.getTableNumber() == tableNumber) {
				for (Seat seat : table.getSeats()) {
					if (seat.getSeatNumber() == seatNumber) {
						return seat;
					}
				}
			}
		}
		throw new IllegalArgumentException("Kein Sitz " + tableNumber + "/" + seatNumber);
	}

	private static Booking bookingOf(Presentation presentation, Seat seat) {
		return presentation.getBookings().stream().filter(b -> seat.getId().equals(b.getSeatId())).findFirst()
				.orElse(null);
	}

	/** Eine Buchung mit Momentaufnahme eines Platzes, der in keiner Bestuhlung stehen muss. */
	private static Booking snapshotBooking(Presentation presentation, String rowLabel, int tableNumber,
			int seatNumber) {
		Booking booking = presentation.addBooking();
		booking.setSeatId("alt-" + rowLabel + tableNumber + "-" + seatNumber);
		booking.setRowLabel(rowLabel);
		booking.setTableNumber(tableNumber);
		booking.setSeatNumber(seatNumber);
		return booking;
	}

	@Test
	void sync_legt_fuer_jeden_Sitz_eine_leere_Buchung_an() {
		SeatingPlan plan = newPlan();
		plan.getTables().get(0).setCategory("Parkett");
		seat(plan, 2, 2).setWheelchairAccessible(true);
		Presentation presentation = newPresentation(plan);

		Report report = BookingSync.sync(presentation, plan);

		assertEquals(new Report(0, 4, 0, 0), report);
		assertEquals(4, presentation.getBookings().size());
		assertFalse(presentation.isPlanMissing());
		Set<String> seatIds = new HashSet<>();
		for (Seat seat : plan.getSeats()) {
			Booking booking = bookingOf(presentation, seat);
			assertNotNull(booking);
			assertSame(presentation, booking.getParent());
			assertEquals("A", booking.getRowLabel());
			assertEquals(seat.getParent().getTableNumber(), booking.getTableNumber());
			assertEquals(seat.getSeatNumber(), booking.getSeatNumber());
			assertEquals(seat.getParent().getCategory(), booking.getCategory());
			assertEquals(seat.isWheelchairAccessible(), booking.isWheelchairAccessible());
			assertFalse(booking.hasData());
			assertFalse(booking.isUnassigned());
			seatIds.add(booking.getSeatId());
		}
		assertEquals(4, seatIds.size());
	}

	@Test
	void zweiter_sync_aendert_nichts_und_zaehlt_alle_als_getroffen() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		BookingSync.sync(presentation, plan);
		List<Booking> before = List.copyOf(presentation.getBookings());

		Report report = BookingSync.sync(presentation, plan);

		assertEquals(new Report(4, 0, 0, 0), report);
		assertEquals(before, presentation.getBookings());
	}

	@Test
	void sync_folgt_dem_Sitz_beim_Umnummerieren() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		BookingSync.sync(presentation, plan);
		Seat seat = seat(plan, 2, 1);
		Booking booking = bookingOf(presentation, seat);
		booking.setLastName("Müller");
		String seatId = booking.getSeatId();

		seat.getParent().setTableNumber(7);
		Report report = BookingSync.sync(presentation, plan);

		assertEquals(new Report(4, 0, 0, 0), report);
		assertEquals(4, presentation.getBookings().size());
		assertSame(booking, bookingOf(presentation, seat));
		assertEquals(seatId, booking.getSeatId());
		assertEquals("Müller", booking.getLastName());
		assertEquals(7, booking.getTableNumber());
		assertFalse(booking.isUnassigned());
	}

	@Test
	void wechsel_uebertraegt_ueber_die_Momentaufnahme() {
		Hall hall = new Hall();
		SeatingPlan newPlan = planWithRowA(hall, 3, 2);
		Presentation presentation = new Presentation();
		presentation.setSeatingPlanId("alte-bestuhlung");
		Booking booking = snapshotBooking(presentation, "A", 3, 2);
		booking.setLastName("Meier");
		booking.setPrice(1800);
		String oldSeatId = booking.getSeatId();

		Report report = BookingSync.switchPlan(presentation, newPlan);

		assertEquals(newPlan.getId(), presentation.getSeatingPlanId());
		assertEquals(6, presentation.getBookings().size());
		assertSame(booking, bookingOf(presentation, seat(newPlan, 3, 2)));
		assertNotEquals(oldSeatId, booking.getSeatId());
		assertEquals("Meier", booking.getLastName());
		assertEquals(Integer.valueOf(1800), booking.getPrice());
		assertFalse(booking.isUnassigned());
		assertEquals(new Report(1, 5, 0, 0), report);
	}

	@Test
	void wechsel_laesst_Buchung_mit_Daten_ohne_Treffer_ohne_Platz() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		BookingSync.sync(presentation, plan);
		Booking lost = snapshotBooking(presentation, "Z", 9, 9);
		lost.setLastName("Schmidt");

		Report report = BookingSync.sync(presentation, plan);

		assertTrue(presentation.getBookings().contains(lost));
		assertTrue(lost.isUnassigned());
		assertEquals("Schmidt", lost.getLastName());
		assertEquals(new Report(4, 0, 0, 1), report);
		assertEquals(5, presentation.getBookings().size());
	}

	@Test
	void unassigned_wird_bei_jedem_Lauf_neu_gesetzt() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		Booking lost = snapshotBooking(presentation, "A", 5, 1);
		lost.setLastName("Schmidt");

		BookingSync.sync(presentation, plan);
		assertTrue(lost.isUnassigned());

		// die Bestuhlung bekommt den Platz A5/1 nachträglich: der nächste Lauf ordnet die Buchung zu
		TableRow row = plan.getRows().get(0);
		Table table = plan.addTable(row);
		table.setTableNumber(5);
		table.addSeat();
		Report report = BookingSync.sync(presentation, plan);

		assertFalse(lost.isUnassigned());
		assertEquals(table.getSeats().get(0).getId(), lost.getSeatId());
		assertEquals(0, report.unassigned());
	}

	@Test
	void wechsel_entfernt_leere_Buchung_ohne_Treffer() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		Booking empty = snapshotBooking(presentation, "Z", 9, 9);
		Booking onlyPrice = snapshotBooking(presentation, "Z", 9, 8);
		onlyPrice.setPrice(2000);

		Report report = BookingSync.sync(presentation, plan);

		assertFalse(presentation.getBookings().contains(empty));
		assertFalse(presentation.getBookings().contains(onlyPrice));
		assertEquals(new Report(0, 4, 2, 0), report);
		assertEquals(4, presentation.getBookings().size());
	}

	@Test
	void zwei_Buchungen_mit_gleicher_Momentaufnahme_belegen_nur_einen_Sitz() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		Booking first = snapshotBooking(presentation, "A", 1, 2);
		first.setLastName("Erste");
		Booking second = snapshotBooking(presentation, "A", 1, 2);
		second.setLastName("Zweite");

		Report report = BookingSync.sync(presentation, plan);

		Seat target = seat(plan, 1, 2);
		assertEquals(target.getId(), first.getSeatId());
		assertFalse(first.isUnassigned());
		assertTrue(second.isUnassigned());
		assertEquals("Zweite", second.getLastName());
		assertEquals(new Report(1, 3, 0, 1), report);
		assertEquals(1, presentation.getBookings().stream().filter(b -> target.getId().equals(b.getSeatId()))
				.count());
	}

	@Test
	void zwei_Sitze_mit_gleicher_Nummer_nehmen_nie_dieselbe_Buchung() {
		SeatingPlan plan = newPlan();
		// zweiter Sitz 2 an Tisch 1: dieselbe Momentaufnahme (A, 1, 2) bei zwei Sitzen
		Seat duplicate = plan.getTables().get(0).addSeat();
		duplicate.setSeatNumber(2);
		Presentation presentation = newPresentation(plan);
		Booking first = snapshotBooking(presentation, "A", 1, 2);
		first.setLastName("Erste");
		Booking second = snapshotBooking(presentation, "A", 1, 2);
		second.setLastName("Zweite");

		Report report = BookingSync.sync(presentation, plan);

		assertFalse(first.isUnassigned());
		assertFalse(second.isUnassigned());
		assertNotEquals(first.getSeatId(), second.getSeatId());
		Set<String> seatIds = new HashSet<>();
		for (Booking booking : presentation.getBookings()) {
			assertTrue(seatIds.add(booking.getSeatId()), "Sitz doppelt belegt: " + booking.getSeatId());
		}
		assertEquals(5, plan.getSeats().size());
		assertEquals(5, presentation.getBookings().size());
		assertEquals(new Report(2, 3, 0, 0), report);
	}

	@Test
	void zwei_Buchungen_gleicher_seatId_belegen_nur_einen_Sitz() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		Seat target = seat(plan, 1, 1);
		Booking first = presentation.addBooking();
		first.setSeatId(target.getId());
		first.setLastName("Erste");
		Booking second = presentation.addBooking();
		second.setSeatId(target.getId());
		second.setLastName("Zweite");

		BookingSync.sync(presentation, plan);

		assertSame(first, bookingOf(presentation, target));
		assertTrue(second.isUnassigned());
		assertEquals(1, presentation.getBookings().stream()
				.filter(b -> target.getId().equals(b.getSeatId()) && !b.isUnassigned()).count());
	}

	@Test
	void Treffer_ueber_seatId_hat_Vorrang_vor_der_Momentaufnahme_einer_anderen_Buchung() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		Seat target = seat(plan, 1, 1);
		// die erste Buchung hat nur die Momentaufnahme von A1/1, die zweite besitzt den Sitz über die Id
		Booking byKey = snapshotBooking(presentation, "A", 1, 1);
		byKey.setLastName("PerNummer");
		Booking byId = presentation.addBooking();
		byId.takeSnapshot(target.getParent(), target);
		byId.setLastName("PerId");

		BookingSync.sync(presentation, plan);

		assertSame(byId, bookingOf(presentation, target));
		assertFalse(byId.isUnassigned());
		assertTrue(byKey.isUnassigned());
	}

	@Test
	void fehlender_Reihenbuchstabe_zaehlt_als_leer_und_Buchstaben_werden_exakt_verglichen() {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow plain = plan.addRow();
		plain.setLabel(null);
		plan.fillRow(plain, 1, 1);
		TableRow lower = plan.addRow();
		lower.setLabel("a");
		plan.fillRow(lower, 1, 1);
		Presentation presentation = newPresentation(plan);
		Booking noLabel = snapshotBooking(presentation, null, 1, 1);
		noLabel.setLastName("Ohne");
		Booking upper = snapshotBooking(presentation, "A", 1, 1);
		upper.setLastName("Gross");

		BookingSync.sync(presentation, plan);

		assertFalse(noLabel.isUnassigned());
		assertEquals(plain.getId(), plan.findSeat(noLabel.getSeatId()).getParent().getRowId());
		assertTrue(upper.isUnassigned());
	}

	@Test
	void fehlende_Bestuhlung_friert_ein() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		BookingSync.sync(presentation, plan);
		Booking filled = presentation.getBookings().get(0);
		filled.setLastName("Müller");
		List<Booking> before = List.copyOf(presentation.getBookings());

		Report report = BookingSync.sync(presentation, null);

		assertTrue(presentation.isPlanMissing());
		assertEquals(new Report(0, 0, 0, 0), report);
		assertEquals(before, presentation.getBookings());
		assertEquals("Müller", filled.getLastName());
		assertFalse(filled.isUnassigned());
		assertEquals(plan.getId(), presentation.getSeatingPlanId());

		// ist die Bestuhlung wieder da, hebt der nächste Lauf die Markierung auf
		BookingSync.sync(presentation, plan);
		assertFalse(presentation.isPlanMissing());
	}

	@Test
	void ohne_Bestuhlung_ist_ein_No_Op() {
		Presentation presentation = new Presentation();
		Booking booking = presentation.addBooking();
		booking.setLastName("Müller");
		presentation.setPlanMissing(true);

		Report report = BookingSync.sync(presentation, null);

		assertFalse(presentation.isPlanMissing());
		assertEquals(new Report(0, 0, 0, 0), report);
		assertEquals(List.of(booking), presentation.getBookings());
		assertFalse(booking.isUnassigned());
		assertNull(presentation.getSeatingPlanId());
	}

	@Test
	void preview_veraendert_nichts_und_liefert_denselben_Report_wie_switchPlan() {
		SeatingPlan oldPlan = newPlan();
		Presentation presentation = newPresentation(oldPlan);
		BookingSync.sync(presentation, oldPlan);
		Booking filled = bookingOf(presentation, seat(oldPlan, 1, 2));
		filled.setLastName("Meier");
		Booking lost = snapshotBooking(presentation, "Z", 9, 9);
		lost.setLastName("Ohne Platz");
		Booking empty = snapshotBooking(presentation, "Z", 9, 8);
		SeatingPlan other = planWithRowA(oldPlan.getParent(), 1, 3);

		List<Booking> bookingsBefore = List.copyOf(presentation.getBookings());
		String seatIdBefore = filled.getSeatId();

		Report preview = BookingSync.preview(presentation, other);

		assertEquals(oldPlan.getId(), presentation.getSeatingPlanId());
		assertEquals(bookingsBefore, presentation.getBookings());
		assertEquals(seatIdBefore, filled.getSeatId());
		assertFalse(lost.isUnassigned());
		assertTrue(presentation.getBookings().contains(empty));
		assertEquals(new Report(2, 1, 3, 1), preview);

		Report actual = BookingSync.switchPlan(presentation, other);

		assertEquals(preview, actual);
		assertEquals(other.getId(), presentation.getSeatingPlanId());
		assertTrue(lost.isUnassigned());
		assertEquals(4, presentation.getBookings().size());
	}

	@Test
	void preview_ohne_Bestuhlung_liefert_nur_Nullen() {
		Presentation presentation = new Presentation();
		presentation.addBooking().setLastName("Müller");

		assertEquals(new Report(0, 0, 0, 0), BookingSync.preview(presentation, null));
		assertEquals(1, presentation.getBookings().size());
	}

	@Test
	void assign_ersetzt_die_leere_Buchung_des_freien_Sitzes() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		BookingSync.sync(presentation, plan);
		Booking lost = snapshotBooking(presentation, "Z", 9, 9);
		lost.setLastName("Schmidt");
		BookingSync.sync(presentation, plan);
		assertTrue(lost.isUnassigned());
		Seat target = seat(plan, 2, 1);
		Booking emptyOfSeat = bookingOf(presentation, target);

		boolean assigned = BookingSync.assign(presentation, plan, lost, target);

		assertTrue(assigned);
		assertFalse(presentation.getBookings().contains(emptyOfSeat));
		assertSame(lost, bookingOf(presentation, target));
		assertFalse(lost.isUnassigned());
		assertEquals("A", lost.getRowLabel());
		assertEquals(2, lost.getTableNumber());
		assertEquals(1, lost.getSeatNumber());
		assertEquals("Schmidt", lost.getLastName());
		assertEquals(4, presentation.getBookings().size());
	}

	@Test
	void assign_lehnt_belegten_Sitz_ab() {
		SeatingPlan plan = newPlan();
		Presentation presentation = newPresentation(plan);
		BookingSync.sync(presentation, plan);
		Seat target = seat(plan, 1, 1);
		Booking occupant = bookingOf(presentation, target);
		occupant.setLastName("Besetzt");
		Booking lost = snapshotBooking(presentation, "Z", 9, 9);
		lost.setLastName("Schmidt");
		lost.setUnassigned(true);
		int sizeBefore = presentation.getBookings().size();

		boolean assigned = BookingSync.assign(presentation, plan, lost, target);

		assertFalse(assigned);
		assertEquals(sizeBefore, presentation.getBookings().size());
		assertSame(occupant, bookingOf(presentation, target));
		assertTrue(lost.isUnassigned());
		assertEquals(9, lost.getTableNumber());
	}
}
