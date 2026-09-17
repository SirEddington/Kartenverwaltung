package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
		eventComboBox.getItems().setAll(repository.loadEvents().stream().filter(event -> !event.isArchived()).toList());
		if (eventComboBox.getItems().size() == 1) {
			eventComboBox.setValue(eventComboBox.getItems().get(0));
		}

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

		loadContent();
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
