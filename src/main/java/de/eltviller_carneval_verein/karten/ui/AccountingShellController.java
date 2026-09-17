package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;
import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.StackPane;

/**
 * Eigenständiger Bereich "Abrechnung", losgelöst vom Kartenverkauf: eigene
 * Event-/Vorstellungsauswahl, da die Nutzung zeitlich und fachlich getrennt
 * vom Verkauf am Stand stattfindet (siehe Roadmap-Diskussion zur
 * Navigationsstruktur). Aktuell nur der Kassenabgleich; Platz für künftige
 * Umsatz-Statistiken im selben Bereich.
 */
public class AccountingShellController {

	private final JsonTicketRepository repository = JsonTicketRepository.getInstance();

	@FXML
	private ComboBox<Event> eventComboBox;
	@FXML
	private CheckBox chkIncludeArchived;
	@FXML
	private ComboBox<Presentation> presComboBox;
	@FXML
	private Button btnToggleEdit;
	@FXML
	private StackPane contentArea;

	private ContentController activeContentController;
	private Event currentEvent;
	private Presentation currentPresentation;
	private boolean editMode = false;

	@FXML
	public void initialize() {
		reloadEventItems();

		chkIncludeArchived.selectedProperty().addListener((obs, oldVal, newVal) -> reloadEventItems());

		eventComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			currentEvent = selectedEvent;
			presComboBox.getItems().clear();
			if (currentEvent != null) {
				presComboBox.getItems().setAll(currentEvent.getPresentations());
			}
			if (activeContentController != null) {
				activeContentController.setEvent(currentEvent);
			}
		});

		// Ohne konkrete Vorstellung gibt es nichts Vorstellungs-Spezifisches zu
		// speichern (der Ist-Kasseninhalt hängt an genau einer Vorstellung) -
		// der Bearbeiten-Modus ist dann gesperrt.
		presComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedPresentation) -> {
			currentPresentation = selectedPresentation;
			updateEditAvailability();
			if (activeContentController != null) {
				activeContentController.setPresentation(currentPresentation);
			}
		});

		loadContent();
		updateEditAvailability();
	}

	private void reloadEventItems() {
		Event previousSelection = eventComboBox.getValue();

		List<Event> events = repository.loadEvents().stream().filter(event -> chkIncludeArchived.isSelected() || !event.isArchived()).toList();

		eventComboBox.getItems().setAll(events);

		if (events.contains(previousSelection)) {
			eventComboBox.setValue(previousSelection);
		} else if (events.size() == 1) {
			eventComboBox.setValue(events.get(0));
		}
	}

	private void updateEditAvailability() {
		btnToggleEdit.setDisable(currentPresentation == null);

		if (currentPresentation == null && editMode) {
			editMode = false;
			btnToggleEdit.setText("Bearbeiten");
			if (activeContentController != null) {
				activeContentController.setEditMode(false);
			}
		}
	}

	private void loadContent() {
		try {
			FXMLLoader loader = new FXMLLoader(getClass().getResource("/de/eltviller_carneval_verein/karten/ui/CashReconciliationView.fxml"));
			Node view = loader.load();

			this.activeContentController = loader.getController();

			contentArea.getChildren().setAll(view);

			activeContentController.setEvent(currentEvent);
			activeContentController.setPresentation(currentPresentation);
			activeContentController.setEditMode(editMode);
		} catch (IOException e) {
			e.printStackTrace();
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
	private void clearPresentationSelection() {
		presComboBox.getSelectionModel().clearSelection();
	}

	@FXML
	private void handleBackToMenu() {
		MainApp.showMenuView();
	}

	@FXML
	private void handleSave() {
		if (activeContentController != null) {
			activeContentController.save();
		}
	}
}
