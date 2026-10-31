package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;

/**
 * Die JavaFX-Umsetzung von {@link SharedFolderEvents.Ui}: nicht blockierende Popups, die Frage im Betrieb und die Fußzeile.
 * Die Logik steht in {@link SharedFolderEvents}. Alles auf dem FX-Thread.
 */
public final class SharedFolderUi implements SharedFolderEvents.Ui {

	/** Das Popup "nicht erreichbar", solange es offen sein kann. */
	private Alert unreachable;

	@Override
	public boolean unreachableOpen() {
		return unreachable != null && unreachable.isShowing();
	}

	@Override
	public void showUnreachable(String text) {
		unreachable = popup(SharedFolderTexts.UNREACHABLE_TITLE, text, SharedFolderTexts.CONTINUE_WORKING, AlertType.WARNING);
	}

	@Override
	public void showDuplicateMachine(String text) {
		popup(SharedFolderTexts.DUPLICATE_MACHINE_TITLE, text, SharedFolderTexts.OK, AlertType.INFORMATION);
	}

	@Override
	public void askDecision(String dir, boolean setAsideAllowed, Consumer<ConnectMode> decide, Runnable cancel) {
		ConnectDecisionDialog.show(MainApp.primaryStage(), setAsideAllowed, true, decide, cancel);
	}

	@Override
	public void closeDecision() {
		ConnectDecisionDialog.closeOpen();
	}

	@Override
	public void footer(Severity severity, String text) {
		// ohne sichtbare Fußzeile (Hauptmenü, Ladeansichten) kein Dialog: Sonst stapelten sich modale Fenster
		StatusMessage.getInstance().showInFooter(severity, text);
	}

	/**
	 * Ein Popup mit einem Knopf, das die Arbeit nicht blockiert: {@code show()} statt {@code showAndWait()}, und nicht modal,
	 * sonst ließe sich das Hauptfenster bis zum Schließen nicht bedienen ("Weiterarbeiten").
	 */
	private static Alert popup(String title, String text, String buttonLabel, AlertType type) {
		Alert alert = new Alert(type, text, new ButtonType(buttonLabel, ButtonData.OK_DONE));
		alert.setTitle(title);
		alert.setHeaderText(null);
		alert.initModality(Modality.NONE);
		if (MainApp.primaryStage() != null) {
			alert.initOwner(MainApp.primaryStage());
		}
		MainApp.applyAppIcon(alert);
		alert.show();
		return alert;
	}
}
