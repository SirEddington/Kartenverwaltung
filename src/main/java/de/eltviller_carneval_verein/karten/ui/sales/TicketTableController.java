package de.eltviller_carneval_verein.karten.ui.sales;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingCancellation;
import de.eltviller_carneval_verein.karten.model.BookingMove;
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
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.ui.ColumnMinWidths;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import de.eltviller_carneval_verein.karten.ui.DisplayPreferences;
import de.eltviller_carneval_verein.karten.ui.EditingUnits;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.GermanDecimalStringConverter;
import de.eltviller_carneval_verein.karten.ui.LiveViews;
import de.eltviller_carneval_verein.karten.ui.LiveViews.Registration;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.input.InputIssue;
import de.eltviller_carneval_verein.karten.ui.input.ParsingTextFieldTreeTableCell;
import de.eltviller_carneval_verein.karten.ui.input.TableInput;
import de.eltviller_carneval_verein.karten.ui.sales.TicketFilter.FilterValue;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.event.EventType;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

/**
 * Die Kartentabelle der Verkaufsansicht: eine Zeile je Buchung der gewählten Vorstellung (oder aller Vorstellungen des
 * Events). Tisch, Sitz, Kategorie und Rollstuhlplatz stammen aus der Momentaufnahme der Buchung und sind nur lesbar; der
 * Preis ist der effektive Preis. Buchungen ohne Platz stehen als Gruppe "Ohne Platz" oben und lassen sich über
 * "Platz zuweisen…" einem freien Sitz der Bestuhlung zuordnen.
 *
 * <p>Die Tabelle ist eine {@link TreeTableView} (KARV-193): Ihre Bedienung und die Auswahl als Menge von Buchungen liegen
 * in {@link TicketTreeTable}, das Warten auf das Ende einer Bearbeitung und einer Mausbedienung ({@link MouseGesture}) und
 * die Ausnahme für selbst geänderte Buchungen in {@link TicketEdits}. Jeder Neuaufbau läuft über {@link #rebuild()}:
 * Buchungen, Suche, Spaltenfilter, Gruppen.
 */
public class TicketTableController implements ContentController, SalesStateAware {

	private static final GermanDecimalStringConverter PRICE_CONVERTER = new GermanDecimalStringConverter();
	private static final String ASSIGN_TEXT = "Platz zuweisen…";
	/** Namen und Kommentar bleiben auch schmal gezogen so breit, dass vom Inhalt etwas zu lesen ist (KARV-126). */
	private static final double CONTENT_MIN_WIDTH = 80;
	/** Nur Buchungszeilen lassen sich bearbeiten, Gruppenzeilen nie. */
	private static final Predicate<TicketRow> BOOKING_ROW = row -> row instanceof TicketRow.BookingRow;
	/** Bezeichnung einer Buchungszeile für Meldungen; die Zellen fragen nur für Buchungszeilen. */
	private static final Function<TicketRow, String> ROW_LABEL = row -> EntityLabels.booking(((TicketRow.BookingRow) row).dto().getBooking());

	private final StoreHallRepository hallRepository = StoreHallRepository.getInstance();
	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;

	/** Alle Zeilen der gezeigten Vorstellung(en) vor Suche und Filter, Stand des letzten Neuaufbaus. */
	private List<BookingDTO> allRows = List.of();
	// Der Suchtext, nach dem die Tabelle gerade filtert (die Shell gibt ihn mit filter vor)
	private String searchQuery = "";
	private final TicketFilter columnFilter = new TicketFilter();
	/** Gruppierung und Gruppenordnung aus {@link DisplayPreferences} (Menü "Gruppieren"), Stand der letzten Änderung. */
	private List<TicketColumn> grouping = List.of();
	private GroupOrder groupOrder = GroupOrder.VALUE;
	private boolean groupingNested = true;
	// Angemeldet nur, solange die Tabelle zu sehen ist (bindGrouping). Der Neuaufbau wartet wie jeder andere, bis
	// keine Zelle mehr bearbeitet wird; erst er übernimmt die Gruppierung in die Tabelle.
	private final ChangeListener<Object> groupingChanges = (obs, before, now) -> followGrouping();
	private TicketTreeTable treeTable;
	private TicketEdits edits;
	/** Die laufende Mausbedienung der Tabelle; solange sie läuft, wartet jeder Neuaufbau ({@link #trackMouseGesture}). */
	private MouseGesture gesture;
	private SalesViewState salesState;
	/** Das Menü zum Sammelverkauf auf den Zeilen ({@link #showSelectionMenu}) und seine Einträge. */
	private ContextMenu selectionMenu;
	private MenuItem sellItem;
	private MenuItem assignItem;
	private MenuItem moveItem;
	private MenuItem cancelItem;
	/** "Alle aufklappen" und "Alle zuklappen" mit ihrem Trenner, nur auf einem Gruppenkopf (Spec Abschnitt 14.1). */
	private SeparatorMenuItem allSeparator;
	private MenuItem expandAllItem;
	private MenuItem collapseAllItem;
	private SeatDetailPopup activeDialog;
	/** Ob der Verkaufsdialog offen ist; sperrt "Alle aufklappen" und "Alle zuklappen" der Leiste ({@link #trackDialog}). */
	private final SimpleBooleanProperty dialogOpen = new SimpleBooleanProperty(this, "dialogOpen", false);
	/** Buchungen ohne Platz unter {@link #allRows}, Stand des letzten Neuaufbaus. */
	private long unassignedCount;

	private final StoreEventRepository eventRepository = StoreEventRepository.getInstance();
	// Zuletzt geänderte Buchungen, die dadurch storniert sein können (nach Identität); geprüft, sobald keine Zelle im Bearbeiten ist
	// und keine Mausbedienung läuft (TicketEdits)
	private final Set<Booking> cancelCandidates = Collections.newSetFromMap(new IdentityHashMap<>());
	private final RepositoryListener<Event> foreignChanges = new RepositoryListener<>() {
		// Ein neu zusammengesetztes Event (anderes Objekt) bindet die Shell mit setEvent neu
		private final RepositoryListener<Event> refreshing = LiveViews.onFxThread(
				units -> LiveViews.touches(units, selectedEvent, selectedPres), TicketTableController.this::refreshAfterForeignChange);

		@Override
		public void changedByOthers(Event event, ForeignBatch<Event> batch) {
			refreshing.changedByOthers(event, batch);
		}

		@Override
		public void changedElsewhere(Event event, Set<UnitKey> units) {
			refreshing.changedElsewhere(event, units);
		}
	};

	// Baumtabelle und Spalten
	@FXML
	private TreeTableView<TicketRow> seatTable;
	@FXML
	private TreeTableColumn<TicketRow, String> colTree;
	@FXML
	private TreeTableColumn<TicketRow, String> colPresentation;
	@FXML
	private TreeTableColumn<TicketRow, TableLabel> colTable;
	@FXML
	private TreeTableColumn<TicketRow, String> colCategory;
	@FXML
	private TreeTableColumn<TicketRow, Integer> colSeatNumber;
	@FXML
	private TreeTableColumn<TicketRow, String> colLastName;
	@FXML
	private TreeTableColumn<TicketRow, String> colFirstName;
	@FXML
	private TreeTableColumn<TicketRow, Double> colPrice;
	@FXML
	private TreeTableColumn<TicketRow, Double> colPaidAmount;
	@FXML
	private TreeTableColumn<TicketRow, PaymentStatus> colPaymentStatus;
	@FXML
	private TreeTableColumn<TicketRow, Boolean> colCollected;
	@FXML
	private TreeTableColumn<TicketRow, Boolean> colWheelchair;
	@FXML
	private TreeTableColumn<TicketRow, String> colComment;
	@FXML
	private TreeTableColumn<TicketRow, Boolean> colReserved;
	@FXML
	private TreeTableColumn<TicketRow, LocalDate> colReservedOn;
	@FXML
	private TreeTableColumn<TicketRow, LocalDate> colPaidOn;
	@FXML
	private TreeTableColumn<TicketRow, LocalDate> colCollectedOn;

	// Leiste "Gruppieren" über der Tabelle: Menü, Bausteine der Gruppierung, "Alle aufklappen" und "Alle zuklappen"
	@FXML
	private MenuButton btnGrouping;
	@FXML
	private HBox groupingChips;
	@FXML
	private Button btnExpandAll;
	@FXML
	private Button btnCollapseAll;

	// Leiste über der Tabelle, solange Buchungen ohne Platz da sind
	@FXML
	private HBox unassignedBar;
	@FXML
	private Label lblUnassigned;
	@FXML
	private Button btnAssign;

	@FXML
	public void initialize() {
		// 1. Werte der Spalten: für Buchungszeilen wie bisher, für Gruppenzeilen keine
		colPresentation.setCellValueFactory(TicketCells.value(dto -> new SimpleStringProperty(EntityLabels.nameOrPlaceholder(dto.getPresentation().getName()))));
		colTable.setCellValueFactory(TicketCells.value(dto -> new SimpleObjectProperty<>(TableLabel.of(dto.getBooking()))));
		colCategory.setCellValueFactory(TicketCells.value(dto -> new SimpleStringProperty(dto.getBooking().getCategory())));
		colSeatNumber.setCellValueFactory(TicketCells.value(dto -> new SimpleIntegerProperty(dto.getBooking().getSeatNumber()).asObject()));
		colLastName.setCellValueFactory(TicketCells.value(dto -> new SimpleStringProperty(dto.getBooking().getLastName())));
		colFirstName.setCellValueFactory(TicketCells.value(dto -> new SimpleStringProperty(dto.getBooking().getFirstName())));
		colPrice.setCellValueFactory(TicketCells.value(dto -> new SimpleDoubleProperty(dto.getBooking().getEffectivePrice() / 100.0).asObject()));
		// gezahlter Betrag nur bei bezahlten Buchungen, sonst leer
		colPaidAmount.setCellValueFactory(TicketCells.value(dto -> new SimpleObjectProperty<>(MoneyFormat.toEuros(dto.getBooking().getPaidCents()))));
		colPaymentStatus.setCellValueFactory(TicketCells.value(dto -> dto.getBooking().getPaymentStatusProperty()));
		colComment.setCellValueFactory(TicketCells.value(dto -> new SimpleStringProperty(dto.getBooking().getComment())));

		colCollected.setCellValueFactory(TicketCells.value(dto -> new SimpleBooleanProperty(dto.getBooking().isCollected())));
		colWheelchair.setCellValueFactory(TicketCells.value(dto -> new SimpleBooleanProperty(dto.getBooking().isWheelchairAccessible())));
		colReserved.setCellValueFactory(TicketCells.value(dto -> new SimpleBooleanProperty(dto.getBooking().isReserved())));
		colReservedOn.setCellValueFactory(TicketCells.value(dto -> new SimpleObjectProperty<>(dto.getBooking().getReservationDate())));
		colPaidOn.setCellValueFactory(TicketCells.value(dto -> new SimpleObjectProperty<>(dto.getBooking().getPaymentDate())));
		colCollectedOn.setCellValueFactory(TicketCells.value(dto -> new SimpleObjectProperty<>(dto.getBooking().getCollectionDate())));

		// 2. Die Baumtabelle (Auswahl als Menge, Tasten, Sortierung) vor TableInput: Ihre Filter laufen vor dessen
		treeTable = new TicketTreeTable(seatTable, colTree);
		gesture = new MouseGesture(() -> System.nanoTime() / 1_000_000, TicketTableController::later, () -> edits.idle());
		edits = new TicketEdits(() -> seatTable.getEditingCell() != null, gesture::active, this::rebuild, this::removeCancelled);
		treeTable.setRebuild(edits::requestRebuild);
		treeTable.setFrozen(this::isDialogOpen);
		bindGrouping();

		// 3. CellFactories & Edit-Handler einmalig aufsetzen, Spaltenfilter im Kopf jeder Datenspalte
		setupCellFactories();
		installColumnFilters();
		ColumnMinWidths.install(seatTable, Map.of(colLastName, CONTENT_MIN_WIDTH, colFirstName, CONTENT_MIN_WIDTH, colComment, CONTENT_MIN_WIDTH));

		// 4. Mehrfachauswahl (Strg-/Shift-Klick) setzt TicketTreeTable; bei offenem Dialog erreicht keine Bedienung der Zeilen
		// die Tabelle, auch nicht TableInput. Am selben Elternknoten verwirft die Sperre für längere Doppelklickzeiten den
		// zweiten Klick auf eine andere Zeile
		freezeSelectionWhileDialogOpen();
		treeTable.installMultiClickGuard(seatTable.getParent());
		TableInput.install(seatTable, BOOKING_ROW);
		sellItem = new MenuItem();
		assignItem = new MenuItem(ASSIGN_TEXT);
		moveItem = new MenuItem();
		cancelItem = new MenuItem();
		allSeparator = new SeparatorMenuItem();
		expandAllItem = new MenuItem("Alle aufklappen");
		collapseAllItem = new MenuItem("Alle zuklappen");
		selectionMenu = new ContextMenu(sellItem, assignItem, moveItem, cancelItem, allSeparator, expandAllItem, collapseAllItem);
		sellItem.setOnAction(event -> sellSelection());
		moveItem.setOnAction(event -> moveSelection());
		cancelItem.setOnAction(event -> cancelSelection());
		expandAllItem.setOnAction(event -> afterEditing(treeTable::expandAll));
		collapseAllItem.setOnAction(event -> afterEditing(treeTable::collapseAll));
		// Das Menü schließt bei jeder Bedienung der Tabelle (wie in der Hallenübersicht). JavaFX schließt ein Menü nur bei
		// einem unverarbeiteten Ereignis daneben; die Tabelle verarbeitet Klick und Mausrad selbst (Auswahl, Bearbeiten),
		// deshalb blieb es sonst neben der bearbeiteten Zelle stehen. Der Rechtsklick schließt zuerst und öffnet danach neu.
		seatTable.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> selectionMenu.hide());
		seatTable.addEventFilter(ScrollEvent.ANY, event -> selectionMenu.hide());
		// Die Zeilen zeigen den Kopf einer Gruppe über die ganze Breite und Buchungen ohne Platz kursiv (TicketTreeRow)
		seatTable.setRowFactory(table -> {
			TreeTableRow<TicketRow> row = new TicketTreeRow(treeTable);
			row.setOnContextMenuRequested(event -> {
				event.consume();
				showSelectionMenu(row, event);
			});
			return row;
		});

		treeTable.selectedBookingsProperty().addListener((obs, before, now) -> updateUnassignedBar());
		updateUnassignedBar();

		// Eine Zelle im Bearbeiten hält ihre Buchung für fremde Änderungen zurück; endet sie, kommt Verpasstes nach (auch
		// das Entfernen stornierter Buchungen, TicketEdits)
		EditingUnits.getInstance().watch(seatTable, row -> row instanceof TicketRow.BookingRow booking ? booking.dto().getBooking() : null);
		seatTable.editingCellProperty().addListener((obs, before, cell) -> {
			if (cell == null) {
				edits.idle();
			}
		});
		// Fremde Änderungen nur, solange die Tabelle zu sehen ist; eine mit der Ansicht verschwundene Zelle gibt ihre Buchung frei
		// Ebenso eine geänderte Sperrliste: Ein gesperrter Sitz verschwindet aus der Tabelle oder kehrt zurück (KARV-111); wie
		// eine fremde Änderung erst nach dem Ende einer Bearbeitung. Die Mausbedienung endet, sobald die Tabelle nicht mehr
		// zu sehen ist
		LiveViews.whileShown(seatTable, Registration.of(eventRepository::addListener, eventRepository::removeListener, foreignChanges),
				LockedSeats.whileShown(this::refreshAfterForeignChange), trackMouseGesture(),
				Registration.onDetach(() -> EditingUnits.getInstance().end(seatTable)));
	}

	/** Plant {@code task} nach {@code delayMillis} auf dem FX-Thread ein (Planer der {@link MouseGesture}). */
	private static void later(long delayMillis, Runnable task) {
		PauseTransition pause = new PauseTransition(Duration.millis(delayMillis));
		pause.setOnFinished(event -> task.run());
		pause.play();
	}

	/**
	 * Meldet der {@link MouseGesture}, was sie braucht (Spec Abschnitt 14.5): Ein Druck in einer Zeile der Tabelle beginnt
	 * sie, ein Loslassen irgendwo in der Szene beendet sie nach der Nachlaufzeit, ebenso das erste Mausereignis ohne
	 * gedrückte Taste, falls das Loslassen verloren ging. Verliert das Fenster den Fokus (modaler Dialog) oder ist die
	 * Tabelle nicht mehr zu sehen, endet sie sofort. Die Filter hängen an der Szene, nicht an der Tabelle, und nur, solange
	 * die Tabelle zu sehen ist: Die Szene der Shell lebt länger als der Controller.
	 */
	private Registration trackMouseGesture() {
		EventHandler<MouseEvent> pressed = event -> {
			if (isRowArea(event.getTarget()) && isInTable(event.getTarget())) {
				gesture.pressed();
			}
		};
		EventHandler<MouseEvent> released = event -> gesture.released();
		EventHandler<MouseEvent> withoutButton = event -> {
			if (!event.isPrimaryButtonDown() && !event.isSecondaryButtonDown() && !event.isMiddleButtonDown()) {
				gesture.mouseWithoutButton();
			}
		};
		ChangeListener<Boolean> focus = (obs, before, focused) -> {
			if (!focused) {
				gesture.focusLost();
			}
		};
		Scene[] scene = new Scene[1];
		Window[] window = new Window[1];
		return new Registration(() -> {
			scene[0] = seatTable.getScene();
			window[0] = scene[0].getWindow();
			scene[0].addEventFilter(MouseEvent.MOUSE_PRESSED, pressed);
			scene[0].addEventFilter(MouseEvent.MOUSE_RELEASED, released);
			scene[0].addEventFilter(MouseEvent.MOUSE_MOVED, withoutButton);
			scene[0].addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, withoutButton);
			window[0].focusedProperty().addListener(focus);
		}, () -> {
			scene[0].removeEventFilter(MouseEvent.MOUSE_PRESSED, pressed);
			scene[0].removeEventFilter(MouseEvent.MOUSE_RELEASED, released);
			scene[0].removeEventFilter(MouseEvent.MOUSE_MOVED, withoutButton);
			scene[0].removeEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, withoutButton);
			window[0].focusedProperty().removeListener(focus);
			gesture.detached();
		});
	}

	/**
	 * Gruppierung und Gruppenordnung kommen aus {@link DisplayPreferences} (beim Start nur mit "Anzeige beim Start
	 * wiederherstellen") und gelten so auch nach dem Wechsel zur Saalansicht und zurück. Das Menü "Gruppieren" und die
	 * Bausteine der Leiste ({@link GroupingBar}) ändern sie dort; jede Änderung baut die Tabelle neu auf.
	 *
	 * <p>Die Listener hängen nur, solange die Tabelle zu sehen ist ({@link LiveViews#whileShown}): Der Controller entsteht
	 * bei jedem Wechsel zur Saalansicht und zurück neu, die Einstellungen leben mit der App. Ein abgehängter Controller baut
	 * so nicht mehr neu auf und meldet keine alte Auswahl ({@link #setSalesState}). Beim Anmelden holt er nach, was sich
	 * geändert hat, während die Tabelle nicht zu sehen war.
	 */
	private void bindGrouping() {
		DisplayPreferences preferences = DisplayPreferences.instance();
		grouping = preferences.ticketGroupingProperty().get();
		groupOrder = preferences.ticketGroupOrderProperty().get();
		groupingNested = preferences.ticketGroupingNestedProperty().get();
		LiveViews.whileShown(seatTable, () -> {
			preferences.ticketGroupingProperty().addListener(groupingChanges);
			preferences.ticketGroupOrderProperty().addListener(groupingChanges);
			preferences.ticketGroupingNestedProperty().addListener(groupingChanges);
			followGrouping();
		}, () -> {
			preferences.ticketGroupingProperty().removeListener(groupingChanges);
			preferences.ticketGroupOrderProperty().removeListener(groupingChanges);
			preferences.ticketGroupingNestedProperty().removeListener(groupingChanges);
		});
		new GroupingMenu(btnGrouping, preferences.ticketGroupingProperty(), preferences.ticketGroupOrderProperty());
		new GroupingBar(groupingChips, preferences.ticketGroupingProperty());
		bindExpandButtons();
	}

	/**
	 * "Alle aufklappen" und "Alle zuklappen" der Leiste (Spec Abschnitt 14.1): zu sehen und im Layout nur, solange die
	 * Tabelle gruppiert zeigt (Gruppierung des letzten Neuaufbaus), gesperrt bei offenem Verkaufsdialog, auch im
	 * Anzeigemodus bedienbar.
	 */
	private void bindExpandButtons() {
		for (Button button : List.of(btnExpandAll, btnCollapseAll)) {
			button.visibleProperty().bind(treeTable.groupedProperty());
			button.managedProperty().bind(treeTable.groupedProperty());
			button.disableProperty().bind(dialogOpen);
		}
		btnExpandAll.setOnAction(event -> afterEditing(treeTable::expandAll));
		btnCollapseAll.setOnAction(event -> afterEditing(treeTable::collapseAll));
	}

	/**
	 * "Alle aufklappen" und "Alle zuklappen" aus Leiste oder Kontextmenü: Eine laufende Bearbeitung endet zuerst (die Zelle
	 * übernimmt ihre Eingabe).
	 */
	private void afterEditing(Runnable action) {
		seatTable.edit(-1, null);
		action.run();
	}

	/** Übernimmt Gruppierung und Gruppenordnung aus den Einstellungen; weicht eine ab, wird neu aufgebaut. */
	private void followGrouping() {
		DisplayPreferences preferences = DisplayPreferences.instance();
		List<TicketColumn> nowGrouping = preferences.ticketGroupingProperty().get();
		GroupOrder nowOrder = preferences.ticketGroupOrderProperty().get();
		boolean nowNested = preferences.ticketGroupingNestedProperty().get();
		if (!nowGrouping.equals(grouping) || nowOrder != groupOrder || nowNested != groupingNested) {
			grouping = nowGrouping;
			groupOrder = nowOrder;
			groupingNested = nowNested;
			edits.requestRebuild();
		}
	}

	/**
	 * Rechtsklick auf den Kopf einer Datenspalte öffnet ihren Filter ({@link ColumnFilterMenu}, Spec Abschnitt 8). Ändert
	 * er die Menge der abgewählten Werte, endet die Ausnahme für selbst geänderte Buchungen (Spec Abschnitt 11).
	 */
	private void installColumnFilters() {
		Map<TreeTableColumn<TicketRow, ?>, TicketColumn> columns = new LinkedHashMap<>();
		columns.put(colPresentation, TicketColumn.PRESENTATION);
		columns.put(colTable, TicketColumn.TABLE);
		columns.put(colCategory, TicketColumn.CATEGORY);
		columns.put(colSeatNumber, TicketColumn.SEAT);
		columns.put(colLastName, TicketColumn.LAST_NAME);
		columns.put(colFirstName, TicketColumn.FIRST_NAME);
		columns.put(colReserved, TicketColumn.RESERVED);
		columns.put(colReservedOn, TicketColumn.RESERVED_ON);
		columns.put(colPrice, TicketColumn.PRICE);
		columns.put(colPaidAmount, TicketColumn.PAID_AMOUNT);
		columns.put(colPaymentStatus, TicketColumn.PAYMENT_STATUS);
		columns.put(colPaidOn, TicketColumn.PAID_ON);
		columns.put(colCollected, TicketColumn.COLLECTED);
		columns.put(colCollectedOn, TicketColumn.COLLECTED_ON);
		columns.put(colWheelchair, TicketColumn.WHEELCHAIR);
		columns.put(colComment, TicketColumn.COMMENT);
		Runnable applied = () -> {
			edits.endPinning();
			edits.requestRebuild();
		};
		columns.forEach((column, key) -> ColumnFilterMenu.install(column, key, columnFilter, () -> filterValues(key), applied));
	}

	/**
	 * Die Werteliste einer Spalte: Buchungen nach der Suche unter den Filtern der anderen Spalten; selbst geänderte
	 * bestehen beide (Spec Abschnitte 2 und 11).
	 */
	private List<FilterValue> filterValues(TicketColumn key) {
		String query = searchQuery;
		List<BookingDTO> afterSearch = edits.visible(allRows, dto -> BookingSearch.matches(dto.getBooking(), query));
		Set<Booking> pinned = edits.pinned();
		return columnFilter.values(key, afterSearch, dto -> pinned.contains(dto.getBooking()), shownPresentations());
	}

	/**
	 * Das Menü zum Sammelverkauf gehört zu einer Zeile (nicht zur leeren Fläche) und gibt es nicht bei offenem Dialog (er
	 * würde sonst samt Eingaben ersetzt). Im Anzeigemodus sind die Einträge sichtbar, aber nicht ausführbar. Auf einer
	 * nicht markierten Zeile gilt das Menü nur für diese Zeile (bei einer Gruppe für ihre Buchungen), auf einer markierten
	 * für die ganze Auswahl, wie im Explorer. "Platz zuweisen…" gibt es nur auf einer Buchung ohne Platz und gilt nur für
	 * sie. "Buchung verschieben…" gilt für die belegenden Buchungen der Auswahl mit Platz ({@link #moveSelection}).
	 * "Stornieren" gilt wie der Sammelverkauf für die Auswahl und ist gesperrt, wenn keine gewählte Buchung Daten trägt.
	 * Auf einem Gruppenkopf folgen ein Trenner, "Alle aufklappen" und "Alle zuklappen" (Spec Abschnitt 14.1), auch im
	 * Anzeigemodus.
	 */
	private void showSelectionMenu(TreeTableRow<TicketRow> row, ContextMenuEvent event) {
		TreeItem<TicketRow> clicked = row.getTreeItem();
		if (row.isEmpty() || clicked == null || isDialogOpen()) {
			return;
		}
		// Der Rechtsklick hat eine nicht markierte Zeile schon gewählt (TicketTreeTable); hier für die Menütaste
		if (!isMarked(clicked)) {
			treeTable.restoreSelection(treeTable.bookingsUnder(clicked));
		}
		List<Booking> selected = treeTable.selectedBookings();
		sellItem.setDisable(!editMode);
		sellItem.setText(SeatSelectionEditor.menuText(selected.size()));
		CancelAction.prepare(cancelItem, selected, editMode);
		MoveAction.prepare(moveItem, selected, editMode);
		BookingDTO dto = TicketCells.dtoOf(clicked);
		assignItem.setVisible(dto != null && dto.getBooking().isUnassigned());
		assignItem.setDisable(!editMode);
		assignItem.setOnAction(action -> assignPlace(dto));
		boolean groupHeader = clicked.getValue() instanceof TicketRow.GroupRow;
		allSeparator.setVisible(groupHeader);
		expandAllItem.setVisible(groupHeader);
		collapseAllItem.setVisible(groupHeader);
		selectionMenu.show(row, event.getScreenX(), event.getScreenY());
	}

	/** "Markiert" nach Regel 2 (Spec Abschnitt 6): eine gewählte Buchung bzw. eine Gruppe, deren Buchungen alle gewählt sind. */
	private boolean isMarked(TreeItem<TicketRow> item) {
		Booking booking = TicketCells.bookingOf(item);
		return booking != null ? treeTable.selectedBookings().stream().anyMatch(selected -> selected == booking) : treeTable.isGroupSelected(item);
	}

	/** Storniert die gewählten Buchungen nach Rückfrage (KARV-121); stornierte ohne Platz verschwinden aus der Tabelle. */
	private void cancelSelection() {
		// Eine laufende Bearbeitung endet zuerst (die Zelle übernimmt ihre Eingabe): Eine stornierte Buchung ohne Platz
		// verschwindet sofort, eine Eingabe ginge sonst in eine Buchung, die es nicht mehr gibt
		seatTable.edit(-1, null);
		if (CancelAction.run(treeTable.selectedBookings(), seatTable.getScene().getWindow())) {
			treeTable.clearSelection();
			edits.endPinning();
			rebuild();
		}
	}

	private void sellSelection() {
		List<Booking> bookings = treeTable.selectedBookings();
		// Der Dialog kann eine Buchung ohne Platz leeren (Namen löschen): Sie ist dann storniert wie nach einer Zelle. Die
		// Buchungen, deren Werte der Dialog tatsächlich geändert hat, gelten als selbst geändert und bleiben trotz Suche und
		// Filter sichtbar (Spec Abschnitt 11). Nimmt der Dialog Buchungen heraus, verlassen sie die Auswahl der Tabelle (und
		// damit den gemeinsamen Verkaufszustand).
		Map<Booking, TicketEdits.Values> before = new IdentityHashMap<>();
		bookings.forEach(booking -> before.put(booking, TicketEdits.Values.of(booking)));
		activeDialog = SeatSelectionEditor.edit(bookings, seatTable.getScene().getWindow(), selectionMenu.getAnchorX(), selectionMenu.getAnchorY(), () -> {
			bookings.forEach(booking -> edits.changed(booking, !before.get(booking).equals(TicketEdits.Values.of(booking))));
			bookings.forEach(this::removeIfCancelledAfterCommit);
		}, treeTable::deselect);
		trackDialog();
	}

	/**
	 * {@link #dialogOpen} folgt dem gerade geöffneten Verkaufsdialog, bis er sich schließt (auch über "Anwenden",
	 * "Abbrechen" oder {@link #closeDialog}); ohne Dialog ist er falsch.
	 */
	private void trackDialog() {
		if (activeDialog == null) {
			dialogOpen.unbind();
			dialogOpen.set(false);
		} else {
			dialogOpen.bind(activeDialog.popup().showingProperty());
		}
	}

	/** Knopf "Platz zuweisen…" der Leiste: für die eine gewählte Buchung ohne Platz. */
	@FXML
	private void handleAssign() {
		List<Booking> selected = treeTable.selectedBookings();
		if (selected.size() == 1 && selected.get(0).isUnassigned()) {
			Booking booking = selected.get(0);
			allRows.stream().filter(dto -> dto.getBooking() == booking).findFirst().ifPresent(this::assignPlace);
		}
	}

	/**
	 * Ordnet die Buchung ohne Platz einem freien Sitz der Bestuhlung ihrer Vorstellung zu ({@link AssignPlaceDialog}, dann
	 * {@link BookingSync#assign}). Geht das nicht (keine Bestuhlung, kein Sitz frei, Sitz inzwischen belegt), nennt die
	 * Fußzeile den Grund. Danach ist die Buchung allein gewählt und bleibt als selbst geänderte sichtbar.
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
		String refusal = PlaceAssignment.refusal(presentation, plan);
		if (refusal != null) {
			StatusMessage.getInstance().show(Severity.ERROR, refusal);
			return;
		}
		Optional<Seat> seat = AssignPlaceDialog.choose(presentation, plan, seatTable.getScene().getWindow());
		if (seat.isEmpty()) {
			return;
		}
		String before = booking.getPlaceLabel();
		if (!BookingSync.assign(presentation, plan, booking, seat.get())) {
			StatusMessage.getInstance().show(Severity.ERROR, PlaceAssignment.takenText(seat.get()));
			return;
		}
		// wie eine eigene Änderung, ohne die Auswahl zu leeren; dann gleich, damit die Buchung gewählt werden kann. Eine
		// noch laufende Bearbeitung endet zuerst, sonst verschöbe der Neuaufbau ihre Zeile
		seatTable.edit(-1, null);
		edits.pin(booking);
		rebuild();
		treeTable.selectAndReveal(booking);
		StatusMessage.getInstance().show(Severity.SUCCESS, "Buchung von bisher " + before + " auf " + booking.getPlaceLabel() + " zugewiesen");
	}

	/**
	 * Verschiebt die belegenden Buchungen der Auswahl auf Plätze aus einer Liste (KARV-140, {@link MoveTargetDialog}). Die
	 * Quellen müssen zu einer Vorstellung mit Bestuhlung gehören, und keiner ihrer Sitze darf gesperrt sein; sonst nennt die
	 * Fußzeile den Grund, und es öffnet sich keine Liste. Was danach geschieht, steht in {@link #moveTo}.
	 */
	private void moveSelection() {
		closeDialog();
		seatTable.edit(-1, null);
		List<Booking> movable = MoveTexts.movable(treeTable.selectedBookings());
		if (!editMode || movable.isEmpty()) {
			return; // der Eintrag ist dann gesperrt
		}
		Presentation presentation = movable.get(0).getParent();
		if (movable.stream().anyMatch(booking -> booking.getParent() != presentation)) {
			StatusMessage.getInstance().show(Severity.ERROR, MoveTexts.MULTIPLE_PRESENTATIONS);
			return;
		}
		SeatingPlan plan = planOf(presentation);
		if (plan == null) {
			StatusMessage.getInstance().show(Severity.ERROR, PlaceAssignment.noPlanText(presentation));
			return;
		}
		List<BookingMove.Source> sources = movable.stream().map(BookingMove.Source::of).toList();
		Set<String> locked = LockedSeats.lockedSeatIds(presentation);
		Seat lockedSeat = MoveAction.lockedSourceSeat(plan, sources, locked);
		if (lockedSeat != null) {
			StatusMessage.getInstance().show(Severity.ERROR, MoveTexts.locked(lockedSeat));
			return;
		}
		Window owner = seatTable.getScene().getWindow();
		MoveTargetDialog.show(presentation, plan, sources, locked, targets -> moveTo(presentation, plan, sources, targets, owner), owner);
	}

	/**
	 * "Weiter" in der Platzliste: plant den Vorgang und fragt nach ({@link MoveAction#run}). "Nichts zu tun" und "Platz
	 * gesperrt" zeigt die Liste selbst und bleibt mit ihrer Wahl offen, "Abbrechen" im Bestätigungsdialog ebenso. Jede
	 * veraltete Auswahl schließt sie mit der Meldung in der Fußzeile, ebenso eine Tabelle, die während der Liste ein anderes
	 * Modell bekommen hat (neu zusammengesetztes Event, ersetzte Bestuhlung). Nach dem Ausführen gelten alle umgezogenen
	 * Buchungen als selbst geändert, und die Quellen sind die Auswahl.
	 *
	 * @param plan die Bestuhlung beim Öffnen der Liste
	 * @return wie {@code onNext} von {@link MoveTargetDialog#show}: ein Text für die Liste, "" ohne Text, {@code null} zum
	 *         Schließen
	 */
	private String moveTo(Presentation presentation, SeatingPlan plan, List<BookingMove.Source> sources, List<String> targets,
			Window owner) {
		if (!showsMoveModel(presentation, plan)) {
			moveStale();
			return null;
		}
		BookingMove.Plan shown = BookingMove.plan(presentation, plan, sources, targets, LockedSeats.lockedSeatIds(presentation));
		if (shown.refused()) {
			switch (shown.refusal()) {
			case NOTHING_TO_DO, LOCKED -> {
				return MoveTexts.refusal(shown, presentation);
			}
			case STALE, COUNT, NO_PLAN -> {
				moveStale();
				return null;
			}
			}
		}
		// Meldungen nach dem Ausführen und bei veralteter Auswahl zeigt MoveAction selbst. Ausgeführt wird nur, solange die
		// Tabelle dieses Modell zeigt: Ein neu zusammengesetztes Event hängt das alte ab (es würde nie gespeichert), und auf
		// einer ersetzten Bestuhlung läge die Buchung womöglich auf einem Sitz, den es nicht mehr gibt
		switch (MoveAction.run(presentation, plan, sources, targets, shown, () -> showsMoveModel(presentation, plan), owner)) {
		case CANCELLED -> {
			return "";
		}
		case STALE -> rebuild();
		case DONE -> {
			// Das Speichern kann fremde Änderungen einarbeiten; die Tabelle zeigt dann womöglich schon ein anderes Modell
			if (showsMoveModel(presentation, plan)) {
				shown.steps().forEach(step -> edits.pin(step.booking()));
				rebuild();
				treeTable.restoreSelection(sources.stream().map(BookingMove.Source::booking).toList());
			} else {
				rebuild();
			}
		}
		}
		return null;
	}

	/** Die Auswahl ist veraltet: Meldung in der Fußzeile, die Tabelle zeigt den neuen Stand. */
	private void moveStale() {
		StatusMessage.getInstance().show(Severity.ERROR, MoveTexts.STALE);
		rebuild();
	}

	/**
	 * Ob die Tabelle noch die Vorstellung zeigt (dasselbe Objekt im gezeigten Event) und ihre Bestuhlung im Modell noch
	 * {@code plan} ist.
	 */
	private boolean showsMoveModel(Presentation presentation, SeatingPlan plan) {
		boolean inEvent = selectedEvent != null && selectedEvent.getPresentations() != null
				&& selectedEvent.getPresentations().stream().anyMatch(p -> p == presentation);
		return inEvent && shownPresentations().stream().anyMatch(p -> p == presentation) && planOf(presentation) == plan;
	}

	/** Die Bestuhlung der Vorstellung, wie sie gerade im Modell steht. */
	private SeatingPlan planOf(Presentation presentation) {
		return SalesPlan.of(presentation, hallRepository.findById(presentation.getHallId()));
	}

	/**
	 * Die Leiste "Ohne Platz" erscheint nur mit solchen Buchungen (gezählt ohne Suche und Filter, beim Neuaufbau); der
	 * Knopf gilt für genau eine gewählte davon (die zuletzt gemeldete Auswahl der Tabelle). "(oben in der Tabelle)" stimmt
	 * nur, solange die Tabelle weder gruppiert zeigt (Gruppierung des letzten Neuaufbaus) noch sortiert ist.
	 */
	private void updateUnassignedBar() {
		List<Booking> selected = treeTable.selectedBookingsProperty().get();
		boolean visible = unassignedCount > 0;
		unassignedBar.setVisible(visible);
		unassignedBar.setManaged(visible);
		String where = !treeTable.groupedProperty().get() && seatTable.getSortOrder().isEmpty() ? " (oben in der Tabelle)" : "";
		lblUnassigned.setText((unassignedCount == 1 ? "1 Buchung ohne Platz" : unassignedCount + " Buchungen ohne Platz") + where);
		boolean oneUnassigned = selected.size() == 1 && selected.get(0).isUnassigned();
		btnAssign.setDisable(!editMode || !oneUnassigned);
	}

	/**
	 * Solange der Verkaufsdialog offen ist, ändert die Tabelle ihre Auswahl nicht: Klicks auf Zeilen (auch mit Strg, Umschalt,
	 * beim Ziehen und auf den Pfeil einer Gruppe) und Tasten werden abgefangen, bevor die Tabelle sie sieht. Die Filter
	 * hängen am Elternknoten der Tabelle: Was dort verbraucht wird, erreicht weder {@link TicketTreeTable} noch
	 * {@link TableInput} (auf demselben Knoten liefen die übrigen Filter trotz Verbrauch weiter). Der Dialog gehört zu genau
	 * dieser Auswahl; die Spaltenköpfe und der Scroll-Balken bleiben bedienbar.
	 */
	private void freezeSelectionWhileDialogOpen() {
		Node area = seatTable.getParent();
		for (EventType<MouseEvent> type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED, MouseEvent.MOUSE_DRAGGED)) {
			area.addEventFilter(type, event -> {
				if (isDialogOpen() && isRowArea(event.getTarget())) {
					event.consume();
				}
			});
		}
		area.addEventFilter(KeyEvent.ANY, event -> {
			if (isDialogOpen() && isInTable(event.getTarget())) {
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
			if (node instanceof TreeTableRow<?> || node instanceof TreeTableCell<?, ?>) {
				return true;
			}
		}
		return false;
	}

	/** Ob das Ziel die Tabelle oder ein Knoten darin ist (etwa das Feld einer bearbeiteten Zelle). */
	private boolean isInTable(Object target) {
		for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
			if (node == seatTable) {
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
			trackDialog();
		}
	}

	private void setupCellFactories() {
		// Tisch: "A3" bzw. "3"; ohne Platz "Ohne Platz (bisher A3)", gefiltert und sortiert als eine Gruppe
		colTable.setCellFactory(TicketCells.tableLabel());

		// Textfelder; jede eigene Änderung baut die Tabelle nach dem Ende der Bearbeitung neu auf (TicketEdits)
		colLastName.setCellFactory(ParsingTextFieldTreeTableCell.forTextColumn(BOOKING_ROW));
		// Name, Zahlung und "Abgeholt" über SaleDates: Wird eine Aktion neu gesetzt, trägt es das Datum ein
		colLastName.setOnEditCommit(editEvent -> {
			Booking edited = TicketCells.bookingOf(editEvent.getRowValue());
			if (edited == null) {
				return;
			}
			String newLastName = editEvent.getNewValue();
			Booking after = BookingCancellation.withLastName(edited, newLastName);
			if (BookingCancellation.leavesData(edited, after)) {
				// Die Rückfrage erst nach dem Commit: Die Tabelle kann ihn auch im Layout auslösen (Ende des Bearbeitens), und
				// dort ist ein modaler Dialog verboten. Bis zur Antwort bleibt das Modell unverändert
				Platform.runLater(() -> applyNameRemoval(edited, newLastName, after));
				return;
			}
			applyLastName(edited, newLastName);
		});

		colFirstName.setCellFactory(ParsingTextFieldTreeTableCell.forTextColumn(BOOKING_ROW));
		colFirstName.setOnEditCommit(editEvent -> commit(editEvent.getRowValue(), Booking::getFirstName,
				booking -> booking.setFirstName(editEvent.getNewValue()), true));

		// Preis: der effektive Preis; der Standardpreis der Vorstellung als Eingabe entfernt den Einzelpreis
		colPrice.setCellFactory(ParsingTextFieldTreeTableCell.forTableColumn(PRICE_CONVERTER, InputIssue.NOT_A_NUMBER, "Preis", ROW_LABEL, null, BOOKING_ROW));
		// über SaleDates: Bei einer bezahlten Buchung ohne Betrag (älterer Stand) hält es den bisherigen Preis als Betrag fest
		colPrice.setOnEditCommit(editEvent -> commit(editEvent.getRowValue(), Booking::getEffectivePrice,
				booking -> SaleDates.change(booking, target -> target.setEffectivePrice(toCents(editEvent.getNewValue()))), false));

		// Gezahlter Betrag: nur bei bezahlten Buchungen bearbeitbar
		colPaidAmount.setCellFactory(ParsingTextFieldTreeTableCell.forTableColumn(PRICE_CONVERTER, InputIssue.NOT_A_NUMBER, "Bezahlt", ROW_LABEL,
				row -> row instanceof TicketRow.BookingRow booking && booking.dto().getBooking().isPaid(), BOOKING_ROW));
		colPaidAmount.setOnEditCommit(editEvent -> commit(editEvent.getRowValue(), Booking::getPaidCents,
				booking -> booking.setPaidAmount(toCents(editEvent.getNewValue())), false));

		colComment.setCellFactory(ParsingTextFieldTreeTableCell.forTextColumn(BOOKING_ROW));
		colComment.setOnEditCommit(editEvent -> commit(editEvent.getRowValue(), Booking::getComment,
				booking -> booking.setComment(editEvent.getNewValue()), true));

		// Enum / ComboBox
		colPaymentStatus.setOnEditCommit(editEvent -> commit(editEvent.getRowValue(), Booking::getPaymentStatus,
				booking -> SaleDates.change(booking, target -> target.setPaymentStatus(editEvent.getNewValue())), true));
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
		colPaymentStatus.setCellFactory(TicketCells.comboBox(converter, PaymentStatus.values()));

		// Checkbox "Abgeholt": die Buchung kommt über das TreeItem der Zeile, nicht über einen Listenindex
		colCollected.setCellFactory(TicketCells.checkBox(dto -> {
			SimpleBooleanProperty prop = new SimpleBooleanProperty(dto.getBooking().isCollected());
			prop.addListener((obs, oldVal, newVal) -> change(dto.getBooking(), Booking::isCollected,
					booking -> SaleDates.change(booking, target -> target.setCollected(newVal)), true));
			return prop;
		}));

		// Die Daten der Aktionen: Datumsspalten mit strengem Format; leere Eingabe löscht das Datum, ein unlesbarer Text in
		// einem leeren Feld trägt heute ein (INP-002 meldet es trotzdem)
		colReservedOn.setCellFactory(ParsingTextFieldTreeTableCell.forDateColumn("Reservierungsdatum", ROW_LABEL, LocalDate::now, BOOKING_ROW));
		colReservedOn.setOnEditCommit(editEvent -> commit(editEvent.getRowValue(), Booking::getReservationDate,
				booking -> booking.setReservationDate(editEvent.getNewValue()), false));
		colPaidOn.setCellFactory(ParsingTextFieldTreeTableCell.forDateColumn("Bezahldatum", ROW_LABEL, LocalDate::now, BOOKING_ROW));
		colPaidOn.setOnEditCommit(editEvent -> commit(editEvent.getRowValue(), Booking::getPaymentDate,
				booking -> booking.setPaymentDate(editEvent.getNewValue()), false));
		colCollectedOn.setCellFactory(ParsingTextFieldTreeTableCell.forDateColumn("Abholdatum", ROW_LABEL, LocalDate::now, BOOKING_ROW));
		colCollectedOn.setOnEditCommit(editEvent -> commit(editEvent.getRowValue(), Booking::getCollectionDate,
				booking -> booking.setCollectionDate(editEvent.getNewValue()), false));

		// Nur lesbar: Rollstuhlplatz (gehört zur Bestuhlung) und Reserviert (folgt aus dem Namen)
		colWheelchair.setCellFactory(TicketCells.checkBox(dto -> new SimpleBooleanProperty(dto.getBooking().isWheelchairAccessible())));
		colReserved.setCellFactory(TicketCells.checkBox(dto -> new SimpleBooleanProperty(dto.getBooking().isReserved())));
	}

	/** Übernimmt den Commit einer Zelle in die Buchung der Zeile ({@link #change}); eine Gruppenzeile lässt sich nicht bearbeiten. */
	private void commit(TreeItem<TicketRow> item, Function<Booking, Object> value, Consumer<Booking> change, boolean mayEmpty) {
		Booking booking = TicketCells.bookingOf(item);
		if (booking != null) {
			change(booking, value, change, mayEmpty);
		}
	}

	/**
	 * Ändert die Buchung und meldet das an {@link TicketEdits}; als selbst geändert gilt sie nur, wenn sich der Wert der
	 * Spalte dadurch geändert hat. {@code mayEmpty}: Die Änderung kann die Buchung leeren (Name, Kommentar, Zahlung,
	 * abgeholt), danach {@link #removeIfCancelledAfterCommit}.
	 */
	private void change(Booking booking, Function<Booking, Object> value, Consumer<Booking> change, boolean mayEmpty) {
		Object before = value.apply(booking);
		change.accept(booking);
		edits.changed(booking, !Objects.equals(before, value.apply(booking)));
		if (mayEmpty) {
			removeIfCancelledAfterCommit(booking);
		}
	}

	private void applyLastName(Booking booking, String newLastName) {
		change(booking, Booking::getLastName, target -> SaleDates.change(target, changed -> changed.setLastName(newLastName)), true);
	}

	/**
	 * Der Nachname wurde geleert, die Buchung trüge ohne ihn noch Daten: Fragt, ob sie storniert werden soll (KARV-121), und
	 * übernimmt erst dann. So schreibt "sofort speichern" den gelöschten Namen nicht vorher.
	 */
	private void applyNameRemoval(Booking booking, String newLastName, Booking remaining) {
		switch (NameRemovalDialog.ask(List.of(remaining), seatTable.getScene().getWindow())) {
		case ABORT -> edits.requestRebuild(); // die Zelle zeigt sonst den leeren Text, den sie beim Commit übernommen hat
		case CANCEL_BOOKINGS -> {
			// Bearbeitet nach Tab schon die nächste Zelle, endet das zuerst: Eine Buchung ohne Platz verschwindet sofort
			seatTable.edit(-1, null);
			BookingCancellation.cancel(List.of(booking));
			rebuild();
		}
		case NAME_ONLY -> applyLastName(booking, newLastName);
		}
	}

	/**
	 * Euro in Cent, gerundet und auf den int-Bereich begrenzt, damit auch ein riesiger Betrag als zu hoch (SEA-003) bzw.
	 * negativ (SEA-001) gemeldet wird.
	 */
	static int toCents(Double euros) {
		return MoneyFormat.toCentsOrZero(euros);
	}

	/** Die Zeilen der gewählten Vorstellung bzw. aller Vorstellungen des gewählten Events. */
	private List<BookingDTO> buildRows() {
		List<BookingDTO> rows = new ArrayList<>();
		for (Presentation presentation : shownPresentations()) {
			shownBookings(presentation).forEach(booking -> rows.add(new BookingDTO(presentation.getParent(), presentation, booking)));
		}
		return rows;
	}

	/** Die gezeigten Vorstellungen in ihrer Reihenfolge im Event. */
	private List<Presentation> shownPresentations() {
		if (selectedPres != null) {
			return List.of(selectedPres);
		}
		if (selectedEvent != null && selectedEvent.getPresentations() != null) {
			return List.copyOf(selectedEvent.getPresentations());
		}
		return List.of();
	}

	/**
	 * Die Buchungen der Vorstellung, die als Zeile erscheinen: erst die ohne Platz, dann nach Platz (A2 vor A10, Tische ohne
	 * Reihe vor A). Ein gesperrter Sitz fehlt ({@link LockedSeats}, KARV-111): Seine Buchung ist unlesbar, der Link
	 * "gesperrte Einträge" in der Fußzeile nennt ihn.
	 */
	static List<Booking> shownBookings(Presentation presentation) {
		if (presentation.getBookings() == null) {
			return List.of();
		}
		return presentation.getBookings().stream().filter(booking -> !LockedSeats.isLocked(booking)).sorted(TableLabel.BOOKING_ORDER)
				.toList();
	}

	/**
	 * Fremde Änderungen am gezeigten Event zeigen. Nie, solange eine Zelle im Bearbeiten ist (der Neuaufbau risse sie weg):
	 * Dann kommt er nach dem Ende der Bearbeitung nach ({@link TicketEdits}), und die zurückgestellten Einheiten der
	 * bearbeiteten Buchung folgen über das erneute Einarbeiten.
	 */
	private void refreshAfterForeignChange() {
		edits.requestRebuild();
	}

	/**
	 * Nach einer Änderung, die die Buchung leeren kann (Name, Kommentar, Zahlung, abgeholt): Ist sie ohne Platz und trägt
	 * dadurch keine Daten mehr, ist sie storniert. Sie verschwindet gleich aus Vorstellung, Tabelle und Zähler der Leiste
	 * ({@link BookingSync#removeIfCancelled}), nicht erst beim nächsten Laden. Erst nach dem laufenden Commit.
	 */
	private void removeIfCancelledAfterCommit(Booking booking) {
		cancelCandidates.add(booking);
		edits.requestRemoval();
	}

	/**
	 * Entfernt die zuletzt geänderten Buchungen, die dadurch storniert sind. {@link TicketEdits} ruft das erst, wenn keine
	 * Zelle mehr bearbeitet wird (Tab aus dem geleerten Namen) und keine Mausbedienung läuft: Sonst ginge eine Eingabe in
	 * eine Buchung, die es nicht mehr gibt, oder eine Zeile rückte unter den zweiten Klick eines Doppelklicks. Hat die
	 * Kassenkraft die Buchung inzwischen wieder gefüllt, prüft {@link BookingSync#removeIfCancelled} das selbst.
	 */
	private void removeCancelled() {
		boolean removed = false;
		for (Booking booking : List.copyOf(cancelCandidates)) {
			removed |= BookingSync.removeIfCancelled(booking);
		}
		cancelCandidates.clear();
		if (removed) {
			edits.requestRebuild(); // läuft gleich, und ein schon angeforderter Neuaufbau nicht noch einmal
		}
	}

	/**
	 * Baut die Tabelle nach dem Stand des Modells auf (Spec Abschnitt 11): Buchungen der gezeigten Vorstellungen, Suche und
	 * Spaltenfilter (selbst geänderte Buchungen bestehen beide), Gruppen und Sortierung. Bei gleicher Struktur zeichnet
	 * {@link TicketTreeTable#show} nur neu; die Auswahl bleibt bis auf Buchungen, die nicht mehr in der Tabelle stehen.
	 * Danach gehen die Treffer an den Verkaufszustand (KARV-192): alle Buchungen des Baums, auch in zugeklappten Gruppen.
	 */
	private void rebuild() {
		allRows = buildRows();
		unassignedCount = allRows.stream().filter(dto -> dto.getBooking().isUnassigned()).count();
		String query = searchQuery;
		List<BookingDTO> shown = edits.visible(allRows, dto -> BookingSearch.matches(dto.getBooking(), query) && columnFilter.passes(dto));
		treeTable.setGrouping(grouping, groupingNested);
		treeTable.show(TicketGrouping.group(shown, grouping, groupOrder, groupingNested, treeTable.sortComparator(), shownPresentations()));
		if (salesState != null) {
			salesState.showSearchHits(this, treeTable.allBookings());
		}
		updateUnassignedBar();
		// Erscheint oder verschwindet durch neue Daten der senkrechte Scroll-Balken, rechnet die Tabelle die Spaltenköpfe
		// mit der alten Breite, die Zeilen aber ohne den Balken: Köpfe und Spalten sitzen bis zum nächsten Layout (z. B. dem
		// ersten Scrollen) versetzt. Ein zweiter Layout-Durchgang nach dem Einfügen behebt das.
		Platform.runLater(seatTable::requestLayout);
	}

	private void applyEditMode() {
		closeDialog();
		seatTable.setEditable(editMode);

		// Nicht editierbare Spalten
		colTree.setEditable(false);
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
		colPaidAmount.setEditable(editMode);
		colPaymentStatus.setEditable(editMode);
		colCollected.setEditable(editMode);
		colReservedOn.setEditable(editMode);
		colPaidOn.setEditable(editMode);
		colCollectedOn.setEditable(editMode);
		colComment.setEditable(editMode);
		updateUnassignedBar();
	}

	/**
	 * Meldet die Auswahl der Tabelle (die Menge der Buchungen, nur nach abgeschlossenen Schritten) als ausgewählte Buchungen,
	 * auch ohne Bearbeitungsmodus, und die gezeigten Buchungen als Treffer. Die Auswahl nur, solange die Tabelle zu sehen
	 * ist (Spec Abschnitt 14.5): Nach dem Wechsel zur Saalansicht laufen nachgeschobene Aufgaben dieses Controllers noch
	 * (etwa das Entfernen stornierter Buchungen) und dürfen die Auswahl dort nicht ändern. Beim Anmelden meldet er nichts
	 * von selbst; die Auswahl kommt über {@link #restoreSelection}. Die Treffer prüft {@link SalesViewState} selbst nach der
	 * aktiven Ansicht.
	 */
	@Override
	public void setSalesState(SalesViewState state) {
		boolean first = salesState == null;
		salesState = state;
		if (first) {
			ChangeListener<List<Booking>> reportSelection = (obs, before, now) -> salesState.replaceSelection(now);
			LiveViews.whileShown(seatTable, () -> treeTable.selectedBookingsProperty().addListener(reportSelection),
					() -> treeTable.selectedBookingsProperty().removeListener(reportSelection));
		}
		state.showSearchHits(this, treeTable.allBookings());
	}

	/** Wählt die Buchungen, die die Tabelle zeigt (nach dem Wechsel von der Saalansicht). */
	@Override
	public void restoreSelection(Collection<Booking> bookings) {
		treeTable.restoreSelection(bookings);
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

	/** Neu laden für ein anderes (oder neu gesetztes) Event bzw. eine Vorstellung: Auswahl und Ausnahmen beginnen leer. */
	private void loadBookings() {
		treeTable.clearSelection();
		edits.endPinning();
		rebuild();
	}

	@Override
	public void save() {
		// Aktuellen Stand speichern
		if (selectedEvent != null) {
			EventSaver.save(selectedEvent);
		}
	}

	/**
	 * Vorname, Nachname und Kommentar zusammen, jedes Suchwort muss vorkommen ({@link BookingSearch}). Ein geänderter
	 * Suchtext beendet die Ausnahme für selbst geänderte Buchungen.
	 */
	@Override
	public void filter(String query) {
		String next = query == null ? "" : query;
		if (next.equals(searchQuery)) {
			return;
		}
		searchQuery = next;
		edits.endPinning();
		edits.requestRebuild();
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}
