package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingMove;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Syncs;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.ui.sales.MoveAction.Outcome;

/** Der Kern des Verschiebens ohne Oberfläche: Antwort, Ausführen, Abholmarkierung (KARV-140). */
class MoveActionTest {

	private final SeatingPlan plan = new Hall().addSeatingPlan();
	private final Presentation presentation = new Event().addPresentation("Samstag");
	private final Seat seat1;
	private final Seat seat2;
	private final Seat seat3;

	MoveActionTest() {
		TableRow row = plan.addRow();
		row.setLabel("A");
		Table table = plan.addTable(row);
		seat1 = table.addSeat(1);
		seat2 = table.addSeat(2);
		seat3 = table.addSeat(3);
		presentation.setSeatingPlanId(plan.getId());
		Syncs.sync(presentation, plan);
	}

	@AfterEach
	void forget() {
		LockedSeats.reset();
	}

	private Booking bookingOn(Seat seat) {
		return presentation.getBookings().stream().filter(b -> seat.getId().equals(b.getSeatId()) && !b.isUnassigned())
				.findFirst().orElseThrow();
	}

	private Booking reserve(Seat seat, String lastName) {
		Booking booking = bookingOn(seat);
		booking.setLastName(lastName);
		return booking;
	}

	private BookingMove.Plan planFor(List<BookingMove.Source> sources, List<String> targets) {
		return BookingMove.plan(presentation, plan, sources, targets, Set.of());
	}

	private Outcome execute(Booking source, Seat target, java.util.function.Supplier<Optional<Boolean>> answer) {
		return execute(source, target, () -> true, answer);
	}

	private Outcome execute(Booking source, Seat target, BooleanSupplier stillCurrent,
			java.util.function.Supplier<Optional<Boolean>> answer) {
		List<BookingMove.Source> sources = List.of(BookingMove.Source.of(source));
		List<String> targets = List.of(target.getId());
		return MoveAction.execute(presentation, plan, sources, targets, planFor(sources, targets), stillCurrent, answer);
	}

	@Test
	void cancelLeavesEverythingUnchanged() {
		Booking anna = reserve(seat1, "Müller");

		assertEquals(Outcome.CANCELLED, execute(anna, seat3, Optional::empty));

		assertEquals(seat1.getId(), anna.getSeatId());
		assertEquals(anna, bookingOn(seat1));
	}

	@Test
	void confirmedMoveIsDone() {
		Booking anna = reserve(seat1, "Müller");

		assertEquals(Outcome.DONE, execute(anna, seat3, () -> Optional.of(true)));

		assertEquals(seat3.getId(), anna.getSeatId());
		assertEquals(anna, bookingOn(seat3));
		assertFalse(bookingOn(seat1).isTaken(), "der alte Platz ist wieder frei");
	}

	@Test
	void keepCollectedLeavesTheMark() {
		Booking anna = reserve(seat1, "Müller");
		anna.setCollected(true);
		anna.setCollectionDate(LocalDate.of(2026, 11, 20));

		assertEquals(Outcome.DONE, execute(anna, seat3, () -> Optional.of(true)));

		assertTrue(anna.isCollected());
		assertEquals(LocalDate.of(2026, 11, 20), anna.getCollectionDate());
	}

	@Test
	void dropCollectedClearsMarkAndDate() {
		Booking anna = reserve(seat1, "Müller");
		anna.setCollected(true);
		anna.setCollectionDate(LocalDate.of(2026, 11, 20));

		assertEquals(Outcome.DONE, execute(anna, seat3, () -> Optional.of(false)));

		assertEquals(seat3.getId(), anna.getSeatId());
		assertFalse(anna.isCollected());
		assertNull(anna.getCollectionDate());
	}

	@Test
	void staleWhenTheTargetChangedWhileAsking() {
		Booking anna = reserve(seat1, "Müller");

		Outcome outcome = execute(anna, seat3, () -> {
			reserve(seat3, "Schmidt"); // ein anderer Rechner verkauft das Ziel, während der Dialog offen ist
			return Optional.of(true);
		});

		assertEquals(Outcome.STALE, outcome);
		assertEquals(seat1.getId(), anna.getSeatId());
		assertEquals("Schmidt", bookingOn(seat3).getLastName());
	}

	@Test
	void staleWhenTheViewMovedOnWhileAsking() {
		Booking anna = reserve(seat1, "Müller");
		Booking free = bookingOn(seat3);

		// Die Ansicht hat während der Rückfrage auf ein anderes Event oder eine andere Vorstellung gewechselt
		assertEquals(Outcome.STALE, execute(anna, seat3, () -> false, () -> Optional.of(true)));

		assertEquals(seat1.getId(), anna.getSeatId());
		assertEquals(anna, bookingOn(seat1));
		assertEquals(free, bookingOn(seat3));
		assertFalse(free.isTaken());
	}
}
