package de.eltviller_carneval_verein.karten.model;

import java.util.List;

/**
 * Stornieren einer Buchung (KARV-121): Eine Buchung auf einem Sitz wird zum leeren Platzhalter des Sitzes, eine Buchung
 * ohne Platz verschwindet aus ihrer Vorstellung. Beides vergibt eine neue Kennung; für den Abgleich mit anderen Rechnern
 * ist die alte Buchung damit gelöscht, wie nach dem vollständigen Leeren einer Zeile.
 */
public final class BookingCancellation {

	/**
	 * Die Zahlen für die Rückfrage: wie viele Buchungen mit Daten storniert würden, wie viele davon bezahlt sind (samt
	 * gezahltem Betrag in Cent) und wie viele abgeholt.
	 */
	public record Summary(int count, int paid, long paidCents, int collected) {

		public static Summary of(List<Booking> bookings) {
			int count = 0;
			int paid = 0;
			long paidCents = 0;
			int collected = 0;
			for (Booking booking : bookings) {
				if (!booking.hasData()) {
					continue;
				}
				count++;
				if (booking.isPaid()) {
					paid++;
					paidCents += booking.getPaidCents();
				}
				if (booking.isCollected()) {
					collected++;
				}
			}
			return new Summary(count, paid, paidCents, collected);
		}
	}

	private BookingCancellation() {
	}

	/**
	 * Leert die Buchung ({@link Booking#clearCustomerData()}), ohne sie aus der Vorstellung zu nehmen. Eine Buchung ohne
	 * Platz bleibt "ohne Platz", damit {@link BookingSync#removeIfCancelled} sie danach als storniert erkennt. Für die
	 * Kartentabelle, die während eines Commits nichts aus der Liste nehmen darf. Eine Buchung ohne Daten bleibt unberührt.
	 */
	public static void empty(Booking booking) {
		if (!booking.hasData()) {
			return;
		}
		boolean unassigned = booking.isUnassigned();
		booking.clearCustomerData();
		booking.setUnassigned(unassigned);
	}

	/** Storniert die Buchungen: {@link #empty} und danach {@link BookingSync#removeIfCancelled}. */
	public static void cancel(List<Booking> bookings) {
		for (Booking booking : bookings) {
			empty(booking);
			BookingSync.removeIfCancelled(booking);
		}
	}

	/**
	 * Ob das Löschen des Nachnamens Daten zurücklässt: Der bisherige Nachname ist nicht leer, der neue leer, und ohne
	 * Nachnamen trüge die Buchung noch Daten ({@link Booking#hasData()}). Dann wird gefragt, ob storniert werden soll.
	 */
	public static boolean leavesData(Booking booking, String newLastName) {
		if (isBlank(booking.getLastName()) || !isBlank(newLastName)) {
			return false;
		}
		Booking withoutName = booking.copy();
		withoutName.setLastName(null);
		return withoutName.hasData();
	}

	private static boolean isBlank(String text) {
		return text == null || text.isBlank();
	}
}
