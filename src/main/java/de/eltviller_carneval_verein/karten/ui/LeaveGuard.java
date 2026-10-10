package de.eltviller_carneval_verein.karten.ui;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.tracking.EventRestorer;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;

/**
 * Schützt vor verlorenen Änderungen beim Verlassen eines Screens (Wechsel zu einem anderen Screen, Schließen der
 * App): Sind Events geändert, fragt ein Dialog nach <strong>Speichern</strong> (über den {@link EventSaver}; bei
 * Fehlern bleibt man auf dem Screen), <strong>Nicht speichern</strong> (der gespeicherte Stand wird mit dem
 * {@link EventRestorer} wiederhergestellt) oder <strong>Abbrechen</strong>. Wird der Screen verlassen, vergisst der
 * {@link ChangeTracker} danach alle Events: Er gilt nur je Screen-Sitzung.
 */
public final class LeaveGuard {

	private static final Logger LOG = Logger.getLogger(LeaveGuard.class.getName());
	private static final int MAX_NAMES_SHOWN = 5;

	/** Antwort auf die Rückfrage. */
	enum Decision {
		SAVE, DISCARD, CANCEL
	}

	private LeaveGuard() {
	}

	/**
	 * Fragt bei ungespeicherten Änderungen nach und entscheidet, ob der Screen verlassen werden darf.
	 *
	 * @return {@code true}, wenn gewechselt bzw. geschlossen werden darf
	 */
	public static boolean confirmLeave() {
		return confirmLeave(ChangeTracker.getInstance(), LeaveGuard::askUser, EventSaver::save, EventRestorer.getInstance());
	}

	/** Die Entscheidung mit austauschbaren Teilen (paketsichtbar für Tests ohne Oberfläche). */
	static boolean confirmLeave(ChangeTracker tracker, Function<List<Event>, Decision> ask, Predicate<Event> saver, EventRestorer restorer) {
		try {
			// Der Filter vergleicht erst nach 300 ms Ruhe; der letzte Stand muss aber vor der Entscheidung stimmen
			tracker.checkAll();
		} catch (RuntimeException e) {
			// Gilt dann der zuletzt erkannte Stand: Wer wegen eines Fehlers nicht mehr weg kann, hätte nichts gewonnen
			LOG.log(Level.SEVERE, "Ungespeicherte Änderungen konnten vor dem Verlassen nicht geprüft werden", e);
		}

		List<Event> dirty = tracker.dirtyEvents();
		if (dirty.isEmpty()) {
			tracker.reset();
			return true;
		}

		switch (ask.apply(dirty)) {
		case SAVE:
			for (Event event : dirty) {
				if (!saver.test(event)) {
					// EventSaver hat die Fehler in der Fußzeile gemeldet; der Nutzer bleibt auf dem Screen
					return false;
				}
			}
			tracker.reset();
			return true;
		case DISCARD:
			for (Event event : dirty) {
				restorer.restoreBaseline(event);
			}
			tracker.reset();
			return true;
		default:
			return false;
		}
	}

	private static Decision askUser(List<Event> dirty) {
		ButtonType save = new ButtonType("Speichern", ButtonData.YES);
		ButtonType discard = new ButtonType("Nicht speichern", ButtonData.NO);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);

		Alert alert = new Alert(AlertType.CONFIRMATION, message(dirty), save, discard, cancel);
		alert.setTitle("Ungespeicherte Änderungen");
		alert.setHeaderText(null);
		MainApp.applyAppIcon(alert);

		ButtonType answer = alert.showAndWait().orElse(cancel);
		if (answer == save) {
			return Decision.SAVE;
		}
		if (answer == discard) {
			return Decision.DISCARD;
		}
		return Decision.CANCEL;
	}

	/** Text des Dialogs: welche Events geändert sind (paketsichtbar für Tests). */
	static String message(List<Event> dirty) {
		String names = dirty.stream().limit(MAX_NAMES_SHOWN).map(event -> "\"" + event + "\"").collect(Collectors.joining(", "));
		if (dirty.size() > MAX_NAMES_SHOWN) {
			names += " und " + (dirty.size() - MAX_NAMES_SHOWN) + " weitere";
		}
		String subject = dirty.size() == 1 ? "Das Event " + names + " hat" : "Die Events " + names + " haben";
		return subject + " ungespeicherte Änderungen.\n\nMöchtest du sie speichern?";
	}
}
