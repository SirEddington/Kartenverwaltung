package de.eltviller_carneval_verein.karten.ui.event;

import java.util.ArrayList;
import java.util.List;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.event.BookingTree.TableGroup;

/**
 * Die Zeilen der Tabelle "Tisch" im Event-Detail: die Tische der gewählten Vorstellung, aus ihren Buchungen gruppiert
 * ({@link BookingTree}), davor die Gruppe "Ohne Platz", wenn es solche Buchungen gibt. Die Buchungen einer Zeile sind
 * die Zeilen der Tabelle "Sitz". Ohne JavaFX prüfbar.
 */
final class PresentationTables {

	private PresentationTables() {
	}

	/**
	 * Ein Tisch (oder die Gruppe "Ohne Platz") mit seinen Buchungen in der Reihenfolge der Plätze.
	 *
	 * @param label      "Tisch A3" bzw. "Tisch 3", für die Gruppe ohne Platz {@link BookingTree#UNASSIGNED_TEXT}
	 * @param unassigned die Gruppe der Buchungen ohne Platz
	 * @param bookings   die Buchungen (Sitze) der Zeile
	 */
	record Item(String label, boolean unassigned, List<Booking> bookings) {

		/** Anzahl der Sitze (Buchungen) der Zeile. */
		int seats() {
			return bookings.size();
		}

		/** Anzahl der Buchungen mit Daten ({@link Booking#hasData()}): reserviert, bezahlt, abgeholt, Kommentar. */
		long occupied() {
			return bookings.stream().filter(Booking::hasData).count();
		}
	}

	/** Die Zeilen der Tabelle "Tisch": zuerst "Ohne Platz" (falls vorhanden), dann die Tische in Reihenfolge der Plätze. */
	static List<Item> items(Presentation presentation) {
		List<Item> items = new ArrayList<>();
		List<Booking> unassigned = BookingTree.unassigned(presentation);
		if (!unassigned.isEmpty()) {
			items.add(new Item(BookingTree.UNASSIGNED_TEXT, true, unassigned));
		}
		for (TableGroup group : BookingTree.groups(presentation)) {
			items.add(new Item(group.label(), false, group.bookings()));
		}
		return items;
	}

	/**
	 * Der Inhalt der Spalte "Sitz": die Sitznummer, für eine Buchung ohne Platz mit ihrem bisherigen Platz ("Sitz 2
	 * (bisher Tisch A3)").
	 */
	static String seatText(Booking booking) {
		String seat = "Sitz " + booking.getSeatNumber();
		return booking.isUnassigned() ? seat + " (bisher " + booking.getTableLabel() + ")" : seat;
	}
}
