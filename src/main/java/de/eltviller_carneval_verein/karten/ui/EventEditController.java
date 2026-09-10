package de.eltviller_carneval_verein.karten.ui;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

import org.controlsfx.control.table.TableFilter;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import javafx.util.converter.IntegerStringConverter;
import javafx.util.converter.LocalDateStringConverter;
import javafx.util.converter.LocalTimeStringConverter;

public class EventEditController {

	private final JsonTicketRepository repository = new JsonTicketRepository();

	private final ObservableList<Event> masterEventData = FXCollections.observableArrayList();
	private FilteredList<Event> filteredEventData;

	private final ObservableList<Presentation> masterPresData = FXCollections.observableArrayList();
	private FilteredList<Presentation> filteredPresData;

	private final ObservableList<Table> masterTableData = FXCollections.observableArrayList();
	private FilteredList<Table> filteredTableData;

	private final ObservableList<Seat> masterSeatData = FXCollections.observableArrayList();
	private FilteredList<Seat> filteredSeatData;

	@FXML
	private HBox eventPerfHBox;
	@FXML
	private HBox tableSeatHBox;

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

	// Tisch Tabelle
	@FXML
	private TableView<Table> tableTable;
	@FXML
	private TableColumn<Table, Integer> colTableNumber;
	@FXML
	private TableColumn<Table, String> colTableDesc;
	@FXML
	private TableColumn<Table, String> colTableCategory;

	// Sitz Tabelle
	@FXML
	private TableView<Seat> seatTable;
	@FXML
	private TableColumn<Seat, Integer> colSeatNumber;
	@FXML
	private TableColumn<Seat, String> colSeatComment;

	@FXML
	private Spinner<Double> spnDoublePrice;
	@FXML
	private ComboBox<PaymentStatus> paymentComboBox;
	@FXML
	private CheckBox checkCollected;
	@FXML
	private CheckBox checkWheelchairAccessible;
	@FXML
	private TextField txtLastName;
	@FXML
	private TextField txtFirstName;
	@FXML
	private TextField txtMail;
	@FXML
	private TextField txtComment;

	DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

	Event currentEvent;
	Presentation currentPres;
	Table currentTable;
	Seat currentSeat;

	private boolean editMode = false;

	public void initData(Event event, Presentation presentation, Table table, Seat seat, boolean editable) {

		if (event != null) {
			eventTable.getSelectionModel().select(event);
			eventTable.scrollTo(event);
			this.currentEvent = event;

			if (presentation != null && presTable.getItems().contains(presentation)) {
				presTable.getSelectionModel().select(presentation);
				presTable.scrollTo(presentation);
				this.currentPres = presentation;

				if (table != null && tableTable.getItems().contains(table)) {
					tableTable.getSelectionModel().select(table);
					tableTable.scrollTo(table);
					this.currentTable = table;

					if (seat != null && seatTable.getItems().contains(seat)) {
						seatTable.getSelectionModel().select(seat);
						seatTable.scrollTo(seat);
						this.currentSeat = seat;
					}
				}
			}
		}

		setEditable(editable);
	}

	@FXML
	public void initialize() {
		setupCollums();
		setupContextMenu();
	}

	private void setupCollums() {
		// 1. Spalten-ValueFactorys & CellFactorys EINMALIG konfigurieren

		// --- Event Tabelle ---
		colEventName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getName()));
		colEventName.setCellFactory(TextFieldTableCell.forTableColumn());
		colEventName.setOnEditCommit(e -> e.getRowValue().changeName(e.getNewValue()));

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
		colPresName.setOnEditCommit(e -> e.getRowValue().changeName(e.getNewValue()));

		colPresDesc.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDescription()));
		colPresDesc.setCellFactory(TextFieldTableCell.forTableColumn());
		colPresDesc.setOnEditCommit(e -> e.getRowValue().setDescription(e.getNewValue()));

		colPresDate.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getDate()));
		colPresDate.setCellFactory(TextFieldTableCell.forTableColumn(new LocalDateStringConverter(dateFormatter, null)));
		colPresDate.setOnEditCommit(e -> e.getRowValue().setDate(e.getNewValue()));

		colPresTime.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getTime()));
		colPresTime.setCellFactory(TextFieldTableCell.forTableColumn(new LocalTimeStringConverter(timeFormatter, null)));
		colPresTime.setOnEditCommit(e -> e.getRowValue().setTime(e.getNewValue()));

		// --- Tisch Tabelle ---
		colTableNumber.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getTableNumber()).asObject());
		colTableNumber.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
		colTableNumber.setOnEditCommit(e -> e.getRowValue().changeTableNumber(e.getNewValue()));

		colTableCategory.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getCategory()));
		colTableCategory.setCellFactory(TextFieldTableCell.forTableColumn());
		colTableCategory.setOnEditCommit(e -> e.getRowValue().setCategory(e.getNewValue()));

		colTableDesc.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDesc()));
		colTableDesc.setCellFactory(TextFieldTableCell.forTableColumn());
		colTableDesc.setOnEditCommit(e -> e.getRowValue().setDesc(e.getNewValue()));

		// --- Sitz Tabelle ---
		colSeatNumber.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getSeatNumber()).asObject());
		colSeatNumber.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
		colSeatNumber.setOnEditCommit(e -> e.getRowValue().changeSeatNumber(e.getNewValue()));

		colSeatComment.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getComment()));
		colSeatComment.setCellFactory(TextFieldTableCell.forTableColumn());
		colSeatComment.setOnEditCommit(e -> e.getRowValue().setComment(e.getNewValue()));

		// --- Sitzdetails ---
		paymentComboBox.getItems().setAll(PaymentStatus.values());
		spnDoublePrice.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 100.0, 0.0, 0.5));
		StringConverter<PaymentStatus> converter = new StringConverter<>() {
			@Override
			public String toString(PaymentStatus status) {
				return status == null ? "" : status.getDisplayName();
			}

			@Override
			public PaymentStatus fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		};
		paymentComboBox.setConverter(converter);

		// 2. FilteredList um Master-Daten legen
		filteredEventData = new FilteredList<>(masterEventData, p -> true);
		eventTable.setItems(filteredEventData);

		filteredPresData = new FilteredList<>(masterPresData, p -> true);
		presTable.setItems(filteredPresData);

		filteredTableData = new FilteredList<>(masterTableData, p -> true);
		tableTable.setItems(filteredTableData);

		filteredSeatData = new FilteredList<>(masterSeatData, p -> true);
		seatTable.setItems(filteredSeatData);

		// 3. Spaltenkopf-Filter von ControlsFX aktivieren
		TableFilter.forTableView(eventTable).apply();
		TableFilter.forTableView(presTable).apply();
		TableFilter.forTableView(tableTable).apply();
		TableFilter.forTableView(seatTable).apply();

		// 4. Events in Tabelle laden
		masterEventData.clear();
		masterEventData.setAll(repository.loadEvents());

		// 5. Auswahl-Listener mit Null-Checks gegen NPEs
		eventTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			refreshPresentationTable(selectedEvent, null);
		});

		presTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedPres) -> {
			refreshTableTable(selectedPres, null);
		});

		tableTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedTable) -> {
			refreshSeatTable(selectedTable, null);
		});

		seatTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedSeat) -> {
			refreshSeatDetails(selectedSeat);
		});

		// Initialen EditMode anwenden
		applyEditMode();

		// Tabellenhöhe automatisch ermitteln
		setupAutoHeight(eventPerfHBox, eventTable, presTable, 5);
		setupAutoHeight(tableSeatHBox, tableTable, seatTable, 15);

	}

	private void setupContextMenu() {
		// 1. Kontextmenü für Event-Tabelle
		setupTableContextMenu(eventTable, event -> handleAddEvent(), event -> handleCopyEvent(event), event -> handleDeleteEvent(event));

		// 2. Kontextmenü für Aufführungen/Vorstellungen
		setupTableContextMenu(presTable, pres -> handleAddPres(), pres -> handleCopyPres(pres, null), pres -> handleDeletePres(pres));

		// 3. Kontextmenü für Tisch-Tabelle
		setupTableContextMenu(tableTable, table -> handleAddTable(), table -> handleCopyTable(table, null), table -> handleDeleteTable(table));

		// 4. Kontextmenü für Sitzplatz-Tabelle
		setupTableContextMenu(seatTable, seat -> handleAddSeat(), seat -> handleCopySeat(seat, null), seat -> handleDeleteSeat(seat));
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
				addItem.setVisible(true);
				copyItem.setVisible(data instanceof Event || data instanceof Presentation || data instanceof Table || data instanceof Seat);
				separator.setVisible(data instanceof Event || data instanceof Presentation || data instanceof Table || data instanceof Seat);
				deleteItem.setVisible(data instanceof Event || data instanceof Presentation || data instanceof Table || data instanceof Seat);
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

		// ToDo Popup für name
		newEvent.setDescription(event.getDescription());

		for (Presentation pres : event.getPresentations()) {
			handleCopyPres(pres, newEvent);
		}

		repository.saveEvent(newEvent);
	}

	private void handleDeleteEvent(Event event) {
		Alert confirmation = new Alert(AlertType.CONFIRMATION);
		confirmation.setTitle("Event löschen");
		confirmation.setHeaderText(null);
		confirmation.setContentText("Soll das Event \"" + event.getName() + "\" wirklich unwiderruflich gelöscht werden?");

		if (confirmation.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
			return;
		}

		repository.deleteEvent(event);
		
		if (currentEvent.equals(event)) {
			currentEvent = null;
		}
		refreshEventTable(currentEvent);
	}

	private void handleAddPres() {
		Presentation newPres = eventTable.getSelectionModel().getSelectedItem().addPresentation();
		refreshPresentationTable(currentEvent, newPres);
	}

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
		newPres.setDefaultSeatHeight(presentation.getDefaultSeatHeight());
		newPres.setDefaultSeatWidth(presentation.getDefaultSeatWidth());
		newPres.setDefaultTableHeight(presentation.getDefaultTableHeight());
		newPres.setDefaultTableWidth(presentation.getDefaultTableWidth());
		newPres.setHallHeight(presentation.getHallHeight());
		newPres.setHallWidth(presentation.getHallWidth());
		newPres.setHallObjects(presentation.getHallObjects());
		newPres.setTableRows(presentation.getTableRows());

		for (Table table : presentation.getTables()) {
			handleCopyTable(table, newPres);
		}

		refreshPresentationTable(event, newPres);

	}

	private void handleDeletePres(Presentation presentation) {
		Alert confirmation = new Alert(AlertType.CONFIRMATION);
		confirmation.setTitle("Event löschen");
		confirmation.setHeaderText(null);
		confirmation.setContentText("Soll die Vorstellung \"" + presentation.getName() + "\" wirklich unwiderruflich gelöscht werden?");

		if (confirmation.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
			return;
		}

		presentation.getParent().deletePresentation(presentation);
		if (currentPres.equals(presentation)) {
			currentPres = null;
		}
		refreshPresentationTable(currentEvent, currentPres);
	}

	private void handleAddTable() {
		Table newTable = presTable.getSelectionModel().getSelectedItem().addTable();
		refreshTableTable(currentPres, newTable);
	}

	private void handleCopyTable(Table table, Presentation parentPresentation) {
		Table newTable;
		Presentation parentPres;
		if (parentPresentation != null) {
			parentPres = parentPresentation;
		} else {
			parentPres = table.getParent();
		}

		newTable = parentPres.addTable(table.getTableNumber());

		newTable.setCategory(table.getCategory());
		newTable.setDesc(table.getDesc());
		newTable.setWidth(table.getWidth());
		newTable.setHeight(table.getHeight());

		for (Seat seat : table.getSeats()) {
			handleCopySeat(seat, newTable);
		}

		refreshTableTable(parentPres, newTable);

	}

	private void handleDeleteTable(Table table) {
		Alert confirmation = new Alert(AlertType.CONFIRMATION);
		confirmation.setTitle("Event löschen");
		confirmation.setHeaderText(null);
		confirmation.setContentText("Soll Tisch " + table.getTableNumber() + " wirklich unwiderruflich gelöscht werden?");

		if (confirmation.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
			return;
		}

		table.getParent().deleteTable(table);
		if (currentTable.equals(table)) {
			currentTable = null;
		}
		refreshTableTable(currentPres, currentTable);
	}

	private void handleAddSeat() {
		Seat newSeat = tableTable.getSelectionModel().getSelectedItem().addSeat();
		refreshSeatTable(currentTable, newSeat);
	}

	private void handleCopySeat(Seat seat, Table parentTable) {
		Table table;
		Seat newSeat;

		if (parentTable != null) {
			table = parentTable;
		} else {
			table = seat.getParent();
		}

		newSeat = table.addSeat(seat.getSeatNumber());

		newSeat.setPrice(seat.getPrice());
		newSeat.setWheelchairAccessible(seat.isWheelchairAccessible());
		newSeat.setHeight(seat.getHeight());
		newSeat.setWidth(seat.getWidth());

		refreshSeatTable(table, newSeat);

	}

	private void handleDeleteSeat(Seat seat) {
		Alert confirmation = new Alert(AlertType.CONFIRMATION);
		confirmation.setTitle("Event löschen");
		confirmation.setHeaderText(null);
		confirmation.setContentText("Soll Sitz " + seat.getSeatNumber() + " wirklich unwiderruflich gelöscht werden?");

		if (confirmation.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
			return;
		}

		seat.getParent().deleteSeat(seat);
		if (currentSeat.equals(seat)) {
			currentSeat = null;
		}
		refreshSeatTable(currentTable, currentSeat);
	}

	private void loadDetailsOfSeat(Seat selectedSeat) {
		// Felder initialisieren
		clearDetails();

		// Felder befüllen
		spnDoublePrice.getValueFactory().setValue(selectedSeat.getPriceDouble());
		paymentComboBox.getSelectionModel().select(selectedSeat.getPaymentStatus());
		checkCollected.setSelected(selectedSeat.isCollected());
		checkWheelchairAccessible.setSelected(selectedSeat.isWheelchairAccessible());
		txtLastName.setText(selectedSeat.getLastName());
		txtFirstName.setText(selectedSeat.getFirstName());
		txtMail.setText(selectedSeat.getEMail());
		txtComment.setText(selectedSeat.getComment());
	}

	private void refreshEventTable(Event selectEvent) {
		currentEvent = selectEvent;
		masterEventData.setAll(repository.loadEvents());
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
		if (selectPresentation != null && masterPresData.contains(selectPresentation)) {
			presTable.getSelectionModel().select(selectPresentation);
		} else {
			refreshTableTable(selectPresentation, null);
		}
	}

	private void refreshTableTable(Presentation selectedPresentation, Table selectTable) {
		currentPres = selectedPresentation;
		masterTableData.clear();
		if (currentPres != null) {
			masterTableData.setAll(currentPres.getTables());
		}
		if (selectTable != null && masterTableData.contains(selectTable)) {
			tableTable.getSelectionModel().select(selectTable);
		} else {
			refreshSeatTable(selectTable, null);
		}
	}

	private void refreshSeatTable(Table selectedTable, Seat selectSeat) {
		currentTable = selectedTable;
		masterSeatData.clear();
		if (currentTable != null) {
			masterSeatData.setAll(currentTable.getSeats());
		}
		setupAutoHeight(eventPerfHBox, eventTable, presTable, 5);
		setupAutoHeight(tableSeatHBox, tableTable, seatTable, 15);
		if (selectSeat != null && masterSeatData.contains(selectSeat)) {
			seatTable.getSelectionModel().select(selectSeat);
		} else {
			refreshSeatDetails(selectSeat);
		}
	}

	private void refreshSeatDetails(Seat selectedSeat) {
		currentSeat = selectedSeat;
		if (currentSeat != null) {
			loadDetailsOfSeat(currentSeat);
		} else {
			clearDetails();
		}
	}

	private void clearDetails() {
		spnDoublePrice.getValueFactory().setValue(0.0);
		paymentComboBox.getSelectionModel().clearSelection();
		checkCollected.setSelected(false);
		checkWheelchairAccessible.setSelected(false);
		txtLastName.clear();
		txtFirstName.clear();
		txtMail.clear();
		txtComment.clear();
	}

	@FXML
	private void toggleEditMode() {
		// Editmode wechseln
		editMode = !editMode;
		applyEditMode();
	}

	private void setEditable(boolean editable) {
		editMode = editable;
		applyEditMode();
	}

	private void applyEditMode() {
		eventTable.setEditable(editMode);
		presTable.setEditable(editMode);
		tableTable.setEditable(editMode);
		seatTable.setEditable(editMode);

		if (btnSave != null) {
			btnSave.setDisable(!editMode);
		}
		if (btnToggleEdit != null) {
			btnToggleEdit.setText(editMode ? "Anzeigen" : "Bearbeiten");
		}

		spnDoublePrice.setEditable(editMode);
		paymentComboBox.setEditable(editMode);
		checkCollected.setDisable(!editMode);
		checkWheelchairAccessible.setDisable(!editMode);
		txtLastName.setEditable(editMode);
		txtFirstName.setEditable(editMode);
		txtMail.setEditable(editMode);
		txtComment.setEditable(editMode);
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
		setEditable(false);
		MainApp.showEventOverviewView();
	}

	@FXML
	private void saveCurrentState() {
		// Aktuellen Stand speichern
		Seat selectedSeat = seatTable.getSelectionModel().getSelectedItem();
		if (selectedSeat != null) {
			selectedSeat.setFirstName(txtFirstName.getText());
			selectedSeat.setLastName(txtLastName.getText());
			selectedSeat.setEMail(txtMail.getText());
			selectedSeat.setComment(txtComment.getText());
			selectedSeat.setPriceDouble(spnDoublePrice.getValue());
			selectedSeat.setPaymentStatus(paymentComboBox.getSelectionModel().getSelectedItem());
			selectedSeat.setCollected(checkCollected.isSelected());
			selectedSeat.setWheelchairAccessible(checkWheelchairAccessible.isSelected());
		}
		repository.saveEvent(eventTable.getSelectionModel().getSelectedItem());
	}

}
