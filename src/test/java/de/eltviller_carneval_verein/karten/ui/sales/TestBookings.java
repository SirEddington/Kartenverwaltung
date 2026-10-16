package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Objects;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

/**
 * Testdaten für die Verkaufsklassen: Buchungen mit Momentaufnahme eines Sitzes einer Bestuhlung. Reihen und Tische
 * werden beim ersten Gebrauch angelegt; jeder Aufruf legt einen neuen Sitz und eine neue Buchung an.
 */
final class TestBookings {

	private final SeatingPlan plan = new Hall().addSeatingPlan();

	/** Buchung an "Tisch {@code tableNumber}, Sitz {@code seatNumber}" ohne Reihenbuchstaben. */
	Booking booking(Presentation presentation, int tableNumber, int seatNumber) {
		return booking(presentation, "", tableNumber, seatNumber);
	}

	/** Buchung an "Tisch {@code rowLabel}{@code tableNumber}, Sitz {@code seatNumber}", Momentaufnahme per {@code takeSnapshot}. */
	Booking booking(Presentation presentation, String rowLabel, int tableNumber, int seatNumber) {
		Table table = table(rowLabel, tableNumber);
		Seat seat = table.addSeat(seatNumber);
		seat.setSeatNumber(seatNumber); // auch eine doppelte Nummer bleibt, wie verlangt
		Booking booking = presentation.addBooking();
		booking.takeSnapshot(table, seat);
		return booking;
	}

	private Table table(String rowLabel, int tableNumber) {
		TableRow row = plan.getRows().stream().filter(r -> Objects.equals(r.getLabel(), rowLabel)).findFirst().orElseGet(() -> {
			TableRow created = plan.addRow();
			created.setLabel(rowLabel);
			return created;
		});
		return plan.getTablesOf(row).stream().filter(t -> t.getTableNumber() == tableNumber).findFirst().orElseGet(() -> {
			Table created = plan.addTable(row);
			created.setTableNumber(tableNumber);
			return created;
		});
	}
}
