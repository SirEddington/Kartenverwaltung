package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

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

	@Test
	void equalPlacesAreOrderedBySeatId() {
		seat2.setSeatNumber(1); // Sitz 1 und 2 tragen beide „A1, Sitz 1“
		Seat lower = seat1.getId().compareTo(seat2.getId()) < 0 ? seat1 : seat2;
		Seat higher = lower == seat1 ? seat2 : seat1;
		Booking onLower = reserve(lower, "unten");
		Booking onHigher = reserve(higher, "oben");

		Plan first = plan(sources(onLower, onHigher), ids(seat3, seat4));
		Plan second = plan(sources(onHigher, onLower), ids(seat4, seat3));

		assertStep(first.steps().get(0), onLower, lower, seat3, false);
		assertStep(first.steps().get(1), onHigher, higher, seat4, false);
		assertTrue(first.sameAs(second));
		assertTrue(second.sameAs(first));
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
}
