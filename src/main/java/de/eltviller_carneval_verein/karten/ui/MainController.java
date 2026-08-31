package de.eltviller_carneval_verein.karten.ui;

import java.util.ArrayList;
import java.util.List;

import org.controlsfx.control.table.TableFilter;

import de.eltviller_carneval_verein.karten.MainApp;
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
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.util.converter.DoubleStringConverter;

public class MainController {

	private final JsonTicketRepository repository = new JsonTicketRepository();

	// Enthält NUR die Sitze des aktuell gewählten Events
	private final ObservableList<SeatDTO> masterData = FXCollections.observableArrayList();
	private FilteredList<SeatDTO> filteredData;

	@FXML
	private ComboBox<Event> eventComboBox;
	@FXML
	private TextField searchField;
	@FXML
	private Button btnToggleEdit = new Button();
	@FXML
	private Button btnSave = new Button();

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

	private boolean editMode = true;

	@FXML
	public void initialize() {
		// 1. Spalten-ValueFactorys konfigurieren
		colPresentation.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getPresentation().getName()));
		colTableNumber.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getTable().getTableNumber()).asObject());
		colCategory.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getTable().getCategory()));
		colSeatNumber.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getSeat().getSeatNumber()).asObject());
		colLastName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getSeat().getLastName()));
		colFirstName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getSeat().getFirstName()));
		colPaid.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty paid = new SimpleBooleanProperty(seatDTO.getSeat().isPaid());
			paid.addListener((obs, oldVal, newVal) -> {
				seatDTO.getSeat().setCollected(newVal);
			});
			return paid;
		}));
		colCollected.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty collected = new SimpleBooleanProperty(seatDTO.getSeat().isCollected());
			collected.addListener((obs, oldVal, newVal) -> {
				seatDTO.getSeat().setCollected(newVal);
			});
			return collected;
		}));
		colWheelchair.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty whellchair = new SimpleBooleanProperty(seatDTO.getSeat().isWheelchairAccessible());
			whellchair.addListener((obs, oldVal, newVal) -> {
				seatDTO.getSeat().setCollected(newVal);
			});
			return whellchair;
		}));
		colComment.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getSeat().getComment()));
		colPrice.setCellValueFactory(cell -> new SimpleDoubleProperty(cell.getValue().getSeat().getPriceDouble()).asObject());
		colReserved.setCellValueFactory(cell -> {
			boolean reserved = cell.getValue().getSeat().isReserved();
			return new SimpleBooleanProperty(reserved);
		});
		colReserved.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty reserved = new SimpleBooleanProperty(seatDTO.getSeat().isReserved());
			reserved.addListener((obs, oldVal, newVal) -> {
				seatDTO.getSeat().setCollected(newVal);
			});
			return reserved;
		}));

		// 2. FilteredList um die Master-Daten legen & an Tabelle binden
		filteredData = new FilteredList<>(masterData, p -> true);
		seatTable.setItems(filteredData);

		// 3. Spaltenkopf-Filter von ControlsFX aktivieren
		TableFilter.forTableView(seatTable).apply();

		// 4. Events in ComboBox laden (Tabelle bleibt initial leer)
		eventComboBox.getItems().setAll(repository.loadEvents().stream().filter(event -> !event.isArchived()).toList());

		// 5. Event-Auswahl: Erst hier werden Daten geladen
		eventComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			loadSeatsForEvent(selectedEvent);
		});

		// 6. Freitext-Suche auf den geladenen Event-Daten
		searchField.textProperty().addListener((obs, oldVal, newValue) -> {
			updateSearchFilter(newValue);
		});

		// 7. Anzeigemodus starten
		toggleEditMode();

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

	@FXML
	private void toggleEditMode() {
		// Editmode wechseln
		editMode = !editMode;
		seatTable.setEditable(editMode);
		btnSave.setDisable(!editMode);
		btnToggleEdit.setText(editMode ? "Anzeigen" : "Bearbeiten");

		// Spalten die nicht bearbeitet werden können sperren
		colPresentation.setEditable(false);
		colTableNumber.setEditable(false);
		colCategory.setEditable(false);
		colReserved.setEditable(false);

		// Eingaben akzeptieren
		colLastName.setCellFactory(TextFieldTableCell.forTableColumn());
		colLastName.setOnEditCommit(event -> {
			SeatDTO seatDTO = event.getRowValue();
			seatDTO.getSeat().setLastName(event.getNewValue());
		});
		colFirstName.setCellFactory(TextFieldTableCell.forTableColumn());
		colFirstName.setOnEditCommit(event -> {
			SeatDTO seatDTO = event.getRowValue();
			seatDTO.getSeat().setFirstName(event.getNewValue());
		});
		colPrice.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));
		colPrice.setOnEditCommit(event -> {
			SeatDTO seatDTO = event.getRowValue();
			seatDTO.getSeat().setPriceDouble(event.getNewValue());
		});
		colComment.setCellFactory(TextFieldTableCell.forTableColumn());
		colComment.setOnEditCommit(event -> {
			SeatDTO seatDTO = event.getRowValue();
			seatDTO.getSeat().setComment(event.getNewValue());
		});
		colPaid.setOnEditCommit(event -> {
			SeatDTO seatDTO = event.getRowValue();
			seatDTO.getSeat().setPaid(event.getNewValue());
		});
		colPaid.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(seatDTO.getSeat().isPaid());
			prop.addListener((obs, oldVal, newVal) -> {
				seatDTO.getSeat().setPaid(newVal);
			});
			return prop;
		}));
		colCollected.setOnEditCommit(event -> {
			SeatDTO seatDTO = event.getRowValue();
			seatDTO.getSeat().setCollected(event.getNewValue());
		});
		colCollected.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(seatDTO.getSeat().isCollected());
			prop.addListener((obs, oldVal, newVal) -> {
				seatDTO.getSeat().setCollected(newVal);
			});
			return prop;
		}));
		colWheelchair.setOnEditCommit(event -> {
			SeatDTO seatDTO = event.getRowValue();
			seatDTO.getSeat().setWheelchairAccessible(event.getNewValue());
		});
		colWheelchair.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(seatDTO.getSeat().isWheelchairAccessible());
			prop.addListener((obs, oldVal, newVal) -> {
				seatDTO.getSeat().setWheelchairAccessible(newVal);
			});
			return prop;
		}));

	}

	@FXML
	private void handleBackToMenu() {
		MainApp.showMenuView();
	}

	@FXML
	private void saveCurrentState() {
		// Aktuellen Stand speichern
		repository.saveEvent(eventComboBox.getValue());
	}
}
