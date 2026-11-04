package de.eltviller_carneval_verein.karten.ui.shared;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;

/**
 * Die gesperrten Einheiten, wie die Oberfläche sie führt (Spec "Gesperrte Einheiten"), ohne JavaFX: gefüllt aus dem
 * Rückruf {@code locked} und den Ladewarnungen ({@code getAndClearLockedAtLoad()}). Aus ihr entstehen der Link in der
 * Fußzeile ({@link #count()}) und die Zeilen des Dialogs ({@link #rows}).
 * <p>
 * Der Knopf "Auf letzten lesbaren Stand zurücksetzen" gehört nur zu einer beschädigten Einheit, die der Versionsspeicher in
 * {@code resettableUnits()} nennt: Im gemeinsamen Betrieb sind das die, bei denen die Reparatur aus dem Ordner gescheitert
 * ist; solange sie aussteht oder offline ist, fehlt er von selbst. {@link #refresh} fragt dafür vorher jede Einheit mit
 * {@code unitState} ab (das stößt im gemeinsamen Betrieb den Reparaturversuch an und führt im Einzelbetrieb den Stand nach)
 * und trägt ein, was sich geändert hat.
 * <p>
 * {@code unitState} und {@code resetToLastReadable} lesen und schreiben Dateien und laufen deshalb auf dem
 * Hintergrund-Executor, die Eintragungen und das Einarbeiten ins Modell auf dem FX-Thread. Nicht threadsicher: alle Aufrufe
 * von außen auf dem FX-Thread.
 */
public final class LockedUnits {

	private static final Logger LOG = Logger.getLogger(LockedUnits.class.getName());

	/** Was die gesperrten Einheiten vom Versionsspeicher brauchen (in der App {@link #of(SyncService)}). */
	public interface Service {

		/** Der aktuelle Zustand der Einheit; liest Dateien. */
		UnitState.Status status(UnitKey unit) throws IOException;

		/** Die Einheiten, die sich zurücksetzen lassen; ohne Datei-Ein-/Ausgabe. */
		Set<UnitKey> resettableUnits();

		/** Setzt die Einheit auf den letzten lesbaren Stand zurück; schreibt Dateien. */
		void resetToLastReadable(UnitKey unit) throws IOException;
	}

	/**
	 * Eine Zeile des Dialogs.
	 *
	 * @param unit       die Einheit
	 * @param entry      die Bezeichnung ({@code ConflictLabels}), sonst der Pfad
	 * @param cause      die Ursache der Sperre
	 * @param resettable ob der Knopf "Auf letzten lesbaren Stand zurücksetzen" zu zeigen ist
	 */
	public record Row(UnitKey unit, String entry, String cause, boolean resettable) {
	}

	private final Service service;
	private final Executor background;
	private final Executor fx;
	private final Consumer<Set<UnitKey>> apply;
	private final BiConsumer<UnitKey, String> failed;
	private final Runnable changed;
	private final Map<UnitKey, UnitState.Status> locked = new LinkedHashMap<>();
	private final Set<UnitKey> resetting = new HashSet<>();

	/**
	 * @param service    der Versionsspeicher
	 * @param background führt Datei-Ein-/Ausgabe aus (in der App der Hintergrund-Executor der Verbindung)
	 * @param fx         führt auf dem FX-Thread aus ({@code Platform::runLater})
	 * @param apply      arbeitet die zurückgesetzten Einheiten in die Repositories ein ({@code applyForeign}, in der App
	 *                   {@code LiveUpdates.catchUp}); läuft auf dem FX-Thread
	 * @param failed     zeigt, dass das Zurücksetzen der Einheit gescheitert ist (Einheit und Ursache); FX-Thread
	 * @param changed    wird gerufen, wenn sich nach einem Hintergrundlauf etwas geändert hat (Link, offener Dialog); FX-Thread
	 */
	public LockedUnits(Service service, Executor background, Executor fx, Consumer<Set<UnitKey>> apply,
			BiConsumer<UnitKey, String> failed, Runnable changed) {
		this.service = service;
		this.background = background;
		this.fx = fx;
		this.apply = apply;
		this.failed = failed;
		this.changed = changed;
	}

	/** Der Versionsspeicher der App als {@link Service}. */
	public static Service of(SyncService sync) {
		return new Service() {
			@Override
			public UnitState.Status status(UnitKey unit) throws IOException {
				return sync.unitState(unit).status();
			}

			@Override
			public Set<UnitKey> resettableUnits() {
				return sync.resettableUnits();
			}

			@Override
			public void resetToLastReadable(UnitKey unit) throws IOException {
				sync.resetToLastReadable(unit);
			}
		};
	}

	/**
	 * Merkt sich eine gesperrte Einheit (ein zweites Mal ersetzt es die Ursache); eine, die nicht gesperrt ist, wird
	 * übergangen. Frischt die Anzeige nicht auf, das tut der Aufrufer.
	 */
	public void add(UnitKey unit, UnitState.Status status) {
		if (isLocked(status)) {
			locked.put(unit, status);
		}
	}

	/** Wie {@link #add} für mehrere, etwa die beim Laden gesperrten. */
	public void addAll(Map<UnitKey, UnitState.Status> units) {
		units.forEach(this::add);
	}

	/** Die Zahl der gesperrten Einheiten (für den Link in der Fußzeile). */
	public int count() {
		return locked.size();
	}

	/** Die gesperrten Einheiten als unveränderliche Sicht (für die gesperrten Sitze der Verkaufsansicht, KARV-111). */
	public Set<UnitKey> units() {
		return Collections.unmodifiableSet(locked.keySet());
	}

	/** Die Ursache der gesperrten Einheit; {@code null}, wenn sie nicht geführt wird. */
	public UnitState.Status status(UnitKey unit) {
		return locked.get(unit);
	}

	/**
	 * Ob {@code text} eine Sperre meldet (SYS-008 oder SYS-009, auch mit Präfix wie "Nicht gespeichert: "), die nicht mehr
	 * gilt: Er ist keine der Meldungen zu den geführten Einheiten, weder mit Bezeichnung noch mit Pfad. Verglichen wird der
	 * ganze Text der Meldung, nicht nur der Name ("Halle 1" steckt auch in "Halle 10"). Für die Fußzeile, die eine solche
	 * Meldung nach dem Zurücksetzen nicht stehen lassen soll; ein anderer Text ist nie veraltet.
	 */
	public boolean isStaleLockMessage(String text, Function<UnitKey, String> labels) {
		if (!text.contains(StoreMessages.SYS_DAMAGED + ": ") && !text.contains(StoreMessages.SYS_NEWER_FORMAT + ": ")) {
			return false;
		}
		for (UnitKey unit : locked.keySet()) {
			for (String name : List.of(label(labels, unit), unit.relativePath())) {
				if (text.contains(StoreMessages.damaged(name)) || text.contains(StoreMessages.newerFormat(name))) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Die Zeilen des Dialogs in der Reihenfolge, in der die Sperren gemeldet wurden. Die Bezeichnung kommt von
	 * {@code labels}; liefert sie nichts oder scheitert, steht der Pfad da.
	 *
	 * @param resettable {@code resettableUnits()} des Versionsspeichers; der Knopf gilt nur einer beschädigten Einheit darin
	 */
	public List<Row> rows(Function<UnitKey, String> labels, Set<UnitKey> resettable) {
		List<Row> rows = new ArrayList<>();
		locked.forEach((unit, status) -> {
			boolean damaged = status == UnitState.Status.LOCKED_DAMAGED;
			rows.add(new Row(unit, label(labels, unit), damaged ? SharedFolderTexts.LOCKED_CAUSE_DAMAGED
					: SharedFolderTexts.LOCKED_CAUSE_FORMAT, damaged && resettable.contains(unit)));
		});
		return rows;
	}

	/** Wie {@link #rows(Function, Set)}, mit {@code resettableUnits()} von jetzt (ohne Datei-Ein-/Ausgabe, auf jedem Thread). */
	public List<Row> rows(Function<UnitKey, String> labels) {
		return rows(labels, service.resettableUnits());
	}

	/**
	 * Fragt den Zustand jeder geführten Einheit im Hintergrund ab, trägt dann auf dem FX-Thread ein, was sich geändert hat
	 * (eine Einheit, die nicht mehr gesperrt ist, fällt heraus; eine, deren Zustand sich nicht lesen ließ, bleibt), meldet
	 * {@code changed} und ruft {@code then}. Danach gibt {@code resettableUnits()} den Stand nach dem Reparaturversuch.
	 */
	public void refresh(Runnable then) {
		List<UnitKey> units = List.copyOf(locked.keySet());
		background.execute(() -> {
			Map<UnitKey, UnitState.Status> found = new LinkedHashMap<>();
			for (UnitKey unit : units) {
				try {
					found.put(unit, service.status(unit));
				} catch (IOException | RuntimeException e) {
					LOG.log(Level.WARNING, "Der Zustand von " + unit.relativePath() + " ließ sich nicht abfragen", e);
				}
			}
			fx.execute(() -> {
				found.forEach((unit, status) -> {
					// nur noch geführte: Eine Einheit, die währenddessen zurückgesetzt wurde, kommt nicht wieder
					if (locked.containsKey(unit)) {
						if (isLocked(status)) {
							locked.put(unit, status);
						} else {
							locked.remove(unit);
						}
					}
				});
				notifyChanged();
				if (then != null) {
					then.run();
				}
			});
		});
	}

	/**
	 * Setzt die Einheit auf den letzten lesbaren Stand zurück: im Hintergrund {@code resetToLastReadable}, dann auf dem
	 * FX-Thread das Einarbeiten ins Modell (sofern das Zurücksetzen schon gilt, siehe {@link #holdsAtOnce}) und das
	 * Entfernen aus der Liste. Scheitert es, bleibt die Zeile, und
	 * {@code failed} bekommt die Ursache; ist die Einheit inzwischen gar nicht mehr beschädigt, wird nur ihr Zustand neu
	 * abgefragt. Läuft für die Einheit schon ein Zurücksetzen, tut es nichts.
	 */
	public void reset(UnitKey unit) {
		if (!resetting.add(unit)) {
			return;
		}
		background.execute(() -> {
			Exception failure = null;
			boolean holds = true;
			try {
				// Der Dialog zeichnet den Knopf nach einem früheren Stand: Ein Vollabgleich oder Abholen kann die Einheit
				// inzwischen wieder reparierbar gemacht haben, dann liegt im Ordner eine heile Kopie, die das Zurücksetzen
				// mit dem älteren Stand überschriebe (bei einer Buchung ginge ein Verkauf verloren)
				if (!service.resettableUnits().contains(unit)) {
					failure = new IllegalStateException(unit.relativePath() + " ist nicht mehr zurücksetzbar.");
				} else {
					service.resetToLastReadable(unit);
					holds = holdsAtOnce(unit);
				}
			} catch (IOException | RuntimeException e) {
				failure = e;
			}
			Exception outcome = failure;
			boolean applyNow = holds;
			fx.execute(() -> finishReset(unit, outcome, applyNow));
		});
	}

	/**
	 * Ob das Zurücksetzen schon gilt. Im gemeinsamen Betrieb gilt es erst, wenn ein Takt es beim Hochladen beansprucht hat;
	 * bis dahin ist die Einheit im Versionsspeicher noch gesperrt. Jetzt eingearbeitet, meldete das Modell sie erneut als
	 * gesperrt (SYS-008 als Warnung); die Meldung des Versionsspeichers nach dem Beanspruchen arbeitet sie ein. Lässt sich
	 * der Zustand nicht lesen, wird wie bisher gleich eingearbeitet.
	 */
	private boolean holdsAtOnce(UnitKey unit) {
		try {
			return !isLocked(service.status(unit));
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.FINE, "Der Zustand von " + unit.relativePath() + " nach dem Zurücksetzen ließ sich nicht abfragen", e);
			return true;
		}
	}

	private void finishReset(UnitKey unit, Exception failure, boolean applyNow) {
		resetting.remove(unit);
		if (failure == null) {
			if (applyNow) {
				try {
					apply.accept(Set.of(unit));
				} catch (RuntimeException e) {
					LOG.log(Level.SEVERE, "Die zurückgesetzte Einheit " + unit.relativePath() + " ließ sich nicht einarbeiten", e);
				}
			}
			locked.remove(unit);
			notifyChanged();
			return;
		}
		LOG.log(Level.WARNING, "Zurücksetzen von " + unit.relativePath() + " gescheitert", failure);
		// Die Einheit ist nicht (mehr) zurücksetzbar, oder der Versionsspeicher lehnt sie ab, weil sie nicht (mehr) wegen
		// einer beschädigten Version gesperrt ist: Dann
		// ist nichts schiefgegangen, nur der Stand hat sich geändert, und die Abfrage unten trägt ihn ein
		if (!(failure instanceof IllegalStateException)) {
			try {
				failed.accept(unit, StoreMessages.describe(failure));
			} catch (RuntimeException e) {
				LOG.log(Level.SEVERE, "Der Fehler beim Zurücksetzen ließ sich nicht anzeigen", e);
			}
		}
		// vielleicht ist die Sperre inzwischen weg (repariert), vielleicht ist der Zustand ein anderer
		refresh(null);
	}

	private void notifyChanged() {
		try {
			changed.run();
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Die gesperrten Einträge ließen sich nicht anzeigen", e);
		}
	}

	private static boolean isLocked(UnitState.Status status) {
		return status == UnitState.Status.LOCKED_DAMAGED || status == UnitState.Status.LOCKED_FORMAT;
	}

	/** Die Bezeichnung der Einheit ({@code ConflictLabels}); ihr Pfad, wenn sie sich nicht benennen lässt. */
	public static String label(Function<UnitKey, String> labels, UnitKey unit) {
		try {
			String label = labels.apply(unit);
			if (label != null) {
				return label;
			}
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Keine Bezeichnung für " + unit.relativePath(), e);
		}
		return unit.relativePath();
	}

	/**
	 * Die Warnung SYS-008 bzw. SYS-009 mit der Bezeichnung der Einheit statt ihres Pfades (für die Meldung beim Laden); im Log
	 * steht der Pfad.
	 *
	 * @return der Text; {@code null}, wenn {@code status} keine Sperre ist
	 */
	public static String warning(Function<UnitKey, String> labels, UnitKey unit, UnitState.Status status) {
		return isLocked(status) ? StoreMessages.locked(label(labels, unit), status) : null;
	}
}
