package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.controlsfx.control.table.TableFilter;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.GermanDecimalStringConverter;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.input.InputIssue;
import de.eltviller_carneval_verein.karten.ui.input.ParsingTextFieldTableCell;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

/**
 * Die Kartentabelle der Verkaufsansicht: eine Zeile je Buchung der gewählten Vorstellung (oder aller Vorstellungen des
 * Events). Tisch, Sitz, Kategorie und Rollstuhlplatz stammen aus der Momentaufnahme der Buchung und sind nur lesbar; der
 * Preis ist der effektive Preis. Buchungen ohne Platz stehen als Gruppe "Ohne Platz" oben und lassen sich über
 * "Platz zuweisen…" einem freien Sitz der Bestuhlung zuordnen.
 */
public class TicketTableController implements ContentController, SalesStateAware {

	private static final GermanDecimalStringConverter PRICE_CONVERTER = new GermanDecimalStringConverter();
	private static final String ASSIGN_TEXT = "Platz zuweisen…";

	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();
	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;

	// Enthält NUR die Buchungen des aktuell gewählten Events
	private final ObservableList<BookingDTO> masterData = FXCollections.observableArrayList();
	private FilteredList<BookingDTO> filteredData;
	private ContextMenu selectionMenu;
	private SeatDetailPopup activeDialog;

	// TableView und Spalten
	@FXML
	private TableView<BookingDTO> seatTable;
	@FXML
	private TableColumn<BookingDTO, String> colPresentation;
	@FXML
	private TableColumn<BookingDTO, TableLabel> colTable;
	@FXML
	private TableColumn<BookingDTO, String> colCategory;
	@FXML
	private TableColumn<BookingDTO, Integer> colSeatNumber;
	@FXML
	private TableColumn<BookingDTO, String> colLastName;
	@FXML
	private TableColumn<BookingDTO, String> colFirstName;
	@FXML
	private TableColumn<BookingDTO, Double> colPrice;
	@FXML
	private TableColumn<BookingDTO, PaymentStatus> colPaymentStatus;
	@FXML
	private TableColumn<BookingDTO, Boolean> colCollected;
	@FXML
	private TableColumn<BookingDTO, Boolean> colWheelchair;
	@FXML
	private TableColumn<BookingDTO, String> colComment;
	@FXML
	private TableColumn<BookingDTO, Boolean> colReserved;

	// Leiste über der Tabelle, solange Buchungen ohne Platz da sind
	@FXML
	private HBox unassignedBar;
	@FXML
	private Label lblUnassigned;
	@FXML
	private Button btnAssign;

	@FXML
	public void initialize() {
		// 1. Spalten-ValueFactorys konfigurieren
		colPresentation.setCellValueFactory(cell -> new SimpleStringProperty(EntityLabels.nameOrPlaceholder(cell.getValue().getPresentation().getName())));
		colTable.setCellValueFactory(cell -> new SimpleObjectProperty<>(TableLabel.of(cell.getValue().getBooking())));
		colCategory.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getBooking().getCategory()));
		colSeatNumber.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getBooking().getSeatNumber()).asObject());
		colLastName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getBooking().getLastName()));
		colFirstName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getBooking().getFirstName()));
		colPrice.setCellValueFactory(cell -> new SimpleDoubleProperty(cell.getValue().getBooking().getEffectivePrice() / 100.0).asObject());
		colPaymentStatus.setCellValueFactory(cell -> cell.getValue().getBooking().getPaymentStatusProperty());
		colComment.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getBooking().getComment()));

		colCollected.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().getBooking().isCollected()));
		colWheelchair.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().getBooking().isWheelchairAccessible()));
		colReserved.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().getBooking().isReserved()));

		// 2. CellFactories & Edit-Handler einmalig aufsetzen
		setupCellFactories();

		// 3. FilteredList um die Master-Daten legen & an Tabelle binden
		filteredData = new FilteredList<>(masterData, p -> true);
		seatTable.setItems(filteredData);

		// 4. Spaltenkopf-Filter von ControlsFX aktivieren
		TableFilter.forTableView(seatTable).apply();

		// 5. Mehrfachauswahl (Strg-/Shift-Klick). Zeilen, die der Suchfilter ausblendet, fallen von selbst aus der Auswahl.
		seatTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		MenuItem sellItem = new MenuItem();
		MenuItem assignItem = new MenuItem(ASSIGN_TEXT);
		selectionMenu = new ContextMenu(sellItem, assignItem);
		sellItem.setOnAction(event -> sellSelection());
		// Das Menü zum Sammelverkauf gehört zu einer Zeile (nicht zur leeren Fläche) und gibt es nicht bei offenem Dialog
		// (er würde sonst samt Eingaben ersetzt). Im Anzeigemodus sind die Einträge sichtbar, aber nicht ausführbar. Auf einer
		// nicht gewählten Zeile gilt das Menü nur für diese Zeile, auf einer gewählten für die ganze Auswahl, wie im Explorer.
		// "Platz zuweisen…" gibt es nur auf einer Buchung ohne Platz und gilt nur für sie.
		seatTable.setRowFactory(table -> {
			TableRow<BookingDTO> row = new TableRow<>() {
				@Override
				protected void updateItem(BookingDTO item, boolean empty) {
					super.updateItem(item, empty);
					// Buchungen ohne Platz kursiv: Sie haben keinen Sitz in der Bestuhlung
					setStyle(!empty && item != null && item.getBooking().isUnassigned() ? "-fx-font-style: italic;" : "");
				}
			};
			row.setOnContextMenuRequested(event -> {
				event.consume();
				if (row.isEmpty() || isDialogOpen()) {
					return;
				}
				if (!row.isSelected()) {
					seatTable.getSelectionModel().clearAndSelect(row.getIndex());
				}
				sellItem.setDisable(!editMode);
				sellItem.setText(SeatSelectionEditor.menuText(seatTable.getSelectionModel().getSelectedItems().size()));
				BookingDTO clicked = row.getItem();
				assignItem.setVisible(clicked.getBooking().isUnassigned());
				assignItem.setDisable(!editMode);
				assignItem.setOnAction(action -> assignPlace(clicked));
				selectionMenu.show(row, event.getScreenX(), event.getScreenY());
			});
			return row;
		});

		seatTable.getSelectionModel().getSelectedItems().addListener((ListChangeListener<BookingDTO>) change -> updateUnassignedBar());
		updateUnassignedBar();
	}

	private void sellSelection() {
		List<Booking> bookings = seatTable.getSelectionModel().getSelectedItems().stream().map(BookingDTO::getBooking).toList();
		activeDialog = SeatSelectionEditor.edit(bookings, seatTable.getScene().getWindow(), selectionMenu.getAnchorX(), selectionMenu.getAnchorY(), seatTable::refresh);
	}

	/** Knopf "Platz zuweisen…" der Leiste: für die eine gewählte Buchung ohne Platz. */
	@FXML
	private void handleAssign() {
		List<BookingDTO> selected = seatTable.getSelectionModel().getSelectedItems();
		if (selected.size() == 1 && selected.get(0).getBooking().isUnassigned()) {
			assignPlace(selected.get(0));
		}
	}

	/**
	 * Ordnet die Buchung ohne Platz einem freien Sitz der Bestuhlung ihrer Vorstellung zu ({@link AssignPlaceDialog}, dann
	 * {@link BookingSync#assign}). Geht das nicht (keine Bestuhlung, kein Sitz frei, Sitz inzwischen belegt), nennt die
	 * Fußzeile den Grund.
	 */
	private void assignPlace(BookingDTO dto) {
		closeDialog();
		Presentation presentation = dto.getPresentation();
		Booking booking = dto.getBooking();
		// Nur Buchungen dieser Vorstellung: BookingSync.assign prüft die Zugehörigkeit selbst nicht
		if (!editMode || !booking.isUnassigned() || presentation.getBookings() == null || !presentation.getBookings().contains(booking)) {
			return;
		}
		SeatingPlan plan = SalesPlan.of(presentation, hallRepository.findById(presentation.getHallId()));
		if (plan == null) {
			StatusMessage.getInstance().show(Severity.ERROR, "Platz zuweisen nicht möglich: " + EntityLabels.presentation(presentation) + " hat keine Bestuhlung.");
			return;
		}
		if (AssignPlaceDialog.freeSeats(presentation, plan).isEmpty()) {
			StatusMessage.getInstance().show(Severity.ERROR, "Platz zuweisen nicht möglich: In der Bestuhlung ist kein Sitz frei.");
			return;
		}
		Optional<Seat> seat = AssignPlaceDialog.choose(presentation, plan, seatTable.getScene().getWindow());
		if (seat.isEmpty()) {
			return;
		}
		String before = booking.getPlaceLabel();
		if (!BookingSync.assign(presentation, plan, booking, seat.get())) {
			StatusMessage.getInstance().show(Severity.ERROR, "Platz zuweisen nicht möglich: " + AssignPlaceDialog.label(seat.get()) + " ist bereits belegt.");
			return;
		}
		loadBookings();
		for (int index = 0; index < seatTable.getItems().size(); index++) {
			if (seatTable.getItems().get(index).getBooking() == booking) {
				seatTable.getSelectionModel().clearAndSelect(index);
				seatTable.scrollTo(index);
				break;
			}
		}
		StatusMessage.getInstance().show(Severity.SUCCESS, "Buchung von bisher " + before + " auf " + booking.getPlaceLabel() + " zugewiesen");
	}

	/** Die Leiste "Ohne Platz" erscheint nur mit solchen Buchungen; der Knopf gilt für genau eine gewählte davon. */
	private void updateUnassignedBar() {
		long count = masterData.stream().filter(dto -> dto.getBooking().isUnassigned()).count();
		boolean visible = count > 0;
		unassignedBar.setVisible(visible);
		unassignedBar.setManaged(visible);
		lblUnassigned.setText(count == 1 ? "1 Buchung ohne Platz (oben in der Tabelle)" : count + " Buchungen ohne Platz (oben in der Tabelle)");
		List<BookingDTO> selected = seatTable.getSelectionModel().getSelectedItems();
		boolean oneUnassigned = selected.size() == 1 && selected.get(0) != null && selected.get(0).getBooking().isUnassigned();
		btnAssign.setDisable(!editMode || !oneUnassigned);
	}

	private boolean isDialogOpen() {
		return activeDialog != null && activeDialog.popup().isShowing();
	}

	private void closeDialog() {
		if (activeDialog != null) {
			activeDialog.popup().hide();
			activeDialog = null;
		}
	}

	private void setupCellFactories() {
		// Tisch: "A3" bzw. "3"; ohne Platz "Ohne Platz (bisher A3)", gefiltert und sortiert als eine Gruppe
		colTable.setCellFactory(column -> new TableCell<>() {
			@Override
			protected void updateItem(TableLabel label, boolean empty) {
				super.updateItem(label, empty);
				if (empty || label == null) {
					setText(null);
					return;
				}
				BookingDTO dto = getTableRow() != null ? getTableRow().getItem() : null;
				setText(label.unassigned() && dto != null ? TableLabel.UNASSIGNED_TEXT + " (bisher " + TableLabel.placeOf(dto.getBooking()) + ")" : label.toString());
			}
		});

		// Textfelder
		colLastName.setCellFactory(TextFieldTableCell.forTableColumn());
		colLastName.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setLastName(editEvent.getNewValue()));

		colFirstName.setCellFactory(TextFieldTableCell.forTableColumn());
		colFirstName.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setFirstName(editEvent.getNewValue()));

		// Preis: der effektive Preis; der Standardpreis der Vorstellung als Eingabe entfernt den Einzelpreis
		colPrice.setCellFactory(ParsingTextFieldTableCell.forTableColumn(PRICE_CONVERTER, InputIssue.NOT_A_NUMBER, "Preis", dto -> EntityLabels.booking(dto.getBooking())));
		colPrice.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setEffectivePrice(toCents(editEvent.getNewValue())));

		colComment.setCellFactory(TextFieldTableCell.forTableColumn());
		colComment.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setComment(editEvent.getNewValue()));

		// Enum / ComboBox
		colPaymentStatus.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setPaymentStatus(editEvent.getNewValue()));
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

		// Checkbox mit korrekt zugewiesenem Setter-Aufruf
		colCollected.setCellFactory(CheckBoxTableCell.forTableColumn(index -> {
			BookingDTO dto = seatTable.getItems().get(index);
			SimpleBooleanProperty prop = new SimpleBooleanProperty(dto.getBooking().isCollected());
			prop.addListener((obs, oldVal, newVal) -> dto.getBooking().setCollected(newVal));
			return prop;
		}));

		// Nur lesbar: Rollstuhlplatz (gehört zur Bestuhlung) und Reserviert (folgt aus dem Namen)
		colWheelchair.setCellFactory(CheckBoxTableCell.forTableColumn(colWheelchair));
		colReserved.setCellFactory(CheckBoxTableCell.forTableColumn(colReserved));
	}

	/** Euro in Cent, gerundet und auf den int-Bereich begrenzt (wie bisher beim Sitz). */
	private static int toCents(Double euros) {
		if (euros == null) {
			return 0;
		}
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(euros * 100)));
	}

	private void loadBookings() {
		List<Presentation> presentations = new ArrayList<>();
		masterData.clear(); // Vorherige Daten leeren

		if (selectedPres != null) {
			presentations.add(selectedPres);
		} else if (selectedEvent != null && selectedEvent.getPresentations() != null) {
			presentations.addAll(selectedEvent.getPresentations());
		} else {
			updateUnassignedBar();
			relayoutTable();
			return;
		}

		// Je Vorstellung: erst die Buchungen ohne Platz, dann nach Platz (A2 vor A10, Tische ohne Reihe vor A)
		List<BookingDTO> rows = new ArrayList<>();
		for (Presentation presentation : presentations) {
			if (presentation.getBookings() != null) {
				presentation.getBookings().stream().sorted(TableLabel.BOOKING_ORDER)
						.forEach(booking -> rows.add(new BookingDTO(presentation.getParent(), presentation, booking)));
			}
		}
		masterData.setAll(rows);
		updateUnassignedBar();
		relayoutTable();
	}

	/**
	 * Erscheint oder verschwindet durch neue Daten der senkrechte Scroll-Balken, rechnet die Tabelle die Spaltenköpfe
	 * mit der alten Breite, die Zeilen aber ohne den Balken: Köpfe und Spalten sitzen bis zum nächsten Layout (z. B. dem
	 * ersten Scrollen) versetzt. Ein zweiter Layout-Durchgang nach dem Einfügen behebt das.
	 */
	private void relayoutTable() {
		Platform.runLater(seatTable::requestLayout);
	}

	private void applyEditMode() {
		closeDialog();
		seatTable.setEditable(editMode);

		// Nicht editierbare Spalten
		colPresentation.setEditable(false);
		colTable.setEditable(false);
		colCategory.setEditable(false);
		colSeatNumber.setEditable(false);
		colReserved.setEditable(false);
		colWheelchair.setEditable(false);

		// Editierbare Spalten (steuern sich über editMode)
		colLastName.setEditable(editMode);
		colFirstName.setEditable(editMode);
		colPrice.setEditable(editMode);
		colPaymentStatus.setEditable(editMode);
		colCollected.setEditable(editMode);
		colComment.setEditable(editMode);
		updateUnassignedBar();
	}

	/** Meldet die gewählten Tabellenzeilen als ausgewählte Buchungen (auch ohne Bearbeitungsmodus). */
	@Override
	public void setSalesState(SalesViewState state) {
		seatTable.getSelectionModel().getSelectedItems().addListener((ListChangeListener<BookingDTO>) change -> {
			state.replaceSelection(seatTable.getSelectionModel().getSelectedItems().stream().map(BookingDTO::getBooking).toList());
		});
	}

	@Override
	public void setEvent(Event event) {
		closeDialog();
		this.selectedEvent = event;
		selectedPres = null;
		loadBookings();
	}

	@Override
	public void setPresentation(Presentation presentation) {
		closeDialog();
		// Keine Vorstellung heißt "Alle Vorstellungen" des gewählten Events
		this.selectedPres = presentation;
		if (selectedPres != null) {
			this.selectedEvent = presentation.getParent();
		}
		loadBookings();
	}

	@Override
	public void save() {
		// Aktuellen Stand speichern
		if (selectedEvent != null) {
			EventSaver.save(selectedEvent);
		}
	}

	@Override
	public void filter(String query) {
		filteredData.setPredicate(dto -> {
			if (query == null || query.isEmpty()) {
				return true;
			}

			String lowerQuery = query.toLowerCase();
			Booking booking = dto.getBooking();

			// Prüft Nachname, Vorname und Kommentar
			return (booking.getLastName() != null && booking.getLastName().toLowerCase().contains(lowerQuery))
					|| (booking.getFirstName() != null && booking.getFirstName().toLowerCase().contains(lowerQuery))
					|| (booking.getComment() != null && booking.getComment().toLowerCase().contains(lowerQuery));
		});
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}
