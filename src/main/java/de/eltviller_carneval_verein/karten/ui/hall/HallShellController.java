package de.eltviller_carneval_verein.karten.ui.hall;

import java.io.IOException;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

/**
 * Shell der Hallen-Verwaltung (Header, wechselnder Inhalt, Footer), analog zur
 * Ticket- und Abrechnungs-Shell. Zwei Unteransichten über RadioButtons umschaltbar:
 * Hallenliste (Stammdaten aller Hallen) und Hallenplan (Hallenobjekte der im
 * Header gewählten Halle).
 */
public class HallShellController {

	@FXML private ComboBox<Hall> hallComboBox;
	@FXML private RadioButton btnListView;
	@FXML private RadioButton btnPlanView;
	@FXML private TextField searchField;
	@FXML private Button btnToggleEdit;
	@FXML private StackPane contentArea;
	@FXML private Label lblHeader;

	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();

	private ContentController activeContentController;
	private Hall currentHall;
	private boolean refreshingHalls = false;
	private boolean editMode = false;

	@FXML
	public void initialize() {
		refreshHalls();
		hallComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedHall) -> {
			if (refreshingHalls) {
				return;
			}
			currentHall = selectedHall;
			if (activeContentController != null) {
				activeContentController.setHall(currentHall);
			}
		});

		// Freitext-Suche an die aktive Ansicht weiterreichen
		searchField.textProperty().addListener((obs, oldVal, newValue) -> {
			if (activeContentController != null) {
				activeContentController.filter((newValue == null) ? "" : newValue.toLowerCase().trim());
			}
		});

		showHallListView();
	}

	@FXML
	private void showHallListView() {
		btnListView.setSelected(true);
		btnPlanView.setSelected(false);
		lblHeader.setText("Hallenliste");
		loadContentView("/de/eltviller_carneval_verein/karten/ui/HallManagementView.fxml");
	}

	@FXML
	private void showHallPlanView() {
		refreshHalls();
		if (currentHall == null) {
			btnListView.setSelected(true);
			btnPlanView.setSelected(false);
			MainApp.showAlert("Fehler", "Erst eine Halle auswählen", AlertType.ERROR);
			return;
		}
		btnListView.setSelected(false);
		btnPlanView.setSelected(true);
		lblHeader.setText("Hallenplan");
		loadContentView("/de/eltviller_carneval_verein/karten/ui/HallPlanView.fxml");
	}

	/** Lädt die Hallen neu in die Auswahl (die Liste kann sich in der Hallenliste geändert haben) und behält die Auswahl bei. */
	private void refreshHalls() {
		refreshingHalls = true;
		try {
			hallComboBox.getItems().setAll(hallRepository.loadHalls());
			if (currentHall != null && hallComboBox.getItems().contains(currentHall)) {
				hallComboBox.getSelectionModel().select(currentHall);
			} else {
				currentHall = null;
			}
		} finally {
			refreshingHalls = false;
		}
	}

	private void loadContentView(String fxmlPath) {
		try {
			FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
			Node view = loader.load();

			// Aktiven Inhalts-Controller merken
			this.activeContentController = loader.getController();

			// Inhalt im mittleren Bereich austauschen
			contentArea.getChildren().setAll(view);

			activeContentController.setHall(currentHall);
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
