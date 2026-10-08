package de.eltviller_carneval_verein.karten.ui.event;

import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.AbstractOverviewController;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
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
				return new SimpleStringProperty("Event: " + event.getName());
			if (data instanceof Presentation presentation)
				return new SimpleStringProperty("Vorstellung: " + presentation.getName());
			if (data instanceof Table table)
				return new SimpleStringProperty("Tisch " + table.getTableNumber());
			if (data instanceof Seat seat)
				return new SimpleStringProperty("Sitz " + seat.getSeatNumber());
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

		// 3. Spalte: Anzahl der Tische
		colTableCount.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Event event) {
				int count = event.getTables() != null ? event.getTables().size() : 0;
				return new SimpleIntegerProperty(count).asObject();
			}
			if (data instanceof Presentation presentation) {
				int count = presentation.getTables() != null ? presentation.getTables().size() : 0;
				return new SimpleIntegerProperty(count).asObject();
			}
			return null;
		});

		// 4. Spalte: Anzahl der Stühle
		colSeatCount.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Event event) {
				int count = event.getSeats() != null ? event.getSeats().size() : 0;
				long countReserved = event.getSeats().stream().filter(Seat::isReserved).count();
				long countPaid = event.getSeats().stream().filter(Seat::isPaid).count();
				return new SimpleStringProperty(countPaid + " / " + countReserved + " / " + count);
			}
			if (data instanceof Presentation presentation) {
				int count = presentation.getSeats() != null ? presentation.getSeats().size() : 0;
				long countReserved = presentation.getSeats().stream().filter(Seat::isReserved).count();
				long countPaid = presentation.getSeats().stream().filter(Seat::isPaid).count();
				return new SimpleStringProperty(countPaid + " / " + countReserved + " / " + count);
			}
			if (data instanceof Table table) {
				int count = table.getSeats() != null ? table.getSeats().size() : 0;
				long countReserved = table.getSeats().stream().filter(Seat::isReserved).count();
				long countPaid = table.getSeats().stream().filter(Seat::isPaid).count();
				return new SimpleStringProperty(countPaid + " / " + countReserved + " / " + count);
			}
			if (data instanceof Seat seat) {
				String paymentStatus = switch (seat.getPaymentStatus()) {
				case CASH -> "Bezahlt (Bar)";
				case CARD -> "Bezahlt (Karte)";
				case TRANSFER -> "Bezahlt (Überweisung)";
				case NONE -> seat.isReserved() ? "Reserviert" : "Frei";
				};
				paymentStatus += seat.isCollected() == true ? " und abgeholt" : "";
				return new SimpleStringProperty(paymentStatus);
			}
			return null;
		});

		// 5. Spalte: aktuelle Einnahmen, erwatete Einnahmen, potenzielle Einnahmen
		// Rechnet in Cent (wie CashReconciliationController), damit hier nie eine andere
		// Zahl herauskommt als in der Bilanz für dieselben Daten.
		colRevenue.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Event event) {
				return new SimpleStringProperty(formatRevenue(event.getSeats()));
			}
			if (data instanceof Presentation presentation) {
				return new SimpleStringProperty(formatRevenue(presentation.getSeats()));
			}
			if (data instanceof Table table) {
				return new SimpleStringProperty(formatRevenue(table.getSeats()));
			}
			if (data instanceof Seat seat) {
				return new SimpleStringProperty(formatRevenue(List.of(seat)));
			}
			return null;
		});

		// 6. Spalte: Hallenname
		colHallName.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Presentation presentation) {
				return new SimpleStringProperty(hallRepository.findById(presentation.getHallId()).getName());
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

	/**
	 * "Einnahmen (bezahlt) / erwartet (reserviert) / potenziell (alle Sitze)" - einheitlich
	 * in Cent aufsummiert und über MoneyFormat formatiert (siehe colRevenue oben).
	 */
	private String formatRevenue(List<Seat> seats) {
		long potentialCents = seats.stream().mapToLong(Seat::getPrice).sum();
		long expectedCents = seats.stream().filter(Seat::isReserved).mapToLong(Seat::getPrice).sum();
		long revenueCents = seats.stream().filter(Seat::isPaid).mapToLong(Seat::getPrice).sum();
		return MoneyFormat.formatCents(revenueCents) + " / " + MoneyFormat.formatCents(expectedCents) + " / " + MoneyFormat.formatCents(potentialCents);
	}

	@Override
	protected String searchText(Object data) {
		if (data instanceof Event event)
			return event.getName() + " " + event.getDescription();
		if (data instanceof Presentation presentation)
			return presentation.getName() + " " + presentation.getDescription();
		if (data instanceof Table table)
			return "Tisch " + table.getTableNumber();
		if (data instanceof Seat seat)
			return "Sitz " + seat.getSeatNumber() + " " + seat.getFirstName() + " " + seat.getLastName() + " " + seat.getComment();
		return null;
	}

	@Override
	protected ContextMenu createContextMenu(TreeTableRow<Object> tableRow) {
		ContextMenu contextMenu = new ContextMenu();
		SeparatorMenuItem editDeleteSeparator = new SeparatorMenuItem();
		SeparatorMenuItem deleteDetailsSeparator = new SeparatorMenuItem();

		MenuItem addPresItem = new MenuItem("+ Vorstellung hinzufügen");
		addPresItem.setOnAction(e -> {
			TreeItem<Object> selectedItem = selectedItem();
			if (selectedItem != null) {
				Event event = findParentInTree(selectedItem, Event.class);
				Presentation pres = event.addPresentation();
				MainApp.showEventEditView(event, pres, null, null, true);
			}
		});

		MenuItem addTableItem = new MenuItem("+ Tisch hinzufügen");
		addTableItem.setOnAction(e -> {
			TreeItem<Object> selectedItem = selectedItem();
			if (selectedItem != null) {
				Event event = findParentInTree(selectedItem, Event.class);
				Presentation pres = findParentInTree(selectedItem, Presentation.class);
				Table table = pres.addTable();
				MainApp.showEventEditView(event, pres, table, null, true);
			}
		});

		MenuItem addSeatItem = new MenuItem("+ Sitz hinzufügen");
		addSeatItem.setOnAction(e -> {
			TreeItem<Object> selectedItem = selectedItem();
			if (selectedItem != null) {
				Event event = findParentInTree(selectedItem, Event.class);
				Presentation pres = findParentInTree(selectedItem, Presentation.class);
				Table table = findParentInTree(selectedItem, Table.class);
				Seat seat = table.addSeat();
				MainApp.showEventEditView(event, pres, table, seat, true);
			}
		});

		MenuItem editItem = new MenuItem("Bearbeiten");
		editItem.setOnAction(e -> openEditView(true));

		MenuItem seeDetails = new MenuItem("Details");
		seeDetails.setOnAction(e -> openEditView(false));

		MenuItem deleteItem = new MenuItem("Löschen");
		deleteItem.setOnAction(e -> handleDelete());

		contextMenu.getItems().addAll(addPresItem, addTableItem, addSeatItem, editItem, editDeleteSeparator, deleteItem, deleteDetailsSeparator, seeDetails);

		contextMenu.setOnShowing(e -> {
			Object data = tableRow.getItem();
			addPresItem.setVisible(data instanceof Event || data instanceof Presentation || data instanceof Table || data instanceof Seat);
			addTableItem.setVisible(data instanceof Presentation || data instanceof Table || data instanceof Seat);
			addSeatItem.setVisible(data instanceof Table || data instanceof Seat);
			editItem.setVisible(data != null);
			seeDetails.setVisible(data != null);
			deleteItem.setVisible(data != null);
			editDeleteSeparator.setVisible(data != null);
			deleteDetailsSeparator.setVisible(data != null);
		});
		return contextMenu;
	}

	private void openEditView(boolean editable) {
		TreeItem<Object> selectedItem = selectedItem();
		if (selectedItem != null) {
			Event event = findParentInTree(selectedItem, Event.class);
			Presentation pres = findParentInTree(selectedItem, Presentation.class);
			Table table = findParentInTree(selectedItem, Table.class);
			Seat seat = findParentInTree(selectedItem, Seat.class);
			MainApp.showEventEditView(event, pres, table, seat, editable);
		}
	}

	/** Löscht den gewählten Eintrag nach Rückfrage und speichert das betroffene Event sofort. */
	private void handleDelete() {
		TreeItem<Object> selectedItem = selectedItem();
		if (selectedItem == null || selectedItem.getParent() == null) {
			return;
		}
		Object data = selectedItem.getValue();
		Event event = findParentInTree(selectedItem, Event.class);
		Presentation pres = findParentInTree(selectedItem, Presentation.class);
		Table table = findParentInTree(selectedItem, Table.class);

		if (data instanceof Event) {
			if (confirmDelete("Event löschen", "Soll das Event \"" + event.getName() + "\" wirklich unwiderruflich gelöscht werden?")) {
				eventRepository.deleteEvent(event);
			} else {
				return;
			}
		} else if (data instanceof Presentation) {
			if (confirmDelete("Vorstellung löschen", "Soll die Vorstellung \"" + pres.getName() + "\" wirklich unwiderruflich gelöscht werden?")) {
				event.deletePresentation(pres);
				eventRepository.saveEvent(event);
			} else {
				return;
			}
		} else if (data instanceof Table) {
			if (confirmDelete("Tisch löschen", "Soll Tisch " + table.getTableNumber() + " wirklich unwiderruflich gelöscht werden?")) {
				pres.deleteTable(table);
				eventRepository.saveEvent(event);
			} else {
				return;
			}
		} else if (data instanceof Seat seat) {
			if (confirmDelete("Sitz löschen", "Soll Sitz " + seat.getSeatNumber() + " wirklich unwiderruflich gelöscht werden?")) {
				table.deleteSeat(seat);
				eventRepository.saveEvent(event);
			} else {
				return;
			}
		}
		reload();
	}

	@Override
	protected TreeItem<Object> buildTree() {
		TreeItem<Object> dummyRoot = new TreeItem<>("Root");
		List<Event> events = eventRepository.loadEvents();

		for (Event event : events) {
			TreeItem<Object> eventNode = new TreeItem<>(event);

			if (event.getPresentations() != null) {
				for (Presentation pres : event.getPresentations()) {
					TreeItem<Object> presNode = new TreeItem<>(pres);

					if (pres.getTables() != null) {
						for (Table table : pres.getTables()) {
							TreeItem<Object> tableNode = new TreeItem<>(table);

							if (table.getSeats() != null) {
								for (Seat seat : table.getSeats()) {
									tableNode.getChildren().add(new TreeItem<>(seat));
								}
							}
							presNode.getChildren().add(tableNode);
						}
					}
					eventNode.getChildren().add(presNode);
				}
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
