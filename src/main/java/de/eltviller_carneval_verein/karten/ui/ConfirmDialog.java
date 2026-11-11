package de.eltviller_carneval_verein.karten.ui;

import java.net.URI;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.MainApp;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
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
		return confirm(title, message, actionLabel, actionStyleClass, "Abbrechen", owner);
	}

	/**
	 * Wie {@link #confirm(String, String, String, String, Window)}, mit eigener Beschriftung der neutralen Antwort (etwa
	 * "Verbunden bleiben").
	 *
	 * @param cancelLabel Beschriftung der Antwort, die nichts tut
	 */
	public static boolean confirm(String title, String message, String actionLabel, String actionStyleClass, String cancelLabel,
			Window owner) {
		return confirm(title, message, actionLabel, actionStyleClass, cancelLabel, owner, null, null, null);
	}

	/**
	 * Wie {@link #confirm(String, String, String, String, String, Window)}, dazu ein Link unter dem Text (KARV-125:
	 * „Was ist neu in Version …?“). Ein Klick ruft {@code open} und lässt die Rückfrage offen; meldet {@code open} einen
	 * Fehler, erscheint er im Dialog mit der Adresse zum Kopieren.
	 *
	 * @param linkText der Linktext; {@code null} für keinen Link (dann sind {@code page} und {@code open} ohne Belang)
	 * @param page     die Adresse, auch als Tooltip
	 * @param open     öffnet die Adresse und meldet einen Fehler an den zweiten Parameter
	 */
	public static boolean confirm(String title, String message, String actionLabel, String actionStyleClass, String cancelLabel,
			Window owner, String linkText, URI page, BiConsumer<URI, Consumer<String>> open) {
		Built built = build(title, message, actionLabel, actionStyleClass, cancelLabel, owner, linkText, page, open);
		return built.alert().showAndWait().filter(button -> button == built.action()).isPresent();
	}

	/** Der fertige, noch nicht gezeigte Dialog und seine Aktion; für Tests. */
	record Built(Alert alert, ButtonType action) {
	}

	/** Baut die Rückfrage, ohne sie zu zeigen. */
	static Built build(String title, String message, String actionLabel, String actionStyleClass, String cancelLabel,
			Window owner, String linkText, URI page, BiConsumer<URI, Consumer<String>> open) {
		ButtonType action = new ButtonType(actionLabel, ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType(cancelLabel, ButtonData.CANCEL_CLOSE);

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
		if (linkText != null) {
			alert.getDialogPane().setContent(linkContent(alert, message, linkText, page, open));
		}
		return new Built(alert, action);
	}

	/** Text, Link und eine zunächst verborgene Fehlerzeile mit der Adresse zum Kopieren. */
	private static VBox linkContent(Alert alert, String message, String linkText, URI page,
			BiConsumer<URI, Consumer<String>> open) {
		// wie das Standard-Label des DialogPane, damit lange Texte umbrechen statt den Dialog zu verbreitern
		Label text = new Label(message);
		text.setWrapText(true);
		text.getStyleClass().add("content");
		text.setPrefWidth(360);

		Label errorText = new Label();
		errorText.setWrapText(true);
		errorText.setPrefWidth(360);
		TextField address = new TextField(page.toString());
		address.setId("confirm-link-address");
		address.setEditable(false);
		VBox error = new VBox(4, errorText, address);
		error.setId("confirm-link-error");
		error.setVisible(false);
		error.setManaged(false);

		Hyperlink link = new Hyperlink(linkText);
		link.setId("confirm-link");
		link.setTooltip(new Tooltip(page.toString()));
		link.setOnAction(e -> open.accept(page, failure -> {
			errorText.setText(failure);
			error.setVisible(true);
			error.setManaged(true);
			Window window = alert.getDialogPane().getScene() == null ? null : alert.getDialogPane().getScene().getWindow();
			if (window != null) {
				window.sizeToScene();
			}
		}));
		return new VBox(8, text, link, error);
	}
}
