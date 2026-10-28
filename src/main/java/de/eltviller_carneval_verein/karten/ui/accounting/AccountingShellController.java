package de.eltviller_carneval_verein.karten.ui.accounting;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.ForeignBatch;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.BookingSyncService;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import de.eltviller_carneval_verein.karten.ui.DirtyIndicator;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard.Outcome;
import de.eltviller_carneval_verein.karten.ui.LiveViews;
import de.eltviller_carneval_verein.karten.ui.SaveShortcut;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.Severity;
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

	private static final Logger LOG = Logger.getLogger(AccountingShellController.class.getName());

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

	private final StoreEventRepository repository = StoreEventRepository.getInstance();
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
	private Button btnSave;
	@FXML
	private SplitMenuButton btnExport;
	@FXML
	private StackPane contentArea;

	private ContentController activeContentController;
	private Event currentEvent;
	private Presentation currentPresentation;
	private boolean editMode = false;
	private ExportFormat primaryExportFormat;

	// Wird gerade eine Liste nach fremden Änderungen nachgeführt, soll die Auswahl nicht an den Inhalt gemeldet werden
	private boolean updatingEvents = false;
	private boolean updatingPresentations = false;
	// Fremde Änderungen an Events: Die Listen und die Auswahl folgen dem Cache
	private final RepositoryListener<Event> foreignChanges = new RepositoryListener<>() {
		@Override
		public void changedByOthers(Event event, ForeignBatch<Event> batch) {
			syncWithRepository();
		}

		@Override
		public void addedByOthers(Event event) {
			syncWithRepository();
		}

		@Override
		public void removedByOthers(Event event) {
			syncWithRepository();
		}
	};

	@FXML
	public void initialize() {
		DirtyIndicator.bind(btnSave, ChangeTracker.events());
		SaveShortcut.install(btnSave);
		btnSave.setDisable(!editMode); // Im Anzeigemodus gibt es nichts zu speichern

		// "Alle" ist als echter (null-)Eintrag Teil der Liste, damit er wie eine
		// normale Auswahl wirkt, statt ein Sonderfall über einen extra Button zu sein.
		presComboBox.setConverter(new StringConverter<Presentation>() {
			@Override
			public String toString(Presentation presentation) {
				return presentation == null ? "Alle (Gesamtbilanz)" : EntityLabels.nameOrPlaceholder(presentation.getName());
			}

			@Override
			public Presentation fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		});

		chkIncludeArchived.selectedProperty().addListener((obs, oldVal, newVal) -> {
			reloadEventItems();
			if (activeContentController instanceof StatisticsController stats) {
				stats.refresh(newVal);
			}
		});

		eventComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			if (!updatingEvents) {
				applyEventSelection(selectedEvent);
			}
		});

		// Ohne konkrete Vorstellung gibt es nichts Vorstellungs-Spezifisches zu
		// speichern (der Ist-Kasseninhalt hängt an genau einer Vorstellung) -
		// der Bearbeiten-Modus ist dann gesperrt.
		presComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedPresentation) -> {
			if (updatingPresentations) {
				return;
			}
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

		// Fremde Änderungen an Events nur, solange die Abrechnung zu sehen ist (danach hielte das Repository sie am Leben)
		LiveViews.whileShown(contentArea, () -> repository.addListener(foreignChanges), () -> repository.removeListener(foreignChanges));

		// Muss NACH den obigen Listenern laufen: setzt bei genau einem Event
		// automatisch dessen Auswahl, was den eventComboBox-Listener auslöst -
		// wäre er noch nicht registriert, bliebe currentEvent fälschlich null.
		reloadEventItems();

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
			LOG.log(Level.SEVERE, "Export fehlgeschlagen: " + targetFile, e);
			StatusMessage.getInstance().show(Severity.ERROR, "Export fehlgeschlagen: " + e.getMessage());
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

	/** Das gewählte Event gilt für die Shell und den Inhalt; die Vorstellung steht danach auf "Alle (Gesamtbilanz)". */
	private void applyEventSelection(Event selectedEvent) {
		currentEvent = selectedEvent;
		if (currentEvent != null) {
			// Buchungen mit den Bestuhlungen abgleichen, bevor das Event angezeigt wird (gilt nicht als Änderung)
			BookingSyncService.prepare(currentEvent);
		}

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
	}

	/**
	 * Führt Event- und Vorstellungsliste nach Änderungen anderer Rechner nach (neues, gelöschtes, archiviertes oder
	 * umbenanntes Event, geänderte Vorstellungen). Die Auswahl bleibt über die ID: Ist das gewählte Event noch dasselbe
	 * Objekt, wird nichts neu belegt (die Inhalte führen sich selbst nach); ist es ein neues Objekt (neu zusammengesetzt),
	 * wird die Auswahl damit neu belegt wie nach "Nicht speichern"; ist es weg, ist die Auswahl leer und die Fußzeile sagt warum.
	 */
	private void syncWithRepository() {
		Event shown = currentEvent;
		Presentation shownPresentation = currentPresentation;
		List<Event> all = repository.loadEvents();
		List<Event> events = all.stream().filter(event -> chkIncludeArchived.isSelected() || !event.isArchived()).toList();
		Event current = shown == null ? null : events.stream().filter(event -> event.equals(shown)).findFirst().orElse(null);

		// Die Auswahl wird unten gezielt nachgeführt; erst abmelden und neu wählen lässt die Box den (ggf. neuen) Namen zeigen
		updatingEvents = true;
		try {
			eventComboBox.getItems().setAll(events);
			eventComboBox.getSelectionModel().clearSelection();
			if (current != null) {
				eventComboBox.getSelectionModel().select(current);
			}
		} finally {
			updatingEvents = false;
		}

		if (shown == null) {
			// Wie beim Öffnen: Gibt es nur dieses eine Event, ist es gewählt
			if (events.size() == 1) {
				eventComboBox.getSelectionModel().select(events.get(0));
			}
			return;
		}
		if (current == null) {
			boolean archived = all.stream().anyMatch(event -> event.equals(shown));
			applyEventSelection(null);
			// Eine ComboBox behält ihren Wert auch ohne Eintrag in der Liste und zeigte sonst weiter das gelöschte Event
			updatingEvents = true;
			updatingPresentations = true;
			try {
				eventComboBox.setValue(null);
				presComboBox.setValue(null);
			} finally {
				updatingEvents = false;
				updatingPresentations = false;
			}
			StatusMessage.getInstance().show(Severity.WARNING,
					"„" + EntityLabels.nameOrPlaceholder(shown.getName()) + "“ wurde auf einem anderen Rechner " + (archived ? "archiviert." : "gelöscht."));
		} else if (current != shown) {
			applyEventSelection(current);
			String presentationId = shownPresentation == null ? null : shownPresentation.getId();
			presComboBox.getItems().stream().filter(presentation -> presentation != null && presentation.getId().equals(presentationId)).findFirst()
					.ifPresent(presentation -> presComboBox.getSelectionModel().select(presentation));
		} else {
			syncPresentations(current, shownPresentation);
		}
	}

	/** Das gewählte Event ist dasselbe Objekt: Vorstellungen können dazugekommen, weg oder umbenannt sein. */
	private void syncPresentations(Event event, Presentation shown) {
		List<Presentation> items = new ArrayList<>();
		items.add(null); // "Alle (Gesamtbilanz)"
		items.addAll(event.getPresentations());
		Presentation kept = shown == null ? null
				: items.stream().filter(presentation -> presentation != null && presentation.equals(shown)).findFirst().orElse(null);
		updatingPresentations = true;
		try {
			presComboBox.getItems().setAll(items);
			presComboBox.getSelectionModel().clearSelection();
			presComboBox.getSelectionModel().select(items.indexOf(kept));
		} finally {
			updatingPresentations = false;
		}
		// Weg oder ein neues Objekt mit derselben ID: der Inhalt zeigt sonst eine Vorstellung, die nicht mehr im Event steht
		if (kept != shown) {
			currentPresentation = kept;
			if (activeContentController != null) {
				activeContentController.setPresentation(kept);
			}
			updateActionAvailability();
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
		btnSave.setDisable(!editMode);

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
			LOG.log(Level.SEVERE, "Ansicht konnte nicht geladen werden: " + fxmlPath, e);
			MainApp.showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
		}
	}

	@FXML
	private void toggleEditMode() {
		// Keine ungespeicherten Änderungen im Anzeigemodus: erst nachfragen
		if (editMode) {
			Outcome outcome = LeaveGuard.confirmSwitchToViewMode();
			if (outcome == Outcome.STAY) {
				return;
			}
			if (outcome == Outcome.LEAVE_RESTORED) {
				rebindToRestoredData();
			}
		}
		editMode = !editMode;
		btnSave.setDisable(!editMode);
		btnToggleEdit.setText(editMode ? "Anzeigen" : "Bearbeiten");
		if (activeContentController != null) {
			activeContentController.setEditMode(editMode);
		}
	}

	/**
	 * Nach "Nicht speichern" sind Event und Vorstellungen neue Objekte (der gespeicherte Stand wurde wiederhergestellt):
	 * Die Auswahl wird mit ihnen neu belegt, die Inhalte laden sich daraus neu.
	 */
	private void rebindToRestoredData() {
		String eventId = currentEvent == null ? null : currentEvent.getId();
		String presentationId = currentPresentation == null ? null : currentPresentation.getId();

		reloadEventItems();
		eventComboBox.getItems().stream().filter(event -> event.getId().equals(eventId)).findFirst().ifPresent(event -> {
			eventComboBox.getSelectionModel().select(event);
			presComboBox.getItems().stream().filter(presentation -> presentation != null && presentation.getId().equals(presentationId)).findFirst()
					.ifPresent(presentation -> presComboBox.getSelectionModel().select(presentation));
		});
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
