package de.eltviller_carneval_verein.karten;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;
import javafx.scene.control.Alert.AlertType;

/**
 * Auffangnetz für unerwartete, technische Fehler (SYS-001): jede Ausnahme, die sonst niemand
 * behandelt - auch auf dem JavaFX-Thread, z. B. ein fehlgeschlagenes Speichern - wird ins Log
 * geschrieben und dem Nutzer als Dialog gezeigt, statt unbemerkt in der Konsole zu enden.
 * Fachliche Fehler (negativer Preis o. ä.) gehören nicht hierher, sondern in die Validatoren.
 */
public final class GlobalErrorHandler {

	/** Fehlercode für unerwartete technische Fehler. */
	public static final String CODE = "SYS-001";

	private static final Logger LOG = Logger.getLogger(GlobalErrorHandler.class.getName());
	private static final AtomicBoolean DIALOG_OPEN = new AtomicBoolean(false);

	private GlobalErrorHandler() {
	}

	/** Registriert den Handler für alle Threads. */
	public static void install() {
		Thread.setDefaultUncaughtExceptionHandler((thread, error) -> handle(thread.getName(), error));
	}

	static void handle(String threadName, Throwable error) {
		LOG.log(Level.SEVERE, CODE + ": Unbehandelter Fehler im Thread '" + threadName + "'", error);
		try {
			Platform.runLater(() -> showDialog(error));
		} catch (IllegalStateException toolkitNotRunning) {
			// Noch vor dem Start der Oberfläche: nur das Log bleibt.
		}
	}

	private static void showDialog(Throwable error) {
		// Folgefehler, während schon ein Dialog offen ist, nur loggen und nicht stapeln.
		if (!DIALOG_OPEN.compareAndSet(false, true)) {
			return;
		}
		try {
			MainApp.showAlert("Unerwarteter Fehler", describe(error) + "\n\nDetails stehen in der Log-Datei:\n" + AppLogging.logDir(), AlertType.ERROR);
		} finally {
			DIALOG_OPEN.set(false);
		}
	}

	/** Text für den Dialog: "SYS-001: <Meldung der Ausnahme>". */
	static String describe(Throwable error) {
		String message = error.getMessage();
		return CODE + ": " + ((message == null || message.isBlank()) ? error.getClass().getSimpleName() : message);
	}
}
