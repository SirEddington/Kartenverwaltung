package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.store.SyncService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Die Frage "Wer verkauft?": ein bearbeitbares Auswahlfeld mit den zuletzt verwendeten Namen, vorbelegt mit dem ersten.
 * Der Name ist Pflicht; "Weiter" bzw. "Übernehmen" ist nur mit einem gültigen Namen ({@link OperatorNames#valid}) aktiv.
 * <p>
 * Beim Start heißen die Knöpfe "Weiter" und "Beenden"; das Schließen des Fensters gilt als "Beenden", die Taste Esc
 * nicht (ein Fehlgriff soll die Kartenverwaltung nicht schließen). Beim Wechsel aus den Einstellungen heißen sie
 * "Übernehmen" und "Abbrechen"; "Abbrechen" und Schließen lassen den alten Bediener. In beiden Fällen liefert
 * {@link #ask} dann nichts; was daraus folgt (beenden oder nichts tun), entscheidet der Aufrufer. Der Dialog setzt
 * selbst nichts, er liefert nur den Namen.
 */
public final class OperatorDialog {

	private static final Logger LOG = Logger.getLogger(OperatorDialog.class.getName());
	/** Breite des Fensters; der Text bricht darin um. */
	private static final double WIDTH = 460;

	private OperatorDialog() {
	}

	/**
	 * Übernimmt den gewählten Namen (FX-Thread): Er gilt als Bediener der Sitzung und steht danach an erster Stelle der
	 * zuletzt verwendeten Namen (Teil-Speichern, andere offene Änderungen der Einstellungen bleiben unberührt). Lässt sich
	 * die Liste nicht schreiben, gilt der Name trotzdem; es fehlt nur der Vorschlag beim nächsten Start.
	 *
	 * @param name ein gültiger Name ({@link OperatorNames#valid})
	 */
	public static void adopt(SyncService sync, String name) {
		sync.setOperator(name);
		try {
			SettingsStore.getInstance().saveOnly(settings -> settings.setRecentOperators(
					OperatorNames.remember(settings.getRecentOperators(), name)));
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Die zuletzt verwendeten Bediener ließen sich nicht speichern", e);
		}
	}

	/**
	 * Zeigt den Dialog und wartet auf die Antwort (FX-Thread).
	 *
	 * @param owner   das Fenster, über dem der Dialog steht (modal); {@code null}: modal für die ganze Anwendung
	 * @param recent  die zuletzt verwendeten Namen, der jüngste zuerst; der erste ist vorbelegt
	 * @param atStart {@code true} beim Start ("Weiter"/"Beenden"), {@code false} beim Wechsel ("Übernehmen"/"Abbrechen")
	 * @return der Name ohne Leerraum am Rand; leer bei "Beenden", "Abbrechen" und beim Schließen des Fensters
	 */
	@SuppressWarnings("exports")
	public static Optional<String> ask(Window owner, List<String> recent, boolean atStart) {
		Stage stage = new Stage();
		stage.setTitle(SharedFolderTexts.OPERATOR_TITLE);
		stage.initModality(owner != null ? Modality.WINDOW_MODAL : Modality.APPLICATION_MODAL);
		if (owner != null) {
			stage.initOwner(owner);
		}
		try {
			stage.getIcons().add(new Image(MainApp.class.getResourceAsStream(
					"/de/eltviller_carneval_verein/karten/ui/images/harlekin_logo.png")));
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Symbol des Bediener-Dialogs nicht ladbar", e);
		}

		Label text = new Label(SharedFolderTexts.OPERATOR_TEXT);
		text.setWrapText(true);
		text.setMaxWidth(Double.MAX_VALUE);
		// Umbrochene Texte werden nicht auf eine Zeile gekürzt
		text.setMinHeight(Region.USE_PREF_SIZE);
		text.getStyleClass().add("settings-label");

		Label fieldLabel = new Label(SharedFolderTexts.OPERATOR_FIELD);
		fieldLabel.getStyleClass().add("settings-label");
		ComboBox<String> names = new ComboBox<>(FXCollections.observableArrayList(recent == null ? List.of() : recent));
		names.setEditable(true);
		names.setMaxWidth(Double.MAX_VALUE);
		if (!names.getItems().isEmpty()) {
			names.setValue(names.getItems().get(0));
		}
		HBox.setHgrow(names, Priority.ALWAYS);
		HBox field = new HBox(10, fieldLabel, names);
		field.setAlignment(Pos.CENTER_LEFT);

		String[] answer = new String[1];
		Button proceed = new Button(atStart ? SharedFolderTexts.OPERATOR_CONTINUE : SharedFolderTexts.OPERATOR_APPLY);
		proceed.getStyleClass().add("button-primary");
		proceed.setDefaultButton(true);
		// Der Text des Feldes zählt, nicht der gewählte Eintrag: Getippt wird nicht in die Liste übernommen
		proceed.disableProperty().bind(names.getEditor().textProperty().map(typed -> OperatorNames.valid(typed).isEmpty()));
		proceed.setOnAction(e -> {
			OperatorNames.valid(names.getEditor().getText()).ifPresent(name -> {
				answer[0] = name;
				stage.close();
			});
		});
		Button leave = new Button(atStart ? SharedFolderTexts.OPERATOR_QUIT : SharedFolderTexts.CANCEL);
		leave.getStyleClass().add("button-default");
		// Beim Wechsel schließt Esc wie "Abbrechen"; beim Start gilt Esc nicht als "Beenden"
		leave.setCancelButton(!atStart);
		leave.setOnAction(e -> stage.close());
		HBox buttons = new HBox(10, proceed, leave);
		buttons.setAlignment(Pos.CENTER_RIGHT);

		VBox root = new VBox(18, text, field, buttons);
		root.setPadding(new Insets(20));
		Scene scene = new Scene(root, WIDTH, -1);
		scene.getStylesheets().add(MainApp.class.getResource("/de/eltviller_carneval_verein/karten/ui/style.css").toExternalForm());
		stage.setScene(scene);
		stage.setResizable(false);
		// Die Eingabe steht bereit: Tippen überschreibt den vorbelegten Namen
		stage.setOnShown(e -> Platform.runLater(() -> {
			names.getEditor().requestFocus();
			names.getEditor().selectAll();
		}));
		stage.showAndWait();
		return Optional.ofNullable(answer[0]);
	}
}
