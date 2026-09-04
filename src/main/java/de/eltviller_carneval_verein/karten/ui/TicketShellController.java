package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

public class TicketShellController {
	
	private final JsonTicketRepository repository = new JsonTicketRepository();

    @FXML private ComboBox<Event> eventComboBox;
    @FXML private ComboBox<Presentation> presComboBox;
    @FXML private TextField searchField;
    @FXML private RadioButton btnTableView;
    @FXML private RadioButton btnHallView;
    @FXML private Button btnToggleEdit;
    @FXML private StackPane contentArea;

    private ContentController activeContentController;
    private Event currentEvent;
    private Presentation currentPresentation;
    private boolean editMode = false;

    @FXML
    public void initialize() {
		// Events in ComboBox laden (Tabelle bleibt initial leer)
		eventComboBox.getItems().setAll(repository.loadEvents().stream().filter(event -> !event.isArchived()).toList());
		if (eventComboBox.getItems().size() == 1) {
			eventComboBox.setValue(eventComboBox.getItems().get(0));
		}
		
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
			activeContentController.filter((newValue == null) ? "" : newValue.toLowerCase().trim());
		});

        // Standardsicht laden
        showTicketTableView();
    }

    @FXML
    private void showTicketTableView() {
    	btnTableView.setSelected(true);
    	btnHallView.setSelected(false);
        loadContentView("/de/eltviller_carneval_verein/karten/ui/TicketTableView.fxml");
    }

    @FXML
    private void showHallView() {
    	if (currentPresentation == null) {
	    	btnTableView.setSelected(true);
	    	btnHallView.setSelected(false);
			MainApp.showAlert("Fehler", "Erst eine Vostellung auswählen", AlertType.ERROR);
		} else {
	    	btnTableView.setSelected(false);
	    	btnHallView.setSelected(true);
	        loadContentView("/de/eltviller_carneval_verein/karten/ui/HallOverviewView.fxml");
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

            // Aktuelles Event und  Vorstellung direkt an den neuen Inhalt übergeben
            activeContentController.setEvent(currentEvent);
            activeContentController.setPresentation(currentPresentation);
        } catch (IOException e) {
            e.printStackTrace();
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