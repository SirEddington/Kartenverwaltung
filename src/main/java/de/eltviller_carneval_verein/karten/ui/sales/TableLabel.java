package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Comparator;

import de.eltviller_carneval_verein.karten.model.Booking;

/**
 * Der Wert der Spalte "Tisch" in der Kartentabelle: "A3" oder "3" aus der Momentaufnahme der Buchung, für alle
 * Buchungen ohne Platz gemeinsam "Ohne Platz". Sortiert wird "Ohne Platz" zuerst, dann wie die Plätze
 * ({@link Booking#ROW_LABEL_ORDER}, Tischnummer); gleiche Werte bilden im Spaltenfilter eine Gruppe.
 *
 * @param unassigned  die Buchung hat keinen Platz in der Bestuhlung
 * @param rowLabel    Reihenbuchstabe ohne Rand, leer ohne Reihe (bei {@code unassigned} immer leer)
 * @param tableNumber Tischnummer (bei {@code unassigned} immer 0)
 */
record TableLabel(boolean unassigned, String rowLabel, int tableNumber) implements Comparable<TableLabel> {

	/** Text der Gruppe der Buchungen ohne Platz. */
	static final String UNASSIGNED_TEXT = "Ohne Platz";

	/** Reihenfolge der Kartentabelle: Buchungen ohne Platz zuerst, dann nach {@link Booking#PLACE_ORDER}. */
	static final Comparator<Booking> BOOKING_ORDER = Comparator.comparing((Booking booking) -> !booking.isUnassigned()).thenComparing(Booking.PLACE_ORDER);

	private static final Comparator<TableLabel> ORDER = Comparator.comparing((TableLabel label) -> !label.unassigned())
			.thenComparing(TableLabel::rowLabel, Booking.ROW_LABEL_ORDER).thenComparingInt(TableLabel::tableNumber);

	static TableLabel of(Booking booking) {
		if (booking.isUnassigned()) {
			return new TableLabel(true, "", 0);
		}
		return new TableLabel(false, rowOf(booking), booking.getTableNumber());
	}

	/** Der Tisch der Momentaufnahme als "A3" bzw. "3", auch bei einer Buchung ohne Platz (ihr bisheriger Tisch). */
	static String placeOf(Booking booking) {
		return rowOf(booking) + booking.getTableNumber();
	}

	private static String rowOf(Booking booking) {
		return booking.getRowLabel() != null ? booking.getRowLabel().trim() : "";
	}

	@Override
	public int compareTo(TableLabel other) {
		return ORDER.compare(this, other);
	}

	@Override
	public String toString() {
		return unassigned ? UNASSIGNED_TEXT : rowLabel + tableNumber;
	}
}
