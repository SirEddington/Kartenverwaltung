package de.eltviller_carneval_verein.karten.util;

import java.util.Locale;

/**
 * Einheitliche Cent-zu-Euro-Formatierung. Rechnungen sollen immer in Cent (long/int)
 * erfolgen und erst hier fürs Anzeigen in Euro umgerechnet werden, damit Bilanz und
 * Event-Übersicht nie mehr unterschiedliche Zahlen für dieselben Daten zeigen.
 */
public final class MoneyFormat {

	private MoneyFormat() {
	}

	public static String formatCents(long cents) {
		return String.format(Locale.GERMANY, "%,.2f €", cents / 100.0);
	}

	/**
	 * Euro in Cent, gerundet und auf den int-Bereich begrenzt, damit auch ein riesiger Betrag als zu hoch (SEA-003,
	 * PRS-008) bzw. negativ gemeldet wird, statt überzulaufen.
	 */
	public static int toCents(double euros) {
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(euros * 100)));
	}
}
