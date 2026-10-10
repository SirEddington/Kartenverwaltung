package de.eltviller_carneval_verein.karten.ui;

import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import javafx.animation.PauseTransition;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Löst den Vergleich der {@link ChangeTracker} aus, ohne dass einzelne Felder verdrahtet werden müssen: Ein
 * Event-Filter an jedem Fenster der App hört auf Maus-, Tasten- und Aktionsereignisse und startet bei jedem davon
 * eine Wartezeit neu. Erst nach {@value #DEBOUNCE_MILLIS} ms Ruhe wird verglichen, weil die eigentlichen Handler
 * (Zellen-Commit, Spinner, Auswahl) erst nach dem Filter laufen und beim Tippen nicht bei jedem Zeichen
 * serialisiert werden soll.
 *
 * <p>
 * Der Filter hängt an den Fenstern, nicht an der Scene des Hauptfensters: Popups (Detailfenster der
 * Saalübersicht, Dropdown-Listen) und Dialoge sind eigene Fenster, ihre Ereignisse erreichen die Scene nie.
 */
public final class ChangeTrigger {

	private static final Logger LOG = Logger.getLogger(ChangeTrigger.class.getName());

	static final int DEBOUNCE_MILLIS = 300;

	/** Schlüssel in {@link Window#getProperties()}: Der Filter ist schon installiert (ein Fenster wird mehrfach gezeigt). */
	private static final Object INSTALLED = new Object();

	private static PauseTransition pause;

	private ChangeTrigger() {
	}

	private static void checkAll(ChangeTracker<?> tracker) {
		try {
			tracker.checkAll();
		} catch (RuntimeException ex) {
			// Nur ins Log: Bei jeder Bedienung einen Fehlerdialog zu zeigen, wäre schlimmer als eine
			// ausbleibende Anzeige. Beim Verlassen des Screens wird erneut geprüft (LeaveGuard).
			LOG.log(Level.SEVERE, "Änderungserkennung fehlgeschlagen", ex);
		}
	}

	/** Installiert den Filter an allen Fenstern, auch an später geöffneten (einmal beim Start, im FX-Thread). */
	public static void install() {
		if (pause != null) {
			return;
		}
		pause = new PauseTransition(Duration.millis(DEBOUNCE_MILLIS));
		pause.setOnFinished(e -> {
			checkAll(ChangeTracker.events());
			checkAll(ChangeTracker.halls());
		});

		Window.getWindows().forEach(ChangeTrigger::attach);
		Window.getWindows().addListener((ListChangeListener<Window>) change -> {
			while (change.next()) {
				if (change.wasAdded()) {
					change.getAddedSubList().forEach(ChangeTrigger::attach);
				}
			}
		});
	}

	private static void attach(Window window) {
		if (window.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) != null) {
			return;
		}
		EventHandler<javafx.event.Event> restart = e -> pause.playFromStart();
		window.addEventFilter(MouseEvent.MOUSE_RELEASED, restart);
		window.addEventFilter(KeyEvent.KEY_RELEASED, restart);
		window.addEventFilter(ActionEvent.ACTION, restart);
	}
}
