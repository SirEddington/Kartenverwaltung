package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * "frei: n von m" über dem Auswahlfeld "Vorstellung" der Verkaufsansicht und sein Tooltip (KARV-215). Gezählt wird über
 * die Buchungen im Modell, unabhängig von Suche, Spaltenfilter und Ansicht: Nach dem Abgleich mit der Bestuhlung hat
 * jeder Sitz genau eine Buchung. Ohne JavaFX.
 * <p>
 * m ist bewusst kleiner als "gesamt" in Event-Übersicht und Statistik ({@code BookingTotals}): Dort zählen Buchungen
 * ohne Platz und gesperrte Sitze mit.
 */
final class FreeSeats {

	private static final String NO_PRESENTATION = "Keine Vorstellung";

	/**
	 * @param free   Sitze, die sich jetzt verkaufen lassen: weder belegt noch gesperrt
	 * @param total  freie und belegte Sitze
	 * @param locked gesperrte Sitze ({@link LockedSeats#isLocked}); sie fehlen in {@code free} und {@code total}
	 */
	record Count(int free, int total, int locked) {

		static final Count NONE = new Count(0, 0, 0);

		Count plus(Count other) {
			return new Count(free + other.free, total + other.total, locked + other.locked);
		}
	}

	private FreeSeats() {
	}

	/**
	 * Zählt die Sitze einer Vorstellung. Eine Buchung ohne Platz ist kein Sitz und zählt nirgends; eine Buchung mit Resten
	 * (Vorname oder Kommentar ohne Nachnamen, nicht bezahlt) ist frei, wie in der Saalansicht.
	 */
	static Count count(Presentation presentation) {
		if (presentation.getBookings() == null) {
			return Count.NONE;
		}
		int free = 0;
		int total = 0;
		int locked = 0;
		for (Booking booking : presentation.getBookings()) {
			if (booking.isUnassigned()) {
				continue;
			}
			if (LockedSeats.isLocked(booking)) {
				locked++;
				continue;
			}
			total++;
			if (!booking.isTaken()) {
				free++;
			}
		}
		return new Count(free, total, locked);
	}

	/** Die Summe über mehrere Vorstellungen. */
	static Count count(Collection<Presentation> presentations) {
		return presentations.stream().map(FreeSeats::count).reduce(Count.NONE, Count::plus);
	}

	/** Die Vorstellungen, für die die Anzeige gilt: die gewählte, ohne Auswahl ("Alle Vorstellungen") alle des Events. */
	static List<Presentation> counted(Event event, Presentation selected) {
		if (selected != null) {
			return List.of(selected);
		}
		return event.getPresentations() == null ? List.of() : event.getPresentations();
	}

	/** "frei: 42 von 180", auch "frei: 0 von 0". */
	static String labelText(Count count) {
		return "frei: " + count.free() + " von " + count.total();
	}

	/**
	 * Der Tooltip zur Anzeige: je Vorstellung eine Zeile "Name: n von m" in der gegebenen Reihenfolge, auch bei nur einer.
	 * Gibt es gesperrte Sitze, nennt eine Zeile darunter ihre Zahl: Sie erklärt, warum m kleiner ist als der Saal.
	 */
	static String tooltipText(List<Presentation> presentations) {
		if (presentations.isEmpty()) {
			return NO_PRESENTATION;
		}
		List<String> lines = new ArrayList<>();
		Count sum = Count.NONE;
		for (Presentation presentation : presentations) {
			Count count = count(presentation);
			sum = sum.plus(count);
			lines.add(EntityLabels.nameOrPlaceholder(presentation.getName()) + ": " + count.free() + " von " + count.total());
		}
		if (sum.locked() > 0) {
			lines.add("");
			lines.add("Gesperrt, nicht mitgezählt: " + sum.locked());
		}
		return String.join("\n", lines);
	}
}
