package de.eltviller_carneval_verein.karten.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Setzt Reservierungs-, Bezahl- und Abholdatum einer {@link Booking Buchung} beim Setzen der Aktion, dazu den gezahlten
 * Betrag. Jede Stelle, die Nachname, Zahlstatus, Preis oder "abgeholt" ändert, führt die Änderung über {@link #change}
 * aus.
 *
 * <p>
 * Die Regeln: Wechselt eine Aktion von "nein" auf "ja" (reserviert, bezahlt, abgeholt), wird das zugehörige Datum auf
 * den heutigen Tag gesetzt und ein vorhandenes überschrieben. Wird die Aktion zurückgenommen oder ändert sich nichts
 * (Zahlart Bar auf Karte, nur der Vorname), bleiben die Daten unberührt; eine Warnung des Validators meldet ein Datum
 * ohne Aktion. Ändert die Änderung selbst ein Datum (der Nutzer tippt es im selben Schritt), gilt dieses.
 *
 * <p>
 * Genauso der gezahlte Betrag ({@link Booking#getPaidAmount()}): Wechselt die Buchung auf "bezahlt", wird er der
 * geltende Preis nach der Änderung (ein im selben Schritt geänderter Preis zählt), es sei denn, die Änderung setzt ihn
 * selbst. Ein Wechsel der Zahlart lässt ihn stehen. Eine offene Buchung trägt nach der Änderung nie einen Betrag. Ändert
 * sich der Preis einer schon bezahlten Buchung ohne Betrag (älterer Stand), wird der bisherige Preis festgehalten; sonst
 * wanderte der Betrag still mit. Deshalb laufen auch Preisänderungen über {@link #change}.
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
		Integer paidAmount = booking.getPaidAmount();
		int price = booking.getEffectivePrice();

		edit.accept(booking);

		if (!booking.isPaid()) {
			booking.setPaidAmount(null);
		} else if (Objects.equals(paidAmount, booking.getPaidAmount())) { // die Änderung hat den Betrag nicht selbst gesetzt
			if (!wasPaid) {
				booking.setPaidAmount(booking.getEffectivePrice());
			} else if (paidAmount == null && booking.getEffectivePrice() != price) {
				booking.setPaidAmount(price);
			}
		}

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
