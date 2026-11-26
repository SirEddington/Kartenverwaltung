package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingMove.Source;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Syncs;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.ui.sales.MoveTargetDialog.Row;

/** Die Zeilen der Platzliste beim Verschieben in der Kartentabelle (KARV-140). */
class MoveTargetDialogTest {

	private final SeatingPlan plan = new Hall().addSeatingPlan();
	private final Presentation presentation = new Event().addPresentation("Prunksitzung");
	private final Seat b1s1;
	private final Seat b1s2;
	private final Seat a3s1;
	private final Seat a3s2;
	private final Seat a3s3;

	MoveTargetDialogTest() {
		// Reihe B vor Reihe A angelegt und die Sitze von A3 rückwärts: Die Liste ordnet nach dem Platz, nicht nach Anlage
		TableRow rowB = plan.addRow();
		rowB.setLabel("B");
		Table b1 = plan.addTable(rowB);
		b1.setTableNumber(1);
		b1s1 = b1.addSeat(1);
		b1s2 = b1.addSeat(2);
		TableRow rowA = plan.addRow();
		rowA.setLabel("A");
		Table a3 = plan.addTable(rowA);
		a3.setTableNumber(3);
		a3s3 = a3.addSeat(3);
		a3s2 = a3.addSeat(2);
		a3s1 = a3.addSeat(1);
		presentation.setSeatingPlanId(plan.getId());
		Syncs.sync(presentation, plan);
	}

	private Booking bookingOn(Seat seat) {
		return presentation.getBookings().stream().filter(b -> seat.getId().equals(b.getSeatId())).findFirst().orElseThrow();
	}

	private Booking person(Seat seat, String firstName, String lastName) {
		Booking booking = bookingOn(seat);
		booking.setFirstName(firstName);
		booking.setLastName(lastName);
		return booking;
	}

	private static List<String> seatIds(List<Row> rows) {
		return rows.stream().map(Row::seatId).toList();
	}

	@Test
	void rowsListEverySeatInPlaceOrder() {
		List<Source> sources = List.of(Source.of(person(b1s1, "Anna", "Müller")));

		List<Row> rows = MoveTargetDialog.rows(presentation, plan, sources, Set.of());

		assertEquals(List.of(a3s1.getId(), a3s2.getId(), a3s3.getId(), b1s1.getId(), b1s2.getId()), seatIds(rows));
		assertEquals("Tisch A3, Sitz 1 – frei", rows.get(0).text());
	}

	@Test
	void rowsSkipLockedSeats() {
		List<Source> sources = List.of(Source.of(person(b1s1, "Anna", "Müller")));

		List<Row> rows = MoveTargetDialog.rows(presentation, plan, sources, Set.of(a3s2.getId(), b1s2.getId()));

		assertEquals(List.of(a3s1.getId(), a3s3.getId(), b1s1.getId()), seatIds(rows));
	}

	@Test
	void rowsNameOccupantAndMarkSourceSeats() {
		List<Source> sources = List.of(Source.of(person(a3s2, "Anna", "Müller")));
		person(a3s3, "Peter", "Schmidt");
		bookingOn(a3s1).setComment("alter Kommentar"); // Reste belegen den Sitz nicht
		person(b1s1, "", "");

		List<String> texts = MoveTargetDialog.rows(presentation, plan, sources, Set.of()).stream().map(Row::text).toList();

		assertEquals(List.of("Tisch A3, Sitz 1 – frei", "Tisch A3, Sitz 2 – Anna Müller (wird verschoben)",
				"Tisch A3, Sitz 3 – Peter Schmidt", "Tisch B1, Sitz 1 – frei", "Tisch B1, Sitz 2 – frei"), texts);
	}

	@Test
	void headerNamesWhoTheTargetsAreFor() {
		Source anna = Source.of(person(a3s2, "Anna", "Müller"));
		Source peter = Source.of(person(a3s3, "Peter", "Schmidt"));

		assertEquals("Ziel wählen für Anna Müller (Tisch A3, Sitz 2): Platz anklicken.", MoveTargetDialog.header(List.of(anna)));
		assertEquals("Ziele wählen für 2 Buchungen", MoveTargetDialog.header(List.of(anna, peter)));
	}
}
