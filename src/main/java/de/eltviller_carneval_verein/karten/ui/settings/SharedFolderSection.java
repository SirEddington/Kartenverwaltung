package de.eltviller_carneval_verein.karten.ui.settings;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.shared.OperatorDialog;
import de.eltviller_carneval_verein.karten.ui.shared.OperatorNames;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderConnection;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderConnection.Pending;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderEvents;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderTexts;
import de.eltviller_carneval_verein.karten.validation.MessageTemplate;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/**
 * Der Abschnitt "Gemeinsamer Ordner" im Einstellungsbildschirm: Pfad mit "Durchsuchen …" und "Verbinden" bzw. "Trennen"
 * samt Statuszeile, Rechnername, Bediener und "Buchungen sofort speichern".
 * <p>
 * Der Pfad ist keine gewöhnliche Einstellung: Er wird nur beim Verbinden und Trennen gespeichert (Teil-Speichern), nicht
 * mit "Speichern", und ist gesperrt, solange dieser Rechner verbunden ist (auch offline oder mit ausstehendem Verbinden):
 * Ein Ordnerwechsel heißt erst trennen, dann neu verbinden. Was auf den Takt des Versionsspeichers wartet
 * ({@code disconnect}, {@code renameMachine}), läuft auf dem Hintergrund-Thread der {@link SharedFolderConnection}.
 * "Buchungen sofort speichern" und "Verbindung zum Cloud-Server prüfen" (KARV-127) sind gewöhnliche Einstellungen und
 * werden mit "Speichern" gespeichert.
 */
public final class SharedFolderSection {

	private static final Logger LOG = Logger.getLogger(SharedFolderSection.class.getName());

	private SharedFolderSection() {
	}

	/**
	 * Baut den Abschnitt; die Knoten gehören zu genau einem Aufbau des Bildschirms.
	 *
	 * @param settings   die bearbeiteten Einstellungen (für "Buchungen sofort speichern")
	 * @param connection das Verbinden der App; liefert auch, was ein vorheriger Aufbau noch zeigen sollte (Pfad, Fehler)
	 */
	public static SettingsSection build(AppSettings settings, SyncService sync, SharedFolderConnection connection) {
		Optional<Pending> pending = connection.takePending();
		List<SettingRow> rows = new ArrayList<>();
		FolderRow folder = new FolderRow(settings, sync, connection, pending);
		rows.add(new SettingRow(SharedFolderTexts.SECTION_TITLE, SharedFolderTexts.SECTION_HINT, folder.node));
		rows.add(new SettingRow(SharedFolderTexts.MACHINE_NAME, SharedFolderTexts.MACHINE_NAME_HINT,
				machineName(sync, connection.background())));
		// Der Hinweis "Angemeldet als …" steht im Bedienelement, nicht als Hinweis der Zeile: Er folgt dem Wechsel sofort
		rows.add(new SettingRow(SharedFolderTexts.OPERATOR, null, operatorControl(sync)));
		rows.add(new SettingRow(SharedFolderTexts.SAVE_IMMEDIATELY, SharedFolderTexts.SAVE_IMMEDIATELY_HINT,
				SettingControls.checkBox(settings.isSaveBookingsImmediately(), settings::setSaveBookingsImmediately)));
		// gilt für die Prüfung erst nach "Speichern" (siehe MainApp)
		rows.add(new SettingRow(SharedFolderTexts.CHECK_CLOUD_SERVER, SharedFolderTexts.CHECK_CLOUD_SERVER_HINT,
				SettingControls.checkBox(settings.isCheckCloudServer(), settings::setCheckCloudServer)));
		return new SettingsSection(SharedFolderTexts.SECTION_TITLE, rows);
	}

	/** Die Zeile mit dem Pfad, den Knöpfen und der Statuszeile; folgt dem Zustand des Versionsspeichers. */
	private static final class FolderRow {

		private final SyncService sync;
		private final SharedFolderConnection connection;
		private final TextField path = new TextField();
		private final Button browse = new Button(SharedFolderTexts.BROWSE);
		private final Button connect = new Button(SharedFolderTexts.CONNECT);
		private final Button disconnect = new Button(SharedFolderTexts.DISCONNECT);
		private final Label error = errorLabel();
		private final Label status = wrapped();
		private final VBox node;
		/** Hält den Listener, solange die Zeile lebt; der Versionsspeicher kennt ihn nur schwach. */
		private final ChangeListener<Object> refresher = (observable, oldValue, newValue) -> refresh();
		private boolean disconnecting;

		FolderRow(AppSettings settings, SyncService sync, SharedFolderConnection connection, Optional<Pending> pending) {
			this.sync = sync;
			this.connection = connection;
			String typed = pending.map(Pending::path).orElse(null);
			path.setText(typed != null ? typed : settings.getSharedDir());
			showError(error, pending.map(Pending::error).orElse(null));

			HBox.setHgrow(path, Priority.ALWAYS);
			browse.getStyleClass().add("button-default");
			browse.setMinWidth(Region.USE_PREF_SIZE);
			browse.setOnAction(e -> browse());
			connect.getStyleClass().add("button-primary");
			connect.setMinWidth(Region.USE_PREF_SIZE);
			connect.setOnAction(e -> {
				showError(error, null);
				connection.connectByButton(path.getText());
			});
			disconnect.getStyleClass().add("button-danger");
			disconnect.setMinWidth(Region.USE_PREF_SIZE);
			disconnect.setOnAction(e -> disconnect());
			HBox line = new HBox(10, path, browse, connect, disconnect);
			line.setAlignment(Pos.CENTER_LEFT);
			node = new VBox(6, line, error, status);
			node.getProperties().put(FolderRow.class, this);

			sync.syncState().addListener(new WeakChangeListener<>(refresher));
			sync.lastSync().addListener(new WeakChangeListener<>(refresher));
			sync.connectionComplete().addListener(new WeakChangeListener<>(refresher));
			refresh();
		}

		/**
		 * Verbunden heißt: ein Ordner ist eingestellt oder der erste Lauf einer Verbindung steht noch aus. Dann ist nur
		 * "Trennen" möglich, und das Feld zeigt den Ordner des Versionsspeichers.
		 */
		private void refresh() {
			String dir = sync.sharedDir();
			boolean connected = dir != null || !sync.connectionComplete().get();
			if (dir != null) {
				path.setText(dir);
			}
			path.setDisable(connected || disconnecting);
			browse.setDisable(connected || disconnecting);
			setShown(connect, !connected);
			setShown(disconnect, connected);
			disconnect.setDisable(disconnecting);
			String cause = SharedFolderEvents.instance().map(SharedFolderEvents::lastFailure).orElse(null);
			status.setText(SharedFolderTexts.status(sync.syncState().get(), dir, sync.lastSync().get(), cause,
					ZoneId.systemDefault()));
		}

		private void browse() {
			DirectoryChooser chooser = new DirectoryChooser();
			chooser.setTitle(SharedFolderTexts.SECTION_TITLE);
			// Kein Startordner aus dem Feld: Schon die Prüfung, ob es ihn gibt, kann bei einem Netzlaufwerk hängen
			File chosen = chooser.showDialog(node.getScene() == null ? null : node.getScene().getWindow());
			if (chosen != null) {
				path.setText(chosen.getAbsolutePath());
				showError(error, null);
			}
		}

		/**
		 * "Trennen": Rückfrage, {@code sharedDir} per Teil-Speichern löschen, dann {@code disconnect()} im Hintergrund. Der
		 * lokale Bestand bleibt, es wird nicht neu geladen. Danach wird der Bildschirm neu aufgebaut.
		 * <p>
		 * Trennen fragt nicht nach ungespeicherten Änderungen ({@code LeaveGuard}): Das Teil-Speichern führt die
		 * Änderungserkennung des Bildschirms nur nach, eine offene Änderung (etwa "Buchungen sofort speichern") bleibt
		 * "geändert". Ein {@code LeaveGuard.confirmLeave()} vorab ließe die Änderungserkennung den Bildschirm vergessen:
		 * Eine ungespeicherte Änderung wäre nach dem Neuaufbau nicht mehr als solche erkennbar, sähe gespeichert aus und
		 * wäre nie geschrieben. Ist beim Neuaufbau etwas offen, fragt der Wechsel der Ansicht selbst.
		 */
		private void disconnect() {
			if (disconnecting || !ConfirmDialog.confirm(SharedFolderTexts.DISCONNECT_TITLE, SharedFolderTexts.DISCONNECT_TEXT,
					SharedFolderTexts.DISCONNECT, "button-danger", SharedFolderTexts.STAY_CONNECTED, window())) {
				return;
			}
			disconnecting = true;
			// der neu aufgebaute Abschnitt zeigt den getrennten Ordner im Feld: bequem, um später wieder zu verbinden
			String disconnectedDir = sync.sharedDir();
			refresh();
			// Zuerst die Einstellung: Schließt die App zwischen beiden Schritten, bliebe nach umgekehrter Reihenfolge der Ordner
			// eingetragen, und der nächste Start verbände das Getrennte wieder. Sie bleibt gelöscht, auch wenn das Trennen scheitert.
			String clearFailure = null;
			try {
				SettingsStore.getInstance().saveOnly(settings -> settings.setSharedDir(null));
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Die Einstellung des gemeinsamen Ordners ließ sich nicht löschen", e);
				clearFailure = message(e);
			}
			String settingFailure = clearFailure;
			connection.background().execute(() -> {
				String failure = settingFailure;
				try {
					sync.disconnect();
				} catch (RuntimeException e) {
					LOG.log(Level.WARNING, "Trennen vom gemeinsamen Ordner ist fehlgeschlagen", e);
					failure = failure == null ? message(e) : failure;
				}
				String disconnectFailure = failure;
				Platform.runLater(() -> disconnected(disconnectedDir, disconnectFailure));
			});
		}

		private void disconnected(String disconnectedDir, String failure) {
			disconnecting = false;
			// Lehnt die Rückfrage zu ungespeicherten Änderungen den Neuaufbau ab ("Abbrechen"), bleibt diese Zeile stehen:
			// Sie zeigt dann den getrennten Stand (Pfad und "Durchsuchen …" frei)
			refresh();
			Scene scene = node.getScene();
			if (scene != null && scene == MainApp.primaryStage().getScene()) {
				connection.reopenSettings(disconnectedDir, failure);
			} else if (failure != null) {
				// Inzwischen in einer anderen Ansicht: dort nicht stören, nur melden
				StatusMessage.getInstance().show(Severity.ERROR, failure);
			}
		}

		private Window window() {
			return node.getScene() == null ? null : node.getScene().getWindow();
		}
	}

	/** Rechnername mit "Ändern": geschrieben im Hintergrund ({@code renameMachine} nimmt {@code workLock}). */
	private static Node machineName(SyncService sync, Executor background) {
		TextField name = new TextField(sync.machineName());
		Button rename = new Button(SharedFolderTexts.RENAME);
		rename.getStyleClass().add("button-default");
		rename.setMinWidth(Region.USE_PREF_SIZE);
		Label error = errorLabel();
		HBox.setHgrow(name, Priority.ALWAYS);
		rename.setOnAction(e -> {
			String typed = name.getText();
			if (typed == null || typed.isBlank()) {
				showError(error, SharedFolderTexts.MACHINE_NAME_EMPTY);
				return;
			}
			showError(error, null);
			rename.setDisable(true);
			background.execute(() -> {
				String failure = null;
				try {
					sync.renameMachine(typed);
				} catch (IllegalArgumentException ex) {
					failure = SharedFolderTexts.MACHINE_NAME_EMPTY;
				} catch (IOException | RuntimeException ex) {
					LOG.log(Level.WARNING, "Rechnername ließ sich nicht ändern", ex);
					failure = message(ex);
				}
				String renameFailure = failure;
				Platform.runLater(() -> {
					rename.setDisable(false);
					if (renameFailure != null) {
						showError(error, renameFailure);
					} else {
						name.setText(sync.machineName());
						StatusMessage.getInstance().showSaved();
					}
				});
			});
		});
		HBox line = new HBox(10, name, rename);
		line.setAlignment(Pos.CENTER_LEFT);
		return new VBox(6, line, error);
	}

	/**
	 * Der angemeldete Bediener und "Bediener wechseln". Der Dialog ({@code atStart = false}) liefert nur einen Namen;
	 * "Abbrechen" und Schließen lassen den alten. Der Wechsel gilt sofort (nicht erst mit "Speichern") und ändert nichts an
	 * anderen offenen Änderungen des Bildschirms.
	 */
	private static Node operatorControl(SyncService sync) {
		Label current = new Label();
		current.setWrapText(true);
		current.setMinHeight(Region.USE_PREF_SIZE);
		current.setText(loggedInText(sync.operator()));
		Button change = new Button(SharedFolderTexts.OPERATOR_SWITCH);
		change.getStyleClass().add("button-default");
		change.setMinWidth(Region.USE_PREF_SIZE);
		change.setOnAction(e -> {
			// Der jetzige Bediener steht vorn und ist vorbelegt, auch wenn die Liste ihn (noch) nicht kennt
			List<String> recent = OperatorNames.remember(SettingsStore.getInstance().current().getRecentOperators(), sync.operator());
			Optional<String> chosen = OperatorDialog.ask(change.getScene() == null ? null : change.getScene().getWindow(), recent, false);
			chosen.ifPresent(name -> {
				OperatorDialog.adopt(sync, name);
				current.setText(loggedInText(sync.operator()));
			});
		});
		HBox line = new HBox(14, current, change);
		line.setAlignment(Pos.CENTER_LEFT);
		return line;
	}

	private static String loggedInText(String operator) {
		return operator == null ? "" : MessageTemplate.format(SharedFolderTexts.OPERATOR_HINT, operator);
	}

	private static Label errorLabel() {
		Label label = wrapped();
		label.getStyleClass().add("settings-error");
		setShown(label, false);
		return label;
	}

	private static Label wrapped() {
		Label label = new Label();
		label.setWrapText(true);
		label.setMinHeight(Region.USE_PREF_SIZE);
		return label;
	}

	private static void showError(Label label, String text) {
		label.setText(text);
		setShown(label, text != null && !text.isBlank());
	}

	private static String message(Throwable failure) {
		return MainApp.failureMessage(failure, shown -> shown.getClass().getSimpleName());
	}

	private static void setShown(Node node, boolean shown) {
		node.setVisible(shown);
		node.setManaged(shown);
	}
}
