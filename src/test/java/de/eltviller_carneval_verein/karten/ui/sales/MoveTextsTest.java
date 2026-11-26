package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingMove;
import de.eltviller_carneval_verein.karten.model.BookingMove.Plan;
import de.eltviller_carneval_verein.karten.model.BookingMove.Refusal;
import de.eltviller_carneval_verein.karten.model.BookingMove.Source;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Syncs;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

/** Texte und Auswahl der Quellen beim Verschieben von Buchungen (KARV-140). */
class MoveTextsTest {

	private final SeatingPlan plan = new Hall().addSeatingPlan();
	private final Presentation presentation = new Event().addPresentation("Prunksitzung");
	private final Seat a3s1;
	private final Seat a3s2;
	private final Seat a3s3;
	private final Seat a3s4;
	private final Seat b1s4;
	private final Seat b1s5;
	private final Seat b1s6;
	private final Seat b1s7;

	MoveTextsTest() {
		TableRow rowA = plan.addRow();
		rowA.setLabel("A");
		Table a3 = plan.addTable(rowA);
		a3.setTableNumber(3);
		a3s1 = a3.addSeat(1);
		a3s2 = a3.addSeat(2);
		a3s3 = a3.addSeat(3);
		a3s4 = a3.addSeat(4);
		TableRow rowB = plan.addRow();
		rowB.setLabel("B");
		Table b1 = plan.addTable(rowB);
		b1.setTableNumber(1);
		b1s4 = b1.addSeat(4);
		b1s5 = b1.addSeat(5);
		b1s6 = b1.addSeat(6);
		b1s7 = b1.addSeat(7);
		presentation.setSeatingPlanId(plan.getId());
		Syncs.sync(presentation, plan);
	}

	private Booking on(Seat seat) {
		return presentation.getBookings().stream().filter(b -> seat.getId().equals(b.getSeatId())).findFirst().orElseThrow();
	}

	private Booking person(Seat seat, String firstName, String lastName) {
		Booking booking = on(seat);
		booking.setFirstName(firstName);
		booking.setLastName(lastName);
		return booking;
	}

	private Plan planOf(List<Booking> sources, Seat... targets) {
		return BookingMove.plan(presentation, plan, sources.stream().map(Source::of).toList(),
				java.util.Arrays.stream(targets).map(Seat::getId).toList(), Set.of());
	}

	@Test
	void movableSkipsFreeAndUnassigned() {
		Booking anna = person(a3s2, "Anna", "Müller");
		Booking free = on(a3s4);
		Booking lost = person(a3s3, null, "Schmidt");
		lost.setUnassigned(true);
		Booking peter = person(b1s4, "Peter", "Schmidt");
		Booking early = person(a3s1, null, "Zweiter");

		assertEquals(List.of(early, anna, peter), MoveTexts.movable(List.of(peter, free, lost, anna, early)));
		assertEquals(List.of(), MoveTexts.movable(List.of(free, lost)));
	}

	@Test
	void menuTextForOneManyNone() {
		Booking anna = person(a3s2, "Anna", "Müller");
		Booking peter = person(b1s4, "Peter", "Schmidt");
		Booking free = on(a3s1);

		assertEquals("Buchung verschieben…", MoveTexts.menuText(List.of(anna, free)));
		assertEquals("Ausgewählte Buchungen verschieben… (2)", MoveTexts.menuText(List.of(anna, peter, free)));
		assertEquals("Buchung verschieben…", MoveTexts.menuText(List.of(free)));
	}

	@Test
	void menuIsDisabledWithoutEditModeOrMovableBooking() {
		Booking anna = person(a3s2, "Anna", "Müller");
		Booking free = on(a3s1);

		assertFalse(MoveTexts.menuDisabled(List.of(anna), true));
		assertTrue(MoveTexts.menuDisabled(List.of(anna), false), "ohne Bearbeitungsmodus");
		assertTrue(MoveTexts.menuDisabled(List.of(free), true), "keine belegende Buchung");
		assertTrue(MoveTexts.menuDisabled(List.of(), true));
	}

	@Test
	void barTextForOneSource() {
		Booking anna = person(a3s2, "Anna", "Müller");

		assertEquals("Ziel wählen für Anna Müller (Tisch A3, Sitz 2): Platz anklicken.",
				MoveTexts.barText(List.of(Source.of(anna)), 0));
	}

	@Test
	void barTextForOneSourceWithoutName() {
		Booking nameless = on(a3s2);
		nameless.setComment("reserviert");
		nameless.setPaymentStatus(de.eltviller_carneval_verein.karten.model.PaymentStatus.CASH);

		assertEquals("Ziel wählen für Buchung ohne Namen (Tisch A3, Sitz 2): Platz anklicken.",
				MoveTexts.barText(List.of(Source.of(nameless)), 0));
	}

	@Test
	void barTextForMany() {
		List<Source> sources = List.of(Source.of(person(a3s1, null, "A")), Source.of(person(a3s2, null, "B")),
				Source.of(person(a3s3, null, "C")), Source.of(person(a3s4, null, "D")));

		assertEquals("Ziele wählen für 4 Buchungen: 2 von 4 Plätzen gewählt.", MoveTexts.barText(sources, 2));
	}

	@Test
	void tooManyAndLockedTexts() {
		assertEquals("Es sind schon 3 Plätze gewählt. Wähle zuerst einen ab.", MoveTexts.tooMany(3));
		assertEquals("Tisch A3, Sitz 2 ist gesperrt (siehe „Gesperrte Einträge“) und lässt sich nicht verschieben oder belegen.",
				MoveTexts.locked(a3s2));
	}

	@Test
	void confirmLinesMarkCounterMoves() {
		Booking anna = person(a3s2, "Anna", "Müller");
		person(b1s4, "Peter", "Schmidt");

		Plan swap = planOf(List.of(anna), b1s4);

		assertEquals(List.of("Anna Müller: Tisch A3, Sitz 2 → Tisch B1, Sitz 4",
				"Peter Schmidt: Tisch B1, Sitz 4 → Tisch A3, Sitz 2 (Tausch)"), MoveTexts.confirmLines(swap));
	}

	@Test
	void confirmLineWithoutCustomerName() {
		Booking nameless = on(a3s2);
		nameless.setPaymentStatus(de.eltviller_carneval_verein.karten.model.PaymentStatus.CASH);

		assertEquals(List.of("Buchung ohne Namen: Tisch A3, Sitz 2 → Tisch B1, Sitz 5"),
				MoveTexts.confirmLines(planOf(List.of(nameless), b1s5)));
	}

	@Test
	void titleFollowsTheNumberOfMoves() {
		Booking anna = person(a3s2, "Anna", "Müller");
		person(b1s4, "Peter", "Schmidt");

		assertEquals("Buchung verschieben", MoveTexts.title(planOf(List.of(anna), b1s5)));
		assertEquals("Buchungen verschieben", MoveTexts.title(planOf(List.of(anna), b1s4)));
	}

	@Test
	void collectedLineSingleMove() {
		Booking anna = person(a3s2, "Anna", "Müller");
		anna.setCollected(true);

		assertEquals("Die Buchung ist abgeholt.", MoveTexts.collectedLine(planOf(List.of(anna), b1s5)));
	}

	@Test
	void collectedLineOneOfMany() {
		Booking anna = person(a3s2, "Anna", "Müller");
		person(b1s4, "Peter", "Schmidt").setCollected(true);

		assertEquals("1 der Buchungen ist abgeholt.", MoveTexts.collectedLine(planOf(List.of(anna), b1s4)));
	}

	@Test
	void collectedLineSeveral() {
		Booking anna = person(a3s2, "Anna", "Müller");
		anna.setCollected(true);
		person(b1s4, "Peter", "Schmidt").setCollected(true);

		assertEquals("2 der Buchungen sind abgeholt.", MoveTexts.collectedLine(planOf(List.of(anna), b1s4)));
	}

	@Test
	void collectedLineIsNullWithoutCollected() {
		Booking anna = person(a3s2, "Anna", "Müller");
		person(b1s4, "Peter", "Schmidt");

		assertNull(MoveTexts.collectedLine(planOf(List.of(anna), b1s4)));
	}

	@Test
	void successForOneMove() {
		Booking anna = person(a3s2, "Anna", "Müller");

		assertEquals("Buchung von Tisch A3, Sitz 2 auf Tisch B1, Sitz 5 verschoben",
				MoveTexts.success(planOf(List.of(anna), b1s5)));
	}

	@Test
	void successForSwap() {
		Booking anna = person(a3s2, "Anna", "Müller");
		person(b1s4, "Peter", "Schmidt");

		assertEquals("Buchungen auf Tisch A3, Sitz 2 und Tisch B1, Sitz 4 getauscht",
				MoveTexts.success(planOf(List.of(anna), b1s4)));
	}

	@Test
	void successForMany() {
		List<Booking> sources = List.of(person(a3s1, null, "A"), person(a3s2, null, "B"), person(a3s3, null, "C"),
				person(a3s4, null, "D"));

		assertEquals("4 Buchungen verschoben", MoveTexts.success(planOf(sources, b1s4, b1s5, b1s6, b1s7)));
	}

	@Test
	void successForManyWithCounterMoves() {
		List<Booking> sources = List.of(person(a3s1, null, "A"), person(a3s2, null, "B"), person(a3s3, null, "C"),
				person(a3s4, null, "D"));
		person(b1s4, null, "E");
		person(b1s5, null, "F");

		Plan moves = planOf(sources, b1s4, b1s5, b1s6, b1s7);

		assertEquals(6, moves.steps().size());
		assertEquals("4 Buchungen verschoben, 2 weitere im Tausch", MoveTexts.success(moves));
	}

	@Test
	void successCountsMovesNotSources() {
		Booking a = person(a3s1, null, "A");
		Booking b = person(a3s2, null, "B");
		// A sitzt schon auf seinem Ziel: zwei Quellen, ein Zug
		Plan one = planOf(List.of(a, b), a3s1, a3s3);

		assertEquals(1, one.steps().size());
		assertEquals("Buchung von Tisch A3, Sitz 2 auf Tisch A3, Sitz 3 verschoben", MoveTexts.success(one));
	}

	@Test
	void refusalTexts() {
		Booking anna = person(a3s2, "Anna", "Müller");

		Plan noPlan = BookingMove.plan(presentation, null, List.of(Source.of(anna)), List.of(a3s3.getId()), Set.of());
		assertEquals(PlaceAssignment.noPlanText(presentation), MoveTexts.refusal(noPlan, presentation));

		Plan stale = BookingMove.plan(presentation, plan, List.of(Source.of(anna)), List.of("gibt-es-nicht"), Set.of());
		assertEquals(Refusal.STALE, stale.refusal());
		assertEquals(MoveTexts.STALE, MoveTexts.refusal(stale, presentation));

		Plan count = BookingMove.plan(presentation, plan, List.of(Source.of(anna)), List.of(), Set.of());
		assertEquals(Refusal.COUNT, count.refusal());
		assertEquals(MoveTexts.STALE, MoveTexts.refusal(count, presentation));

		Plan locked = BookingMove.plan(presentation, plan, List.of(Source.of(anna)), List.of(a3s3.getId()),
				Set.of(a3s3.getId()));
		assertEquals(MoveTexts.locked(a3s3), MoveTexts.refusal(locked, presentation));

		Plan nothing = BookingMove.plan(presentation, plan, List.of(Source.of(anna)), List.of(a3s2.getId()), Set.of());
		assertEquals(MoveTexts.NOTHING_TO_DO, MoveTexts.refusal(nothing, presentation));
	}

	@Test
	void refusalOfAnExecutablePlanIsRejected() {
		Plan plan1 = planOf(List.of(person(a3s2, "Anna", "Müller")), b1s5);

		assertThrows(IllegalArgumentException.class, () -> MoveTexts.refusal(plan1, presentation));
	}

	@Test
	void targetRowFreeTakenAndSource() {
		Booking anna = person(a3s2, "Anna", "Müller");
		Booking nameless = on(a3s3);
		nameless.setComment("reserviert");
		nameless.setPaymentStatus(de.eltviller_carneval_verein.karten.model.PaymentStatus.CASH);

		assertEquals("Tisch A3, Sitz 1 – frei", MoveTexts.targetRow(a3s1, null, false));
		assertEquals("Tisch A3, Sitz 2 – Anna Müller", MoveTexts.targetRow(a3s2, anna, false));
		assertEquals("Tisch A3, Sitz 3 – Buchung ohne Namen", MoveTexts.targetRow(a3s3, nameless, false));
		assertEquals("Tisch A3, Sitz 2 – Anna Müller (wird verschoben)", MoveTexts.targetRow(a3s2, anna, true));
	}
}
