package de.eltviller_carneval_verein.karten.ui.event;

import java.util.ArrayList;
import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.AbstractOverviewController;
import de.eltviller_carneval_verein.karten.ui.BookingSyncService;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog;
import de.eltviller_carneval_verein.karten.ui.backup.EventBackupCatalog;
import de.eltviller_carneval_verein.karten.ui.event.BookingTree.TableGroup;
import de.eltviller_carneval_verein.karten.ui.event.BookingTree.UnassignedGroup;
import de.eltviller_carneval_verein.karten.util.BookingTotals;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.cell.CheckBoxTreeTableCell;

/**
 * Die Event-Übersicht als Baum. Knoten: {@link Event}, {@link Presentation}, {@link TableGroup} (ein Tisch aus den
 * Buchungen), {@link UnassignedGroup} ("Ohne Platz") und {@link Booking}. Tische und Sitze entstehen aus den
 * Buchungen der Vorstellung ({@link BookingTree}), nicht aus der Bestuhlung; angelegt werden sie im Hallen-Editor.
 */
public class EventOverviewController extends AbstractOverviewController {

	private final JsonEventRepository eventRepository = JsonEventRepository.getInstance();
	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();

	// TableView und Spalten
	@FXML
	private TreeTableColumn<Object, String> colName;
	@FXML
	private TreeTableColumn<Object, Integer> colPresCount;
	@FXML
	private TreeTableColumn<Object, Integer> colTableCount;
	@FXML
	private TreeTableColumn<Object, String> colSeatCount;
	@FXML
	private TreeTableColumn<Object, String> colRevenue;
	@FXML
	private TreeTableColumn<Object, String> colHallName;
	@FXML
	private TreeTableColumn<Object, Boolean> colArchive;

	@Override
	protected void setupColumns() {
		// 1. Spalte: Name / Bezeichnung je nach Ebene
		colName.setCellValueFactory(param -> {
			Object data = param.getValue().getValue();
			if (data instanceof Event event)
				return new SimpleStringProperty("Event: " + EntityLabels.nameOrPlaceholder(event.getName()));
			if (data instanceof Presentation presentation)
				return new SimpleStringProperty("Vorstellung: " + EntityLabels.nameOrPlaceholder(presentation.getName()));
			if (data instanceof TableGroup group)
				return new SimpleStringProperty(group.label());
			if (data instanceof UnassignedGroup group)
				return new SimpleStringProperty(group.label());
			if (data instanceof Booking booking)
				return new SimpleStringProperty(BookingTree.bookingLabel(booking));
			return new SimpleStringProperty("");
		});

		// 2. Spalte: Anzahl der Vorstellungen
		colPresCount.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Event event) {
				int count = event.getPresentations() != null ? event.getPresentations().size() : 0;
				return new SimpleIntegerProperty(count).asObject();
			}
			return null;
		});

		// 3. Spalte: Anzahl der Tische (Tische mit Platz, "Ohne Platz" zählt nicht als Tisch)
		colTableCount.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Event event) {
				int count = presentationsOf(event).stream().mapToInt(presentation -> BookingTree.groups(presentation).size()).sum();
				return new SimpleIntegerProperty(count).asObject();
			}
			if (data instanceof Presentation presentation) {
				return new SimpleIntegerProperty(BookingTree.groups(presentation).size()).asObject();
			}
			return null;
		});

		// 4. Spalte: Anzahl der Stühle (bezahlt / reserviert / gesamt), bei einer Buchung ihr Status
		colSeatCount.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Booking booking) {
				return new SimpleStringProperty(BookingTree.statusText(booking));
			}
			List<Booking> bookings = bookingsOf(data);
			return bookings == null ? null : new SimpleStringProperty(BookingTotals.of(bookings).countText());
		});

		// 5. Spalte: aktuelle Einnahmen, erwartete Einnahmen, potenzielle Einnahmen
		// Rechnet in Cent mit dem geltenden Preis jeder Buchung (BookingTotals, dieselbe Quelle wie Statistik und
		// Kassenabgleich), damit hier nie eine andere Zahl herauskommt als dort für dieselben Daten.
		colRevenue.setCellValueFactory(cell -> {
			List<Booking> bookings = bookingsOf(cell.getValue().getValue());
			return bookings == null ? null : new SimpleStringProperty(BookingTotals.of(bookings).revenueText());
		});

		// 6. Spalte: Hallenname
		colHallName.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Presentation presentation) {
				return new SimpleStringProperty(EntityLabels.hallDisplayName(hallRepository.findById(presentation.getHallId())));
			}
			return null;
		});

		// 7. Spalte: Archiviert
		colArchive.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Event event) {
				boolean archived = event.isArchived();
				return new SimpleBooleanProperty(archived).asObject();
			}
			return null;
		});
		// CellFactory für die grafische Checkbox
		colArchive.setCellFactory(CheckBoxTreeTableCell.forTreeTableColumn(colArchive));

	}

	/** Die Vorstellungen des Events; eine fehlende Liste zählt als leer. */
	private static List<Presentation> presentationsOf(Event event) {
		return event.getPresentations() != null ? event.getPresentations() : List.of();
	}

	/**
	 * Die Buchungen, über die ein Knoten Zahlen zeigt: Event, Vorstellung, Tisch, "Ohne Platz" oder eine einzelne
	 * Buchung; {@code null} für alles andere (Wurzel).
	 */
	private static List<Booking> bookingsOf(Object data) {
		if (data instanceof Event event) {
			List<Booking> bookings = new ArrayList<>();
			for (Presentation presentation : presentationsOf(event)) {
				bookings.addAll(bookingsOf(presentation));
			}
			return bookings;
		}
		if (data instanceof Presentation presentation)
			return presentation.getBookings() != null ? presentation.getBookings() : List.of();
		if (data instanceof TableGroup group)
			return group.bookings();
		if (data instanceof UnassignedGroup group)
			return group.bookings();
		if (data instanceof Booking booking)
			return List.of(booking);
		return null;
	}

	@Override
	protected String searchText(Object data) {
		if (data instanceof Event event)
			return joinNonNull(event.getName(), event.getDescription());
		if (data instanceof Presentation presentation)
			return joinNonNull(presentation.getName(), presentation.getDescription());
		if (data instanceof TableGroup group)
			return group.label();
		if (data instanceof UnassignedGroup group)
			return group.label();
		if (data instanceof Booking booking)
			return BookingTree.bookingSearchText(booking);
		return null;
	}

	/** Die vorhandenen Teile mit Leerzeichen verbunden; ein fehlender Teil wird nie als "null" gesucht. */
	private static String joinNonNull(String... parts) {
		StringBuilder text = new StringBuilder();
		for (String part : parts) {
			if (part != null) {
				text.append(text.length() > 0 ? " " : "").append(part);
			}
		}
		return text.toString();
	}

	@Override
	protected ContextMenu createContextMenu(TreeTableRow<Object> tableRow) {
		ContextMenu contextMenu = new ContextMenu();
		SeparatorMenuItem editDeleteSeparator = new SeparatorMenuItem();
		SeparatorMenuItem deleteDetailsSeparator = new SeparatorMenuItem();

		MenuItem addPresItem = new MenuItem("+ Vorstellung hinzufügen");
		addPresItem.setOnAction(e -> addPresentation());

		MenuItem templateItem = new MenuItem("+ Als Vorlage verwenden");
		templateItem.setOnAction(e -> useAsTemplate());

		MenuItem editItem = new MenuItem("Bearbeiten");
		editItem.setOnAction(e -> openEditView(true));

		MenuItem seeDetails = new MenuItem("Details");
		seeDetails.setOnAction(e -> openEditView(false));

		MenuItem deleteItem = new MenuItem("Löschen");
		deleteItem.setOnAction(e -> handleDelete());

		MenuItem hallEditorItem = new MenuItem("Im Hallen-Editor öffnen");
		hallEditorItem.setOnAction(e -> openHallEditor());

		contextMenu.getItems().addAll(addPresItem, templateItem, editItem, editDeleteSeparator, deleteItem, deleteDetailsSeparator, seeDetails, hallEditorItem);

		contextMenu.setOnShowing(e -> {
			Object data = tableRow.getItem();
			boolean deletable = data instanceof Event || data instanceof Presentation;
			boolean belongsToPresentation = data instanceof Presentation || data instanceof TableGroup || data instanceof UnassignedGroup || data instanceof Booking;
			addPresItem.setVisible(data instanceof Event || belongsToPresentation);
			// Als Vorlage dienen nur Events und Vorstellungen (Tische und Buchungen stammen aus der Bestuhlung)
			templateItem.setVisible(deletable);
			editItem.setVisible(data != null);
			seeDetails.setVisible(data != null);
			// Tische und Sitze stammen aus den Buchungen und werden nicht einzeln gelöscht
			deleteItem.setVisible(deletable);
			editDeleteSeparator.setVisible(deletable);
			deleteDetailsSeparator.setVisible(deletable);
			// Der Hallen-Editor braucht eine Halle, die es noch gibt
			hallEditorItem.setVisible(belongsToPresentation);
			hallEditorItem.setDisable(!belongsToPresentation || hallOf(tableRow.getTreeItem()) == null);
		});
		return contextMenu;
	}

	/**
	 * Legt im Event eine neue Vorstellung an und öffnet sie im Detail. Sie ist noch nicht gespeichert: Das Event gilt als
	 * geändert ("Speichern *"), ohne dass beim Wechsel ins Detail nachgefragt wird.
	 */
	private void addPresentation() {
		TreeItem<Object> selectedItem = selectedItem();
		Event event = selectedItem == null ? null : findParentInTree(selectedItem, Event.class);
		if (event == null) {
			return;
		}
		ChangeTracker.events().ensureTracked(event); // Baseline ist der gespeicherte Stand vor dem Hinzufügen
		Presentation presentation = event.addPresentation();
		ChangeTracker.events().check(event);
		MainApp.showEventEditViewWithChanges(event, presentation, true);
	}

	/**
	 * "Als Vorlage verwenden": Eine Vorstellung wird im selben Event kopiert, ein Event samt Vorstellungen als neues Event.
	 * Das Neue ist noch nicht gespeichert und öffnet sich im Detail, wie bei "Vorstellung hinzufügen".
	 */
	private void useAsTemplate() {
		TreeItem<Object> selectedItem = selectedItem();
		Object data = selectedItem == null ? null : selectedItem.getValue();
		if (data instanceof Presentation presentation) {
			Event event = presentation.getParent();
			ChangeTracker.events().ensureTracked(event);
			Presentation copy = EventTemplates.copyPresentation(presentation, event, hallRepository::findById);
			ChangeTracker.events().check(event);
			MainApp.showEventEditViewWithChanges(event, copy, true);
		} else if (data instanceof Event event) {
			Event copy = EventTemplates.copyEvent(event, eventRepository.loadEvents().stream().map(Event::getName).toList(), hallRepository::findById);
			ChangeTracker.events().trackNew(copy);
			MainApp.showEventEditViewWithChanges(copy, presentationsOf(copy).isEmpty() ? null : presentationsOf(copy).get(0), true);
		}
	}

	private void openEditView(boolean editable) {
		TreeItem<Object> selectedItem = selectedItem();
		if (selectedItem != null) {
			Event event = findParentInTree(selectedItem, Event.class);
			// Bei einem Tisch- oder Buchungsknoten öffnet die Bearbeitung die Vorstellung (Tische und Sitze stammen aus der Halle)
			Presentation pres = findParentInTree(selectedItem, Presentation.class);
			MainApp.showEventEditView(event, pres, editable);
		}
	}

	/** Die Halle der Vorstellung, zu der der Knoten gehört; {@code null} ohne Vorstellung, ohne Halle oder wenn sie gelöscht wurde. */
	private Hall hallOf(TreeItem<Object> item) {
		Presentation pres = item == null ? null : findParentInTree(item, Presentation.class);
		return pres == null || pres.getHallId() == null ? null : hallRepository.findById(pres.getHallId());
	}

	/** Öffnet die Halle der gewählten Vorstellung im Hallen-Editor (nur Anzeige). */
	private void openHallEditor() {
		Hall hall = hallOf(selectedItem());
		if (hall != null) {
			MainApp.showHallEditView(hall, null, false);
		}
	}

	@Override
	protected BackupCatalog backupCatalog() {
		return EventBackupCatalog.create();
	}

	/** Löscht den gewählten Eintrag (Event oder Vorstellung) nach Rückfrage und speichert das betroffene Event sofort. */
	private void handleDelete() {
		TreeItem<Object> selectedItem = selectedItem();
		if (selectedItem == null || selectedItem.getParent() == null) {
			return;
		}
		Object data = selectedItem.getValue();
		Event event = findParentInTree(selectedItem, Event.class);
		Presentation pres = findParentInTree(selectedItem, Presentation.class);

		if (data instanceof Event) {
			if (confirmDelete("Event löschen", "Soll das Event \"" + event.getName() + "\" wirklich unwiderruflich gelöscht werden?")) {
				eventRepository.deleteEvent(event);
			} else {
				return;
			}
		} else if (data instanceof Presentation) {
			if (confirmDelete("Vorstellung löschen", "Soll die Vorstellung \"" + pres.getName() + "\" wirklich unwiderruflich gelöscht werden?")) {
				event.deletePresentation(pres);
				EventSaver.saveStructureChange(event);
			} else {
				return;
			}
		} else {
			return;
		}
		reload();
	}

	@Override
	protected TreeItem<Object> buildTree() {
		TreeItem<Object> dummyRoot = new TreeItem<>("Root");
		List<Event> events = eventRepository.loadEvents();

		for (Event event : events) {
			// Buchungen mit den Bestuhlungen abgleichen (keine Änderung des Nutzers), damit "Ohne Platz" und die Zahlen
			// stimmen, auch wenn das Event in dieser Sitzung noch nirgends geöffnet wurde
			BookingSyncService.prepare(event);
			TreeItem<Object> eventNode = new TreeItem<>(event);

			for (Presentation pres : presentationsOf(event)) {
				TreeItem<Object> presNode = new TreeItem<>(pres);

				for (TableGroup group : BookingTree.groups(pres)) {
					TreeItem<Object> tableNode = new TreeItem<>(group);
					group.bookings().forEach(booking -> tableNode.getChildren().add(new TreeItem<>(booking)));
					presNode.getChildren().add(tableNode);
				}

				List<Booking> unassigned = BookingTree.unassigned(pres);
				if (!unassigned.isEmpty()) {
					UnassignedGroup group = new UnassignedGroup(pres, unassigned);
					TreeItem<Object> unassignedNode = new TreeItem<>(group);
					unassigned.forEach(booking -> unassignedNode.getChildren().add(new TreeItem<>(booking)));
					presNode.getChildren().add(unassignedNode);
				}
				eventNode.getChildren().add(presNode);
			}
			dummyRoot.getChildren().add(eventNode);
		}

		return dummyRoot;
	}

	@FXML
	private void handleCreateNewEvent() {
		MainApp.showEventCreateView();
	}
}
