package de.eltviller_carneval_verein.karten.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Setzt Reservierungs-, Bezahl- und Abholdatum einer {@link Booking Buchung} beim Setzen der Aktion. Jede Stelle, die
 * Nachname, Zahlstatus oder "abgeholt" ändert, führt die Änderung über {@link #change} aus.
 *
 * <p>
 * Die Regeln: Wechselt eine Aktion von "nein" auf "ja" (reserviert, bezahlt, abgeholt), wird das zugehörige Datum auf
 * den heutigen Tag gesetzt und ein vorhandenes überschrieben. Wird die Aktion zurückgenommen oder ändert sich nichts
 * (Zahlart Bar auf Karte, nur der Vorname), bleiben die Daten unberührt; eine Warnung des Validators meldet ein Datum
 * ohne Aktion. Ändert die Änderung selbst ein Datum (der Nutzer tippt es im selben Schritt), gilt dieses.
 *
 * <p>
 * Die Setter von {@link Booking} tun das nicht selbst, denn Jackson ruft sie beim Laden auf: Ältere Dateien bekämen
 * sonst beim Laden den heutigen Tag.
 */
public final class SaleDates {

	private SaleDates() {
	}

	/** Führt {@code edit} aus und setzt dabei fällige Daten auf den heutigen Tag. */
	public static void change(Booking booking, Consumer<Booking> edit) {
		change(booking, edit, LocalDate.now());
	}

	/** Wie {@link #change(Booking, Consumer)}, mit einem festen "heute" (für Tests). */
	public static void change(Booking booking, Consumer<Booking> edit, LocalDate today) {
		boolean wasReserved = booking.isReserved();
		boolean wasPaid = booking.isPaid();
		boolean wasCollected = booking.isCollected();
		LocalDate reservationDate = booking.getReservationDate();
		LocalDate paymentDate = booking.getPaymentDate();
		LocalDate collectionDate = booking.getCollectionDate();

		edit.accept(booking);

		if (!wasReserved && booking.isReserved() && Objects.equals(reservationDate, booking.getReservationDate())) {
			booking.setReservationDate(today);
		}
		if (!wasPaid && booking.isPaid() && Objects.equals(paymentDate, booking.getPaymentDate())) {
			booking.setPaymentDate(today);
		}
		if (!wasCollected && booking.isCollected() && Objects.equals(collectionDate, booking.getCollectionDate())) {
			booking.setCollectionDate(today);
		}
	}
}
