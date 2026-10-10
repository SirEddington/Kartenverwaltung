package de.eltviller_carneval_verein.karten.ui;

import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

/**
 * Löst den Vergleich des {@link ChangeTracker} aus, ohne dass einzelne Felder verdrahtet werden müssen: Ein
 * Event-Filter an der Scene hört auf Maus-, Tasten- und Aktionsereignisse und startet bei jedem davon eine
 * Wartezeit neu. Erst nach {@value #DEBOUNCE_MILLIS} ms Ruhe wird verglichen, weil die eigentlichen Handler
 * (Zellen-Commit, Spinner, Auswahl) erst nach dem Filter laufen und beim Tippen nicht bei jedem Zeichen
 * serialisiert werden soll.
 */
public final class ChangeTrigger {

	private static final Logger LOG = Logger.getLogger(ChangeTrigger.class.getName());

	static final int DEBOUNCE_MILLIS = 300;

	private ChangeTrigger() {
	}

	/** Installiert den Filter an der Scene (je Scene einmal; die Scenes der App werden bei jedem Screen neu erzeugt). */
	public static void install(Scene scene) {
		PauseTransition pause = new PauseTransition(Duration.millis(DEBOUNCE_MILLIS));
		pause.setOnFinished(e -> {
			try {
				ChangeTracker.getInstance().checkAll();
			} catch (RuntimeException ex) {
				// Nur ins Log: Bei jeder Bedienung einen Fehlerdialog zu zeigen, wäre schlimmer als eine
				// ausbleibende Anzeige. Beim Verlassen des Screens wird erneut geprüft (LeaveGuard).
				LOG.log(Level.SEVERE, "Änderungserkennung fehlgeschlagen", ex);
			}
		});

		EventHandler<javafx.event.Event> restart = e -> pause.playFromStart();
		scene.addEventFilter(MouseEvent.MOUSE_RELEASED, restart);
		scene.addEventFilter(KeyEvent.KEY_RELEASED, restart);
		scene.addEventFilter(ActionEvent.ACTION, restart);
	}
}
