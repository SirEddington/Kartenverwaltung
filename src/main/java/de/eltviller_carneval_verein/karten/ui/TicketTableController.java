package de.eltviller_carneval_verein.karten.ui;

import java.util.ArrayList;
import java.util.List;

import org.controlsfx.control.table.TableFilter;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
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
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.util.StringConverter;
import javafx.util.converter.DoubleStringConverter;

public class TicketTableController implements ContentController {

	private final JsonTicketRepository repository = JsonTicketRepository.getInstance();
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
	private TableColumn<SeatDTO, PaymentStatus> colPaymentStatus;
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
		colPrice.setCellValueFactory(cell -> new SimpleDoubleProperty(cell.getValue().getSeat().getPriceDouble()).asObject());
		colPaymentStatus.setCellValueFactory(cell -> cell.getValue().getSeat().getPaymentStatusProperty());
		colComment.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getSeat().getComment()));

		colCollected.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().getSeat().isCollected()));
		colWheelchair.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().getSeat().isWheelchairAccessible()));
		colReserved.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().getSeat().isReserved()));

		// 2. CellFactories & Edit-Handler einmalig aufsetzen
		setupCellFactories();

		// 3. FilteredList um die Master-Daten legen & an Tabelle binden
		filteredData = new FilteredList<>(masterData, p -> true);
		seatTable.setItems(filteredData);

		// 4. Spaltenkopf-Filter von ControlsFX aktivieren
		TableFilter.forTableView(seatTable).apply();
	}

	private void setupCellFactories() {
		// Textfelder
		colLastName.setCellFactory(TextFieldTableCell.forTableColumn());
		colLastName.setOnEditCommit(editEvent -> editEvent.getRowValue().getSeat().setLastName(editEvent.getNewValue()));

		colFirstName.setCellFactory(TextFieldTableCell.forTableColumn());
		colFirstName.setOnEditCommit(editEvent -> editEvent.getRowValue().getSeat().setFirstName(editEvent.getNewValue()));

		colPrice.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));
		colPrice.setOnEditCommit(editEvent -> editEvent.getRowValue().getSeat().setPriceDouble(editEvent.getNewValue()));

		colComment.setCellFactory(TextFieldTableCell.forTableColumn());
		colComment.setOnEditCommit(editEvent -> editEvent.getRowValue().getSeat().setComment(editEvent.getNewValue()));

		// Enum / ComboBox
		colPaymentStatus.setCellFactory(ComboBoxTableCell.forTableColumn(PaymentStatus.values()));
		colPaymentStatus.setOnEditCommit(editEvent -> editEvent.getRowValue().getSeat().setPaymentStatus(editEvent.getNewValue()));
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

		// Converter an die CellFactory übergeben:
		colPaymentStatus.setCellFactory(ComboBoxTableCell.forTableColumn(converter, PaymentStatus.values()));

		// Checkboxen mit korrekt zugewiesenen Setter-Aufrufen
		colCollected.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(seatDTO.getSeat().isCollected());
			prop.addListener((obs, oldVal, newVal) -> seatDTO.getSeat().setCollected(newVal));
			return prop;
		}));

		colWheelchair.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			SeatDTO seatDTO = seatTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(seatDTO.getSeat().isWheelchairAccessible());
			prop.addListener((obs, oldVal, newVal) -> seatDTO.getSeat().setWheelchairAccessible(newVal));
			return prop;
		}));

		colReserved.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().getSeat().isReserved()));
		colReserved.setCellFactory(CheckBoxTableCell.forTableColumn(colReserved));
	}

	private void loadSeats() {
		List<Presentation> presentations = new ArrayList<>();
		masterData.clear(); // Vorherige Daten leeren

		if (selectedPres != null) {
			presentations.add(selectedPres);
		} else if (selectedEvent != null && selectedEvent.getPresentations() != null) {
			presentations.addAll(selectedEvent.getPresentations());
		} else {
			return;
		}

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

		// Nicht editierbare Spalten
		colPresentation.setEditable(false);
		colTableNumber.setEditable(false);
		colCategory.setEditable(false);
		colSeatNumber.setEditable(false);
		colReserved.setEditable(false);

		// Editierbare Spalten (steuern sich über editMode)
		colLastName.setEditable(editMode);
		colFirstName.setEditable(editMode);
		colPrice.setEditable(editMode);
		colPaymentStatus.setEditable(editMode);
		colCollected.setEditable(editMode);
		colWheelchair.setEditable(editMode);
		colComment.setEditable(editMode);
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
		if (selectedEvent != null) {
			repository.saveEvent(selectedEvent);
		}
	}

	@Override
	public void filter(String query) {
		filteredData.setPredicate(dto -> {
			if (query == null || query.isEmpty()) {
				return true;
			}

			String lowerQuery = query.toLowerCase();

			// Prüft Nachname, Vorname und Kommentar
			return (dto.getSeat().getLastName() != null && dto.getSeat().getLastName().toLowerCase().contains(lowerQuery))
					|| (dto.getSeat().getFirstName() != null && dto.getSeat().getFirstName().toLowerCase().contains(lowerQuery))
					|| (dto.getSeat().getComment() != null && dto.getSeat().getComment().toLowerCase().contains(lowerQuery));
		});
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}