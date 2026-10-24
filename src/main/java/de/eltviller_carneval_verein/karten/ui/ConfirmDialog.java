package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.MainApp;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

/**
 * Die Rückfrage vor dem Löschen im Stil der App (wie der Dialog des {@link LeaveGuard}): "Löschen" in Rot, weil
 * es Daten kostet, "Abbrechen" neutral und mit dem Fokus, damit ein versehentliches Enter oder Leertaste nichts löscht.
 */
public final class ConfirmDialog {

	private ConfirmDialog() {
	}

	/**
	 * @param title   Titel des Dialogs, z. B. "Event löschen"
	 * @param message die Frage, z. B. "Soll das Event "X" wirklich unwiderruflich gelöscht werden?"
	 * @return {@code true}, wenn der Nutzer das Löschen bestätigt hat
	 */
	public static boolean confirmDelete(String title, String message) {
		return confirm(title, message, "Löschen", "button-danger", null);
	}

	/**
	 * Die allgemeine Rückfrage mit einer Aktion und "Abbrechen" im Stil der App.
	 *
	 * @param actionLabel      Beschriftung der Aktion, z. B. "Wiederherstellen"
	 * @param actionStyleClass Stilklasse der Aktion, z. B. "button-danger" für Aktionen, die Daten kosten
	 * @param owner            Fenster, über dem der Dialog erscheint; {@code null} für das Standardverhalten
	 * @return {@code true}, wenn der Nutzer die Aktion bestätigt hat
	 */
	public static boolean confirm(String title, String message, String actionLabel, String actionStyleClass, Window owner) {
		ButtonType action = new ButtonType(actionLabel, ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);

		Alert alert = new Alert(AlertType.CONFIRMATION, message, action, cancel);
		alert.setTitle(title);
		alert.setHeaderText(null);
		if (owner != null) {
			alert.initOwner(owner);
		}
		MainApp.applyAppIcon(alert);
		Button actionButton = (Button) alert.getDialogPane().lookupButton(action);
		Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancel);
		// Kein Standard-Button: Der Standard (Enter) würde das ECV-Blau aus style.css bekommen und die Stilklasse überdecken
		actionButton.setDefaultButton(false);
		actionButton.getStyleClass().add(actionStyleClass);
		cancelButton.getStyleClass().add("button-default");
		// Der Fokus liegt auf "Abbrechen": Leertaste und Enter lösen die Aktion nicht aus. Erst nach dem Anzeigen
		// setzen, sonst überschreibt ihn der Fokus, den die Szene beim Öffnen selbst vergibt
		alert.setOnShown(event -> Platform.runLater(cancelButton::requestFocus));

		return alert.showAndWait().filter(button -> button == action).isPresent();
	}
}
