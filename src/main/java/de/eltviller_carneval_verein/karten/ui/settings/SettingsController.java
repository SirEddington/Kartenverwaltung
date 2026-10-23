package de.eltviller_carneval_verein.karten.ui.settings;

import java.util.ArrayList;
import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.DirtyIndicator;
import de.eltviller_carneval_verein.karten.ui.SaveShortcut;
import de.eltviller_carneval_verein.karten.ui.SettingsSaver;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Der Einstellungsbildschirm: eine durchsuchbare Liste von Abschnitten mit ihren Einstellungen.
 * <p>
 * Das Gerüst kennt noch keine konkrete Einstellung; Folgetickets hängen ihre Abschnitte in
 * {@link #buildSections(AppSettings)} ein. Die Einstellungen werden über den
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
		return List.of();
	}

	/**
	 * Ersetzt die angezeigten Abschnitte und wendet den aktuellen Suchtext darauf an.
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
