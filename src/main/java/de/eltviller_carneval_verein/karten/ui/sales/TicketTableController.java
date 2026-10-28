package de.eltviller_carneval_verein.karten.ui.sales;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.controlsfx.control.table.TableFilter;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SaleDates;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.ForeignBatch;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import de.eltviller_carneval_verein.karten.ui.EditingUnits;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.GermanDecimalStringConverter;
import de.eltviller_carneval_verein.karten.ui.LiveViews;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.input.InputIssue;
import de.eltviller_carneval_verein.karten.ui.input.ParsingTextFieldTableCell;
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
import javafx.event.EventType;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
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

	private final StoreHallRepository hallRepository = StoreHallRepository.getInstance();
	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;

	// Enthält NUR die Buchungen des aktuell gewählten Events
	private final ObservableList<BookingDTO> masterData = FXCollections.observableArrayList();
	private FilteredList<BookingDTO> filteredData;
	private ContextMenu selectionMenu;
	private SeatDetailPopup activeDialog;

	private final StoreEventRepository eventRepository = StoreEventRepository.getInstance();
	// Eine fremde Änderung kam, während eine Zelle im Bearbeiten war; die Tabelle zeichnet nach deren Ende neu
	private boolean foreignChangePending = false;
	private final RepositoryListener<Event> foreignChanges = new RepositoryListener<>() {
		@Override
		public void changedByOthers(Event event, ForeignBatch<Event> batch) {
			// Ein neu zusammengesetztes Event (anderes Objekt) bindet die Shell mit setEvent neu
			if (event == selectedEvent) {
				refreshAfterForeignChange();
			}
		}
	};

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
	@FXML
	private TableColumn<BookingDTO, LocalDate> colReservedOn;
	@FXML
	private TableColumn<BookingDTO, LocalDate> colPaidOn;
	@FXML
	private TableColumn<BookingDTO, LocalDate> colCollectedOn;

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
		colReservedOn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getBooking().getReservationDate()));
		colPaidOn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getBooking().getPaymentDate()));
		colCollectedOn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getBooking().getCollectionDate()));

		// 2. CellFactories & Edit-Handler einmalig aufsetzen
		setupCellFactories();

		// 3. FilteredList um die Master-Daten legen & an Tabelle binden
		filteredData = new FilteredList<>(masterData, p -> true);
		seatTable.setItems(filteredData);

		// 4. Spaltenkopf-Filter von ControlsFX aktivieren
		TableFilter.forTableView(seatTable).apply();

		// 5. Mehrfachauswahl (Strg-/Shift-Klick). Zeilen, die der Suchfilter ausblendet, fallen von selbst aus der Auswahl.
		seatTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		freezeSelectionWhileDialogOpen();
		TableInput.install(seatTable);
		MenuItem sellItem = new MenuItem();
		MenuItem assignItem = new MenuItem(ASSIGN_TEXT);
		selectionMenu = new ContextMenu(sellItem, assignItem);
		sellItem.setOnAction(event -> sellSelection());
		// Das Menü schließt bei jeder Bedienung der Tabelle (wie in der Hallenübersicht). JavaFX schließt ein Menü nur bei
		// einem unverarbeiteten Ereignis daneben; die Tabelle verarbeitet Klick und Mausrad selbst (Auswahl, Bearbeiten),
		// deshalb blieb es sonst neben der bearbeiteten Zelle stehen. Der Rechtsklick schließt zuerst und öffnet danach neu.
		seatTable.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> selectionMenu.hide());
		seatTable.addEventFilter(ScrollEvent.ANY, event -> selectionMenu.hide());
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

		// Eine Zelle im Bearbeiten hält ihre Buchung für fremde Änderungen zurück; endet sie, kommt Verpasstes nach
		EditingUnits.getInstance().watch(seatTable, BookingDTO::getBooking);
		seatTable.editingCellProperty().addListener((obs, before, cell) -> {
			if (cell == null && foreignChangePending) {
				Platform.runLater(this::refreshAfterForeignChange);
			}
		});
		// Fremde Änderungen nur, solange die Tabelle zu sehen ist; eine mit der Ansicht verschwundene Zelle gibt ihre Buchung frei
		LiveViews.whileShown(seatTable, () -> eventRepository.addListener(foreignChanges), () -> {
			eventRepository.removeListener(foreignChanges);
			EditingUnits.getInstance().end(seatTable);
		});
	}

	private void sellSelection() {
		List<Booking> bookings = seatTable.getSelectionModel().getSelectedItems().stream().map(BookingDTO::getBooking).toList();
		activeDialog = SeatSelectionEditor.edit(bookings, seatTable.getScene().getWindow(), selectionMenu.getAnchorX(), selectionMenu.getAnchorY(), seatTable::refresh,
				this::deselect);
	}

	/** Nimmt die Zeilen dieser Buchungen aus der Auswahl der Tabelle (und damit aus dem gemeinsamen Verkaufszustand). */
	private void deselect(List<Booking> bookings) {
		for (BookingDTO dto : List.copyOf(seatTable.getSelectionModel().getSelectedItems())) {
			if (bookings.contains(dto.getBooking())) {
				seatTable.getSelectionModel().clearSelection(seatTable.getItems().indexOf(dto));
			}
		}
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

	/**
	 * Solange der Verkaufsdialog offen ist, ändert die Tabelle ihre Auswahl nicht: Klicks auf Zeilen (auch mit Strg, Umschalt
	 * und beim Ziehen) und Tasten werden abgefangen, bevor die Tabelle sie auswertet. Der Dialog gehört zu genau dieser
	 * Auswahl; die Spaltenköpfe und der Scroll-Balken bleiben bedienbar. Die Filter hängen vor denen von {@link TableInput}.
	 */
	private void freezeSelectionWhileDialogOpen() {
		for (EventType<MouseEvent> type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED, MouseEvent.MOUSE_DRAGGED)) {
			seatTable.addEventFilter(type, event -> {
				if (isDialogOpen() && isRowArea(event.getTarget())) {
					event.consume();
				}
			});
		}
		seatTable.addEventFilter(KeyEvent.ANY, event -> {
			if (isDialogOpen()) {
				event.consume();
			}
		});
	}

	/** Ob das Ziel eine Zeile oder Zelle der Tabelle ist (und nicht Kopf oder Scroll-Balken). */
	private static boolean isRowArea(Object target) {
		for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
			if (node instanceof ScrollBar) {
				return false;
			}
			if (node instanceof TableRow<?> || node instanceof TableCell<?, ?>) {
				return true;
			}
		}
		return false;
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
		colLastName.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		// Name, Zahlung und "Abgeholt" über SaleDates: Wird eine Aktion neu gesetzt, trägt es das Datum ein; die Tabelle zeichnet
		// sich danach neu, weil dabei ein Datum in einer anderen Spalte entsteht
		colLastName.setOnEditCommit(editEvent -> {
			SaleDates.change(editEvent.getRowValue().getBooking(), booking -> booking.setLastName(editEvent.getNewValue()));
			refreshAfterCommit();
		});

		colFirstName.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		colFirstName.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setFirstName(editEvent.getNewValue()));

		// Preis: der effektive Preis; der Standardpreis der Vorstellung als Eingabe entfernt den Einzelpreis
		colPrice.setCellFactory(ParsingTextFieldTableCell.forTableColumn(PRICE_CONVERTER, InputIssue.NOT_A_NUMBER, "Preis", dto -> EntityLabels.booking(dto.getBooking())));
		colPrice.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setEffectivePrice(toCents(editEvent.getNewValue())));

		colComment.setCellFactory(ParsingTextFieldTableCell.forTextColumn());
		colComment.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setComment(editEvent.getNewValue()));

		// Enum / ComboBox
		colPaymentStatus.setOnEditCommit(editEvent -> {
			SaleDates.change(editEvent.getRowValue().getBooking(), booking -> booking.setPaymentStatus(editEvent.getNewValue()));
			refreshAfterCommit();
		});
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
			prop.addListener((obs, oldVal, newVal) -> {
				SaleDates.change(dto.getBooking(), booking -> booking.setCollected(newVal));
				refreshAfterCommit();
			});
			return prop;
		}));

		// Die Daten der Aktionen: Datumsspalten mit strengem Format; leere Eingabe löscht das Datum, ein unlesbarer Text in
		// einem leeren Feld trägt heute ein (INP-002 meldet es trotzdem)
		colReservedOn.setCellFactory(ParsingTextFieldTableCell.forDateColumn("Reservierungsdatum", dto -> EntityLabels.booking(dto.getBooking()), LocalDate::now));
		colReservedOn.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setReservationDate(editEvent.getNewValue()));
		colPaidOn.setCellFactory(ParsingTextFieldTableCell.forDateColumn("Bezahldatum", dto -> EntityLabels.booking(dto.getBooking()), LocalDate::now));
		colPaidOn.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setPaymentDate(editEvent.getNewValue()));
		colCollectedOn.setCellFactory(ParsingTextFieldTableCell.forDateColumn("Abholdatum", dto -> EntityLabels.booking(dto.getBooking()), LocalDate::now));
		colCollectedOn.setOnEditCommit(editEvent -> editEvent.getRowValue().getBooking().setCollectionDate(editEvent.getNewValue()));

		// Nur lesbar: Rollstuhlplatz (gehört zur Bestuhlung) und Reserviert (folgt aus dem Namen)
		colWheelchair.setCellFactory(CheckBoxTableCell.forTableColumn(colWheelchair));
		colReserved.setCellFactory(CheckBoxTableCell.forTableColumn(colReserved));
	}

	/**
	 * Zeichnet die Tabelle nach einer Änderung neu, die ein Datum in einer anderen Spalte setzen kann. Erst nach dem
	 * laufenden Commit: Ein Neuzeichnen mittendrin lässt die Zelle (z. B. die Zahlungs-ComboBox) im Bearbeiten hängen.
	 */
	private void refreshAfterCommit() {
		Platform.runLater(seatTable::refresh);
	}

	/**
	 * Euro in Cent, gerundet und auf den int-Bereich begrenzt, damit auch ein riesiger Betrag als zu hoch (SEA-003) bzw.
	 * negativ (SEA-001) gemeldet wird.
	 */
	static int toCents(Double euros) {
		if (euros == null) {
			return 0;
		}
		return MoneyFormat.toCents(euros);
	}

	private void loadBookings() {
		masterData.clear(); // Vorherige Daten leeren
		masterData.setAll(buildRows());
		updateUnassignedBar();
		relayoutTable();
	}

	/** Die Zeilen der gewählten Vorstellung bzw. aller Vorstellungen des gewählten Events. */
	private List<BookingDTO> buildRows() {
		List<Presentation> presentations = new ArrayList<>();
		if (selectedPres != null) {
			presentations.add(selectedPres);
		} else if (selectedEvent != null && selectedEvent.getPresentations() != null) {
			presentations.addAll(selectedEvent.getPresentations());
		} else {
			return List.of();
		}

		// Je Vorstellung: erst die Buchungen ohne Platz, dann nach Platz (A2 vor A10, Tische ohne Reihe vor A)
		List<BookingDTO> rows = new ArrayList<>();
		for (Presentation presentation : presentations) {
			if (presentation.getBookings() != null) {
				presentation.getBookings().stream().sorted(TableLabel.BOOKING_ORDER)
						.forEach(booking -> rows.add(new BookingDTO(presentation.getParent(), presentation, booking)));
			}
		}
		return rows;
	}

	/**
	 * Fremde Änderungen am gezeigten Event: Sind es dieselben Buchungen in derselben Reihenfolge, genügt ein Neuzeichnen der
	 * Zellen; sonst wird die Liste neu gesetzt und die bisherige Auswahl wiederhergestellt. Nie, solange eine Zelle im Bearbeiten
	 * ist (das Neuzeichnen risse sie weg): Dann kommt es nach dem Ende der Bearbeitung nach, und die zurückgestellten Einheiten
	 * der bearbeiteten Buchung folgen über das erneute Einarbeiten.
	 */
	private void refreshAfterForeignChange() {
		if (seatTable.getEditingCell() != null) {
			foreignChangePending = true;
			return;
		}
		foreignChangePending = false;
		List<BookingDTO> rows = buildRows();
		if (sameBookings(masterData, rows)) {
			seatTable.refresh();
			updateUnassignedBar();
			return;
		}
		List<Booking> selection = seatTable.getSelectionModel().getSelectedItems().stream().map(BookingDTO::getBooking).toList();
		masterData.clear();
		masterData.setAll(rows);
		updateUnassignedBar();
		relayoutTable();
		restoreSelection(selection);
	}

	/** Ob beide Listen dieselben Buchungen (dieselben Objekte) in derselben Reihenfolge enthalten. */
	static boolean sameBookings(List<BookingDTO> shown, List<BookingDTO> expected) {
		if (shown.size() != expected.size()) {
			return false;
		}
		for (int index = 0; index < shown.size(); index++) {
			if (shown.get(index).getBooking() != expected.get(index).getBooking()) {
				return false;
			}
		}
		return true;
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
		colReservedOn.setEditable(editMode);
		colPaidOn.setEditable(editMode);
		colCollectedOn.setEditable(editMode);
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

	/** Wählt die Zeilen der Buchungen, die die Tabelle zeigt (nach dem Wechsel von der Saalansicht). */
	@Override
	public void restoreSelection(java.util.Collection<Booking> bookings) {
		seatTable.getSelectionModel().clearSelection();
		List<Integer> indices = new ArrayList<>();
		for (int index = 0; index < filteredData.size(); index++) {
			if (bookings.contains(filteredData.get(index).getBooking())) {
				indices.add(index);
			}
		}
		if (!indices.isEmpty()) {
			seatTable.getSelectionModel().selectIndices(indices.get(0), indices.subList(1, indices.size()).stream().mapToInt(Integer::intValue).toArray());
		}
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
		// Vorname, Nachname und Kommentar zusammen, jedes Suchwort muss vorkommen (BookingSearch)
		filteredData.setPredicate(dto -> BookingSearch.matches(dto.getBooking(), query));
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}
