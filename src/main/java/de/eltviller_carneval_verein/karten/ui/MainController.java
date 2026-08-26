package de.eltviller_carneval_verein.karten.ui;

import java.util.ArrayList;
import java.util.List;

import org.controlsfx.control.table.TableFilter;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

public class MainController {

	private final JsonTicketRepository repository = new JsonTicketRepository();

	// Enthält NUR die Sitze des aktuell gewählten Events
	private final ObservableList<SeatDTO> masterData = FXCollections.observableArrayList();
	private FilteredList<SeatDTO> filteredData;

	@FXML
	private ComboBox<Event> eventComboBox;
	@FXML
	private TextField searchField;

	// TableView und Spalten
	@FXML
	private TableView<SeatDTO> seatTable;
	@FXML
	private TableColumn<SeatDTO, String> colPresentation;
	@FXML
	private TableColumn<SeatDTO, Integer> colTableNumber;
	@FXML
	private TableColumn<SeatDTO, String> colCategory;
	@FXML
	private TableColumn<SeatDTO, Integer> colSeatNumber;
	@FXML
	private TableColumn<SeatDTO, String> colLastName;
	@FXML
	private TableColumn<SeatDTO, String> colFirstName;
	@FXML
	private TableColumn<SeatDTO, Double> colPrice;
	@FXML
	private TableColumn<SeatDTO, Boolean> colPaid;
	@FXML
	private TableColumn<SeatDTO, Boolean> colCollected;
	@FXML
	private TableColumn<SeatDTO, Boolean> colWheelchair;
	@FXML
	private TableColumn<SeatDTO, String> colComment;
	@FXML
	private TableColumn<SeatDTO, Boolean> colReserved;

	@FXML
	public void initialize() {
		// 1. Spalten-ValueFactorys konfigurieren ...
		colPresentation.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getPresentation().getName()));
		colTableNumber.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getTable().getTableNumber()).asObject());
		colPresentation.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getTable().getCategory()));
		colSeatNumber.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getSeat().getSeatNumber()).asObject());
		colLastName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getSeat().getLastName()));
		colFirstName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getSeat().getFirstName()));
		colPaid.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().getSeat().isPaid()));
		colCollected.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().getSeat().isCollected()));
		colWheelchair.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().getSeat().isWheelchairAccessible()));
		colComment.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getSeat().getComment()));
		colPrice.setCellValueFactory(cell -> new SimpleDoubleProperty(cell.getValue().getSeat().getPriceDouble()).asObject());
		colReserved.setCellValueFactory(cell -> {
			boolean reserved = cell.getValue().getSeat().isReserved();
			return new SimpleBooleanProperty(reserved);
		});

		// 2. FilteredList um die Master-Daten legen & an Tabelle binden
		filteredData = new FilteredList<>(masterData, p -> true);
		seatTable.setItems(filteredData);

		// 3. Spaltenkopf-Filter von ControlsFX aktivieren
		TableFilter.forTableView(seatTable).apply();

		// 4. Events in ComboBox laden (Tabelle bleibt initial leer)
		eventComboBox.getItems().setAll(repository.loadEvents());

		// 5. Event-Auswahl: Erst hier werden Daten geladen
		eventComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			loadSeatsForEvent(selectedEvent);
		});

		// 6. Freitext-Suche auf den geladenen Event-Daten
		searchField.textProperty().addListener((obs, oldVal, newValue) -> {
			updateSearchFilter(newValue);
		});
	}

	private void loadSeatsForEvent(Event event) {
		masterData.clear(); // Vorherige Daten leeren

		if (event == null || event.getPresentations() == null) {
			return;
		}

		List<SeatDTO> eventSeats = new ArrayList<>();
		for (Presentation presentation : event.getPresentations()) {
			if (presentation.getTables() != null) {
				for (Table table : presentation.getTables()) {
					if (table.getSeats() != null) {
						table.getSeats().forEach(seat -> eventSeats.add(new SeatDTO(event, presentation, table, seat)));
					}
				}
			}
		}
		masterData.setAll(eventSeats);
	}

	private void updateSearchFilter(String searchText) {
		
		String lowerCaseFilter = (searchText == null) ? "" : searchText.toLowerCase().trim();

		filteredData.setPredicate(dto -> {
			if (lowerCaseFilter.isEmpty()) {
				return true;
			}

			// Prüft Nachname, Vorname und Kommentar
			return (dto.getSeat().getLastName() != null && dto.getSeat().getLastName().toLowerCase().contains(lowerCaseFilter))
					|| (dto.getSeat().getFirstName() != null && dto.getSeat().getFirstName().toLowerCase().contains(lowerCaseFilter))
					|| (dto.getSeat().getComment() != null && dto.getSeat().getComment().toLowerCase().contains(lowerCaseFilter));
		});
	}

	// Änderungen in der JSON-Datei persistieren
	@FXML
	private void handleSave() {
		Event selectedEvent = eventComboBox.getValue();
		if (selectedEvent != null) {
			repository.saveEvent(selectedEvent);
		}
	}
}
