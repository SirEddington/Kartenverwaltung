package de.eltviller_carneval_verein.karten.ui.sales;

import java.io.IOException;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
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
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;

public class TicketShellController {

	private final JsonEventRepository eventRepository = JsonEventRepository.getInstance();

	@FXML
	private ComboBox<Event> eventComboBox;
	@FXML
	private ComboBox<Presentation> presComboBox;
	@FXML
	private TextField searchField;
	@FXML
	private ToggleGroup viewToggleGroup;
	@FXML
	private RadioButton btnTableView;
	@FXML
	private RadioButton btnHallView;
	@FXML
	private Button btnToggleEdit;
	@FXML
	private StackPane contentArea;
	@FXML
	private Label lblHeader;

	private String searchText = "";
	private ContentController activeContentController;
	private Event currentEvent;
	private Presentation currentPresentation;
	private boolean editMode = false;

	@FXML
	public void initialize() {
		// Events in ComboBox laden (Tabelle bleibt initial leer)
		eventComboBox.getItems().setAll(eventRepository.loadEvents().stream().filter(event -> !event.isArchived()).toList());

		// Event- & Vorstellungs-Listener einrichten
		eventComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			currentEvent = selectedEvent;
			presComboBox.getItems().clear();
			presComboBox.getItems().setAll(currentEvent.getPresentations());
			if (activeContentController != null) {
				activeContentController.setEvent(currentEvent);
			}
		});

		presComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedPresentation) -> {
			currentPresentation = selectedPresentation;
			if (activeContentController != null) {
				activeContentController.setPresentation(currentPresentation);
			}
		});

		// Freitext-Suche auf den geladenen Event-Daten
		searchField.textProperty().addListener((obs, oldVal, newValue) -> {
			searchText = (newValue == null) ? "" : newValue.toLowerCase().trim();
			activeContentController.filter(searchText);
		});

		viewToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
			if (newToggle == btnTableView) {
				showTicketTableView();
			} else {
				showHallView();
			}
		});

		// Standardsicht laden
		showTicketTableView();

		if (eventComboBox.getItems().size() == 1) {
			eventComboBox.getSelectionModel().select(0);
		}

		if (presComboBox.getItems().size() == 1) {
			presComboBox.getSelectionModel().select(0);
		}
	}

	@FXML
	private void showTicketTableView() {
		lblHeader.setText("Kartentabelle");
		loadContentView("/de/eltviller_carneval_verein/karten/ui/TicketTableView.fxml");
		activeContentController.filter(searchText);
	}

	@FXML
	private void showHallView() {
		lblHeader.setText("Saalübersicht");
		loadContentView("/de/eltviller_carneval_verein/karten/ui/HallOverviewView.fxml");
		activeContentController.filter(searchText);
	}

	private void loadContentView(String fxmlPath) {
		try {
			FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
			Node view = loader.load();

			// Aktiven Inhalts-Controller merken
			this.activeContentController = loader.getController();

			// Inhalt im mittleren Bereich austauschen
			contentArea.getChildren().setAll(view);

			// Aktuelles Event und Vorstellung direkt an den neuen Inhalt übergeben
			activeContentController.setEvent(currentEvent);
			activeContentController.setPresentation(currentPresentation);
		} catch (IOException e) {
			e.printStackTrace();
			MainApp.showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
		}
	}

	@FXML
	private void toggleEditMode() {
		this.editMode = !this.editMode;

		// Button-Text anpassen
		if (btnToggleEdit != null) {
			btnToggleEdit.setText(editMode ? "Anzeigen" : "Bearbeiten");
		}

		// Edit-Status an die aktive Inhaltsansicht (Tabelle oder Saalplan) durchreichen
		if (activeContentController != null) {
			activeContentController.setEditMode(editMode);
		}
	}

	@FXML
	private void handleBackToMenu() {
		MainApp.showMenuView();
	}

	@FXML
	private void handleSave() {
		if (activeContentController != null) {
			// Speichere den Zustand der aktuell aktiven Sicht
			activeContentController.save();
		}
	}
}