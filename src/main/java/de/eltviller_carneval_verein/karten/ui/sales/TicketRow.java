package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;
import java.util.stream.Collectors;

/** Eine Zeile der Kartentabelle: eine Buchung oder eine Gruppe von Buchungen. */
public sealed interface TicketRow permits TicketRow.BookingRow, TicketRow.GroupRow {

	/** Eine einzelne Buchung. */
	record BookingRow(BookingDTO dto) implements TicketRow {
	}

	/**
	 * Eine Gruppe. Der Kopftext entsteht in der Oberfläche aus {@code columns}, {@code text} und {@code count};
	 * {@code path} sind die Schlüssel von der obersten Ebene bis zu dieser Gruppe. Eine verschachtelte Gruppe hat genau
	 * eine Spalte; eine Gruppe auf einer Ebene hat alle Gruppierspalten, und ihr Schlüssel ist die Liste der Schlüssel
	 * dieser Spalten.
	 */
	record GroupRow(List<TicketColumn> columns, String text, int count, List<Object> path) implements TicketRow {

		/** Die Überschriften der Spalten, mit Komma verbunden. */
		public String columnsText() {
			return columns.stream().map(TicketColumn::header).collect(Collectors.joining(", "));
		}

		/**
		 * Der Schlüssel dieser Gruppe in ihrer Spalte, der letzte des Pfads; auf einer Ebene (nicht verschachtelt) die
		 * Liste der Spaltenschlüssel.
		 */
		public Object key() {
			return path.get(path.size() - 1);
		}
	}
}
