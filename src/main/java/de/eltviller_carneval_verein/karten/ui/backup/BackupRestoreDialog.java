package de.eltviller_carneval_verein.karten.ui.backup;

import java.time.format.DateTimeFormatter;
import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.repository.BackupStore.Backup;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Entry;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Der Dialog zum Wiederherstellen aus den Sicherungen: links die Events (bzw. Hallen) mit Sicherungen, auch
 * gelöschte, rechts deren Sicherungen mit Zeitpunkt und Größe. Für Events und Hallen derselbe, über einen
 * {@link BackupCatalog}.
 */
public final class BackupRestoreDialog {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

	private BackupRestoreDialog() {
	}

	static String entryText(Entry entry) {
		return entry.deleted() ? entry.name() + " (gelöscht)" : entry.name();
	}

	static String versionText(Backup backup) {
		long kilobytes = Math.max(1, (backup.size() + 512) / 1024);
		return backup.timestamp().format(TIME_FORMAT) + " (" + kilobytes + " KB)";
	}

	/**
	 * Zeigt den Dialog und wartet auf das Schließen.
	 *
	 * @return {@code true}, wenn etwas wiederhergestellt wurde (die Übersicht muss neu laden)
	 */
	public static boolean show(BackupCatalog catalog, Window owner) {
		ListView<Entry> entryList = new ListView<>();
		entryList.setPrefSize(260, 320);
		entryList.setPlaceholder(new Label("Keine Sicherungen vorhanden."));
		entryList.setCellFactory(list -> new ListCell<>() {
			@Override
			protected void updateItem(Entry entry, boolean empty) {
				super.updateItem(entry, empty);
				setText(empty || entry == null ? null : entryText(entry));
			}
		});
		entryList.getItems().setAll(catalog.entries());

		ListView<Backup> versionList = new ListView<>();
		versionList.setPrefSize(280, 320);
		versionList.setPlaceholder(new Label("Links einen Eintrag wählen."));
		versionList.setCellFactory(list -> new ListCell<>() {
			@Override
			protected void updateItem(Backup backup, boolean empty) {
				super.updateItem(backup, empty);
				setText(empty || backup == null ? null : versionText(backup));
			}
		});
		entryList.getSelectionModel().selectedItemProperty().addListener((obs, oldEntry, entry) -> {
			versionList.getItems().setAll(entry == null ? List.of() : catalog.versions(entry.id()));
			versionList.getSelectionModel().selectFirst();
		});

		HBox content = new HBox(15, new VBox(5, new Label(catalog.entityLabel() + ":"), entryList), new VBox(5, new Label("Sicherungen (neueste zuerst):"), versionList));
		content.setPadding(new Insets(5, 0, 0, 0));

		ButtonType restore = new ButtonType("Wiederherstellen", ButtonData.OK_DONE);
		ButtonType close = new ButtonType("Schließen", ButtonData.CANCEL_CLOSE);
		Alert dialog = new Alert(AlertType.NONE, null, restore, close);
		dialog.setTitle(catalog.entityLabel() + " wiederherstellen");
		dialog.setHeaderText(null);
		dialog.getDialogPane().setContent(content);
		if (owner != null) {
			dialog.initOwner(owner);
		}
		MainApp.applyAppIcon(dialog);

		Button restoreButton = (Button) dialog.getDialogPane().lookupButton(restore);
		Button closeButton = (Button) dialog.getDialogPane().lookupButton(close);
		restoreButton.setDefaultButton(false);
		restoreButton.getStyleClass().add("button-primary");
		closeButton.getStyleClass().add("button-default");
		restoreButton.disableProperty().bind(versionList.getSelectionModel().selectedItemProperty().isNull());

		RestoreResult[] done = new RestoreResult[1];
		// Der Dialog bleibt bei "Wiederherstellen" offen, bis es geklappt hat: Fehler zeigt er, ohne die Auswahl zu verlieren
		restoreButton.addEventFilter(ActionEvent.ACTION, event -> {
			event.consume();
			Entry entry = entryList.getSelectionModel().getSelectedItem();
			Backup backup = versionList.getSelectionModel().getSelectedItem();
			if (entry == null || backup == null || !confirm(dialog, catalog, entry, backup)) {
				return;
			}
			RestoreResult result = catalog.restore(entry, backup);
			if (result.success()) {
				done[0] = result;
				dialog.close();
			} else {
				MainApp.showAlert("Wiederherstellen fehlgeschlagen", result.message(), AlertType.ERROR);
			}
		});

		dialog.showAndWait();

		RestoreResult result = done[0];
		if (result != null && !result.notes().isEmpty()) {
			MainApp.showAlert("Wiederhergestellt", result.message() + "\n\n" + String.join("\n", result.notes()), AlertType.INFORMATION);
		}
		return result != null;
	}

	/** Die Rückfrage mit dem Hinweis, dass der aktuelle Stand vorher gesichert wird; Fokus auf "Abbrechen". */
	private static boolean confirm(Alert parent, BackupCatalog catalog, Entry entry, Backup backup) {
		ButtonType yes = new ButtonType("Wiederherstellen", ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);
		String message = catalog.entityLabel() + " \"" + entry.name() + "\" auf den Stand vom " + backup.timestamp().format(TIME_FORMAT)
				+ " zurücksetzen?\n\nDer aktuelle Stand wird vorher gesichert.";
		Alert alert = new Alert(AlertType.CONFIRMATION, message, yes, cancel);
		alert.setTitle("Wiederherstellen");
		alert.setHeaderText(null);
		alert.initOwner(parent.getDialogPane().getScene().getWindow());
		MainApp.applyAppIcon(alert);
		Button yesButton = (Button) alert.getDialogPane().lookupButton(yes);
		Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancel);
		yesButton.setDefaultButton(false);
		yesButton.getStyleClass().add("button-primary");
		cancelButton.getStyleClass().add("button-default");
		alert.setOnShown(event -> cancelButton.requestFocus());
		return alert.showAndWait().filter(button -> button == yes).isPresent();
	}
}
