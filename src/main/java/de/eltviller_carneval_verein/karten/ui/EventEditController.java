package de.eltviller_carneval_verein.karten.ui;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import org.controlsfx.control.table.TableFilter;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
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
	private CheckBox checkPaid;
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
		spnDoublePrice.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 100.0, 0.0, 0.5));

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
			masterPresData.clear();
			masterTableData.clear();
			masterSeatData.clear();
			clearDetails();
			if (selectedEvent != null && selectedEvent.getPresentations() != null) {
				masterPresData.setAll(selectedEvent.getPresentations());
			}
			setupAutoHeight(eventPerfHBox, eventTable, presTable, 5);
			setupAutoHeight(tableSeatHBox, tableTable, seatTable, 15);
		});

		presTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedPres) -> {
			masterTableData.clear();
			masterSeatData.clear();
			clearDetails();
			if (selectedPres != null && selectedPres.getTables() != null) {
				masterTableData.setAll(selectedPres.getTables());
			}
			setupAutoHeight(eventPerfHBox, eventTable, presTable, 5);
			setupAutoHeight(tableSeatHBox, tableTable, seatTable, 15);
		});

		tableTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedTable) -> {
			masterSeatData.clear();
			clearDetails();
			if (selectedTable != null && selectedTable.getSeats() != null) {
				masterSeatData.setAll(selectedTable.getSeats());
			}
			setupAutoHeight(eventPerfHBox, eventTable, presTable, 5);
			setupAutoHeight(tableSeatHBox, tableTable, seatTable, 15);
		});

		seatTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedSeat) -> {
			if (selectedSeat != null) {
				loadDetailsOfSeat(selectedSeat);
			} else {
				clearDetails();
			}
		});

		// Initialen EditMode anwenden
		applyEditMode();

		// Tabellenhöhe automatisch ermitteln
		setupAutoHeight(eventPerfHBox, eventTable, presTable, 5);
		setupAutoHeight(tableSeatHBox, tableTable, seatTable, 15);

	}

	private void loadDetailsOfSeat(Seat selectedSeat) {
		// Felder initialisieren
		clearDetails();

		// Felder befüllen
		spnDoublePrice.getValueFactory().setValue(selectedSeat.getPriceDouble());
		checkPaid.setSelected(selectedSeat.isPaid());
		checkCollected.setSelected(selectedSeat.isCollected());
		checkWheelchairAccessible.setSelected(selectedSeat.isWheelchairAccessible());
		txtLastName.setText(selectedSeat.getLastName());
		txtFirstName.setText(selectedSeat.getFirstName());
		txtMail.setText(selectedSeat.getEMail());
		txtComment.setText(selectedSeat.getComment());
	}

	private void clearDetails() {
		spnDoublePrice.getValueFactory().setValue(0.0);
		checkPaid.setSelected(false);
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
		checkPaid.setDisable(!editMode);
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
			selectedSeat.setPaid(checkPaid.isSelected());
			selectedSeat.setCollected(checkCollected.isSelected());
			selectedSeat.setWheelchairAccessible(checkWheelchairAccessible.isSelected());
		}
		// repository.saveEvent(eventComboBox.getValue());
	}

}
