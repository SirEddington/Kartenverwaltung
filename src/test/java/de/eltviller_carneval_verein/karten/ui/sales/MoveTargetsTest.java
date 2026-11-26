package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingMove.Source;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.ui.sales.MoveTargets.Click;

/** Zustand der Zielwahl beim Verschieben von Buchungen (KARV-140). */
class MoveTargetsTest {

	private final MoveFixture fixture = new MoveFixture("Prunksitzung");
	private final SeatingPlan plan = fixture.plan;
	private final Presentation presentation = fixture.presentation;
	private final Seat a3s1;
	private final Seat a3s2;
	private final Seat a3s3;
	private final Seat a3s4;

	MoveTargetsTest() {
		Table a3 = fixture.table("A", 3);
		a3s1 = a3.addSeat(1);
		a3s2 = a3.addSeat(2);
		a3s3 = a3.addSeat(3);
		a3s4 = a3.addSeat(4);
		fixture.sync();
	}

	private MoveTargets forOne() {
		return new MoveTargets(presentation, List.of(Source.of(fixture.reserve(a3s1, "A"))));
	}

	private MoveTargets forTwo() {
		return new MoveTargets(presentation, List.of(Source.of(fixture.reserve(a3s1, "A")), Source.of(fixture.reserve(a3s2, "B"))));
	}

	@Test
	void toggleAddsAndRemoves() {
		MoveTargets targets = forTwo();

		assertEquals(Click.ADDED, targets.toggle(a3s4.getId(), Set.of()));
		assertEquals(Click.ADDED, targets.toggle(a3s3.getId(), Set.of()));
		assertEquals(List.of(a3s4.getId(), a3s3.getId()), targets.targets());
		assertTrue(targets.isTarget(a3s3.getId()));

		assertEquals(Click.REMOVED, targets.toggle(a3s4.getId(), Set.of()));
		assertEquals(List.of(a3s3.getId()), targets.targets());
		assertFalse(targets.isTarget(a3s4.getId()));
	}

	@Test
	void sourceSeatCanBeATarget() {
		MoveTargets targets = forOne();

		assertEquals(Click.ADDED, targets.toggle(a3s1.getId(), Set.of()));
		assertTrue(targets.isTarget(a3s1.getId()));
	}

	@Test
	void toggleOnALockedSeatIsLocked() {
		MoveTargets targets = forOne();

		assertEquals(Click.LOCKED, targets.toggle(a3s3.getId(), Set.of(a3s3.getId())));
		assertEquals(List.of(), targets.targets());
	}

	@Test
	void toggleBeyondTheCountIsFull() {
		MoveTargets targets = forOne();
		targets.toggle(a3s3.getId(), Set.of());

		assertEquals(Click.FULL, targets.toggle(a3s4.getId(), Set.of()));
		assertEquals(List.of(a3s3.getId()), targets.targets());

		assertEquals(Click.REMOVED, targets.toggle(a3s3.getId(), Set.of()), "ein Ziel lässt sich auch bei voller Wahl abwählen");
		assertEquals(List.of(), targets.targets());
	}

	@Test
	void completeWhenAsManyTargetsAsSources() {
		MoveTargets targets = forTwo();
		assertFalse(targets.complete());

		targets.toggle(a3s3.getId(), Set.of());
		assertFalse(targets.complete());

		targets.toggle(a3s4.getId(), Set.of());
		assertTrue(targets.complete());
	}

	@Test
	void sourcesCurrentTurnsFalseWhenASourceWasCancelled() {
		Booking anna = fixture.reserve(a3s1, "A");
		MoveTargets targets = new MoveTargets(presentation, List.of(Source.of(anna)));
		assertTrue(targets.sourcesCurrent(plan));
		assertTrue(targets.isSource(anna));
		assertFalse(targets.isSource(fixture.reserve(a3s2, "B")));

		anna.setUnassigned(true);

		assertFalse(targets.sourcesCurrent(plan));
		assertFalse(targets.sourcesCurrent(null));
	}

	@Test
	void dropGoneTargetsRemovesLockedAndMissingSeats() {
		MoveTargets targets = new MoveTargets(presentation, List.of(Source.of(fixture.reserve(a3s1, "A")),
				Source.of(fixture.reserve(a3s2, "B")), Source.of(fixture.reserve(a3s3, "C"))));
		targets.toggle(a3s4.getId(), Set.of());
		targets.toggle(a3s3.getId(), Set.of());
		targets.toggle("gibt-es-nicht", Set.of());

		targets.dropGoneTargets(plan, Set.of(a3s4.getId()));

		assertEquals(List.of(a3s3.getId()), targets.targets());

		targets.dropGoneTargets(null, Set.of());

		assertEquals(List.of(), targets.targets());
	}

	@Test
	void barTextFollowsTheChoice() {
		MoveTargets targets = forTwo();
		assertEquals("Ziele wählen für 2 Buchungen: 0 von 2 Plätzen gewählt.", targets.barText());

		targets.toggle(a3s3.getId(), Set.of());

		assertEquals("Ziele wählen für 2 Buchungen: 1 von 2 Plätzen gewählt.", targets.barText());
	}
}
