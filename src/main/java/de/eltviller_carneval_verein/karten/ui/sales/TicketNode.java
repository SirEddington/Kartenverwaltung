package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

/** Ein Knoten des Gruppenbaums; Buchungen haben keine Kinder. */
public record TicketNode(TicketRow row, List<TicketNode> children) {

	/** Ob der Knoten eine Gruppe ist. */
	public boolean isGroup() {
		return row instanceof TicketRow.GroupRow;
	}

	/** Ob der Knoten eine Buchung ist. */
	public boolean isBooking() {
		return row instanceof TicketRow.BookingRow;
	}
}
