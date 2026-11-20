package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

/** Eine Zeile der Kartentabelle: eine Buchung oder eine Gruppe von Buchungen. */
public sealed interface TicketRow permits TicketRow.BookingRow, TicketRow.GroupRow {

	/** Eine einzelne Buchung. */
	record BookingRow(BookingDTO dto) implements TicketRow {
	}

	/**
	 * Eine Gruppe. Der Kopftext entsteht in der Oberfläche aus {@code column.header()}, {@code text} und {@code count};
	 * {@code path} sind die Schlüssel von der obersten Ebene bis zu dieser Gruppe.
	 */
	record GroupRow(TicketColumn column, String text, int count, List<Object> path) implements TicketRow {

		/** Der Schlüssel dieser Gruppe in ihrer Spalte, der letzte des Pfads. */
		public Object key() {
			return path.get(path.size() - 1);
		}
	}
}
