package de.eltviller_carneval_verein.karten.util;

import java.util.Collection;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;

/**
 * Zählungen und Umsätze über Buchungen, in Cent und mit dem geltenden Preis jeder Buchung
 * ({@link Booking#getEffectivePrice()}). Eine Quelle für Event-Übersicht, Statistik und Kassenabgleich, damit alle für
 * dieselben Daten dieselben Zahlen zeigen. Buchungen ohne Platz zählen wie jede andere Buchung mit.
 *
 * @param paid           Anzahl bezahlter Buchungen
 * @param reserved       Anzahl reservierter Buchungen (mit Nachnamen, auch wenn bezahlt)
 * @param total          Anzahl aller Buchungen
 * @param revenueCents   Einnahmen: Summe der bezahlten Buchungen
 * @param expectedCents  erwartet: Summe der reservierten Buchungen
 * @param potentialCents potenziell: Summe aller Buchungen
 */
public record BookingTotals(int paid, int reserved, int total, long revenueCents, long expectedCents, long potentialCents) {

	public static BookingTotals of(Collection<Booking> bookings) {
		int paid = 0;
		int reserved = 0;
		long revenue = 0;
		long expected = 0;
		long potential = 0;
		for (Booking booking : bookings) {
			int price = booking.getEffectivePrice();
			potential += price;
			if (booking.isReserved()) {
				reserved++;
				expected += price;
			}
			if (booking.isPaid()) {
				paid++;
				revenue += price;
			}
		}
		return new BookingTotals(paid, reserved, bookings.size(), revenue, expected, potential);
	}

	/** Anzahl der Buchungen, die mit genau dieser Zahlungsart bezahlt sind. */
	public static int paidCount(Collection<Booking> bookings, PaymentStatus status) {
		return (int) bookings.stream().filter(booking -> booking.isPaid() && booking.getPaymentStatus() == status).count();
	}

	/** Summe der geltenden Preise der Buchungen, die mit genau dieser Zahlungsart bezahlt sind. */
	public static long paidCents(Collection<Booking> bookings, PaymentStatus status) {
		return bookings.stream().filter(booking -> booking.isPaid() && booking.getPaymentStatus() == status).mapToLong(Booking::getEffectivePrice).sum();
	}

	/** "bezahlt / reserviert / gesamt", z. B. "2 / 3 / 4". */
	public String countText() {
		return paid + " / " + reserved + " / " + total;
	}

	/** "Einnahmen / erwartet / potenziell" in Euro, z. B. "35,00 € / 40,00 € / 50,00 €". */
	public String revenueText() {
		return MoneyFormat.formatCents(revenueCents) + " / " + MoneyFormat.formatCents(expectedCents) + " / " + MoneyFormat.formatCents(potentialCents);
	}
}
