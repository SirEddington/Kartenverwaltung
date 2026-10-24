package de.eltviller_carneval_verein.karten.ui.settings;

import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.mirror.MirrorService;
import de.eltviller_carneval_verein.karten.mirror.MirrorState;
import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.DirtyIndicator;
import de.eltviller_carneval_verein.karten.ui.SaveShortcut;
import de.eltviller_carneval_verein.karten.ui.SettingsSaver;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

/**
 * Der Einstellungsbildschirm: eine durchsuchbare Liste von Abschnitten mit ihren Einstellungen.
 * <p>
 * Die Abschnitte entstehen in {@link #buildSections(AppSettings)}; Folgetickets hängen ihre Einstellungen dort
 * ein. Die Einstellungen werden über den
 * {@link ChangeTracker#settings() Tracker} verfolgt, sodass Speichern-Knopf und Verlassen-Abfrage wie in den
 * anderen Ansichten arbeiten.
 */
public class SettingsController {

	@FXML
	private TextField searchField;
	@FXML
	private VBox sectionsBox;
	@FXML
	private Label infoLabel;
	@FXML
	private Button btnSave;

	/** Ein aufgebauter Abschnitt samt seinem Knoten und den Knoten seiner Zeilen (gleiche Reihenfolge wie die Zeilen). */
	private record BuiltSection(SettingsSection section, VBox node, List<VBox> rowNodes) {
	}

	private final List<BuiltSection> built = new ArrayList<>();

	@FXML
	public void initialize() {
		AppSettings settings = SettingsStore.getInstance().current();
		ChangeTracker.settings().track(settings);
		DirtyIndicator.bind(btnSave, ChangeTracker.settings());
		SaveShortcut.install(btnSave);

		searchField.textProperty().addListener((obs, oldText, newText) -> applyFilter());
		showSections(buildSections(settings));
	}

	/**
	 * Die Abschnitte dieses Bildschirms. Folgetickets ergänzen hier ihre Einstellungen.
	 *
	 * @param settings die bearbeiteten Einstellungen
	 */
	List<SettingsSection> buildSections(AppSettings settings) {
		return List.of(new SettingsSection("Datenspeicherung",
				List.of(new SettingRow("Spiegelordner",
						"Eine Kopie aller Events und Hallen wird zusätzlich hier abgelegt, z. B. auf einem Netzlaufwerk oder in Nextcloud. Gearbeitet wird weiter lokal.",
						buildMirrorFolderControl(settings)),
						new SettingRow("Status der Spiegelung", "", buildMirrorStatusControl()))));
	}

	/** Pfad des Spiegelordners (nur lesbar) mit den Knöpfen zum Wählen und Entfernen. Gilt erst nach dem Speichern. */
	private Node buildMirrorFolderControl(AppSettings settings) {
		TextField pathField = new TextField(settings.getMirrorDir());
		pathField.setEditable(false);
		pathField.setPromptText("Keine Spiegelung");
		pathField.setPrefWidth(420.0);

		Button chooseButton = new Button("Ordner wählen …");
		chooseButton.getStyleClass().add("button-default");
		Button removeButton = new Button("Entfernen");
		removeButton.getStyleClass().add("button-default");
		removeButton.setDisable(isBlank(settings.getMirrorDir()));

		chooseButton.setOnAction(event -> {
			DirectoryChooser chooser = new DirectoryChooser();
			chooser.setTitle("Spiegelordner wählen");
			File current = isBlank(settings.getMirrorDir()) ? null : new File(settings.getMirrorDir());
			if (current != null && current.isDirectory()) {
				chooser.setInitialDirectory(current);
			}
			File chosen = chooser.showDialog(chooseButton.getScene().getWindow());
			if (chosen != null) {
				settings.setMirrorDir(chosen.getAbsolutePath());
				pathField.setText(settings.getMirrorDir());
				removeButton.setDisable(false);
			}
		});
		removeButton.setOnAction(event -> {
			settings.setMirrorDir(null);
			pathField.clear();
			removeButton.setDisable(true);
		});

		HBox box = new HBox(8.0, pathField, chooseButton, removeButton);
		box.setAlignment(Pos.CENTER_LEFT);
		return box;
	}

	/**
	 * Zustand der aktiven Spiegelung (nicht des noch ungespeicherten Feldes) mit dem Knopf zum sofortigen Abgleich.
	 * Die Listener am langlebigen {@link MirrorService} hängen nur, solange die Zeile in einer Szene liegt: Beim
	 * Verlassen der Ansicht wird sie aus der Szene genommen und die Listener werden ausdrücklich abgemeldet.
	 */
	private Node buildMirrorStatusControl() {
		MirrorService mirror = MirrorService.getInstance();
		Label statusLabel = new Label();
		Button syncButton = new Button("Jetzt abgleichen");
		syncButton.getStyleClass().add("button-default");
		syncButton.setOnAction(event -> mirror.syncNow());

		HBox box = new HBox(12.0, statusLabel, syncButton);
		box.setAlignment(Pos.CENTER_LEFT);

		Runnable refresh = () -> {
			MirrorState state = mirror.state().get();
			statusLabel.setText(MirrorStatusText.of(state, mirror.lastSync().get(), ZoneId.systemDefault()));
			syncButton.setDisable(state == MirrorState.AUS || state == MirrorState.ABGLEICH);
		};
		ChangeListener<Object> listener = (obs, oldValue, newValue) -> refresh.run();
		refresh.run();
		box.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (oldScene != null) {
				mirror.state().removeListener(listener);
				mirror.lastSync().removeListener(listener);
			}
			if (newScene != null) {
				mirror.state().addListener(listener);
				mirror.lastSync().addListener(listener);
				refresh.run();
			}
		});
		return box;
	}

	private static boolean isBlank(String text) {
		return text == null || text.isBlank();
	}

	/**
	 * Ersetzt die angezeigten Abschnitte und wendet den aktuellen Suchtext darauf an. Die Steuerelemente der Zeilen
	 * dürfen nicht über mehrere Aufrufe wiederverwendet werden, weil ein Node nur einen Parent haben kann.
	 *
	 * @param sections die anzuzeigenden Abschnitte
	 */
	public void showSections(List<SettingsSection> sections) {
		built.clear();
		sectionsBox.getChildren().clear();
		for (SettingsSection section : sections) {
			VBox sectionNode = new VBox(10.0);
			Label title = new Label(section.title());
			title.getStyleClass().add("header-label");
			sectionNode.getChildren().add(title);

			List<VBox> rowNodes = new ArrayList<>();
			for (SettingRow row : section.rows()) {
				VBox rowNode = new VBox(2.0);
				rowNode.getChildren().add(new Label(row.label()));
				if (row.hint() != null && !row.hint().isBlank()) {
					Label hint = new Label(row.hint());
					hint.getStyleClass().add("field-caption");
					rowNode.getChildren().add(hint);
				}
				rowNode.getChildren().add(row.control());
				rowNodes.add(rowNode);
				sectionNode.getChildren().add(rowNode);
			}
			built.add(new BuiltSection(section, sectionNode, rowNodes));
			sectionsBox.getChildren().add(sectionNode);
		}
		applyFilter();
	}

	/** Blendet Abschnitte und Zeilen nach dem Suchtext ein oder aus und setzt den Hinweis darunter. */
	private void applyFilter() {
		String query = searchField.getText();
		boolean anyVisible = false;
		for (BuiltSection entry : built) {
			boolean sectionVisible = SettingsFilter.sectionVisible(query, entry.section());
			setShown(entry.node(), sectionVisible);
			anyVisible |= sectionVisible;
			List<SettingRow> rows = entry.section().rows();
			for (int i = 0; i < rows.size(); i++) {
				setShown(entry.rowNodes().get(i), SettingsFilter.rowVisible(query, entry.section(), rows.get(i)));
			}
		}

		String info = null;
		if (built.isEmpty()) {
			info = "Noch keine Einstellungen verfügbar";
		} else if (query != null && !query.isBlank() && !anyVisible) {
			info = "Keine Treffer";
		}
		infoLabel.setText(info);
		setShown(infoLabel, info != null);
	}

	private static void setShown(Node node, boolean shown) {
		node.setVisible(shown);
		node.setManaged(shown);
	}

	@FXML
	private void handleBackToMenu() {
		MainApp.showMenuView();
	}

	@FXML
	private void handleSave() {
		SettingsSaver.save();
	}
}
