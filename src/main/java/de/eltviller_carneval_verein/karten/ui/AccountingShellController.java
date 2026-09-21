package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;
import java.util.ArrayList;
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
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;

/**
 * Eigenständiger Bereich "Abrechnung", losgelöst vom Kartenverkauf: eigene
 * Event-/Vorstellungsauswahl, da die Nutzung zeitlich und fachlich getrennt
 * vom Verkauf am Stand stattfindet (siehe Roadmap-Diskussion zur
 * Navigationsstruktur). Zwei Unteransichten über RadioButtons umschaltbar:
 * Kassenabgleich (pro Event/Vorstellung) und Statistik (über alle Events).
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
	private ToggleGroup viewToggleGroup;
	@FXML
	private RadioButton btnViewCash;
	@FXML
	private RadioButton btnViewStats;
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
		// "Alle" ist als echter (null-)Eintrag Teil der Liste, damit er wie eine
		// normale Auswahl wirkt, statt ein Sonderfall über einen extra Button zu sein.
		presComboBox.setConverter(new StringConverter<Presentation>() {
			@Override
			public String toString(Presentation presentation) {
				return presentation == null ? "Alle (Gesamtbilanz)" : presentation.getName();
			}

			@Override
			public Presentation fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		});

		reloadEventItems();

		chkIncludeArchived.selectedProperty().addListener((obs, oldVal, newVal) -> {
			reloadEventItems();
			if (activeContentController instanceof StatisticsController stats) {
				stats.refresh(newVal);
			}
		});

		eventComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			currentEvent = selectedEvent;

			List<Presentation> presentationItems = new ArrayList<>();
			presentationItems.add(null); // "Alle (Gesamtbilanz)"
			if (currentEvent != null) {
				presentationItems.addAll(currentEvent.getPresentations());
			}
			presComboBox.getItems().setAll(presentationItems);
			presComboBox.getSelectionModel().selectFirst();

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

		viewToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
			if (newToggle == btnViewStats) {
				loadStatisticsView();
			} else {
				loadCashReconciliationView();
			}
		});

		loadCashReconciliationView();
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

	/** Statistik ist rein lesend (aggregiert über alle Events), Bearbeiten ergibt dort keinen Sinn. */
	private void updateEditAvailability() {
		boolean statisticsActive = viewToggleGroup.getSelectedToggle() == btnViewStats;
		boolean shouldDisable = statisticsActive || currentPresentation == null;
		btnToggleEdit.setDisable(shouldDisable);

		if (shouldDisable && editMode) {
			editMode = false;
			btnToggleEdit.setText("Bearbeiten");
			if (activeContentController != null) {
				activeContentController.setEditMode(false);
			}
		}
	}

	private void loadCashReconciliationView() {
		loadContent("/de/eltviller_carneval_verein/karten/ui/CashReconciliationView.fxml");
		updateEditAvailability();
	}

	private void loadStatisticsView() {
		loadContent("/de/eltviller_carneval_verein/karten/ui/StatisticsView.fxml");
		if (activeContentController instanceof StatisticsController stats) {
			stats.refresh(chkIncludeArchived.isSelected());
		}
		updateEditAvailability();
	}

	private void loadContent(String fxmlPath) {
		try {
			FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
			Node view = loader.load();

			// Aktiven Inhalts-Controller merken
			this.activeContentController = loader.getController();

			contentArea.getChildren().setAll(view);

			// Aktuelles Event und Vorstellung direkt an den neuen Inhalt übergeben
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
