package de.eltviller_carneval_verein.karten.ui.sales;

import de.eltviller_carneval_verein.karten.MainApp;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;

/**
 * Die Rückfrage vor dem Sammelverkauf, wenn die Auswahl schon reservierte oder verkaufte Sitze enthält. Sind alle
 * gewählten Sitze belegt, ist "Nur freie Sitze" gesperrt: Es bleibt Überschreiben oder Abbrechen.
 */
final class OccupiedSeatsDialog {

	/** Was mit den belegten Sitzen der Auswahl geschehen soll. */
	enum Choice {
		OVERWRITE, FREE_ONLY, CANCEL
	}

	private OccupiedSeatsDialog() {
	}

	static Choice ask(SeatSelectionSplit split) {
		int occupied = split.occupied().size();
		int total = occupied + split.free().size();
		String message = occupied == total
				? (total == 1 ? "Der gewählte Sitz ist bereits reserviert oder verkauft." : "Alle " + total + " gewählten Sitze sind bereits reserviert oder verkauft.")
				: occupied + " von " + total + " gewählten Sitzen sind bereits reserviert oder verkauft.";

		ButtonType overwrite = new ButtonType("Überschreiben", ButtonData.OK_DONE);
		ButtonType freeOnly = new ButtonType("Nur freie Sitze", ButtonData.YES);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);

		Alert alert = new Alert(AlertType.CONFIRMATION, message + "\nWas soll mit diesen Sitzen geschehen?", overwrite, freeOnly, cancel);
		alert.setTitle("Belegte Sitze");
		alert.setHeaderText(null);
		MainApp.applyAppIcon(alert);
		Button overwriteButton = (Button) alert.getDialogPane().lookupButton(overwrite);
		Button freeOnlyButton = (Button) alert.getDialogPane().lookupButton(freeOnly);
		Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancel);
		// Kein Standard-Button: Der Standard (Enter) würde das ECV-Blau aus style.css bekommen und das Rot überdecken
		overwriteButton.setDefaultButton(false);
		overwriteButton.getStyleClass().add("button-danger");
		freeOnlyButton.setDefaultButton(false);
		freeOnlyButton.getStyleClass().add("button-primary");
		freeOnlyButton.setDisable(!split.hasFree());
		cancelButton.getStyleClass().add("button-default");
		// Der Fokus liegt auf "Abbrechen": Leertaste und Enter überschreiben nichts. Erst nach dem Anzeigen setzen,
		// sonst überschreibt ihn der Fokus, den die Szene beim Öffnen selbst vergibt
		alert.setOnShown(event -> Platform.runLater(cancelButton::requestFocus));

		return alert.showAndWait().map(button -> button == overwrite ? Choice.OVERWRITE : button == freeOnly ? Choice.FREE_ONLY : Choice.CANCEL).orElse(Choice.CANCEL);
	}
}
