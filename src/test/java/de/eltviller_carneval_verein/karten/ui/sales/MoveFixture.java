package de.eltviller_carneval_verein.karten.ui.sales;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Syncs;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

/**
 * Eine kleine Bestuhlung mit einer Vorstellung für die Tests des Verschiebens (KARV-140): Tische mit Sitzen anlegen,
 * dann {@link #sync}; danach hat jeder Sitz seine Buchung.
 */
final class MoveFixture {

	final SeatingPlan plan = new Hall().addSeatingPlan();
	final Presentation presentation;

	MoveFixture(String presentationName) {
		presentation = new Event().addPresentation(presentationName);
		presentation.setSeatingPlanId(plan.getId());
	}

	/** Eine neue Reihe mit dem Buchstaben und darin ein Tisch mit der nächsten freien Nummer. */
	Table table(String rowLabel) {
		TableRow row = plan.addRow();
		row.setLabel(rowLabel);
		return plan.addTable(row);
	}

	/** Eine neue Reihe mit dem Buchstaben und darin ein Tisch mit der Nummer. */
	Table table(String rowLabel, int tableNumber) {
		Table table = table(rowLabel);
		table.setTableNumber(tableNumber);
		return table;
	}

	/** Gleicht die Buchungen der Vorstellung an die Bestuhlung an. */
	void sync() {
		Syncs.sync(presentation, plan);
	}

	/** Die Buchung mit Platz auf dem Sitz. */
	Booking bookingOn(Seat seat) {
		return presentation.getBookings().stream().filter(b -> seat.getId().equals(b.getSeatId()) && !b.isUnassigned())
				.findFirst().orElseThrow();
	}

	/** Reserviert den Sitz auf den Nachnamen. */
	Booking reserve(Seat seat, String lastName) {
		Booking booking = bookingOn(seat);
		booking.setLastName(lastName);
		return booking;
	}

	/** Reserviert den Sitz auf Vor- und Nachnamen. */
	Booking person(Seat seat, String firstName, String lastName) {
		Booking booking = bookingOn(seat);
		booking.setFirstName(firstName);
		booking.setLastName(lastName);
		return booking;
	}
}
