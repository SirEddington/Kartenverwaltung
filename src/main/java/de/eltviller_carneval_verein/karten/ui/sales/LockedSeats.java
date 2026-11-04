package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.ui.LiveViews;

/**
 * Welche Sitze die Verkaufsansicht als gesperrt zeigt (KARV-111), ohne JavaFX. Ist die einzige Version einer Buchung
 * unlesbar (SYS-008/SYS-009), fehlt sie im Modell, und auf ihrem Sitz liegt ein leerer Platzhalter: Ohne diese Abfrage wirkte
 * der Sitz frei. Gesperrt ist ein Sitz, dessen Buchung frei wirkt ({@link Booking#isTaken()} ist {@code false}, auch mit
 * Resten) und dessen Einheit die Liste der gesperrten Einträge führt. Zeigt das Modell noch eine ältere, belegte Version,
 * bleibt es bei ihr.
 * <p>
 * Die App richtet die Abfrage mit {@link #install} ein (die Liste ist {@code LockedUnits}); ohne Einrichtung ist nichts
 * gesperrt. Saalplan, Kundenfenster und Kartentabelle zeichnen über {@link #addListener} neu, wenn sich die Liste ändert.
 * Nicht threadsicher für die Abfrage selbst: alle Aufrufe auf dem FX-Thread.
 */
public final class LockedSeats {

	private static final Logger LOG = Logger.getLogger(LockedSeats.class.getName());
	private static final Predicate<UnitKey> NOTHING_LOCKED = unit -> false;
	private static final Runnable NOTHING_TO_OPEN = () -> {
	};

	private static Predicate<UnitKey> locked = NOTHING_LOCKED;
	private static Runnable open = NOTHING_TO_OPEN;
	private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	private LockedSeats() {
	}

	/**
	 * Richtet die Abfrage ein.
	 *
	 * @param locked ob die Liste der gesperrten Einträge die Einheit führt
	 * @param open   öffnet den Dialog "Gesperrte Einträge" (wie der Link in der Fußzeile)
	 */
	public static void install(Predicate<UnitKey> locked, Runnable open) {
		LockedSeats.locked = locked;
		LockedSeats.open = open;
	}

	/** Ob der Sitz der Buchung gesperrt ist (siehe Klassenbeschreibung). */
	public static boolean isLocked(Booking booking) {
		if (booking.getSeatId() == null || booking.isUnassigned() || booking.isTaken()) {
			return false;
		}
		Presentation presentation = booking.getParent();
		Event event = presentation == null ? null : presentation.getParent();
		if (event == null) {
			return false;
		}
		UnitKey unit;
		try {
			unit = UnitKey.booking(event.getId(), presentation.getId(), booking.getSeatId());
		} catch (IllegalArgumentException e) {
			// Eine ID, die keine Einheit haben kann: Es gibt nichts, das gesperrt sein könnte (und das Zeichnen läuft weiter)
			return false;
		}
		return locked.test(unit);
	}

	/**
	 * Nimmt die gesperrten Sitze aus einer Auswahl: Sie lassen sich weder verkaufen noch stornieren.
	 *
	 * @return ob sich die Auswahl geändert hat
	 */
	public static boolean dropFrom(Collection<Booking> bookings) {
		return bookings.removeIf(LockedSeats::isLocked);
	}

	/** Öffnet den Dialog "Gesperrte Einträge"; ohne Einrichtung nichts. */
	public static void open() {
		open.run();
	}

	public static void addListener(Runnable listener) {
		listeners.add(listener);
	}

	public static void removeListener(Runnable listener) {
		listeners.remove(listener);
	}

	/** Meldet {@code listener} an, solange die Ansicht zu sehen ist ({@link LiveViews#whileShown}). */
	public static LiveViews.Registration whileShown(Runnable listener) {
		return new LiveViews.Registration(() -> addListener(listener), () -> removeListener(listener));
	}

	/** Die gesperrten Einträge haben sich geändert: Jeder Listener zeichnet neu; ein Fehler hält die übrigen nicht auf. */
	public static void changed() {
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException e) {
				LOG.log(Level.SEVERE, "Eine Ansicht ließ sich nach geänderten Sperren nicht neu zeichnen", e);
			}
		}
	}

	/** Nur für Tests: ohne Einrichtung und ohne Listener. */
	static void reset() {
		locked = NOTHING_LOCKED;
		open = NOTHING_TO_OPEN;
		listeners.clear();
	}
}
