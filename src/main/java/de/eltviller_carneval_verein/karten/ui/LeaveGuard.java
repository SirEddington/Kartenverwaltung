package de.eltviller_carneval_verein.karten.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.tracking.StateRestorer;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;

/**
 * Schützt vor verlorenen Änderungen beim Verlassen eines Screens (Wechsel zu einem anderen Screen, Schließen der
 * App): Sind Events, Hallen oder die Einstellungen geändert, fragt ein Dialog nach <strong>Speichern</strong> (über
 * {@link EventSaver}, {@link HallSaver} bzw. {@link SettingsSaver}; bei Fehlern bleibt man auf dem Screen), <strong>Nicht
 * speichern</strong> (der gespeicherte Stand wird mit dem {@link StateRestorer} wiederhergestellt) oder
 * <strong>Abbrechen</strong>. Wird der Screen verlassen, vergessen die {@link ChangeTracker} danach alles: Sie
 * gelten nur je Screen-Sitzung.
 */
public final class LeaveGuard {

	private static final Logger LOG = Logger.getLogger(LeaveGuard.class.getName());
	private static final int MAX_NAMES_SHOWN = 5;

	/** Antwort auf die Rückfrage. */
	enum Decision {
		SAVE, DISCARD, CANCEL
	}

	/**
	 * Eine Art von Entität (Event, Halle oder Einstellungen), die beim Verlassen geprüft wird.
	 *
	 * @param label    Bezeichnung im Dialog, z. B. "Event"
	 * @param tracker  erkennt die Änderungen
	 * @param restorer stellt den gespeicherten Stand wieder her
	 * @param saver    speichert eine Entität; {@code false} heißt "nicht gespeichert" (der Saver hat es gemeldet)
	 * @param describe Zeile im Dialog für eine geänderte Entität
	 */
	record Scope<T>(String label, ChangeTracker<T> tracker, StateRestorer<T> restorer, Predicate<T> saver, Function<T, String> describe) {

		/** Mit der Darstellung {@code Label "Name"}, wie sie für Events und Hallen gilt. */
		Scope(String label, ChangeTracker<T> tracker, StateRestorer<T> restorer, Predicate<T> saver) {
			this(label, tracker, restorer, saver, entity -> label + " \"" + entity + "\"");
		}

		void checkAll() {
			try {
				// Der Filter vergleicht erst nach 300 ms Ruhe; der letzte Stand muss aber vor der Entscheidung stimmen
				tracker.checkAll();
			} catch (RuntimeException e) {
				// Gilt dann der zuletzt erkannte Stand: Wer wegen eines Fehlers nicht mehr weg kann, hätte nichts gewonnen
				LOG.log(Level.SEVERE, "Ungespeicherte Änderungen (" + label + ") konnten vor dem Verlassen nicht geprüft werden", e);
			}
		}

		List<String> describeDirty() {
			return tracker.dirtyEntities().stream().map(describe).toList();
		}

		boolean saveDirty() {
			for (T entity : tracker.dirtyEntities()) {
				if (!saver.test(entity)) {
					return false;
				}
			}
			return true;
		}

		void discardDirty() {
			for (T entity : tracker.dirtyEntities()) {
				if (tracker.isNew(entity)) {
					// Nie gespeichert: Es gibt keinen Stand zum Wiederherstellen, die Entität verschwindet einfach
					tracker.untrack(entity);
				} else {
					restorer.restoreBaseline(entity);
				}
			}
		}
	}

	private LeaveGuard() {
	}

	/**
	 * Fragt bei ungespeicherten Änderungen nach und entscheidet, ob der Screen verlassen werden darf.
	 *
	 * @return {@code true}, wenn gewechselt bzw. geschlossen werden darf
	 */
	public static boolean confirmLeave() {
		return confirmLeave(appScopes(), LeaveGuard::askUser);
	}

	private static List<Scope<?>> appScopes() {
		return List.of(new Scope<>("Event", ChangeTracker.events(), StateRestorer.events(), EventSaver::save), new Scope<>("Halle", ChangeTracker.halls(), StateRestorer.halls(), HallSaver::save),
				new Scope<>("Einstellungen", ChangeTracker.settings(), StateRestorer.settings(), settings -> SettingsSaver.save(), settings -> "Einstellungen"));
	}

	/** Wie es nach der Rückfrage weitergeht. */
	public enum Outcome {
		/** Der Nutzer bleibt auf dem Screen (Abbrechen oder ein Speichern, das nicht gelang). */
		STAY,
		/** Weiter; die Entitäten sind unverändert oder gespeichert, die gehaltenen Objekte bleiben gültig. */
		LEAVE,
		/** Weiter, aber der gespeicherte Stand wurde wiederhergestellt: Gehaltene Objekte sind veraltet und neu zu holen. */
		LEAVE_RESTORED
	}

	/**
	 * Die Rückfrage beim Wechsel vom Bearbeitungs- in den Anzeigemodus: wie {@link #confirmLeave()}, nur bleibt der
	 * Screen stehen. Deshalb vergessen die Tracker nichts: Im Bearbeitungsmodus, in den man gleich wieder wechseln
	 * kann, soll weiter auf Änderungen geachtet werden.
	 */
	public static Outcome confirmSwitchToViewMode() {
		return decide(appScopes(), LeaveGuard::askUser, false);
	}

	/** Die Entscheidung mit austauschbaren Teilen (paketsichtbar für Tests ohne Oberfläche). */
	static boolean confirmLeave(List<Scope<?>> scopes, Function<List<String>, Decision> ask) {
		return decide(scopes, ask, true) != Outcome.STAY;
	}

	/**
	 * @param resetTrackers {@code true} beim Verlassen des Screens (die Tracker gelten nur je Screen-Sitzung), {@code false}
	 *                      wenn der Screen stehen bleibt
	 */
	static Outcome decide(List<Scope<?>> scopes, Function<List<String>, Decision> ask, boolean resetTrackers) {
		scopes.forEach(Scope::checkAll);

		List<String> dirty = new ArrayList<>();
		scopes.forEach(scope -> dirty.addAll(scope.describeDirty()));
		if (dirty.isEmpty()) {
			resetIf(resetTrackers, scopes);
			return Outcome.LEAVE;
		}

		switch (ask.apply(dirty)) {
		case SAVE:
			for (Scope<?> scope : scopes) {
				if (!scope.saveDirty()) {
					// Der Saver hat die Fehler in der Fußzeile gemeldet; der Nutzer bleibt auf dem Screen
					return Outcome.STAY;
				}
			}
			resetIf(resetTrackers, scopes);
			return Outcome.LEAVE;
		case DISCARD:
			scopes.forEach(Scope::discardDirty);
			resetIf(resetTrackers, scopes);
			return Outcome.LEAVE_RESTORED;
		default:
			return Outcome.STAY;
		}
	}

	private static void resetIf(boolean resetTrackers, List<Scope<?>> scopes) {
		if (resetTrackers) {
			scopes.forEach(scope -> scope.tracker().reset());
		}
	}

	private static Decision askUser(List<String> dirty) {
		ButtonType save = new ButtonType("Speichern", ButtonData.YES);
		ButtonType discard = new ButtonType("Nicht speichern", ButtonData.NO);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);

		Alert alert = new Alert(AlertType.CONFIRMATION, message(dirty), save, discard, cancel);
		alert.setTitle("Ungespeicherte Änderungen");
		alert.setHeaderText(null);
		MainApp.applyAppIcon(alert);
		// Speichern ist der Standard-Button (ECV-Blau, aus style.css); Verwerfen ist die eine Antwort, die Daten
		// kostet, und steht deshalb in Rot; Abbrechen ist die neutrale Rückkehr (weiß mit rotem Rand)
		alert.getDialogPane().lookupButton(discard).getStyleClass().add("button-danger");
		alert.getDialogPane().lookupButton(cancel).getStyleClass().add("button-default");

		ButtonType answer = alert.showAndWait().orElse(cancel);
		if (answer == save) {
			return Decision.SAVE;
		}
		if (answer == discard) {
			return Decision.DISCARD;
		}
		return Decision.CANCEL;
	}

	/** Text des Dialogs: was geändert ist (paketsichtbar für Tests). */
	static String message(List<String> dirty) {
		String list = dirty.stream().limit(MAX_NAMES_SHOWN).map(item -> "• " + item).collect(Collectors.joining("\n"));
		if (dirty.size() > MAX_NAMES_SHOWN) {
			list += "\n... und " + (dirty.size() - MAX_NAMES_SHOWN) + " weitere";
		}
		return "Es gibt ungespeicherte Änderungen:\n\n" + list + "\n\nMöchtest du sie speichern?";
	}
}
