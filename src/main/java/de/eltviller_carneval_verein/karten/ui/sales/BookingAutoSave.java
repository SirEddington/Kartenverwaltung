package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.repository.LockedUnitException;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker.Snapshot;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.validation.Severity;

/**
 * Speichert Buchungsänderungen der Verkaufsansicht sofort (Einstellung "Buchungen sofort speichern"), ohne dass einzelne
 * Stellen es aufrufen: Ein Listener am {@link ChangeTracker} der Events meldet jede Zustandsänderung, die vom
 * gespeicherten Stand abweicht, und plant das Speichern ein (in der App {@code EventSaver.save} über
 * {@code Platform.runLater}, nicht mitten im Vergleich). Das Ergebnis meldet der Speicher wie bisher in der Fußzeile.
 *
 * <p>
 * Scheitert das Speichern (Prüfung nicht bestanden), versucht es erst die nächste Änderung erneut; "Speichern *" bleibt
 * sichtbar. {@link #flush()} vergleicht sofort und speichert, was aussteht, vor dem Verlassen der Ansicht und beim
 * Schließen der App: Sonst fragte der {@code LeaveGuard} nach Ungespeichertem, wenn jemand schneller als die
 * {@code ChangeTrigger}-Wartezeit wechselt. Alles auf dem FX-Thread.
 */
public final class BookingAutoSave {

	private static final Logger LOG = Logger.getLogger(BookingAutoSave.class.getName());

	/** Wie bei einer gescheiterten Prüfung im {@code EventSaver}: "Nicht gespeichert: ..." als Fehler. */
	private static final String FAILURE_PREFIX = "Nicht gespeichert: ";

	/** Die Instanz der offenen Verkaufsansicht, für {@link #flushActive()}; höchstens eine. */
	private static BookingAutoSave current;

	private final ChangeTracker<Event> tracker;
	private final Predicate<Event> save;
	private final Executor later;
	private final BooleanSupplier enabled;
	private final Consumer<String> showFailure;
	private final ChangeTracker.Listener<Event> listener = this::changed;
	private boolean open;
	private boolean flushing;

	/** Wie {@link #BookingAutoSave(ChangeTracker, Predicate, Executor, BooleanSupplier, Consumer)}, mit der Fußzeile als Ziel der Fehlermeldung. */
	public BookingAutoSave(ChangeTracker<Event> tracker, Predicate<Event> save, Executor later, BooleanSupplier enabled) {
		this(tracker, save, later, enabled, text -> StatusMessage.getInstance().show(Severity.ERROR, text));
	}

	/**
	 * @param tracker     der Tracker der Events
	 * @param save        speichert ein Event; {@code false} heißt "nicht gespeichert" (der Speicher hat es gemeldet)
	 * @param later       plant das Speichern für später ein (in der App {@code Platform::runLater})
	 * @param enabled     Bearbeitungsmodus an und Einstellung "Buchungen sofort speichern" auf Ja; wird bei jeder Änderung neu
	 *                    gefragt, nie gemerkt
	 * @param showFailure zeigt die Meldung, wenn {@code save} eine Ausnahme wirft (in der App die Fußzeile)
	 */
	public BookingAutoSave(ChangeTracker<Event> tracker, Predicate<Event> save, Executor later, BooleanSupplier enabled, Consumer<String> showFailure) {
		this.tracker = tracker;
		this.save = save;
		this.later = later;
		this.enabled = enabled;
		this.showFailure = showFailure;
	}

	/** Ob die Einstellung "Buchungen sofort speichern" gerade auf Ja steht (liest sie bei jedem Aufruf neu). */
	public static boolean settingOn() {
		return SettingsStore.getInstance().current().isSaveBookingsImmediately();
	}

	/**
	 * Die Verkaufsansicht ist offen ({@code true}) bzw. verlassen ({@code false}): Nur solange sie offen ist, hört der
	 * Listener am Tracker zu.
	 */
	public void setActive(boolean active) {
		if (active == open) {
			return;
		}
		open = active;
		if (active) {
			tracker.addListener(listener);
			current = this;
		} else {
			tracker.removeListener(listener);
			if (current == this) {
				current = null;
			}
		}
	}

	/** {@link #flush()} der gerade offenen Verkaufsansicht; ohne offene Ansicht ohne Wirkung. */
	public static void flushActive() {
		BookingAutoSave active = current;
		if (active != null) {
			active.flush();
		}
	}

	/**
	 * Vergleicht jetzt (wie der {@code ChangeTrigger} nach seiner Wartezeit) und speichert gleich, was aussteht, auch
	 * ein früher gescheiterter Versuch. Ohne Wirkung, wenn gerade nicht automatisch gespeichert wird.
	 */
	public void flush() {
		if (!isEnabled()) {
			return;
		}
		// Die Listener planen hier nichts ein: Gespeichert wird gleich, in einem Zug
		flushing = true;
		try {
			tracker.checkAll();
		} catch (RuntimeException e) {
			// Wie im ChangeTrigger: Was nicht verglichen werden kann, bleibt dem LeaveGuard überlassen
			LOG.log(Level.SEVERE, "Änderungserkennung vor dem Speichern fehlgeschlagen", e);
		} finally {
			flushing = false;
		}
		for (Event event : List.copyOf(tracker.dirtyEntities())) {
			saveIfPending(event);
		}
	}

	private void changed(Event event, Snapshot before, Snapshot after) {
		if (flushing || !isEnabled() || !differsFromSaved(event, after)) {
			return;
		}
		later.execute(() -> saveIfPending(event));
	}

	private boolean differsFromSaved(Event event, Snapshot state) {
		Snapshot saved = tracker.baseline(event);
		return saved != null && !state.equals(saved);
	}

	/** Speichert, wenn das Event im Moment noch ungespeichert ist: Ein Speichern dazwischen oder eine Rücknahme erübrigt es. */
	private void saveIfPending(Event event) {
		if (!isEnabled() || !tracker.isDirty(event)) {
			return;
		}
		try {
			save.test(event);
		} catch (RuntimeException e) {
			// Das Event bleibt ungespeichert ("Speichern *", LeaveGuard); die Bedienung läuft weiter. Ohne Dialog: Eine
			// gesperrte Einheit scheiterte bei jeder Änderung neu. Die Fußzeile nennt den Grund, sonst hielte der Bediener
			// den Sitz für gebucht.
			LOG.log(Level.SEVERE, "Sofortiges Speichern fehlgeschlagen: " + event, e);
			showFailure.accept(FAILURE_PREFIX + reason(e));
		}
	}

	/** Der Text für die Bedienung: bei einer gesperrten Einheit ihre eigene Meldung, sonst die Ursache wie in den Meldungen des Versionsspeichers. */
	private static String reason(RuntimeException e) {
		if (e instanceof LockedUnitException && e.getMessage() != null && !e.getMessage().isBlank()) {
			return e.getMessage();
		}
		return StoreMessages.describe(e);
	}

	private boolean isEnabled() {
		return open && enabled.getAsBoolean();
	}
}
