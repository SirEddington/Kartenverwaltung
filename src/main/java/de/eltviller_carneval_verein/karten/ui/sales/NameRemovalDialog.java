package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Booking;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

/**
 * Die Rückfrage, wenn ein Nachname gelöscht wird und andere Daten stehen bleiben (KARV-121): Die Buchung wäre dann
 * nicht storniert, sondern eine Buchung ohne Namen. Gefragt wird in der Kartentabelle vor dem Übernehmen der Zelle und
 * im Verkaufsdialog vor dem Übernehmen aller Felder.
 */
final class NameRemovalDialog {

	/** Was mit den Buchungen geschehen soll. */
	enum Choice {
		/** die ganzen Buchungen stornieren */
		CANCEL_BOOKINGS,
		/** nur den Namen löschen, der Rest bleibt (wie bisher) */
		NAME_ONLY,
		/** nichts ändern, der Name bleibt */
		ABORT
	}

	private NameRemovalDialog() {
	}

	/**
	 * @param remaining die Buchungen im Stand nach dem Löschen des Namens (mit ihrer Vorstellung, für den gezahlten
	 *                  Betrag)
	 */
	static Choice ask(List<Booking> remaining, Window owner) {
		ButtonType cancelBookings = new ButtonType("Stornieren", ButtonData.OK_DONE);
		ButtonType nameOnly = new ButtonType("Nur Namen löschen", ButtonData.NO);
		ButtonType abort = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);

		Alert alert = new Alert(AlertType.CONFIRMATION, message(remaining), cancelBookings, nameOnly, abort);
		alert.setTitle("Name gelöscht");
		alert.setHeaderText(null);
		if (owner != null) {
			alert.initOwner(owner);
		}
		MainApp.applyAppIcon(alert);
		Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancelBookings);
		Button nameOnlyButton = (Button) alert.getDialogPane().lookupButton(nameOnly);
		Button abortButton = (Button) alert.getDialogPane().lookupButton(abort);
		// Kein Standard-Button: Der Standard (Enter) würde das ECV-Blau aus style.css bekommen und das Rot überdecken
		cancelButton.setDefaultButton(false);
		cancelButton.getStyleClass().add("button-danger");
		nameOnlyButton.setDefaultButton(false);
		nameOnlyButton.getStyleClass().add("button-secondary");
		abortButton.getStyleClass().add("button-default");
		// Der Fokus liegt auf "Abbrechen": Leertaste und Enter ändern nichts. Erst nach dem Anzeigen setzen, sonst
		// überschreibt ihn der Fokus, den die Szene beim Öffnen selbst vergibt
		alert.setOnShown(event -> Platform.runLater(abortButton::requestFocus));

		return alert.showAndWait()
				.map(button -> button == cancelBookings ? Choice.CANCEL_BOOKINGS : button == nameOnly ? Choice.NAME_ONLY : Choice.ABORT)
				.orElse(Choice.ABORT);
	}

	/** Der Text der Rückfrage; bei einer Buchung mit der Liste dessen, was bleibt. */
	static String message(List<Booking> remaining) {
		String text;
		if (remaining.size() == 1) {
			text = "Der Name ist gelöscht, aber andere Daten bleiben (" + String.join(", ", remaining.get(0).dataLabels())
					+ "). Buchung stornieren?";
		} else {
			text = "Bei " + remaining.size() + " Buchungen wird der Name gelöscht, andere Daten bleiben. Buchungen stornieren?";
		}
		String hint = CancelAction.paymentHint(remaining);
		return hint.isEmpty() ? text : text + "\n" + hint;
	}
}
