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
import de.eltviller_carneval_verein.karten.ui.PopupWindows;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
	// Gemeinsamer Zustand mit dem Kundenfenster: Die Shell und ihre Inhalte schreiben ihn, das Kundenfenster liest ihn
	private final SalesViewState state = new SalesViewState();

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
	private CheckBox chkSalesMode;
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
	private CustomerDisplay customerDisplay;
	private final ChangeListener<Boolean> windowShowing = (obs, wasShowing, isShowing) -> {
		if (!isShowing) {
			closeCustomerDisplay();
		}
	};

	@FXML
	public void initialize() {
		DirtyIndicator.bind(btnSave, ChangeTracker.events());

		// Das Kundenfenster verschwindet mit der Ansicht: Wird die Szene ersetzt (Menü, anderer Bildschirm) oder das
		// Hauptfenster geschlossen, schließt es mit (sonst hielte es die App am Leben). Die Szene bleibt an contentArea
		// hängen, auch wenn das Fenster eine andere zeigt: Deshalb wird ihr Fenster beobachtet, nicht contentArea.
		contentArea.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene != null) {
				newScene.windowProperty().addListener((o, oldWindow, newWindow) -> {
					if (oldWindow != null) {
						oldWindow.showingProperty().removeListener(windowShowing);
					}
					if (newWindow == null) {
						closeCustomerDisplay();
					} else {
						newWindow.showingProperty().addListener(windowShowing);
					}
				});
			}
		});

		// "Alle Vorstellungen" ist als echter (null-)Eintrag Teil der Liste, wie in der Abrechnung: So lässt sich die
		// Auswahl einer Vorstellung wieder aufheben.
		presComboBox.setConverter(new StringConverter<Presentation>() {
			@Override
			public String toString(Presentation presentation) {
				return presentation == null ? ALL_PRESENTATIONS : EntityLabels.nameOrPlaceholder(presentation.getName());
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
			state.eventProperty().set(currentEvent);
			state.presentationProperty().set(null);
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
			state.presentationProperty().set(currentPresentation);
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

			// Inhalt im mittleren Bereich austauschen; ein offener Dialog der alten Ansicht würde sonst stehen bleiben
			PopupWindows.hideOwnedBy(contentArea.getScene() == null ? null : contentArea.getScene().getWindow());
			contentArea.getChildren().setAll(view);

			// Auswahl und Ausschnitt der alten Ansicht gelten nicht mehr; der neue Inhalt meldet seine eigenen
			state.resetView();
			if (activeContentController instanceof SalesStateAware aware) {
				aware.setSalesState(state);
			}

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

	/** Die Checkbox "Verkaufsmodus" öffnet und schließt das Kundenfenster. */
	@FXML
	private void toggleSalesMode() {
		if (chkSalesMode.isSelected()) {
			openCustomerDisplay();
		} else {
			closeCustomerDisplay();
		}
	}

	private void openCustomerDisplay() {
		if (customerDisplay != null) {
			return;
		}
		CustomerDisplay display = new CustomerDisplay(state);
		customerDisplay = display;
		display.show(contentArea.getScene().getWindow(), () -> {
			// Schließt der Anwender das Fenster selbst, ist der Verkaufsmodus aus
			if (customerDisplay == display) {
				customerDisplay = null;
			}
			chkSalesMode.setSelected(false);
		});
	}

	private void closeCustomerDisplay() {
		CustomerDisplay display = customerDisplay;
		customerDisplay = null;
		if (display != null) {
			display.close();
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