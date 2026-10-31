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
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderConnection;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderConnection.Pending;
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
import javafx.scene.control.CheckBox;
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
 * "Buchungen sofort speichern" ist eine gewöhnliche Einstellung und wird mit "Speichern" gespeichert.
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
		// Der Bediener kommt mit dem Bediener-Dialog (KARV-87, Aufgabe 10); bis dahin ist keiner gesetzt
		String operator = sync.operator();
		if (operator != null) {
			rows.add(new SettingRow(SharedFolderTexts.OPERATOR, MessageTemplate.format(SharedFolderTexts.OPERATOR_HINT, operator),
					operatorControl()));
		}
		rows.add(new SettingRow(SharedFolderTexts.SAVE_IMMEDIATELY, SharedFolderTexts.SAVE_IMMEDIATELY_HINT,
				saveImmediately(settings)));
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
			// Die Ursache einer Störung kennt erst die Fußzeile (Aufgabe 11); bis dahin steht nur "Abgleich gestört"
			status.setText(SharedFolderTexts.status(sync.syncState().get(), dir, sync.lastSync().get(), null,
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
		 * "Trennen": Rückfrage, ungespeicherte Änderungen klären, {@code disconnect()} im Hintergrund, dann {@code sharedDir}
		 * per Teil-Speichern löschen. Der lokale Bestand bleibt, es wird nicht neu geladen. Danach wird der Bildschirm neu
		 * aufgebaut, weil die Frage nach ungespeicherten Änderungen die Änderungserkennung zurückgesetzt hat.
		 */
		private void disconnect() {
			if (disconnecting || !ConfirmDialog.confirm(SharedFolderTexts.DISCONNECT_TITLE, SharedFolderTexts.DISCONNECT_TEXT,
					SharedFolderTexts.DISCONNECT, "button-danger", SharedFolderTexts.STAY_CONNECTED, window())) {
				return;
			}
			if (!LeaveGuard.confirmLeave()) {
				return;
			}
			disconnecting = true;
			refresh();
			connection.background().execute(() -> {
				String failure = null;
				try {
					sync.disconnect();
				} catch (RuntimeException e) {
					LOG.log(Level.WARNING, "Trennen vom gemeinsamen Ordner ist fehlgeschlagen", e);
					failure = message(e);
				}
				String disconnectFailure = failure;
				Platform.runLater(() -> disconnected(disconnectFailure));
			});
		}

		private void disconnected(String disconnectFailure) {
			String failure = disconnectFailure;
			try {
				SettingsStore.getInstance().saveOnly(settings -> settings.setSharedDir(null));
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Die Einstellung des gemeinsamen Ordners ließ sich nicht löschen", e);
				failure = failure == null ? message(e) : failure;
			}
			disconnecting = false;
			Scene scene = node.getScene();
			if (scene != null && scene == MainApp.primaryStage().getScene()) {
				connection.reopenSettings(null, failure);
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

	/** "Bediener wechseln": den Dialog liefert Aufgabe 10, bis dahin ist der Knopf ausgeblendet. */
	private static Node operatorControl() {
		Button change = new Button(SharedFolderTexts.OPERATOR_SWITCH);
		change.getStyleClass().add("button-default");
		setShown(change, false);
		return new HBox(change);
	}

	private static Node saveImmediately(AppSettings settings) {
		CheckBox box = new CheckBox();
		box.setSelected(settings.isSaveBookingsImmediately());
		box.selectedProperty().addListener((observable, was, now) -> settings.setSaveBookingsImmediately(now));
		return box;
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
