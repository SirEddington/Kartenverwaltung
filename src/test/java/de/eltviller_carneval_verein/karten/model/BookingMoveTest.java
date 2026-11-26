package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.TextNode;

import de.eltviller_carneval_verein.karten.model.BookingMove.Plan;
import de.eltviller_carneval_verein.karten.model.BookingMove.Refusal;
import de.eltviller_carneval_verein.karten.model.BookingMove.Source;
import de.eltviller_carneval_verein.karten.model.BookingMove.Step;

class BookingMoveTest {

	private final SeatingPlan plan = planWithRowA(new Hall(), 1, 4);
	private final Presentation presentation = newPresentation(plan);
	private final Seat seat1 = seat(plan, 1, 1);
	private final Seat seat2 = seat(plan, 1, 2);
	private final Seat seat3 = seat(plan, 1, 3);
	private final Seat seat4 = seat(plan, 1, 4);

	BookingMoveTest() {
		Syncs.sync(presentation, plan);
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

	/** Reserviert die Buchung des Sitzes auf den Namen. */
	private Booking reserve(Seat seat, String lastName) {
		Booking booking = bookingOf(presentation, seat);
		booking.setLastName(lastName);
		return booking;
	}

	private static List<Source> sources(Booking... bookings) {
		List<Source> sources = new ArrayList<>();
		for (Booking booking : bookings) {
			sources.add(Source.of(booking));
		}
		return sources;
	}

	private static List<String> ids(Seat... seats) {
		List<String> ids = new ArrayList<>();
		for (Seat seat : seats) {
			ids.add(seat.getId());
		}
		return ids;
	}

	private Plan plan(List<Source> sources, List<String> targetSeatIds) {
		return BookingMove.plan(presentation, plan, sources, targetSeatIds, Set.of());
	}

	private static void assertStep(Step step, Booking booking, Seat from, Seat to, boolean counterMove) {
		assertSame(booking, step.booking());
		assertSame(from, step.from());
		assertSame(to, step.to());
		assertEquals(counterMove, step.counterMove());
	}

	private static void assertRefused(Plan result, Refusal refusal) {
		assertTrue(result.refused());
		assertEquals(refusal, result.refusal());
		assertTrue(result.steps().isEmpty());
		assertTrue(result.emptied().isEmpty());
		assertTrue(result.emptiedWithData().isEmpty());
		assertTrue(result.vacated().isEmpty());
	}

	// Beispieltabelle aus dem Spec, Abschnitt 2

	@Test
	void moveToFreeSeat() {
		Booking a = reserve(seat1, "A");

		Plan result = plan(sources(a), ids(seat3));

		assertFalse(result.refused());
		assertNull(result.lockedSeat());
		assertEquals(1, result.steps().size());
		assertStep(result.steps().get(0), a, seat1, seat3, false);
		assertEquals(List.of(seat3), result.emptied());
		assertTrue(result.emptiedWithData().isEmpty());
		assertEquals(List.of(seat1), result.vacated());
	}

	@Test
	void swapWithOccupiedSeat() {
		Booking a = reserve(seat1, "A");
		Booking c = reserve(seat3, "C");

		Plan result = plan(sources(a), ids(seat3));

		assertEquals(2, result.steps().size());
		assertStep(result.steps().get(0), a, seat1, seat3, false);
		assertStep(result.steps().get(1), c, seat3, seat1, true);
		assertTrue(result.emptied().isEmpty());
		assertTrue(result.vacated().isEmpty());
	}

	@Test
	void groupShiftsByOneSeat() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");

		Plan result = plan(sources(a, b), ids(seat2, seat3));

		assertEquals(2, result.steps().size());
		assertStep(result.steps().get(0), a, seat1, seat2, false);
		assertStep(result.steps().get(1), b, seat2, seat3, false);
		assertEquals(List.of(seat3), result.emptied());
		assertEquals(List.of(seat1), result.vacated());
	}

	@Test
	void chainEndsOnOccupiedSeatCounterMovesToChainStart() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");
		Booking c = reserve(seat3, "C");

		Plan result = plan(sources(a, b), ids(seat2, seat3));

		assertEquals(3, result.steps().size());
		assertStep(result.steps().get(0), a, seat1, seat2, false);
		assertStep(result.steps().get(1), b, seat2, seat3, false);
		assertStep(result.steps().get(2), c, seat3, seat1, true);
		assertTrue(result.emptied().isEmpty());
		assertTrue(result.vacated().isEmpty());
	}

	@Test
	void twoChainsOneFreeOneOccupied() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");
		Booking d = reserve(seat4, "D");

		Plan result = plan(sources(a, b), ids(seat3, seat4));

		assertEquals(3, result.steps().size());
		assertStep(result.steps().get(0), a, seat1, seat3, false);
		assertStep(result.steps().get(1), b, seat2, seat4, false);
		assertStep(result.steps().get(2), d, seat4, seat2, true);
		assertEquals(List.of(seat3), result.emptied());
		assertEquals(List.of(seat1), result.vacated());
	}

	@Test
	void everySourceAlreadyOnItsTargetIsNothingToDo() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");

		assertRefused(plan(sources(a, b), ids(seat1, seat2)), Refusal.NOTHING_TO_DO);
		assertRefused(plan(sources(b, a), ids(seat2, seat1)), Refusal.NOTHING_TO_DO);
	}

	// Zuordnung

	@Test
	void pairAlreadyInPlaceDropsOutOfThePlan() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");

		Plan result = plan(sources(a, b), ids(seat1, seat3));

		assertEquals(1, result.steps().size());
		assertStep(result.steps().get(0), b, seat2, seat3, false);
		assertEquals(List.of(seat3), result.emptied());
		assertEquals(List.of(seat2), result.vacated());
	}

	@Test
	void sourcesAndTargetsArePairedByPlaceOrderNotByInputOrder() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");

		Plan result = plan(sources(b, a), ids(seat4, seat3));

		assertStep(result.steps().get(0), a, seat1, seat3, false);
		assertStep(result.steps().get(1), b, seat2, seat4, false);
		assertEquals(List.of(seat3, seat4), result.emptied());
		assertEquals(List.of(seat1, seat2), result.vacated());
	}

	@Test
	void targetsAreResolvedByIdInAReplacedPlan() {
		Booking a = reserve(seat1, "A");
		List<Source> sources = sources(a);
		SeatingPlan replaced = replacedPlan(plan);

		Plan result = BookingMove.plan(presentation, replaced, sources, ids(seat3), Set.of());

		assertEquals(1, result.steps().size());
		assertStep(result.steps().get(0), a, replaced.findSeat(seat1.getId()), replaced.findSeat(seat3.getId()), false);
		assertEquals(List.of(replaced.findSeat(seat3.getId())), result.emptied());
		assertSame(replaced.findSeat(seat3.getId()), result.emptied().get(0));
		assertSame(replaced.findSeat(seat1.getId()), result.vacated().get(0));
	}

	/** Eine neu gebaute Bestuhlung mit denselben Kennungen der Tische und Sitze, aber neuen Objekten. */
	private static SeatingPlan replacedPlan(SeatingPlan original) {
		SeatingPlan replaced = new Hall().addSeatingPlan();
		TableRow row = replaced.addRow();
		row.setLabel("A");
		for (Table table : original.getTables()) {
			Table copy = new Table(table.getId());
			copy.setPlan(replaced);
			copy.setRowId(row.getId());
			copy.setTableNumber(table.getTableNumber());
			for (Seat seat : table.getSeats()) {
				Seat seatCopy = new Seat(seat.getId());
				seatCopy.setParent(copy);
				seatCopy.setSeatNumber(seat.getSeatNumber());
				copy.getSeats().add(seatCopy);
			}
			replaced.getTables().add(copy);
		}
		return replaced;
	}

	/**
	 * Drei Quellen tragen dieselbe Platzangabe, drei Ziele eine andere: Gepaart wird nach der Kennung des Sitzes. Die
	 * Kennungen sind so gewählt, dass die Reihenfolge einer {@code HashMap} der Quellsitze ({@code quelle-3, quelle-2,
	 * quelle-1}) und die der Eingabe nicht zufällig die richtige Zuordnung ergeben.
	 */
	@Test
	void equalPlacesAreOrderedBySeatId() {
		SeatingPlan tied = new Hall().addSeatingPlan();
		TableRow row = tied.addRow();
		row.setLabel("A");
		Table table = tied.addTable(row);
		for (String id : List.of("quelle-1", "quelle-2", "quelle-3")) {
			addSeat(table, id, 1);
		}
		for (String id : List.of("ziel-1", "ziel-2", "ziel-3")) {
			addSeat(table, id, 2);
		}
		Presentation tiedPresentation = newPresentation(tied);
		Syncs.sync(tiedPresentation, tied);
		Booking[] onSource = new Booking[3];
		for (int i = 0; i < 3; i++) {
			onSource[i] = bookingOf(tiedPresentation, tied.findSeat("quelle-" + (i + 1)));
			onSource[i].setLastName("Q" + (i + 1));
		}

		Plan first = BookingMove.plan(tiedPresentation, tied, sources(onSource[2], onSource[0], onSource[1]),
				List.of("ziel-1", "ziel-2", "ziel-3"), Set.of());
		Plan second = BookingMove.plan(tiedPresentation, tied, sources(onSource[1], onSource[2], onSource[0]),
				List.of("ziel-3", "ziel-1", "ziel-2"), Set.of());

		for (Plan result : List.of(first, second)) {
			assertEquals(3, result.steps().size());
			for (int i = 0; i < 3; i++) {
				assertStep(result.steps().get(i), onSource[i], tied.findSeat("quelle-" + (i + 1)),
						tied.findSeat("ziel-" + (i + 1)), false);
			}
		}
		assertTrue(first.sameAs(second));
	}

	private static void addSeat(Table table, String id, int seatNumber) {
		Seat seat = new Seat(id);
		seat.setParent(table);
		seat.setSeatNumber(seatNumber);
		table.getSeats().add(seat);
	}

	@Test
	void emptiedWithDataNamesTargetsWithLeftovers() {
		Booking a = reserve(seat1, "A");
		bookingOf(presentation, seat3).setComment("alter Kommentar");
		bookingOf(presentation, seat4).setComment("noch ein Rest");

		Plan result = plan(sources(a), ids(seat3));

		assertEquals(List.of(seat3), result.emptied());
		assertEquals(Set.of(seat3.getId()), result.emptiedWithData());
		assertStep(result.steps().get(0), a, seat1, seat3, false);
	}

	// Vergleich zweier Pläne

	@Test
	void sameAsComparesStepsByIdsAndIgnoresBookingData() {
		Booking a = reserve(seat1, "A");
		reserve(seat3, "C");
		Plan before = plan(sources(a), ids(seat3));

		a.setLastName("Anders");
		assertTrue(before.sameAs(plan(sources(a), ids(seat3))));

		assertFalse(before.sameAs(plan(sources(a), ids(seat4))));
		bookingOf(presentation, seat3).setLastName(null);
		assertFalse(before.sameAs(plan(sources(a), ids(seat3))), "Gegenzug fällt weg");
	}

	@Test
	void sameAsSeesLeftoversAppearOnAnEmptiedSeat() {
		Booking a = reserve(seat1, "A");
		Plan before = plan(sources(a), ids(seat3));

		bookingOf(presentation, seat3).setComment("Rest");

		assertFalse(before.sameAs(plan(sources(a), ids(seat3))));
	}

	@Test
	void sameAsComparesRefusals() {
		Booking a = reserve(seat1, "A");
		Plan refused = BookingMove.plan(null, null, sources(a), ids(seat3), Set.of());

		assertTrue(refused.sameAs(BookingMove.plan(presentation, null, sources(a), ids(seat3), Set.of())));
		assertFalse(refused.sameAs(plan(sources(a), ids(seat3))));
		assertFalse(plan(sources(a), ids(seat3)).sameAs(refused));
	}

	// Ablehnung

	@Test
	void noPlanIsRefused() {
		Booking a = reserve(seat1, "A");

		Plan result = BookingMove.plan(presentation, null, sources(a), ids(seat3), Set.of());

		assertRefused(result, Refusal.NO_PLAN);
		assertNull(result.lockedSeat());
	}

	@Test
	void sourceNoLongerInPresentationIsStale() {
		Booking a = reserve(seat1, "A");
		List<Source> sources = sources(a);
		presentation.getBookings().remove(a);

		assertRefused(plan(sources, ids(seat3)), Refusal.STALE);
	}

	@Test
	void unassignedSourceIsStale() {
		Booking a = reserve(seat1, "A");
		List<Source> sources = sources(a);
		a.setUnassigned(true);

		assertRefused(plan(sources, ids(seat3)), Refusal.STALE);
	}

	@Test
	void sourceNoLongerTakenIsStale() {
		Booking a = reserve(seat1, "A");
		List<Source> sources = sources(a);
		a.setLastName(null);

		assertRefused(plan(sources, ids(seat3)), Refusal.STALE);
	}

	@Test
	void sourceCarryingAnotherIdIsStale() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");
		List<Source> sources = sources(a);
		a.copyFrom(b); // eine fremde Änderung legt einen anderen Stand in dasselbe Objekt

		assertRefused(plan(sources, ids(seat3)), Refusal.STALE);
	}

	@Test
	void sourceOnAnotherSeatIsStale() {
		Booking a = reserve(seat1, "A");
		List<Source> sources = sources(a);
		a.takeSnapshot(seat4.getParent(), seat4);

		assertRefused(plan(sources, ids(seat3)), Refusal.STALE);
	}

	@Test
	void sourceSeatNoLongerInPlanIsStale() {
		Booking a = reserve(seat1, "A");
		List<Source> sources = sources(a);
		seat1.getParent().deleteSeat(seat1);

		assertRefused(plan(sources, ids(seat3)), Refusal.STALE);
	}

	@Test
	void missingTargetSeatIsStale() {
		Booking a = reserve(seat1, "A");

		assertRefused(plan(sources(a), List.of("kein-Sitz")), Refusal.STALE);
	}

	@Test
	void differentCountsAreRefused() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");

		assertRefused(plan(sources(a, b), ids(seat3)), Refusal.COUNT);
		assertRefused(plan(sources(a), ids(seat3, seat4)), Refusal.COUNT);
	}

	@Test
	void duplicateTargetIsRefused() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");

		assertRefused(plan(sources(a, b), ids(seat3, seat3)), Refusal.COUNT);
	}

	@Test
	void duplicateSourceIsRefused() {
		Booking a = reserve(seat1, "A");

		assertRefused(plan(sources(a, a), ids(seat3, seat4)), Refusal.COUNT);
	}

	@Test
	void twoObjectsWithTheSameBookingIdAreADuplicateSource() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");
		b.setId(a.getId());

		assertRefused(plan(sources(a, b), ids(seat3, seat4)), Refusal.COUNT);
	}

	@Test
	void noSourceIsRefused() {
		assertRefused(plan(List.of(), List.of()), Refusal.COUNT);
	}

	@Test
	void lockedSourceSeatIsRefused() {
		Booking a = reserve(seat1, "A");

		Plan result = BookingMove.plan(presentation, plan, sources(a), ids(seat3), Set.of(seat1.getId()));

		assertRefused(result, Refusal.LOCKED);
		assertSame(seat1, result.lockedSeat());
	}

	@Test
	void lockedTargetSeatIsRefused() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");
		reserve(seat4, "D");

		Plan free = BookingMove.plan(presentation, plan, sources(a), ids(seat3), Set.of(seat3.getId()));
		assertRefused(free, Refusal.LOCKED);
		assertSame(seat3, free.lockedSeat());

		Plan taken = BookingMove.plan(presentation, plan, sources(a), ids(seat4), Set.of(seat4.getId()));
		assertRefused(taken, Refusal.LOCKED);
		assertSame(seat4, taken.lockedSeat());

		Plan both = BookingMove.plan(presentation, plan, sources(a, b), ids(seat3, seat4),
				Set.of(seat4.getId(), seat2.getId(), seat3.getId()));
		assertRefused(both, Refusal.LOCKED);
		assertSame(seat2, both.lockedSeat(), "der erste gesperrte Sitz in der Reihenfolge der Plätze");
	}

	@Test
	void firstRefusalInTableOrderWins() {
		Booking a = reserve(seat1, "A");
		List<Source> sources = sources(a);
		a.setLastName(null);

		Plan result = BookingMove.plan(presentation, plan, sources, ids(seat3), Set.of(seat3.getId()));

		assertRefused(result, Refusal.STALE);
		assertNull(result.lockedSeat());
	}

	// Buchungen „ohne Platz“

	@Test
	void unassignedBookingOnATargetIsNeitherOccupantNorRemoved() {
		Booking a = reserve(seat1, "A");
		Booking withoutPlace = presentation.addBooking();
		withoutPlace.takeSnapshot(seat3.getParent(), seat3);
		withoutPlace.setLastName("Ohne Platz");
		withoutPlace.setUnassigned(true);
		List<Source> sources = sources(a);

		Plan shown = plan(sources, ids(seat3));

		assertEquals(1, shown.steps().size(), "kein Gegenzug für eine Buchung ohne Platz");
		assertStep(shown.steps().get(0), a, seat1, seat3, false);
		assertEquals(List.of(seat3), shown.emptied());
		assertTrue(shown.emptiedWithData().isEmpty(), "eine Buchung ohne Platz ist kein Rest des Sitzes");

		assertTrue(apply(sources, ids(seat3), shown));

		assertTrue(presentation.getBookings().contains(withoutPlace));
		assertEquals(seat3.getId(), withoutPlace.getSeatId());
		assertTrue(withoutPlace.isUnassigned());
		assertEquals(seat3.getId(), a.getSeatId());
	}

	// Ausführen

	private boolean apply(List<Source> sources, List<String> targetSeatIds, Plan shown) {
		return BookingMove.apply(presentation, plan, sources, targetSeatIds, Set.of(), shown);
	}

	/** Auf jeden Sitz der Bestuhlung zeigt genau eine Buchung, und ein Abgleich hat nichts zu tun. */
	private void assertOneBookingPerSeat() {
		for (Seat seat : plan.getSeats()) {
			long count = presentation.getBookings().stream().filter(b -> seat.getId().equals(b.getSeatId())).count();
			assertEquals(1, count, "Buchungen auf Sitz " + seat.getSeatNumber());
		}
		BookingSync.Report report = Syncs.sync(presentation, plan);
		assertEquals(0, report.created());
		assertEquals(0, report.removedEmpty());
		assertEquals(0, report.unassigned());
	}

	/** Die Buchungsliste und alle Sitzkennungen sind wie vorher. */
	private void assertUnchanged(List<Booking> bookingsBefore, List<String> seatIdsBefore) {
		assertEquals(bookingsBefore, presentation.getBookings());
		assertEquals(seatIdsBefore, presentation.getBookings().stream().map(Booking::getSeatId).toList());
	}

	@Test
	void applyMovesAndLeavesAPlaceholder() {
		Booking a = reserve(seat1, "A");
		Booking oldOnTarget = bookingOf(presentation, seat3);
		List<Source> sources = sources(a);
		Plan shown = plan(sources, ids(seat3));

		assertTrue(apply(sources, ids(seat3), shown));

		assertEquals(seat3.getId(), a.getSeatId());
		assertFalse(a.isUnassigned());
		assertFalse(presentation.getBookings().contains(oldOnTarget));
		Booking placeholder = bookingOf(presentation, seat1);
		assertFalse(placeholder.isTaken());
		assertFalse(placeholder.hasData());
		assertEquals(1, placeholder.getSeatNumber());
		assertOneBookingPerSeat();
	}

	@Test
	void applySwaps() {
		Booking a = reserve(seat1, "A");
		Booking c = reserve(seat3, "C");
		List<Source> sources = sources(a);
		Plan shown = plan(sources, ids(seat3));

		assertTrue(apply(sources, ids(seat3), shown));

		assertEquals(seat3.getId(), a.getSeatId());
		assertEquals(seat1.getId(), c.getSeatId());
		assertEquals(1, c.getSeatNumber());
		assertOneBookingPerSeat();
	}

	@Test
	void applyChainWithCounterMove() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");
		Booking c = reserve(seat3, "C");
		List<Source> sources = sources(a, b);
		Plan shown = plan(sources, ids(seat2, seat3));

		assertTrue(apply(sources, ids(seat2, seat3), shown));

		assertEquals(seat2.getId(), a.getSeatId());
		assertEquals(seat3.getId(), b.getSeatId());
		assertEquals(seat1.getId(), c.getSeatId());
		assertOneBookingPerSeat();
	}

	@Test
	void applyKeepsIdentityAndData() {
		Booking a = reserve(seat1, "Meier");
		a.setFirstName("Anna");
		a.setEMail("anna@example.org");
		a.setPaymentStatus(PaymentStatus.CARD);
		a.setPrice(1500);
		a.setPaidAmount(1400);
		a.setCollected(true);
		a.setReservationDate(LocalDate.of(2026, 11, 1));
		a.setPaymentDate(LocalDate.of(2026, 11, 2));
		a.setCollectionDate(LocalDate.of(2026, 11, 3));
		a.setComment("Fensterplatz");
		a.getUnknown().put("x-test-feld", TextNode.valueOf("wert"));
		seat3.getParent().setCategory("Loge");
		seat3.setWheelchairAccessible(true);
		a.setRowLabel("Z"); // eine veraltete Momentaufnahme: der neue Sitz zählt
		a.setTableNumber(9);
		Booking before = a.copy();
		List<Source> sources = sources(a);
		Plan shown = plan(sources, ids(seat3));

		assertTrue(apply(sources, ids(seat3), shown));

		assertEquals(before.getId(), a.getId());
		assertEquals("Meier", a.getLastName());
		assertEquals("Anna", a.getFirstName());
		assertEquals("anna@example.org", a.getEMail());
		assertEquals(PaymentStatus.CARD, a.getPaymentStatus());
		assertEquals(1400, a.getPaidAmount());
		assertEquals(1500, a.getPrice());
		assertTrue(a.isCollected());
		assertEquals(LocalDate.of(2026, 11, 1), a.getReservationDate());
		assertEquals(LocalDate.of(2026, 11, 2), a.getPaymentDate());
		assertEquals(LocalDate.of(2026, 11, 3), a.getCollectionDate());
		assertEquals("Fensterplatz", a.getComment());
		assertEquals(before.getUnknown().asMap(), a.getUnknown().asMap());
		assertEquals("A", a.getRowLabel());
		assertEquals(1, a.getTableNumber());
		assertEquals(3, a.getSeatNumber());
		assertEquals("Loge", a.getCategory());
		assertTrue(a.isWheelchairAccessible());
	}

	@Test
	void applyDropsLeftoversOfAFreeTarget() {
		Booking a = reserve(seat1, "A");
		Booking leftover = bookingOf(presentation, seat3);
		leftover.setComment("alter Kommentar");
		List<Source> sources = sources(a);
		Plan shown = plan(sources, ids(seat3));

		assertTrue(apply(sources, ids(seat3), shown));

		assertFalse(presentation.getBookings().contains(leftover));
		assertSame(a, bookingOf(presentation, seat3));
		assertOneBookingPerSeat();
	}

	@Test
	void applyRefusesWhenAFreeTargetGotTaken() {
		Booking a = reserve(seat1, "A");
		List<Source> sources = sources(a);
		Plan shown = plan(sources, ids(seat3));
		reserve(seat3, "Fremd");
		List<Booking> bookingsBefore = new ArrayList<>(presentation.getBookings());
		List<String> seatIdsBefore = bookingsBefore.stream().map(Booking::getSeatId).toList();

		assertFalse(apply(sources, ids(seat3), shown));

		assertUnchanged(bookingsBefore, seatIdsBefore);
	}

	@Test
	void applyRefusesWhenAFreeTargetGotLeftovers() {
		Booking a = reserve(seat1, "A");
		List<Source> sources = sources(a);
		Plan shown = plan(sources, ids(seat3));
		bookingOf(presentation, seat3).setComment("Rest");
		List<Booking> bookingsBefore = new ArrayList<>(presentation.getBookings());
		List<String> seatIdsBefore = bookingsBefore.stream().map(Booking::getSeatId).toList();

		assertFalse(apply(sources, ids(seat3), shown));

		assertUnchanged(bookingsBefore, seatIdsBefore);
	}

	@Test
	void applyRefusesWhenASourceCarriesAnotherId() {
		Booking a = reserve(seat1, "A");
		reserve(seat3, "C");
		List<Source> sources = sources(a);
		Plan shown = plan(sources, ids(seat3));
		a.setId("fremde-Kennung"); // eine fremde Änderung legt eine andere Buchung in dasselbe Objekt
		List<Booking> bookingsBefore = new ArrayList<>(presentation.getBookings());
		List<String> seatIdsBefore = bookingsBefore.stream().map(Booking::getSeatId).toList();

		assertFalse(apply(sources, ids(seat3), shown));

		assertUnchanged(bookingsBefore, seatIdsBefore);
	}

	@Test
	void applyIgnoresChangedDataOfAMovedBooking() {
		Booking a = reserve(seat1, "A");
		Booking c = reserve(seat3, "C");
		List<Source> sources = sources(a);
		Plan shown = plan(sources, ids(seat3));
		c.setLastName("Anders"); // eine andere Kasse ändert den Namen der Gegenzug-Buchung

		assertTrue(apply(sources, ids(seat3), shown));

		assertEquals(seat1.getId(), c.getSeatId());
		assertEquals("Anders", c.getLastName());
		assertEquals(seat3.getId(), a.getSeatId());
	}

	// Abholmarkierung

	private static void collect(Booking booking) {
		booking.setCollected(true);
		booking.setCollectionDate(LocalDate.of(2026, 11, 20));
	}

	@Test
	void clearCollectedRemovesMarkAndDate() {
		Booking a = reserve(seat1, "A");
		Booking b = reserve(seat2, "B");
		Booking c = reserve(seat3, "C");
		Booking d = reserve(seat4, "D");
		collect(a);
		collect(c);
		collect(d); // nicht am Vorgang beteiligt
		List<Source> sources = sources(a, b);
		Plan shown = plan(sources, ids(seat2, seat3)); // A 1→2, B 2→3, C 3→1 (Gegenzug)
		assertTrue(apply(sources, ids(seat2, seat3), shown));

		BookingMove.clearCollected(shown);

		for (Booking cleared : List.of(a, c)) {
			assertFalse(cleared.isCollected());
			assertNull(cleared.getCollectionDate());
		}
		assertFalse(b.isCollected());
		assertTrue(d.isCollected());
		assertEquals(LocalDate.of(2026, 11, 20), d.getCollectionDate());
	}

	@Test
	void collectedCountCountsSourcesAndCounterMoves() {
		Booking a = reserve(seat1, "A");
		reserve(seat2, "B");
		Booking c = reserve(seat3, "C");
		collect(a);
		collect(c);

		Plan result = plan(sources(a, bookingOf(presentation, seat2)), ids(seat3, seat4)); // A 1→3, B 2→4, C 3→1

		assertEquals(3, result.steps().size());
		assertEquals(2, BookingMove.collectedCount(result));
		assertEquals(0, BookingMove.collectedCount(plan(sources(bookingOf(presentation, seat2)), ids(seat4))));
	}
}
