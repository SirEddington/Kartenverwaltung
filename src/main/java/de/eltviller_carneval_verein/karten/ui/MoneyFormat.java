package de.eltviller_carneval_verein.karten.ui;

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
}
