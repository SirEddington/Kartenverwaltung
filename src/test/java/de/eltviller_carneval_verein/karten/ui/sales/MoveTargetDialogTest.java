package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.BookingMove.Source;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.ui.sales.MoveTargetDialog.Row;

/** Die Zeilen der Platzliste beim Verschieben in der Kartentabelle (KARV-140). */
class MoveTargetDialogTest {

	private final MoveFixture fixture = new MoveFixture("Prunksitzung");
	private final SeatingPlan plan = fixture.plan;
	private final Presentation presentation = fixture.presentation;
	private final Seat b1s1;
	private final Seat b1s2;
	private final Seat a3s1;
	private final Seat a3s2;
	private final Seat a3s3;

	MoveTargetDialogTest() {
		// Reihe B vor Reihe A angelegt und die Sitze von A3 rückwärts: Die Liste ordnet nach dem Platz, nicht nach Anlage
		Table b1 = fixture.table("B", 1);
		b1s1 = b1.addSeat(1);
		b1s2 = b1.addSeat(2);
		Table a3 = fixture.table("A", 3);
		a3s3 = a3.addSeat(3);
		a3s2 = a3.addSeat(2);
		a3s1 = a3.addSeat(1);
		fixture.sync();
	}

	private static List<String> seatIds(List<Row> rows) {
		return rows.stream().map(Row::seatId).toList();
	}

	@Test
	void rowsListEverySeatInPlaceOrder() {
		List<Source> sources = List.of(Source.of(fixture.person(b1s1, "Anna", "Müller")));

		List<Row> rows = MoveTargetDialog.rows(presentation, plan, sources, Set.of());

		assertEquals(List.of(a3s1.getId(), a3s2.getId(), a3s3.getId(), b1s1.getId(), b1s2.getId()), seatIds(rows));
		assertEquals("Tisch A3, Sitz 1 – frei", rows.get(0).text());
	}

	@Test
	void rowsSkipLockedSeats() {
		List<Source> sources = List.of(Source.of(fixture.person(b1s1, "Anna", "Müller")));

		List<Row> rows = MoveTargetDialog.rows(presentation, plan, sources, Set.of(a3s2.getId(), b1s2.getId()));

		assertEquals(List.of(a3s1.getId(), a3s3.getId(), b1s1.getId()), seatIds(rows));
	}

	@Test
	void rowsNameOccupantAndMarkSourceSeats() {
		List<Source> sources = List.of(Source.of(fixture.person(a3s2, "Anna", "Müller")));
		fixture.person(a3s3, "Peter", "Schmidt");
		fixture.bookingOn(a3s1).setComment("alter Kommentar"); // Reste belegen den Sitz nicht
		fixture.person(b1s1, "", "");

		List<String> texts = MoveTargetDialog.rows(presentation, plan, sources, Set.of()).stream().map(Row::text).toList();

		assertEquals(List.of("Tisch A3, Sitz 1 – frei", "Tisch A3, Sitz 2 – Anna Müller (wird verschoben)",
				"Tisch A3, Sitz 3 – Peter Schmidt", "Tisch B1, Sitz 1 – frei", "Tisch B1, Sitz 2 – frei"), texts);
	}
}
