package de.eltviller_carneval_verein.karten.ui.hall;

import java.io.IOException;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

/**
 * Shell der Hallen-Verwaltung (Header, wechselnder Inhalt, Footer), analog zur
 * Ticket- und Abrechnungs-Shell. Aktuell gibt es nur die Hallenliste als Inhalt;
 * der Hallenplan-Editor folgt als weitere Unteransicht.
 */
public class HallShellController {

	@FXML private TextField searchField;
	@FXML private Button btnToggleEdit;
	@FXML private StackPane contentArea;
	@FXML private Label lblHeader;

	private ContentController activeContentController;
	private boolean editMode = false;

	@FXML
	public void initialize() {
		// Freitext-Suche an die aktive Ansicht weiterreichen
		searchField.textProperty().addListener((obs, oldVal, newValue) -> {
			if (activeContentController != null) {
				activeContentController.filter((newValue == null) ? "" : newValue.toLowerCase().trim());
			}
		});

		showHallListView();
	}

	private void showHallListView() {
		lblHeader.setText("Hallen-Verwaltung");
		loadContentView("/de/eltviller_carneval_verein/karten/ui/HallManagementView.fxml");
	}

	private void loadContentView(String fxmlPath) {
		try {
			FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
			Node view = loader.load();

			// Aktiven Inhalts-Controller merken
			this.activeContentController = loader.getController();

			// Inhalt im mittleren Bereich austauschen
			contentArea.getChildren().setAll(view);

			activeContentController.setEditMode(editMode);
		} catch (IOException e) {
			e.printStackTrace();
			MainApp.showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
		}
	}

	@FXML
	private void toggleEditMode() {
		editMode = !editMode;
		btnToggleEdit.setText(editMode ? "Anzeigen" : "Bearbeiten");
		if (activeContentController != null) {
			activeContentController.setEditMode(editMode);
		}
	}

	@FXML
	private void handleBackToManagement() {
		MainApp.showManagementMenuView();
	}

	@FXML
	private void handleSave() {
		if (activeContentController != null) {
			activeContentController.save();
		}
	}
}
