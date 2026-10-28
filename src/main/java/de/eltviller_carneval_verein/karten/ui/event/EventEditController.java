package de.eltviller_carneval_verein.karten.ui.event;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.controlsfx.control.table.TableFilter;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SaleDates;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.ForeignBatch;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.BookingSyncService;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.ui.DirtyIndicator;
import de.eltviller_carneval_verein.karten.ui.EditingUnits;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.GermanDecimalStringConverter;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard.Outcome;
import de.eltviller_carneval_verein.karten.ui.LiveViews;
import de.eltviller_carneval_verein.karten.ui.SaveShortcut;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.input.InputConverters;
import de.eltviller_carneval_verein.karten.ui.input.InputIssue;
import de.eltviller_carneval_verein.karten.ui.input.ParsingTextFieldTableCell;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import de.eltviller_carneval_verein.karten.ui.event.PresentationTables.Item;
import de.eltviller_carneval_verein.karten.ui.input.TableInput;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
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
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

/**
 * Bearbeitung von Events und Vorstellungen. Tische und Sitze stehen in den Bestuhlungen der Halle (Hallen-Editor); eine
 * Vorstellung wählt Halle, Bestuhlung und Standardpreis. Der Wechsel der Bestuhlung läuft über
 * {@link PlanSwitchDialog}, der Standardpreis einer bestehenden Vorstellung über {@link Presentation#changePrice(int)}.
 */
public class EventEditController {

	private final StoreEventRepository repository = StoreEventRepository.getInstance();
	private final StoreHallRepository hallRepository = StoreHallRepository.getInstance();

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

	// Tisch Tabelle: die Tische der gewählten Vorstellung, aus ihren Buchungen gruppiert
	@FXML
	private HBox tableSeatHBox;
	@FXML
	private TableView<Item> tableTable;
	@FXML
	private TableColumn<Item, String> colTableName;
	@FXML
	private TableColumn<Item, Integer> colTableSeats;
	@FXML
	private TableColumn<Item, Integer> colTableOccupied;

	// Sitz Tabelle: die Buchungen des gewählten Tisches
	@FXML
	private TableView<Booking> seatTable;
	@FXML
	private TableColumn<Booking, Integer> colSeat;
	@FXML
	private TableColumn<Booking, String> colSeatCategory;
	@FXML
	private TableColumn<Booking, Boolean> colSeatWheelchair;
	@FXML
	private TableColumn<Booking, String> colSeatLastName;
	@FXML
	private TableColumn<Booking, String> colSeatFirstName;
	@FXML
	private TableColumn<Booking, LocalDate> colSeatReservedOn;
	@FXML
	private TableColumn<Booking, Double> colSeatPrice;
	@FXML
	private TableColumn<Booking, PaymentStatus> colSeatPayment;
	@FXML
	private TableColumn<Booking, LocalDate> colSeatPaidOn;
	@FXML
	private TableColumn<Booking, Boolean> colSeatCollected;
	@FXML
	private TableColumn<Booking, LocalDate> colSeatCollectedOn;
	@FXML
	private TableColumn<Booking, String> colSeatComment;

	private final ObservableList<Item> masterTableData = FXCollections.observableArrayList();
	private final ObservableList<Booking> masterSeatData = FXCollections.observableArrayList();
	// die Vorstellung, deren Tische die Tabelle Tisch zeigt (der gewählte Tisch bleibt nur innerhalb derselben Vorstellung)
	private Presentation tablesOf;

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

	// Eine fremde Änderung kam, während eine Eingabe offen war; die Ansicht führt sich nach deren Ende nach
	private boolean foreignChangePending = false;
	// Fremde Änderungen an Events: Die Tabellen führen sich nach; ist das gezeigte Event gelöscht, geht es zurück zur Übersicht
	private final RepositoryListener<Event> foreignChanges = new RepositoryListener<>() {
		@Override
		public void changedByOthers(Event event, ForeignBatch<Event> batch) {
			showForeignChanges();
		}

		@Override
		public void addedByOthers(Event event) {
			showForeignChanges();
		}

		@Override
		public void removedByOthers(Event event) {
			if (event.equals(currentEvent)) {
				leaveDeletedEvent(event);
			} else {
				showForeignChanges();
			}
		}
	};

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
		setupBookingTables();
		setupCollums();
		TableInput.install(eventTable);
		TableInput.install(presTable);
		TableInput.install(seatTable);
		setupPresentationDetails();
		setupContextMenu();
		DirtyIndicator.bind(btnSave, ChangeTracker.events());
		SaveShortcut.install(btnSave);
		setupForeignChanges();
	}

	/**
	 * Fremde Änderungen: Eine Zelle im Bearbeiten hält ihr Objekt für fremde Stände zurück ({@link EditingUnits#watch}); endet
	 * die offene Eingabe, kommt Verpasstes nach. Angemeldet ist die Ansicht nur, solange sie zu sehen ist; eine mit ihr
	 * verschwundene Zelle gibt dabei ihr Objekt frei.
	 */
	private void setupForeignChanges() {
		EditingUnits editing = EditingUnits.getInstance();
		editing.watch(eventTable, event -> event);
		editing.watch(presTable, presentation -> presentation);
		editing.watch(seatTable, booking -> booking);
		for (TableView<?> table : List.of(eventTable, presTable, seatTable)) {
			table.editingCellProperty().addListener((obs, before, cell) -> {
				if (cell == null) {
					showPendingForeignChanges();
				}
			});
		}
		spnPresPrice.getEditor().focusedProperty().addListener((obs, wasFocused, focused) -> {
			if (!focused) {
				showPendingForeignChanges();
			}
		});
		LiveViews.whileShown(eventPerfHBox, () -> repository.addListener(foreignChanges), () -> {
			repository.removeListener(foreignChanges);
			editing.end(eventTable);
			editing.end(presTable);
			editing.end(seatTable);
		});
	}

	/** Ob eine Eingabe offen ist, die ein Neuzeichnen der Tabellen und Felder wegrisse. */
	private boolean inputOpen() {
		return eventTable.getEditingCell() != null || presTable.getEditingCell() != null || seatTable.getEditingCell() != null
				|| spnPresPrice.getEditor().isFocused();
	}

	/** Holt eine zurückgestellte Auffrischung nach, nachdem die laufende Bestätigung der Eingabe zu Ende ist. */
	private void showPendingForeignChanges() {
		if (foreignChangePending) {
			Platform.runLater(this::showForeignChanges);
		}
	}

	/**
	 * Führt Event-Tabelle, Vorstellungstabelle, Tische, Sitze und die Felder der gezeigten Vorstellung nach Änderungen
	 * anderer Rechner nach. Event und Vorstellung bleiben über die ID gewählt, auch wenn ein anderer Rechner das Objekt
	 * ersetzt hat (ein veraltetes Objekt bliebe sonst gewählt, und sein Speichern würde abgelehnt); der Bearbeitungsmodus
	 * bleibt. Nie während einer offenen Eingabe (das Neuzeichnen risse sie weg): Dann kommt es nach deren Ende nach.
	 */
	private void showForeignChanges() {
		if (inputOpen()) {
			foreignChangePending = true;
			return;
		}
		foreignChangePending = false;
		String eventId = currentEvent == null ? null : currentEvent.getId();
		String presentationId = currentPres == null ? null : currentPres.getId();
		Item shownTable = tableTable.getSelectionModel().getSelectedItem();
		String tableLabel = shownTable == null ? null : shownTable.label();
		List<Booking> selectedSeats = new ArrayList<>(seatTable.getSelectionModel().getSelectedItems());

		loadEvents();
		// Erst abwählen: Ein gleiches (ID), aber veraltetes Objekt bliebe sonst die Auswahl der Tabelle
		eventTable.getSelectionModel().clearSelection();
		Event event = masterEventData.stream().filter(candidate -> candidate.getId().equals(eventId)).findFirst().orElse(null);
		if (event == null) {
			refreshPresentationTable(null, null);
			return;
		}
		eventTable.getSelectionModel().select(event);
		Presentation presentation = event.getPresentations().stream().filter(candidate -> candidate.getId().equals(presentationId)).findFirst().orElse(null);
		refreshPresentationTable(event, presentation);

		masterTableData.stream().filter(item -> item.label().equals(tableLabel)).findFirst().ifPresent(item -> tableTable.getSelectionModel().select(item));
		selectedSeats.stream().filter(masterSeatData::contains).forEach(booking -> seatTable.getSelectionModel().select(booking));
	}

	/**
	 * Das gezeigte Event hat ein anderer Rechner gelöscht: zurück zur Übersicht (ohne Rückfrage zu diesem Event, der Tracker
	 * hat es schon vergessen), die Fußzeile sagt warum. Bleibt die Ansicht stehen (Abbruch der Rückfrage zu anderen
	 * ungespeicherten Events), verschwindet das Event aus den Tabellen.
	 */
	private void leaveDeletedEvent(Event removed) {
		MainApp.showEventOverviewView();
		if (btnSave.getScene() != null && btnSave.getScene().getWindow() != null) {
			showForeignChanges();
		}
		// Die Übersicht hat keine Fußzeile, die Meldung öffnet dort einen Dialog: erst nach dem Ende der Benachrichtigung der Listener
		String text = "„" + EntityLabels.nameOrPlaceholder(removed.getName()) + "“ wurde auf einem anderen Rechner gelöscht.";
		Platform.runLater(() -> StatusMessage.getInstance().show(Severity.WARNING, text));
	}

	private void setupCollums() {
		// 1. Spalten-ValueFactorys & CellFactorys EINMALIG konfigurieren

		// --- Event Tabelle ---
		colEventName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getName()));
		colEventName.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		colEventName.setOnEditCommit(e -> e.getRowValue().setName(e.getNewValue()));

		colEventDesc.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDescription()));
		colEventDesc.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
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
		colPresName.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		colPresName.setOnEditCommit(e -> e.getRowValue().setName(e.getNewValue()));

		colPresDesc.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDescription()));
		colPresDesc.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
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
	 * Die Tabellen Tisch und Sitz: Die Tische der gewählten Vorstellung stammen aus ihren Buchungen
	 * ({@link PresentationTables}), die Sitze eines Tisches sind seine Buchungen. Bearbeitet werden die Buchungsdaten;
	 * Sitz, Kategorie und Rollstuhlplatz gehören zur Bestuhlung (Hallen-Editor) und bleiben lesbar.
	 */
	private void setupBookingTables() {
		// --- Tisch Tabelle ---
		colTableName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().label()));
		colTableSeats.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().seats()).asObject());
		colTableOccupied.setCellValueFactory(cell -> new SimpleIntegerProperty((int) cell.getValue().occupied()).asObject());
		tableTable.setItems(masterTableData);
		TableFilter.forTableView(tableTable).apply();
		tableTable.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, item) -> masterSeatData.setAll(item == null ? List.of() : item.bookings()));

		// --- Sitz Tabelle ---
		colSeat.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getSeatNumber()).asObject());
		colSeat.setCellFactory(column -> new TableCell<>() {
			@Override
			protected void updateItem(Integer seat, boolean empty) {
				super.updateItem(seat, empty);
				Booking booking = getTableRow() == null ? null : getTableRow().getItem();
				setText(empty || seat == null || booking == null ? null : PresentationTables.seatText(booking));
			}
		});
		colSeatCategory.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getCategory()));
		colSeatWheelchair.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().isWheelchairAccessible()));
		colSeatWheelchair.setCellFactory(CheckBoxTableCell.forTableColumn(colSeatWheelchair));
		colSeat.setEditable(false);
		colSeatCategory.setEditable(false);
		colSeatWheelchair.setEditable(false);

		colSeatLastName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getLastName()));
		colSeatLastName.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		// Name, Zahlung und "Abgeholt" über SaleDates: Wird eine Aktion neu gesetzt, trägt es das Datum ein
		colSeatLastName.setOnEditCommit(e -> {
			SaleDates.change(e.getRowValue(), booking -> booking.setLastName(e.getNewValue()));
			bookingChanged();
		});

		colSeatFirstName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getFirstName()));
		colSeatFirstName.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		colSeatFirstName.setOnEditCommit(e -> {
			e.getRowValue().setFirstName(e.getNewValue());
			bookingChanged();
		});

		// Preis: der effektive Preis; der Standardpreis der Vorstellung als Eingabe entfernt den Einzelpreis
		colSeatPrice.setCellValueFactory(cell -> new SimpleDoubleProperty(cell.getValue().getEffectivePrice() / 100.0).asObject());
		colSeatPrice.setCellFactory(ParsingTextFieldTableCell.forTableColumn(new GermanDecimalStringConverter(), InputIssue.NOT_A_NUMBER, "Preis", EntityLabels::booking));
		colSeatPrice.setOnEditCommit(e -> {
			e.getRowValue().setEffectivePrice(e.getNewValue() == null ? 0 : MoneyFormat.toCents(e.getNewValue()));
			bookingChanged();
		});

		colSeatPayment.setCellValueFactory(cell -> cell.getValue().getPaymentStatusProperty());
		colSeatPayment.setCellFactory(ComboBoxTableCell.forTableColumn(new StringConverter<PaymentStatus>() {
			@Override
			public String toString(PaymentStatus status) {
				return status == null ? "" : status.getDisplayName();
			}

			@Override
			public PaymentStatus fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		}, PaymentStatus.values()));
		colSeatPayment.setOnEditCommit(e -> {
			SaleDates.change(e.getRowValue(), booking -> booking.setPaymentStatus(e.getNewValue()));
			bookingChanged();
		});

		colSeatCollected.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().isCollected()));
		colSeatCollected.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			if (index < 0 || index >= seatTable.getItems().size()) {
				return new SimpleBooleanProperty(false);
			}
			Booking booking = seatTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(booking.isCollected());
			prop.addListener((obs, oldVal, newVal) -> {
				SaleDates.change(booking, b -> b.setCollected(newVal));
				bookingChanged();
			});
			return prop;
		}));

		// Die Daten der Aktionen: Datumsspalten mit strengem Format; leere Eingabe löscht das Datum, ein unlesbarer Text in
		// einem leeren Feld trägt heute ein (INP-002 meldet es trotzdem)
		colSeatReservedOn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getReservationDate()));
		colSeatReservedOn.setCellFactory(ParsingTextFieldTableCell.forDateColumn("Reservierungsdatum", EntityLabels::booking, LocalDate::now));
		colSeatReservedOn.setOnEditCommit(e -> e.getRowValue().setReservationDate(e.getNewValue()));
		colSeatPaidOn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getPaymentDate()));
		colSeatPaidOn.setCellFactory(ParsingTextFieldTableCell.forDateColumn("Bezahldatum", EntityLabels::booking, LocalDate::now));
		colSeatPaidOn.setOnEditCommit(e -> e.getRowValue().setPaymentDate(e.getNewValue()));
		colSeatCollectedOn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getCollectionDate()));
		colSeatCollectedOn.setCellFactory(ParsingTextFieldTableCell.forDateColumn("Abholdatum", EntityLabels::booking, LocalDate::now));
		colSeatCollectedOn.setOnEditCommit(e -> e.getRowValue().setCollectionDate(e.getNewValue()));

		colSeatComment.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getComment()));
		colSeatComment.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		colSeatComment.setOnEditCommit(e -> {
			e.getRowValue().setComment(e.getNewValue());
			bookingChanged();
		});

		seatTable.setItems(masterSeatData);
		TableFilter.forTableView(seatTable).apply();
		seatTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		// Buchungen ohne Platz kursiv: Sie haben keinen Sitz in der Bestuhlung
		seatTable.setRowFactory(table -> new TableRow<>() {
			@Override
			protected void updateItem(Booking item, boolean empty) {
				super.updateItem(item, empty);
				setStyle(!empty && item != null && item.isUnassigned() ? "-fx-font-style: italic;" : "");
			}
		});
	}

	/**
	 * Eine Buchung wurde bearbeitet: Die Zahl der belegten Sitze im Tisch ändert sich mit, und eine neu gesetzte Aktion
	 * hat ein Datum in einer anderen Spalte der Sitz-Tabelle gesetzt (erst nach dem laufenden Commit neu zeichnen:
	 * Ein Neuzeichnen mittendrin lässt die Zahlungs-ComboBox im Bearbeiten hängen).
	 */
	private void bookingChanged() {
		tableTable.refresh();
		Platform.runLater(seatTable::refresh);
	}

	/**
	 * Zeigt die Tische der Vorstellung (ohne Vorstellung: leer). Der gewählte Tisch bleibt gewählt, solange es ihn in
	 * derselben Vorstellung noch gibt, sonst ist der erste gewählt.
	 */
	private void showBookingTables(Presentation presentation) {
		Item previous = tableTable.getSelectionModel().getSelectedItem();
		String keep = previous != null && presentation == tablesOf ? previous.label() : null;
		tablesOf = presentation;
		List<Item> items = presentation == null ? List.of() : PresentationTables.items(presentation);
		masterTableData.setAll(items);
		Item select = items.stream().filter(item -> item.label().equals(keep)).findFirst().orElse(items.isEmpty() ? null : items.get(0));
		if (select == null) {
			masterSeatData.clear();
		} else {
			tableTable.getSelectionModel().select(select);
		}
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
		List<Event> events = new ArrayList<>(repository.loadEvents());
		// Neue, noch nicht gespeicherte Events (Hinzufügen, Als Vorlage) stehen noch nicht im Repository, gehören aber hierher
		for (Event unsaved : ChangeTracker.events().dirtyEntities()) {
			if (ChangeTracker.events().isNew(unsaved) && !events.contains(unsaved)) {
				events.add(unsaved);
			}
		}
		masterEventData.setAll(events);
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
		showBookingTables(presentation);
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
		showBookingTables(presentation);
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
		showBookingTables(presentation);
		applyEditMode();
	}

	private void setupContextMenu() {
		// 1. Kontextmenü für Event-Tabelle
		setupTableContextMenu(eventTable, "+ Event hinzufügen", this::handleAddEvent, () -> true, this::handleCopyEvent, this::handleDeleteEvent);

		// 2. Kontextmenü für Aufführungen/Vorstellungen: Eine Vorstellung braucht ein gewähltes Event
		setupTableContextMenu(presTable, "+ Vorstellung hinzufügen", this::handleAddPres, () -> eventTable.getSelectionModel().getSelectedItem() != null, this::handleCopyPres,
				this::handleDeletePres);
	}

	/**
	 * Das Kontextmenü einer Tabelle. "Hinzufügen" gilt für die Tabelle, nicht für die Zeile darunter: Es funktioniert
	 * auch bei einem Rechtsklick in leere Zeilen oder in die leere Fläche (dort das Menü der Tabelle), und fehlt, wenn
	 * es nichts hinzuzufügen gibt ({@code canAdd}, z. B. keine Vorstellung ohne gewähltes Event). "Als Vorlage" und
	 * "Löschen" gelten für die Zeile.
	 */
	private <T> void setupTableContextMenu(TableView<T> tableView, String addText, Runnable onAdd, BooleanSupplier canAdd, Consumer<T> onCopy, Consumer<T> onDelete) {
		// Rechtsklick dorthin, wo gar keine Zeile ist (leere Tabelle): nur "Hinzufügen"
		MenuItem tableAddItem = new MenuItem(addText);
		tableAddItem.setOnAction(e -> onAdd.run());
		ContextMenu tableMenu = new ContextMenu(tableAddItem);
		tableMenu.setOnShowing(e -> {
			tableAddItem.setDisable(!editMode);
			tableAddItem.setVisible(canAdd.getAsBoolean());
			hideIfNothingVisible(tableMenu);
		});
		tableView.setContextMenu(tableMenu);

		tableView.setRowFactory(tv -> {
			TableRow<T> tableRow = new TableRow<>();
			ContextMenu contextMenu = new ContextMenu();
			SeparatorMenuItem separator = new SeparatorMenuItem();

			MenuItem addItem = new MenuItem(addText);
			addItem.setOnAction(e -> onAdd.run());

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
				boolean entity = data instanceof Event || data instanceof Presentation;
				addItem.setVisible(canAdd.getAsBoolean());
				copyItem.setVisible(entity);
				separator.setVisible(entity && addItem.isVisible());
				deleteItem.setVisible(entity);
				hideIfNothingVisible(contextMenu);
			});

			// Auch leere Zeilen (die Tabelle füllt die Fläche damit auf) bekommen das Menü: "Hinzufügen" gilt dort ebenso
			tableRow.setContextMenu(contextMenu);

			return tableRow;
		});
	}

	/** Ein Menü ohne sichtbaren Eintrag erscheint nicht als leere Fläche. */
	private static void hideIfNothingVisible(ContextMenu menu) {
		if (menu.getItems().stream().noneMatch(MenuItem::isVisible)) {
			Platform.runLater(menu::hide);
		}
	}

	/** Ein neues Event, noch nicht gespeichert ("Speichern *"); es ist in der Tabelle gewählt. */
	private void handleAddEvent() {
		Event created = EventTemplates.newEvent(masterEventData.stream().map(Event::getName).toList());
		ChangeTracker.events().trackNew(created);
		refreshEventTable(created);
	}

	/** Das Event samt Vorstellungen als Vorlage für ein neues Event, noch nicht gespeichert; es ist in der Tabelle gewählt. */
	private void handleCopyEvent(Event event) {
		Event copy = EventTemplates.copyEvent(event, masterEventData.stream().map(Event::getName).toList(), hallRepository::findById);
		ChangeTracker.events().trackNew(copy);
		refreshEventTable(copy);
	}

	private void handleDeleteEvent(Event event) {
		boolean neverSaved = ChangeTracker.events().isNew(event);
		if (!neverSaved && !ConfirmDialog.confirmDelete("Event löschen", "Soll das Event \"" + event.getName() + "\" wirklich unwiderruflich gelöscht werden?")) {
			return;
		}

		if (neverSaved) {
			// Nie gespeichert: Es gibt nichts zu löschen, es verschwindet nur wieder aus der Tabelle
			ChangeTracker.events().untrack(event);
		} else {
			repository.deleteEvent(event);
		}

		if (event.equals(currentEvent)) {
			currentEvent = null;
		}
		refreshEventTable(currentEvent);
	}

	/** Eine neue Vorstellung im gewählten Event, noch nicht gespeichert; sie ist in der Tabelle gewählt. */
	private void handleAddPres() {
		Event event = eventTable.getSelectionModel().getSelectedItem();
		if (event == null) {
			return;
		}
		Presentation newPres = event.addPresentation();
		ChangeTracker.events().check(event);
		refreshPresentationTable(event, newPres);
	}

	/** Kopiert die Vorstellung als Vorlage in dasselbe Event ({@link EventTemplates#copyPresentation}), noch nicht gespeichert. */
	private void handleCopyPres(Presentation presentation) {
		Event event = presentation.getParent();
		Presentation copy = EventTemplates.copyPresentation(presentation, event, hallRepository::findById);
		ChangeTracker.events().check(event);
		refreshPresentationTable(event, copy);
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
		Event event = StoreEventRepository.getInstance().loadEvents().stream().filter(candidate -> candidate.getId().equals(eventId)).findFirst().orElse(null);
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
		seatTable.setEditable(editMode);

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
