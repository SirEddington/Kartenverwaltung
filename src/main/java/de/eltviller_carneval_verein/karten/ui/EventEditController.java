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
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
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
	private Button btnToggleEdit = new Button();
	@FXML
	private Button btnSave = new Button();

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

	private boolean editMode = true;

	@FXML
	public void initialize() {
		// 1. Spalten-ValueFactorys konfigurieren
		// Eventtabelle
		colEventName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getName()));
		colEventDesc.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDescription()));
		colEventArchived.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().isArchived()));
		colEventArchived.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			Event event = eventTable.getItems().get(index);
			SimpleBooleanProperty archived = new SimpleBooleanProperty(event.isArchived());

			// Wenn die CheckBox geklickt wird, den neuen Wert im Event speichern
			archived.addListener((obs, oldVal, newVal) -> {
				event.setArchived(newVal);
			});

			return archived;
		}));

		// Verstellungstabelle
		colPresName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getName()));
		colPresDesc.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDescription()));
		colPresDate.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getDate()));
		colPresDate.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(LocalDate date, boolean empty) {
				super.updateItem(date, empty);
				if (empty || date == null) {
					setText(null);
				} else {
					setText(dateFormatter.format(date));
				}
			}
		});
		colPresTime.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getTime()));
		colPresTime.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(LocalTime time, boolean empty) {
				super.updateItem(time, empty);
				if (empty || time == null) {
					setText(null);
				} else {
					setText(timeFormatter.format(time) + " Uhr");
				}
			}
		});

		// Tischtabelle
		colTableNumber.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getTableNumber()).asObject());
		colTableCategory.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getCategory()));
		colTableDesc.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDesc()));

		// Sitztabelle
		colSeatNumber.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getSeatNumber()).asObject());
		colSeatComment.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getComment()));

		// 2. FilteredList um die Master-Daten legen & an Tabelle binden
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

		// 5. Auswahl: Erst hier werden Daten geladen
		eventTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			masterPresData.clear();
			masterPresData.setAll(selectedEvent.getPresentations());
		});
		presTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedPres) -> {
			masterTableData.clear();
			masterTableData.setAll(selectedPres.getTables());
		});
		tableTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedTable) -> {
			masterSeatData.clear();
			masterSeatData.setAll(selectedTable.getSeats());
		});
		seatTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedSeat) -> {
			loadDetailsOfSeat(selectedSeat);
		});

		// 7. Anzeigemodus starten
		toggleEditMode();
	}

	private void loadDetailsOfSeat(Seat selectedSeat) {
		// Felder initialisieren
		spnDoublePrice.getValueFactory().setValue(0.0);
		checkPaid.setSelected(false);
		checkCollected.setSelected(false);
		checkWheelchairAccessible.setSelected(false);
		txtLastName.clear();
		txtFirstName.clear();
		txtMail.clear();
		txtComment.clear();

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

	@FXML
	private void toggleEditMode() {
		// Editmode wechseln
		editMode = !editMode;
		eventTable.setEditable(editMode);
		presTable.setEditable(editMode);
		tableTable.setEditable(editMode);
		seatTable.setEditable(editMode);
		btnSave.setDisable(!editMode);
		btnToggleEdit.setText(editMode ? "Anzeigen" : "Bearbeiten");

		// Eventtabelle
		colEventName.setCellFactory(TextFieldTableCell.forTableColumn());
		colEventName.setOnEditCommit(editEvent -> {
			Event event = editEvent.getRowValue();
			event.changeName(editEvent.getNewValue());
		});
		colEventDesc.setCellFactory(TextFieldTableCell.forTableColumn());
		colEventDesc.setOnEditCommit(editEvent -> {
			Event event = editEvent.getRowValue();
			event.setDescription(editEvent.getNewValue());
		});
		colEventArchived.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			Event event = eventTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(event.isArchived());
			prop.addListener((obs, oldVal, newVal) -> {
				event.setArchived(newVal);
			});
			return prop;
		}));
		colEventArchived.setOnEditCommit(editEvent -> {
			Event event = editEvent.getRowValue();
			event.setArchived(editEvent.getNewValue());
		});

		// Vorstellunstabelle
		colPresName.setCellFactory(TextFieldTableCell.forTableColumn());
		colPresName.setOnEditCommit(editEvent -> {
			Presentation pres = editEvent.getRowValue();
			pres.changeName(editEvent.getNewValue());
		});
		colPresDesc.setCellFactory(TextFieldTableCell.forTableColumn());
		colPresDesc.setOnEditCommit(editEvent -> {
			Presentation pres = editEvent.getRowValue();
			pres.setDescription(editEvent.getNewValue());
		});
		colPresDate.setCellFactory(TextFieldTableCell.forTableColumn(new LocalDateStringConverter()));
		colPresDate.setOnEditCommit(editEvent -> {
			Presentation pres = editEvent.getRowValue();
			pres.setDate(editEvent.getNewValue());
		}); // ToDo umwandlung/integration eines DatePickers
		colPresTime.setCellFactory(TextFieldTableCell.forTableColumn(new LocalTimeStringConverter()));
		colPresTime.setOnEditCommit(editEvent -> {
			Presentation pres = editEvent.getRowValue();
			pres.setTime(editEvent.getNewValue());
		});

		// Tischtabelle
		colTableNumber.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
		colTableNumber.setOnEditCommit(editEvent -> {
			Table table = editEvent.getRowValue();
			table.changeTableNumber(editEvent.getNewValue());
		});
		colTableCategory.setCellFactory(TextFieldTableCell.forTableColumn());
		colTableCategory.setOnEditCommit(editEvent -> {
			Table table = editEvent.getRowValue();
			table.setCategory(editEvent.getNewValue());
		});
		colTableDesc.setCellFactory(TextFieldTableCell.forTableColumn());
		colTableDesc.setOnEditCommit(editEvent -> {
			Table table = editEvent.getRowValue();
			table.setDesc(editEvent.getNewValue());
		});

		// Sitztabelle
		colSeatNumber.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
		colSeatNumber.setOnEditCommit(editEvent -> {
			Seat seat = editEvent.getRowValue();
			seat.changeSeatNumber(editEvent.getNewValue());
		});
		colSeatComment.setCellFactory(TextFieldTableCell.forTableColumn());
		colSeatComment.setOnEditCommit(editEvent -> {
			Seat seat = editEvent.getRowValue();
			seat.setComment(editEvent.getNewValue());
		});

		// Sitzdetails
		spnDoublePrice.setEditable(editMode);
		checkPaid.setDisable(!editMode);
		checkCollected.setDisable(!editMode);
		checkWheelchairAccessible.setDisable(!editMode);
		txtLastName.setEditable(editMode);
		txtFirstName.setEditable(editMode);
		txtMail.setEditable(editMode);
		txtComment.setEditable(editMode);
	}

	@FXML
	private void handleBackToEventOverview() {
		MainApp.showEventOverviewView();
	}

	@FXML
	private void saveCurrentState() {
		// Aktuellen Stand speichern
		// repository.saveEvent(eventComboBox.getValue());
	}

}
