package de.eltviller_carneval_verein.karten.ui;

import java.util.concurrent.Callable;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.mirror.MirrorMessages;
import de.eltviller_carneval_verein.karten.mirror.MirrorSetup;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tooltip;
import javafx.stage.Modality;

/**
 * Die Dialoge der Spiegelung im Stil der App (wie {@link ConfirmDialog} und der Dialog des {@link LeaveGuard}):
 * die Rückfragen beim Einrichten und bei einem nicht erreichbaren Spiegelordner sowie der Wartedialog. Titel, Texte
 * und Buttons stehen im Entwurf zum Spiegelordner.
 */
public final class MirrorDialogs {

	private static final String WAIT_TITLE = "Spiegelordner";

	private MirrorDialogs() {
	}

	/**
	 * Beim Einrichten haben der Spiegelordner und die lokalen Daten unterschiedliche Inhalte: Wer soll gelten?
	 * Die Antwort steht am Ende; ein Schließen des Fensters zählt als Abbrechen. Keiner der Buttons ist der
	 * Standard und der Fokus liegt auf "Abbrechen", damit ein versehentliches Enter keine Daten ersetzt.
	 *
	 * @param path der gewählte Spiegelordner
	 */
	public static MirrorSetup.Decision askConflict(String path) {
		ButtonType adopt = new ButtonType("Daten im Spiegelordner übernehmen", ButtonData.OTHER);
		ButtonType writeLocal = new ButtonType("Lokale Daten in den Spiegelordner schreiben", ButtonData.OTHER);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);

		Alert alert = new Alert(AlertType.CONFIRMATION, "Im Ordner " + path + " liegen andere Daten als lokal. Was soll gelten?",
				adopt, writeLocal, cancel);
		alert.setTitle("Daten im Spiegelordner gefunden");
		alert.setHeaderText(null);
		MainApp.applyAppIcon(alert);
		Button adoptButton = (Button) alert.getDialogPane().lookupButton(adopt);
		Button writeButton = (Button) alert.getDialogPane().lookupButton(writeLocal);
		Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancel);
		// Beide Antworten ersetzen Daten und stehen deshalb in Rot; die Folgen stehen im Hinweis am Button
		adoptButton.getStyleClass().add("button-danger");
		writeButton.getStyleClass().add("button-danger");
		cancelButton.getStyleClass().add("button-default");
		adoptButton.setTooltip(new Tooltip("Die lokalen Daten werden vorher gesichert und ersetzt."));
		writeButton.setTooltip(new Tooltip("Die Daten dort werden ersetzt."));
		// Erst nach dem Anzeigen: Den Fokus beim Öffnen setzt die Szene selbst und überschriebe ihn sonst
		alert.setOnShown(event -> Platform.runLater(cancelButton::requestFocus));

		ButtonType answer = alert.showAndWait().orElse(cancel);
		if (answer == adopt) {
			return MirrorSetup.Decision.ADOPT_TARGET;
		}
		if (answer == writeLocal) {
			return MirrorSetup.Decision.WRITE_LOCAL;
		}
		return MirrorSetup.Decision.CANCEL;
	}

	/**
	 * Beim Start ist der Spiegelordner nicht erreichbar (SYS-002). Läuft vor dem Hauptfenster und hat deshalb keinen
	 * Besitzer.
	 *
	 * @param path der Spiegelordner
	 * @return {@code true} bei "Lokal weiterarbeiten", {@code false} bei "Beenden" (auch beim Schließen des Fensters)
	 */
	public static boolean askUnreachableAtStart(String path) {
		ButtonType carryOn = new ButtonType("Lokal weiterarbeiten", ButtonData.OK_DONE);
		ButtonType quit = new ButtonType("Beenden", ButtonData.CANCEL_CLOSE);

		Alert alert = new Alert(AlertType.WARNING, MirrorMessages.unreachable(path), carryOn, quit);
		alert.setTitle("Spiegelordner nicht erreichbar");
		alert.setHeaderText(null);
		MainApp.applyAppIcon(alert);
		// "Lokal weiterarbeiten" ist der Standard (ECV-Blau, aus style.css), "Beenden" die neutrale Alternative
		alert.getDialogPane().lookupButton(quit).getStyleClass().add("button-default");

		return alert.showAndWait().filter(button -> button == carryOn).isPresent();
	}

	/**
	 * Die Verbindung zum Spiegelordner ging während der Sitzung verloren (SYS-002). Es gibt nur "Verstanden", kein
	 * "Beenden": Das ginge an ungespeicherten Änderungen vorbei.
	 *
	 * @param path der Spiegelordner
	 */
	public static void infoUnreachable(String path) {
		ButtonType understood = new ButtonType("Verstanden, lokal weiterarbeiten", ButtonData.OK_DONE);

		Alert alert = new Alert(AlertType.WARNING, MirrorMessages.unreachable(path), understood);
		alert.setTitle("Spiegelordner nicht erreichbar");
		alert.setHeaderText(null);
		MainApp.applyAppIcon(alert);
		alert.showAndWait();
	}

	/**
	 * Führt {@code work} in einem Hintergrund-Thread aus und zeigt dazu einen modalen Wartedialog "Bitte warten ...",
	 * den der Nutzer nicht schließen kann. Die Methode kehrt erst zurück, wenn die Arbeit fertig und der Dialog zu ist:
	 * Der Dialog läuft in einer eigenen Ereignisschleife ({@code showAndWait}), die Oberfläche friert also nicht ein,
	 * der Aufrufer bleibt aber synchron. Läuft der Aufruf nicht im JavaFX-Thread (soll nicht vorkommen), läuft die
	 * Arbeit direkt.
	 *
	 * @param message Text im Dialog, z. B. "Bitte warten ..."
	 * @return das Ergebnis von {@code work}
	 * @throws Exception was {@code work} geworfen hat
	 */
	public static <T> T runWithWaitDialog(String message, Callable<T> work) throws Exception {
		if (!Platform.isFxApplicationThread()) {
			return work.call();
		}

		Task<T> task = new Task<>() {
			@Override
			protected T call() throws Exception {
				return work.call();
			}
		};

		// Ohne Buttons: der Nutzer kann den Dialog weder schließen noch mit Escape wegschicken
		Alert wait = new Alert(AlertType.NONE, message);
		wait.setTitle(WAIT_TITLE);
		wait.setHeaderText(null);
		wait.initModality(Modality.APPLICATION_MODAL);
		MainApp.applyAppIcon(wait);
		// Das Ergebnis vor dem Schließen setzen, sonst fragt ein Dialog ohne Buttons nach der Erlaubnis zum Schließen
		Runnable close = () -> {
			wait.setResult(ButtonType.CLOSE);
			wait.close();
		};
		task.setOnSucceeded(event -> close.run());
		task.setOnFailed(event -> close.run());
		task.setOnCancelled(event -> close.run());

		Thread thread = new Thread(task, "mirror-setup");
		thread.setDaemon(true);
		thread.start();
		// Die Ereignisse des Tasks kommen erst in der Ereignisschleife von showAndWait an, der Dialog steht dann schon
		wait.showAndWait();

		Throwable failure = task.getException();
		if (failure instanceof Exception e) {
			throw e;
		}
		if (failure != null) {
			throw new IllegalStateException(failure);
		}
		return task.getValue();
	}
}
