package de.eltviller_carneval_verein.karten.ui.sales;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import de.eltviller_carneval_verein.karten.ui.DirtyIndicator;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
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
import javafx.util.StringConverter;

public class TicketShellController {

	private static final Logger LOG = Logger.getLogger(TicketShellController.class.getName());
	private static final String ALL_PRESENTATIONS = "Alle Vorstellungen";

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
	private Button btnSave;
	@FXML
	private StackPane contentArea;
	@FXML
	private Label lblHeader;

	// Wird gerade die Vorstellungsliste neu gefüllt, soll die Auswahl nicht an den Inhalt gemeldet werden
	private boolean updatingPresentations = false;
	private String searchText = "";
	private ContentController activeContentController;
	private Event currentEvent;
	private Presentation currentPresentation;
	private boolean editMode = false;

	@FXML
	public void initialize() {
		DirtyIndicator.bind(btnSave, ChangeTracker.events());

		// "Alle Vorstellungen" ist als echter (null-)Eintrag Teil der Liste, wie in der Abrechnung: So lässt sich die
		// Auswahl einer Vorstellung wieder aufheben.
		presComboBox.setConverter(new StringConverter<Presentation>() {
			@Override
			public String toString(Presentation presentation) {
				return presentation == null ? ALL_PRESENTATIONS : presentation.getName();
			}

			@Override
			public Presentation fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		});

		// Events in ComboBox laden (Tabelle bleibt initial leer)
		eventComboBox.getItems().setAll(eventRepository.loadEvents().stream().filter(event -> !event.isArchived()).toList());

		// Event- & Vorstellungs-Listener einrichten
		eventComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			currentEvent = selectedEvent;
			currentPresentation = null;
			if (currentEvent != null) {
				ChangeTracker.events().ensureTracked(currentEvent);
			}
			// Das Neufüllen der Liste meldet der Inhalt nicht einzeln: setEvent unten setzt ihn auf "Alle Vorstellungen"
			updatingPresentations = true;
			try {
				List<Presentation> presentationItems = new ArrayList<>();
				presentationItems.add(null); // "Alle Vorstellungen"
				if (currentEvent != null) {
					presentationItems.addAll(currentEvent.getPresentations());
				}
				presComboBox.getItems().setAll(presentationItems);
				presComboBox.getSelectionModel().selectFirst();
			} finally {
				updatingPresentations = false;
			}
			if (activeContentController != null) {
				activeContentController.setEvent(currentEvent);
			}
		});

		presComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedPresentation) -> {
			if (updatingPresentations) {
				return;
			}
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

		// Hat das einzige Event nur eine Vorstellung, ist sie gleich gewählt (Eintrag 0 ist "Alle Vorstellungen")
		if (presComboBox.getItems().size() == 2) {
			presComboBox.getSelectionModel().select(1);
		}
	}

	@FXML
	private void showTicketTableView() {
		StatusMessage.getInstance().clear();
		lblHeader.setText("Kartentabelle");
		loadContentView("/de/eltviller_carneval_verein/karten/ui/TicketTableView.fxml");
		activeContentController.filter(searchText);
	}

	@FXML
	private void showHallView() {
		StatusMessage.getInstance().clear();
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
			// Der Bearbeitungsmodus gilt für die ganze Shell, nicht nur für die Ansicht, in der er eingeschaltet wurde
			activeContentController.setEditMode(editMode);
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Ansicht konnte nicht geladen werden: " + fxmlPath, e);
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