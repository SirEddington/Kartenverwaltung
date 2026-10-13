package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.MainApp;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;

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
		ButtonType delete = new ButtonType("Löschen", ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);

		Alert alert = new Alert(AlertType.CONFIRMATION, message, delete, cancel);
		alert.setTitle(title);
		alert.setHeaderText(null);
		MainApp.applyAppIcon(alert);
		Button deleteButton = (Button) alert.getDialogPane().lookupButton(delete);
		Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancel);
		// Kein Standard-Button: Der Standard (Enter) würde das ECV-Blau aus style.css bekommen und das Rot überdecken
		deleteButton.setDefaultButton(false);
		deleteButton.getStyleClass().add("button-danger");
		cancelButton.getStyleClass().add("button-default");
		// Der Fokus liegt auf "Abbrechen": Leertaste und Enter löschen nichts
		alert.setOnShown(event -> cancelButton.requestFocus());

		return alert.showAndWait().filter(button -> button == delete).isPresent();
	}
}
