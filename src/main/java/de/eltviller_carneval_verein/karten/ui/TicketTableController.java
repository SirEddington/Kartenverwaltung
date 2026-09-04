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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.util.converter.DoubleStringConverter;

public class TicketTableController implements ContentController {

	private final JsonTicketRepository repository = new JsonTicketRepository();
	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;

	// Enthält NUR die Sitze des aktuell gewählten Events
	private final ObservableList<SeatDTO> masterData = FXCollections.observableArrayList();
	private FilteredList<SeatDTO> filteredData;

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

	}

	private void loadSeats() {
		List<Presentation> presentations = new ArrayList<Presentation>();
		masterData.clear(); // Vorherige Daten leeren

		if (selectedPres != null) {
			presentations.add(selectedPres);
		} else if (selectedEvent != null && selectedEvent.getPresentations() != null) {
			presentations.addAll(selectedEvent.getPresentations());
		} else {
			return;
		}
		
		// Liste an Vorstellungen erstellen und mitgegeben anhängen. Dann so oder so über die Verstellungen loopen

		List<SeatDTO> seats = new ArrayList<>();
		for (Presentation presentation : presentations) {
			if (presentation.getTables() != null) {
				for (Table table : presentation.getTables()) {
					if (table.getSeats() != null) {
						table.getSeats().forEach(seat -> seats.add(new SeatDTO(presentation.getParent(), presentation, table, seat)));
					}
				}
			}
		}
		masterData.setAll(seats);
	}

	private void applyEditMode() {
		seatTable.setEditable(editMode);

		// Spalten die nicht bearbeitet werden können sperren
		colPresentation.setEditable(false);
		colTableNumber.setEditable(false);
		colCategory.setEditable(false);
		colReserved.setEditable(false);

		// Eingaben akzeptieren
		colLastName.setCellFactory(TextFieldTableCell.forTableColumn());
		colLastName.setOnEditCommit(editEvent -> {
			SeatDTO seatDTO = editEvent.getRowValue();
			seatDTO.getSeat().setLastName(editEvent.getNewValue());
		});
		colFirstName.setCellFactory(TextFieldTableCell.forTableColumn());
		colFirstName.setOnEditCommit(editEvent -> {
			SeatDTO seatDTO = editEvent.getRowValue();
			seatDTO.getSeat().setFirstName(editEvent.getNewValue());
		});
		colPrice.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));
		colPrice.setOnEditCommit(editEvent -> {
			SeatDTO seatDTO = editEvent.getRowValue();
			seatDTO.getSeat().setPriceDouble(editEvent.getNewValue());
		});
		colComment.setCellFactory(TextFieldTableCell.forTableColumn());
		colComment.setOnEditCommit(editEvent -> {
			SeatDTO seatDTO = editEvent.getRowValue();
			seatDTO.getSeat().setComment(editEvent.getNewValue());
		});
		colPaid.setOnEditCommit(editEvent -> {
			SeatDTO seatDTO = editEvent.getRowValue();
			seatDTO.getSeat().setPaid(editEvent.getNewValue());
		});
		colPaid.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(seatDTO.getSeat().isPaid());
			prop.addListener((obs, oldVal, newVal) -> {
				seatDTO.getSeat().setPaid(newVal);
			});
			return prop;
		}));
		colCollected.setOnEditCommit(editEvent -> {
			SeatDTO seatDTO = editEvent.getRowValue();
			seatDTO.getSeat().setCollected(editEvent.getNewValue());
		});
		colCollected.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(seatDTO.getSeat().isCollected());
			prop.addListener((obs, oldVal, newVal) -> {
				seatDTO.getSeat().setCollected(newVal);
			});
			return prop;
		}));
		colWheelchair.setOnEditCommit(editEvent -> {
			SeatDTO seatDTO = editEvent.getRowValue();
			seatDTO.getSeat().setWheelchairAccessible(editEvent.getNewValue());
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

	@Override
	public void setEvent(Event event) {
		this.selectedEvent = event;
		selectedPres = null;
		loadSeats();
	}

	@Override
	public void setPresentation(Presentation presentation) {
		this.selectedPres = presentation;
		if (selectedPres != null) {
			this.selectedEvent = presentation.getParent();
		} else {
			selectedEvent = null;
		}
		loadSeats();
	}

	@Override
	public void save() {
		// Aktuellen Stand speichern
		repository.saveEvent(selectedEvent);
	}

	@Override
	public void filter(String query) {

		filteredData.setPredicate(dto -> {
			if (query.isEmpty()) {
				return true;
			}

			// Prüft Nachname, Vorname und Kommentar
			return (dto.getSeat().getLastName() != null && dto.getSeat().getLastName().toLowerCase().contains(query))
					|| (dto.getSeat().getFirstName() != null && dto.getSeat().getFirstName().toLowerCase().contains(query))
					|| (dto.getSeat().getComment() != null && dto.getSeat().getComment().toLowerCase().contains(query));
		});
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}
