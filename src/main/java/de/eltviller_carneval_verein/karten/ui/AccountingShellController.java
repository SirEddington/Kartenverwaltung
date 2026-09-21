package de.eltviller_carneval_verein.karten.ui;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * Eigenständiger Bereich "Abrechnung", losgelöst vom Kartenverkauf: eigene
 * Event-/Vorstellungsauswahl, da die Nutzung zeitlich und fachlich getrennt
 * vom Verkauf am Stand stattfindet (siehe Roadmap-Diskussion zur
 * Navigationsstruktur). Zwei Unteransichten über RadioButtons umschaltbar:
 * Kassenabgleich (pro Event/Vorstellung) und Statistik (über alle Events).
 * Der Export-Knopf sitzt auf Shell-Ebene und delegiert an die jeweils aktive
 * Ansicht, sofern diese {@link Exportable} implementiert.
 */
public class AccountingShellController {

	/** Verfügbare Export-Formate, samt Anzeigename und Dateiendung. */
	private enum ExportFormat {
		CSV("Als CSV exportieren", "csv"), PDF("Als PDF exportieren", "pdf");

		private final String menuLabel;
		private final String extension;

		ExportFormat(String menuLabel, String extension) {
			this.menuLabel = menuLabel;
			this.extension = extension;
		}
	}

	private static final String PREF_KEY_LAST_EXPORT_FORMAT = "lastExportFormat";
	private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final JsonTicketRepository repository = JsonTicketRepository.getInstance();
	private final Preferences prefs = Preferences.userNodeForPackage(AccountingShellController.class);

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
	private SplitMenuButton btnExport;
	@FXML
	private StackPane contentArea;

	private ContentController activeContentController;
	private Event currentEvent;
	private Presentation currentPresentation;
	private boolean editMode = false;
	private ExportFormat primaryExportFormat;

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
			updateActionAvailability();
		});

		// Ohne konkrete Vorstellung gibt es nichts Vorstellungs-Spezifisches zu
		// speichern (der Ist-Kasseninhalt hängt an genau einer Vorstellung) -
		// der Bearbeiten-Modus ist dann gesperrt.
		presComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedPresentation) -> {
			currentPresentation = selectedPresentation;
			if (activeContentController != null) {
				activeContentController.setPresentation(currentPresentation);
			}
			updateActionAvailability();
		});

		viewToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
			if (newToggle == btnViewStats) {
				loadStatisticsView();
			} else {
				loadCashReconciliationView();
			}
		});

		setupExportButton();
		loadCashReconciliationView();
	}

	private void setupExportButton() {
		for (ExportFormat format : ExportFormat.values()) {
			MenuItem item = new MenuItem(format.menuLabel);
			item.setOnAction(event -> {
				setPrimaryExportFormat(format);
				runExport(format);
			});
			btnExport.getItems().add(item);
		}

		ExportFormat lastUsed;
		try {
			lastUsed = ExportFormat.valueOf(prefs.get(PREF_KEY_LAST_EXPORT_FORMAT, ExportFormat.CSV.name()));
		} catch (IllegalArgumentException e) {
			lastUsed = ExportFormat.CSV;
		}
		setPrimaryExportFormat(lastUsed);

		btnExport.setOnAction(event -> runExport(primaryExportFormat));
	}

	private void setPrimaryExportFormat(ExportFormat format) {
		primaryExportFormat = format;
		btnExport.setText(format.menuLabel);
		prefs.put(PREF_KEY_LAST_EXPORT_FORMAT, format.name());
	}

	private void runExport(ExportFormat format) {
		if (!(activeContentController instanceof Exportable exportable) || !exportable.canExport()) {
			return;
		}

		FileChooser fileChooser = new FileChooser();
		String fileName = Exportable.sanitizeFileName(exportable.suggestedFileBaseName() + "_" + LocalDate.now().format(FILE_DATE_FORMAT)) + "." + format.extension;
		fileChooser.setInitialFileName(fileName);
		fileChooser.getExtensionFilters().add(new ExtensionFilter(format.name() + "-Datei", "*." + format.extension));

		Window ownerWindow = btnExport.getScene() != null ? btnExport.getScene().getWindow() : null;
		File targetFile = fileChooser.showSaveDialog(ownerWindow);
		if (targetFile == null) {
			return;
		}

		try {
			switch (format) {
			case CSV -> exportable.exportCsv(targetFile);
			case PDF -> exportable.exportPdf(targetFile);
			}
		} catch (IOException | RuntimeException e) {
			MainApp.showAlert("Fehler", "Export fehlgeschlagen: " + e.getMessage(), AlertType.ERROR);
		}
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

	/**
	 * Statistik ist rein lesend (aggregiert über alle Events), Bearbeiten
	 * ergibt dort keinen Sinn. Export hängt von der jeweiligen Ansicht ab
	 * ({@link Exportable#canExport()}).
	 */
	private void updateActionAvailability() {
		boolean statisticsActive = viewToggleGroup.getSelectedToggle() == btnViewStats;
		boolean shouldDisableEdit = statisticsActive || currentPresentation == null;
		btnToggleEdit.setDisable(shouldDisableEdit);

		if (shouldDisableEdit && editMode) {
			editMode = false;
			btnToggleEdit.setText("Bearbeiten");
			if (activeContentController != null) {
				activeContentController.setEditMode(false);
			}
		}

		btnExport.setDisable(!(activeContentController instanceof Exportable exportable) || !exportable.canExport());
	}

	private void loadCashReconciliationView() {
		loadContent("/de/eltviller_carneval_verein/karten/ui/CashReconciliationView.fxml");
		updateActionAvailability();
	}

	private void loadStatisticsView() {
		loadContent("/de/eltviller_carneval_verein/karten/ui/StatisticsView.fxml");
		if (activeContentController instanceof StatisticsController stats) {
			stats.refresh(chkIncludeArchived.isSelected());
		}
		updateActionAvailability();
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
