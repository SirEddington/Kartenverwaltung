package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;

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
	private TableView<Presentation> PresTable;
	@FXML
	private TableColumn<Presentation, String> colPresName;
	@FXML
	private TableColumn<Presentation, String> colPresDate;
	@FXML
	private TableColumn<Presentation, String> colPresTime;
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

	private boolean editMode = true;

	@FXML
	public void initialize() {
		colEventName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getName()));
		colEventDesc.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDescription()));
		colEventArchived.setCellValueFactory(cellData -> new SimpleBooleanProperty(cellData.getValue().isArchived()));
		colEventArchived.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			Event event = eventTable.getItems().get(index);
			SimpleBooleanProperty archived = new SimpleBooleanProperty(event.isArchived());

			// Wenn die CheckBox geklickt wird, den neuen Wert im Event speichern
			archived.addListener((obs, oldVal, newVal) -> {
				event.setArchived(newVal);
			});

			return archived;
		}));
	}

	private void loadSeatsForEvent(Event event) {
	}

	private void updateSearchFilter(String searchText) {
	}

	@FXML
	private void toggleEditMode() {
	}

	@FXML
	private void handleBackToEventOverview() {
		MainApp.showEventOverviewView();
	}

	@FXML
	private void saveCurrentState() {
		// Aktuellen Stand speichern
		repository.saveEvent(eventComboBox.getValue());
	}

}
