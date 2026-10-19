package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.eltviller_carneval_verein.karten.model.Booking;

/**
 * Die Suche im Verkauf (Kartentabelle und Saalansicht): Vorname, Nachname und Kommentar einer Buchung bilden zusammen
 * einen Text, in dem jedes Suchwort vorkommen muss. So findet "Hans Wurst Idiot" (Vorname "Hans", Nachname "Wurst",
 * Kommentar "Idiot") genauso wie "Hans Wurst" oder "ns Wurs", auch über die Feldgrenzen hinweg. Groß- und
 * Kleinschreibung und überzählige Leerzeichen zählen nicht, die Reihenfolge der Wörter auch nicht.
 */
final class BookingSearch {

	private BookingSearch() {
	}

	/** Ob die Buchung zur Suche passt; eine leere Suche findet alles. */
	static boolean matches(Booking booking, String query) {
		String normalized = normalize(query);
		if (normalized.isEmpty()) {
			return true;
		}
		String haystack = normalize(Stream.of(booking.getFirstName(), booking.getLastName(), booking.getComment()).filter(text -> text != null && !text.isBlank())
				.collect(Collectors.joining(" ")));
		for (String word : normalized.split("\\s+")) {
			if (!haystack.contains(word)) {
				return false;
			}
		}
		return true;
	}

	private static String normalize(String text) {
		return text == null ? "" : text.toLowerCase(Locale.GERMAN).trim();
	}
}
