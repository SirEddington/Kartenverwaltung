package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingMove;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.ui.sales.MoveAction.Outcome;

/** Der Kern des Verschiebens ohne Oberfläche: Antwort, Ausführen, Abholmarkierung (KARV-140). */
class MoveActionTest {

	private final MoveFixture fixture = new MoveFixture("Samstag");
	private final SeatingPlan plan = fixture.plan;
	private final Presentation presentation = fixture.presentation;
	private final Seat seat1;
	private final Seat seat2;
	private final Seat seat3;

	MoveActionTest() {
		Table table = fixture.table("A");
		seat1 = table.addSeat(1);
		seat2 = table.addSeat(2);
		seat3 = table.addSeat(3);
		fixture.sync();
	}

	@AfterEach
	void forget() {
		LockedSeats.reset();
	}

	private BookingMove.Plan planFor(List<BookingMove.Source> sources, List<String> targets) {
		return BookingMove.plan(presentation, plan, sources, targets, Set.of());
	}

	private Outcome execute(Booking source, Seat target, Supplier<Optional<Boolean>> answer) {
		return execute(source, target, () -> true, answer);
	}

	private Outcome execute(Booking source, Seat target, BooleanSupplier stillCurrent,
			Supplier<Optional<Boolean>> answer) {
		List<BookingMove.Source> sources = List.of(BookingMove.Source.of(source));
		List<String> targets = List.of(target.getId());
		return MoveAction.execute(presentation, plan, sources, targets, planFor(sources, targets), stillCurrent, answer);
	}

	@Test
	void lockedSourceSeatIsTheFirstLockedSeatOfASource() {
		Booking anna = fixture.reserve(seat1, "Müller");
		Booking bert = fixture.reserve(seat2, "Schmidt");
		List<BookingMove.Source> sources = List.of(BookingMove.Source.of(anna), BookingMove.Source.of(bert));

		assertNull(MoveAction.lockedSourceSeat(plan, sources, Set.of()));
		assertNull(MoveAction.lockedSourceSeat(plan, sources, Set.of(seat3.getId())), "ein gesperrtes Ziel zählt nicht");
		assertSame(seat2, MoveAction.lockedSourceSeat(plan, sources, Set.of(seat2.getId(), seat3.getId())));
		assertSame(seat1, MoveAction.lockedSourceSeat(plan, sources, Set.of(seat2.getId(), seat1.getId())),
				"der erste in der Reihenfolge der Quellen");
	}

	@Test
	void lockedSourceSeatIgnoresASeatNoLongerInThePlan() {
		Booking anna = fixture.reserve(seat1, "Müller");
		List<BookingMove.Source> sources = List.of(BookingMove.Source.of(anna));
		seat1.getParent().deleteSeat(seat1);

		assertNull(MoveAction.lockedSourceSeat(plan, sources, Set.of(seat1.getId())));
	}

	@Test
	void cancelLeavesEverythingUnchanged() {
		Booking anna = fixture.reserve(seat1, "Müller");

		assertEquals(Outcome.CANCELLED, execute(anna, seat3, Optional::empty));

		assertEquals(seat1.getId(), anna.getSeatId());
		assertEquals(anna, fixture.bookingOn(seat1));
	}

	@Test
	void confirmedMoveIsDone() {
		Booking anna = fixture.reserve(seat1, "Müller");

		assertEquals(Outcome.DONE, execute(anna, seat3, () -> Optional.of(true)));

		assertEquals(seat3.getId(), anna.getSeatId());
		assertEquals(anna, fixture.bookingOn(seat3));
		assertFalse(fixture.bookingOn(seat1).isTaken(), "der alte Platz ist wieder frei");
	}

	@Test
	void keepCollectedLeavesTheMark() {
		Booking anna = fixture.reserve(seat1, "Müller");
		anna.setCollected(true);
		anna.setCollectionDate(LocalDate.of(2026, 11, 20));

		assertEquals(Outcome.DONE, execute(anna, seat3, () -> Optional.of(true)));

		assertTrue(anna.isCollected());
		assertEquals(LocalDate.of(2026, 11, 20), anna.getCollectionDate());
	}

	@Test
	void dropCollectedClearsMarkAndDate() {
		Booking anna = fixture.reserve(seat1, "Müller");
		anna.setCollected(true);
		anna.setCollectionDate(LocalDate.of(2026, 11, 20));

		assertEquals(Outcome.DONE, execute(anna, seat3, () -> Optional.of(false)));

		assertEquals(seat3.getId(), anna.getSeatId());
		assertFalse(anna.isCollected());
		assertNull(anna.getCollectionDate());
	}

	@Test
	void staleWhenTheTargetChangedWhileAsking() {
		Booking anna = fixture.reserve(seat1, "Müller");

		Outcome outcome = execute(anna, seat3, () -> {
			fixture.reserve(seat3, "Schmidt"); // ein anderer Rechner verkauft das Ziel, während der Dialog offen ist
			return Optional.of(true);
		});

		assertEquals(Outcome.STALE, outcome);
		assertEquals(seat1.getId(), anna.getSeatId());
		assertEquals("Schmidt", fixture.bookingOn(seat3).getLastName());
	}

	@Test
	void staleWhenASeatGotLockedWhileAsking() {
		Booking anna = fixture.reserve(seat1, "Müller");
		Booking free = fixture.bookingOn(seat3);
		UnitKey target = UnitKey.booking(presentation.getParent().getId(), presentation.getId(), seat3.getId());

		Outcome outcome = execute(anna, seat3, () -> {
			// die Einheit des Ziels kommt in die Liste der gesperrten Einträge, während der Dialog offen ist
			LockedSeats.install(() -> Set.of(target), () -> {
			});
			return Optional.of(true);
		});

		assertEquals(Outcome.STALE, outcome);
		assertEquals(seat1.getId(), anna.getSeatId());
		assertEquals(anna, fixture.bookingOn(seat1));
		assertEquals(free, fixture.bookingOn(seat3));
		assertFalse(free.isTaken());
	}

	@Test
	void staleWhenTheViewMovedOnWhileAsking() {
		Booking anna = fixture.reserve(seat1, "Müller");
		Booking free = fixture.bookingOn(seat3);

		// Die Ansicht hat während der Rückfrage auf ein anderes Event oder eine andere Vorstellung gewechselt
		assertEquals(Outcome.STALE, execute(anna, seat3, () -> false, () -> Optional.of(true)));

		assertEquals(seat1.getId(), anna.getSeatId());
		assertEquals(anna, fixture.bookingOn(seat1));
		assertEquals(free, fixture.bookingOn(seat3));
		assertFalse(free.isTaken());
	}
}
