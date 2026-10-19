package de.eltviller_carneval_verein.karten.ui.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Presentation;

/**
 * Testzugang zu {@link BookingTree} (paketsichtbar) für Tests außerhalb dieses Pakets: die Knoten, über die die
 * Event-Übersicht ihre Zahlen zeigt.
 */
public final class BookingTreeAccess {

	private BookingTreeAccess() {
	}

	/** Tischbezeichnung ("Tisch A1") → Buchungen des Tisches, in der Reihenfolge von {@link BookingTree#groups}. */
	public static Map<String, List<Booking>> tables(Presentation p) {
		Map<String, List<Booking>> tables = new LinkedHashMap<>();
		BookingTree.groups(p).forEach(group -> tables.put(group.label(), group.bookings()));
		return tables;
	}

	/** Die Buchungen des Knotens "Ohne Platz" ({@link BookingTree#unassigned}). */
	public static List<Booking> unassigned(Presentation p) {
		return BookingTree.unassigned(p);
	}
}
