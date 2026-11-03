package de.eltviller_carneval_verein.karten.model;

import java.util.List;

/**
 * Stornieren einer Buchung (KARV-121): Eine Buchung auf einem Sitz wird zum leeren Platzhalter des Sitzes
 * ({@link Booking#clearCustomerData()}, neue Kennung), eine Buchung ohne Platz verschwindet aus ihrer Vorstellung. Für
 * den Abgleich mit anderen Rechnern ist die alte Buchung damit gelöscht, wie nach dem vollständigen Leeren einer Zeile.
 */
public final class BookingCancellation {

	private BookingCancellation() {
	}

	/** Storniert die Buchungen mit Daten; Buchungen ohne Daten bleiben unberührt. */
	public static void cancel(List<Booking> bookings) {
		for (Booking booking : bookings) {
			if (!booking.hasData()) {
				continue;
			}
			Presentation presentation = booking.getParent();
			if (booking.isUnassigned() && presentation != null && presentation.getBookings() != null) {
				presentation.getBookings().remove(booking);
			} else {
				booking.clearCustomerData();
			}
		}
	}

	/**
	 * Die Buchung mit diesem Nachnamen, als Kopie samt Vorstellung (für Preis und gezahlten Betrag); das Original bleibt.
	 * Für {@link #leavesData} und die Rückfrage, die den Stand danach zeigt.
	 */
	public static Booking withLastName(Booking booking, String lastName) {
		Booking after = booking.copy();
		after.setParent(booking.getParent());
		after.setLastName(lastName);
		return after;
	}

	/**
	 * Ob eine Änderung den Nachnamen löscht und die Buchung danach noch Daten trüge ({@link Booking#hasData()}): vorher
	 * reserviert, nachher nicht, aber nicht leer. Dann wird gefragt, ob storniert werden soll.
	 */
	public static boolean leavesData(Booking before, Booking after) {
		return before.isReserved() && !after.isReserved() && after.hasData();
	}
}
