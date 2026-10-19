package de.eltviller_carneval_verein.karten.ui.event;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.controlsfx.control.table.TableFilter;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.BookingSyncService;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.ui.DirtyIndicator;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard.Outcome;
import de.eltviller_carneval_verein.karten.ui.SaveShortcut;
import de.eltviller_carneval_verein.karten.ui.input.InputConverters;
import de.eltviller_carneval_verein.karten.ui.input.InputIssue;
import de.eltviller_carneval_verein.karten.ui.input.ParsingTextFieldTableCell;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

/**
 * Bearbeitung von Events und Vorstellungen. Tische und Sitze stehen in den Bestuhlungen der Halle (Hallen-Editor); eine
 * Vorstellung wählt Halle, Bestuhlung und Standardpreis. Der Wechsel der Bestuhlung läuft über
 * {@link PlanSwitchDialog}, der Standardpreis einer bestehenden Vorstellung über {@link Presentation#changePrice(int)}.
 */
public class EventEditController {

	private final JsonEventRepository repository = JsonEventRepository.getInstance();
	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();

	private final ObservableList<Event> masterEventData = FXCollections.observableArrayList();
	private FilteredList<Event> filteredEventData;

	private final ObservableList<Presentation> masterPresData = FXCollections.observableArrayList();
	private FilteredList<Presentation> filteredPresData;

	@FXML
	private HBox eventPerfHBox;

	@FXML
	private Button btnToggleEdit;
	@FXML
	private Button btnSave;

	// TableViews und Spalten

	// Event Tabelle
	@FXML
	private TableView<Event> eventTable;
	@FXML
	private TableColumn<Event, String> colEventName;
	@FXML
	private TableColumn<Event, String> colEventDesc;
	@FXML
	private TableColumn<Event, Boolean> colEventArchived;

	// Vorstellung Tabelle
	@FXML
	private TableView<Presentation> presTable;
	@FXML
	private TableColumn<Presentation, String> colPresName;
	@FXML
	private TableColumn<Presentation, LocalDate> colPresDate;
	@FXML
	private TableColumn<Presentation, LocalTime> colPresTime;
	@FXML
	private TableColumn<Presentation, String> colPresDesc;
	@FXML
	private TableColumn<Presentation, String> colPresHall;
	@FXML
	private TableColumn<Presentation, String> colPresPlan;

	// Halle, Bestuhlung und Standardpreis der gewählten Vorstellung
	@FXML
	private Label lblPresDetails;
	@FXML
	private GridPane presDetails;
	@FXML
	private ComboBox<Hall> cmbPresHall;
	@FXML
	private ComboBox<SeatingPlan> cmbPresPlan;
	@FXML
	private Spinner<Double> spnPresPrice;

	Event currentEvent;
	Presentation currentPres;

	private boolean editMode = false;
	// true, solange die Detailfelder aus einer Vorstellung befüllt werden: Dabei wird nichts ins Modell geschrieben
	private boolean loadingDetails = false;

	public void initData(Event event, Presentation presentation, boolean editable) {

		if (event != null) {
			this.currentEvent = event;
			eventTable.getSelectionModel().select(event);
			eventTable.scrollTo(event);

			if (presentation != null && presTable.getItems().contains(presentation)) {
				this.currentPres = presentation;
				presTable.getSelectionModel().select(presentation);
				presTable.scrollTo(presentation);
			}
		}

		setEditable(editable);
	}

	@FXML
	public void initialize() {
		setupCollums();
		setupPresentationDetails();
		setupContextMenu();
		DirtyIndicator.bind(btnSave, ChangeTracker.events());
		SaveShortcut.install(btnSave);
	}

	private void setupCollums() {
		// 1. Spalten-ValueFactorys & CellFactorys EINMALIG konfigurieren

		// --- Event Tabelle ---
		colEventName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getName()));
		colEventName.setCellFactory(TextFieldTableCell.forTableColumn());
		colEventName.setOnEditCommit(e -> e.getRowValue().setName(e.getNewValue()));

		colEventDesc.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDescription()));
		colEventDesc.setCellFactory(TextFieldTableCell.forTableColumn());
		colEventDesc.setOnEditCommit(e -> e.getRowValue().setDescription(e.getNewValue()));

		colEventArchived.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().isArchived()));
		colEventArchived.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			if (index < 0 || index >= eventTable.getItems().size()) {
				return new SimpleBooleanProperty(false);
			}
			Event event = eventTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(event.isArchived());
			prop.addListener((obs, oldVal, newVal) -> event.setArchived(newVal));
			return prop;
		}));

		// --- Vorstellung Tabelle ---
		colPresName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getName()));
		colPresName.setCellFactory(TextFieldTableCell.forTableColumn());
		colPresName.setOnEditCommit(e -> e.getRowValue().setName(e.getNewValue()));

		colPresDesc.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDescription()));
		colPresDesc.setCellFactory(TextFieldTableCell.forTableColumn());
		colPresDesc.setOnEditCommit(e -> e.getRowValue().setDescription(e.getNewValue()));

		colPresDate.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getDate()));
		colPresDate.setCellFactory(ParsingTextFieldTableCell.forTableColumn(InputConverters.date(), InputIssue.NOT_A_DATE, "Datum", EntityLabels::presentation));
		colPresDate.setOnEditCommit(e -> e.getRowValue().setDate(e.getNewValue()));

		colPresTime.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getTime()));
		colPresTime.setCellFactory(ParsingTextFieldTableCell.forTableColumn(InputConverters.time(), InputIssue.NOT_A_TIME, "Uhrzeit", EntityLabels::presentation));
		colPresTime.setOnEditCommit(e -> e.getRowValue().setTime(e.getNewValue()));

		// Halle und Bestuhlung nur zur Anzeige: Gewählt werden sie unter der Tabelle (Wechsel mit Rückfrage)
		colPresHall.setCellValueFactory(cell -> new SimpleStringProperty(EntityLabels.hallDisplayName(hallRepository.findById(cell.getValue().getHallId()))));
		colPresHall.setEditable(false);
		colPresPlan.setCellValueFactory(cell -> new SimpleStringProperty(planDisplayName(cell.getValue(), hallRepository.findById(cell.getValue().getHallId()))));
		colPresPlan.setEditable(false);

		// 2. FilteredList um Master-Daten legen
		filteredEventData = new FilteredList<>(masterEventData, p -> true);
		eventTable.setItems(filteredEventData);

		filteredPresData = new FilteredList<>(masterPresData, p -> true);
		presTable.setItems(filteredPresData);

		// 3. Spaltenkopf-Filter von ControlsFX aktivieren
		TableFilter.forTableView(eventTable).apply();
		TableFilter.forTableView(presTable).apply();

		// 4. Events in Tabelle laden
		loadEvents();

		// 5. Auswahl-Listener mit Null-Checks gegen NPEs
		eventTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			refreshPresentationTable(selectedEvent, null);
		});

		presTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedPres) -> {
			showPresentation(selectedPres);
		});

		// Initialen EditMode anwenden
		applyEditMode();

		// Tabellenhöhe automatisch ermitteln
		setupAutoHeight(eventPerfHBox, eventTable, presTable, 10);
	}

	/**
	 * Anzeige der Bestuhlung in der Tabelle: "–" ohne Bestuhlung, der Name (leer: "(ohne Name)") oder "nicht mehr
	 * vorhanden", wenn die Bestuhlung oder ihre Halle gelöscht wurde (paketsichtbar für Tests).
	 */
	static String planDisplayName(Presentation presentation, Hall hall) {
		if (presentation.getSeatingPlanId() == null) {
			return "–";
		}
		SeatingPlan plan = modelPlan(presentation, hall);
		return plan == null ? "nicht mehr vorhanden" : EntityLabels.nameOrPlaceholder(plan.getName());
	}

	/** Die Bestuhlung, auf die die Vorstellung im Modell zeigt; {@code null} ohne Halle, ohne oder mit unbekannter Id. */
	static SeatingPlan modelPlan(Presentation presentation, Hall hall) {
		return hall != null ? hall.findSeatingPlan(presentation.getSeatingPlanId()) : null;
	}

	/**
	 * Die Rückfrage vor einem Hallenwechsel, wenn Buchungen mit Daten ({@link Booking#hasData()}) ihren Platz verlieren
	 * würden; {@code null}, wenn es keine gibt (dann wird ohne Rückfrage gewechselt).
	 */
	static String hallChangeMessage(Presentation presentation) {
		long withData = presentation.getBookings() == null ? 0 : presentation.getBookings().stream().filter(Booking::hasData).count();
		if (withData == 0) {
			return null;
		}
		String start = withData == 1 ? "1 Buchung mit Daten bleibt erhalten, hat" : withData + " Buchungen mit Daten bleiben erhalten, haben";
		return start + " aber keinen Platz mehr, bis du eine Bestuhlung der neuen Halle wählst. Hallenwechsel fortsetzen?";
	}

	/**
	 * Lädt die Events: Vor dem Verfolgen der Änderungen werden die Buchungen mit den Bestuhlungen abgeglichen (das
	 * gilt nicht als Änderung, siehe {@link BookingSyncService}).
	 */
	private void loadEvents() {
		masterEventData.setAll(repository.loadEvents());
		for (Event event : masterEventData) {
			BookingSyncService.prepare(event);
		}
	}

	/** Halle, Bestuhlung und Standardpreis der gewählten Vorstellung: Auswahl der Hallen und Bestuhlungen, Spinner in Euro. */
	private void setupPresentationDetails() {
		cmbPresHall.getItems().add(null);
		cmbPresHall.getItems().addAll(hallRepository.loadHalls());
		cmbPresHall.setConverter(new StringConverter<Hall>() {
			@Override
			public String toString(Hall hall) {
				return hall == null ? "– keine Halle –" : EntityLabels.nameOrPlaceholder(hall.getName());
			}

			@Override
			public Hall fromString(String string) {
				return null; // Bei fixer Auswahl nicht erforderlich
			}
		});
		cmbPresPlan.setConverter(new StringConverter<SeatingPlan>() {
			@Override
			public String toString(SeatingPlan plan) {
				return plan == null ? "" : EntityLabels.nameOrPlaceholder(plan.getName());
			}

			@Override
			public SeatingPlan fromString(String string) {
				return null; // Bei fixer Auswahl nicht erforderlich
			}
		});
		spnPresPrice.setValueFactory(SpinnerInput.doubleFactory(0.0, 1.0));
		SpinnerInput.install(spnPresPrice, "Standardpreis", () -> currentPres == null ? null : EntityLabels.presentation(currentPres));

		cmbPresHall.valueProperty().addListener((obs, oldHall, newHall) -> {
			if (!loadingDetails && currentPres != null) {
				Presentation presentation = currentPres;
				if (hallChangeMessage(presentation) == null) {
					changeHall(presentation, newHall);
				} else {
					// Die Rückfrage erst nach der Auswahl zeigen, nicht mitten in der Benachrichtigung der ComboBox
					Platform.runLater(() -> confirmHallChange(presentation, newHall));
				}
			}
		});
		cmbPresPlan.valueProperty().addListener((obs, oldPlan, newPlan) -> {
			if (!loadingDetails && currentPres != null && newPlan != null) {
				// Die Rückfrage erst nach der Auswahl zeigen, nicht mitten in der Benachrichtigung der ComboBox
				Presentation presentation = currentPres;
				Platform.runLater(() -> changePlan(presentation, newPlan));
			}
		});
		spnPresPrice.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (!loadingDetails && currentPres != null && newVal != null) {
				// Buchungen mit Daten behalten dabei den bisherigen Preis (nie setPrice: das hielte nichts fest)
				currentPres.changePrice(MoneyFormat.toCents(newVal));
			}
		});
		showPresentation(null);
	}

	/**
	 * Hallenwechsel mit Rückfrage ({@link #hallChangeMessage}), wenn Buchungen mit Daten ihren Platz verlieren; bei
	 * "Abbrechen" kehrt die Auswahl zur Halle des Modells zurück, das Modell bleibt unverändert.
	 */
	private void confirmHallChange(Presentation presentation, Hall newHall) {
		if (presentation != currentPres || cmbPresHall.getValue() != newHall) {
			return; // die Auswahl hat sich inzwischen wieder geändert
		}
		String newHallId = newHall != null ? newHall.getId() : null;
		if (Objects.equals(newHallId, presentation.getHallId())) {
			return;
		}
		String message = hallChangeMessage(presentation);
		if (message != null && !ConfirmDialog.confirm("Halle wechseln", message, "Wechseln", "button-primary", null)) {
			loadingDetails = true;
			try {
				cmbPresHall.setValue(hallRepository.findById(presentation.getHallId()));
			} finally {
				loadingDetails = false;
			}
			return;
		}
		changeHall(presentation, newHall);
	}

	/**
	 * Hallenwechsel: Die Bestuhlung wird zurückgesetzt. Der Abgleich ohne Bestuhlung ({@link BookingSync#sync}) entfernt
	 * die leeren Buchungen, Buchungen mit Daten bleiben ohne Platz, bis eine Bestuhlung der neuen Halle gewählt ist.
	 */
	private void changeHall(Presentation presentation, Hall newHall) {
		String newHallId = newHall != null ? newHall.getId() : null;
		if (Objects.equals(newHallId, presentation.getHallId())) {
			return;
		}
		presentation.setHallId(newHallId);
		presentation.setSeatingPlanId(null);
		BookingSync.sync(presentation, null);
		loadingDetails = true;
		try {
			showPlansOf(newHall, null);
		} finally {
			loadingDetails = false;
		}
		presTable.refresh();
	}

	/**
	 * Bestuhlungswechsel mit Rückfrage; bei "Abbrechen" kehrt die Auswahl zur Bestuhlung des Modells zurück (nicht zum
	 * vorigen Wert der Auswahl, der nach schnellen Mehrfachwechseln schon ein nie bestätigter sein kann).
	 */
	private void changePlan(Presentation presentation, SeatingPlan newPlan) {
		if (presentation != currentPres || cmbPresPlan.getValue() != newPlan) {
			return; // die Auswahl hat sich inzwischen wieder geändert
		}
		if (!PlanSwitchDialog.confirm(presentation, newPlan)) {
			loadingDetails = true;
			try {
				cmbPresPlan.setValue(modelPlan(presentation, hallRepository.findById(presentation.getHallId())));
			} finally {
				loadingDetails = false;
			}
		}
		presTable.refresh();
	}

	/** Füllt die Bestuhlungsauswahl mit den Bestuhlungen der Halle; {@code select} ist die gewählte (oder {@code null}). */
	private void showPlansOf(Hall hall, SeatingPlan select) {
		if (hall == null) {
			cmbPresPlan.getItems().clear();
		} else {
			cmbPresPlan.getItems().setAll(hall.getSeatingPlans());
		}
		cmbPresPlan.setValue(select);
		if (currentPres != null && currentPres.getSeatingPlanId() != null && select == null) {
			cmbPresPlan.setPromptText("Bestuhlung nicht mehr vorhanden");
		} else {
			cmbPresPlan.setPromptText(hall == null ? "– keine Halle –" : "Keine Bestuhlung");
		}
	}

	/** Zeigt Halle, Bestuhlung und Standardpreis der Vorstellung (ohne Vorstellung: leer und gesperrt). */
	private void showPresentation(Presentation presentation) {
		currentPres = presentation;
		loadingDetails = true;
		try {
			if (presentation == null) {
				cmbPresHall.setValue(null);
				showPlansOf(null, null);
				spnPresPrice.getValueFactory().setValue(0.0);
				lblPresDetails.setText("Vorstellung");
			} else {
				Hall hall = hallRepository.findById(presentation.getHallId());
				cmbPresHall.setValue(hall);
				showPlansOf(hall, modelPlan(presentation, hall));
				spnPresPrice.getValueFactory().setValue(presentation.getPrice() / 100.0);
				lblPresDetails.setText(EntityLabels.presentation(presentation));
			}
		} finally {
			loadingDetails = false;
		}
		applyEditMode();
	}

	private void setupContextMenu() {
		// 1. Kontextmenü für Event-Tabelle
		setupTableContextMenu(eventTable, event -> handleAddEvent(), event -> handleCopyEvent(event), event -> handleDeleteEvent(event));

		// 2. Kontextmenü für Aufführungen/Vorstellungen
		setupTableContextMenu(presTable, pres -> handleAddPres(), pres -> handleCopyPres(pres, null), pres -> handleDeletePres(pres));
	}

	private <T> void setupTableContextMenu(TableView<T> tableView, Consumer<T> onAdd, Consumer<T> onCopy, Consumer<T> onDelete) {
		tableView.setRowFactory(tv -> {
			TableRow<T> tableRow = new TableRow<>();
			ContextMenu contextMenu = new ContextMenu();
			SeparatorMenuItem separator = new SeparatorMenuItem();

			MenuItem addItem = new MenuItem("+ Hinzufügen");
			addItem.setOnAction(e -> {
				T item = tableRow.getItem();
				if (item != null && onAdd != null) {
					onAdd.accept(item);
				}
			});

			MenuItem copyItem = new MenuItem("+ als Vorlage Verwenden");
			copyItem.setOnAction(e -> {
				T item = tableRow.getItem();
				if (item != null && onCopy != null) {
					onCopy.accept(item);
				}
			});

			MenuItem deleteItem = new MenuItem("Löschen");
			deleteItem.setOnAction(e -> {
				T item = tableRow.getItem();
				if (item != null && onDelete != null) {
					onDelete.accept(item);
				}
			});

			contextMenu.getItems().addAll(addItem, copyItem, separator, deleteItem);

			contextMenu.setOnShowing(e -> {
				Object data = tableRow.getItem();
				// Im Anzeigemodus bleiben die Einträge sichtbar, sind aber nicht ausführbar
				contextMenu.getItems().forEach(item -> item.setDisable(!editMode));
				addItem.setVisible(true);
				copyItem.setVisible(data instanceof Event || data instanceof Presentation);
				separator.setVisible(data instanceof Event || data instanceof Presentation);
				deleteItem.setVisible(data instanceof Event || data instanceof Presentation);
			});

			// Menü nur binden, wenn die Zeile nicht leer ist
			tableRow.contextMenuProperty().bind(Bindings.when(tableRow.emptyProperty()).then((ContextMenu) contextMenu).otherwise(contextMenu));

			return tableRow;
		});
	}

	private void handleAddEvent() {
		refreshEventTable(currentEvent);

	}

	private void handleCopyEvent(Event event) {
		Event newEvent = new Event();

		// Die Kopie bekommt einen eigenen Namen ("<Name> (Kopie)"), sonst würde sie nie gespeichert (EVT-002); umbenennen geht in der Tabelle
		newEvent.setName(Event.createCopyName(event.getName(), masterEventData.stream().map(Event::getName).toList()));
		newEvent.setDescription(event.getDescription());

		for (Presentation pres : event.getPresentations()) {
			handleCopyPres(pres, newEvent);
		}

		// Bei Erfolg ist die Kopie gewählt; sonst bleibt das Original gewählt (handleCopyPres hat die Kopie schon angezeigt)
		boolean saved = EventSaver.save(newEvent);
		refreshEventTable(saved ? newEvent : event);
	}

	private void handleDeleteEvent(Event event) {
		if (!ConfirmDialog.confirmDelete("Event löschen", "Soll das Event \"" + event.getName() + "\" wirklich unwiderruflich gelöscht werden?")) {
			return;
		}

		repository.deleteEvent(event);

		if (event.equals(currentEvent)) {
			currentEvent = null;
		}
		refreshEventTable(currentEvent);
	}

	private void handleAddPres() {
		Presentation newPres = eventTable.getSelectionModel().getSelectedItem().addPresentation();
		refreshPresentationTable(currentEvent, newPres);
	}

	/**
	 * Kopiert die Vorstellung als Vorlage: Name, Beschreibung, Datum, Zeit, Halle, Bestuhlung und Standardpreis. Die
	 * Buchungen werden nicht kopiert, die Kopie bekommt je Sitz der Bestuhlung eine leere Buchung.
	 */
	private void handleCopyPres(Presentation presentation, Event parentEvent) {
		Event event;
		Presentation newPres;
		if (parentEvent != null) {
			event = parentEvent;
		} else {
			event = presentation.getParent();
		}

		newPres = event.addPresentation(presentation.getName());

		newPres.setDescription(presentation.getDescription());
		newPres.setDate(presentation.getDate());
		newPres.setTime(presentation.getTime());
		newPres.setHallId(presentation.getHallId());
		newPres.setSeatingPlanId(presentation.getSeatingPlanId());
		newPres.setPrice(presentation.getPrice());
		BookingSync.sync(newPres, modelPlan(newPres, hallRepository.findById(newPres.getHallId())));

		refreshPresentationTable(event, newPres);

	}

	private void handleDeletePres(Presentation presentation) {
		if (!ConfirmDialog.confirmDelete("Vorstellung löschen", "Soll die Vorstellung \"" + presentation.getName() + "\" wirklich unwiderruflich gelöscht werden?")) {
			return;
		}

		presentation.getParent().deletePresentation(presentation);
		if (presentation.equals(currentPres)) {
			currentPres = null;
		}
		refreshPresentationTable(currentEvent, currentPres);
	}

	private void refreshEventTable(Event selectEvent) {
		currentEvent = selectEvent;
		loadEvents();
		if (currentEvent != null && masterEventData.contains(selectEvent)) {
			eventTable.getSelectionModel().select(selectEvent);
		} else {
			refreshPresentationTable(selectEvent, null);
		}
	}

	private void refreshPresentationTable(Event selectedEvent, Presentation selectPresentation) {
		currentEvent = selectedEvent;
		masterPresData.clear();
		if (currentEvent != null) {
			masterPresData.setAll(currentEvent.getPresentations());
		}
		setupAutoHeight(eventPerfHBox, eventTable, presTable, 10);
		if (selectPresentation != null && masterPresData.contains(selectPresentation)) {
			presTable.getSelectionModel().select(selectPresentation);
		} else {
			showPresentation(null);
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
				reloadRestoredData();
				return;
			}
		}
		// Editmode wechseln
		editMode = !editMode;
		applyEditMode();
	}

	/**
	 * Nach "Nicht speichern" sind Event und Vorstellung neue Objekte (der gespeicherte Stand wurde wiederhergestellt):
	 * Der Screen öffnet sich mit ihnen neu, im Anzeigemodus.
	 */
	private void reloadRestoredData() {
		String eventId = currentEvent == null ? null : currentEvent.getId();
		String presentationId = currentPres == null ? null : currentPres.getId();
		Event event = JsonEventRepository.getInstance().loadEvents().stream().filter(candidate -> candidate.getId().equals(eventId)).findFirst().orElse(null);
		Presentation presentation = event == null ? null
				: event.getPresentations().stream().filter(candidate -> candidate.getId().equals(presentationId)).findFirst().orElse(null);
		MainApp.showEventEditView(event, presentation, false);
	}

	private void setEditable(boolean editable) {
		editMode = editable;
		applyEditMode();
	}

	private void applyEditMode() {
		eventTable.setEditable(editMode);
		presTable.setEditable(editMode);

		if (btnSave != null) {
			btnSave.setDisable(!editMode);
		}
		if (btnToggleEdit != null) {
			btnToggleEdit.setText(editMode ? "Anzeigen" : "Bearbeiten");
		}

		// Halle, Bestuhlung und Standardpreis: im Anzeigemodus und ohne gewählte Vorstellung nicht änderbar
		presDetails.setDisable(!editMode || currentPres == null);
	}

	private void setupAutoHeight(HBox hbox, TableView<?> leftTable, TableView<?> rightTable, int maxRows) {
		double rowHeight = 25.0; // Höhe einer einzelnen Zeile in Pixel
		double headerHeight = 28.0; // Höhe des Spaltenkopfs
		double minHeight = 103;
		double maxHeight = headerHeight + (rowHeight * maxRows);
		double prefHeight;

		hbox.setMinHeight(minHeight);
		hbox.setMaxHeight(maxHeight);

		leftTable.setFixedCellSize(rowHeight);
		rightTable.setFixedCellSize(rowHeight);

		prefHeight = leftTable.getItems().size() > rightTable.getItems().size() ? (leftTable.getItems().size() * rowHeight) + headerHeight : (rightTable.getItems().size() * rowHeight) + headerHeight;

		hbox.setPrefHeight(prefHeight);
	}

	@FXML
	private void handleBackToEventOverview() {
		boolean wasEditable = editMode;
		setEditable(false);
		MainApp.showEventOverviewView();
		// Hat der Nutzer im Dialog "Abbrechen" gewählt, bleibt dieser Screen stehen: Bearbeitungsmodus zurückgeben
		if (btnSave.getScene() != null && btnSave.getScene().getWindow() != null) {
			setEditable(wasEditable);
		}
	}

	@FXML
	private void saveCurrentState() {
		// Eine abgelehnte Spinner-Eingabe nicht mit "Gespeichert" überschreiben (der Klick auf Speichern löst die Ablehnung aus)
		if (SpinnerInput.blockSaveIfScreenInputRejected(spnPresPrice)) {
			return;
		}
		// Alle geänderten Events speichern, nicht nur das gewählte: "Speichern *" zeigt Änderungen an allen Events
		// dieses Screens an und müsste sonst nach dem Speichern stehen bleiben
		ChangeTracker.events().checkAll();
		List<Event> toSave = new ArrayList<>(ChangeTracker.events().dirtyEntities());
		Event event = eventTable.getSelectionModel().getSelectedItem();
		if (event != null && !toSave.contains(event)) {
			toSave.add(0, event);
		}
		for (Event changed : toSave) {
			if (!EventSaver.save(changed)) {
				break; // die Fehler stehen in der Fußzeile
			}
		}
	}

}
