package de.eltviller_carneval_verein.karten.ui;

import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.cell.CheckBoxTreeTableCell;

public class EventOverviewController {

	private final JsonTicketRepository repository = new JsonTicketRepository();

	@FXML
	private TextField searchField;
	@FXML
	private Button btnToggleEdit = new Button();
	@FXML
	private Button btnSave = new Button();

	// TableView und Spalten
	@FXML
	private TreeTableView<Object> treeTableView;
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
	private TreeTableColumn<Object, Boolean> colArchive;

	@FXML
	public void initialize() {
		setupColumns();
		setupContextMenu();
		loadEventTree();
	}

	private void setupColumns() {
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
				long countReserved = event.getSeats().stream().filter(Seat::isReserved) != null ? event.getSeats().stream().filter(Seat::isReserved).count() : 0;
				long countPaid = event.getSeats().stream().filter(Seat::isPaid) != null ? event.getSeats().stream().filter(Seat::isPaid).count() : 0;
				return new SimpleStringProperty(countPaid + " / " + countReserved + " / " + count);
			}
			if (data instanceof Presentation presentation) {
				int count = presentation.getSeats() != null ? presentation.getSeats().size() : 0;
				long countReserved = presentation.getSeats().stream().filter(Seat::isReserved) != null ? presentation.getSeats().stream().filter(Seat::isReserved).count() : 0;
				long countPaid = presentation.getSeats().stream().filter(Seat::isPaid) != null ? presentation.getSeats().stream().filter(Seat::isPaid).count() : 0;
				return new SimpleStringProperty(countPaid + " / " + countReserved + " / " + count);
			}
			if (data instanceof Table table) {
				int count = table.getSeats() != null ? table.getSeats().size() : 0;
				long countReserved = table.getSeats().stream().filter(Seat::isReserved) != null ? table.getSeats().stream().filter(Seat::isReserved).count() : 0;
				long countPaid = table.getSeats().stream().filter(Seat::isPaid) != null ? table.getSeats().stream().filter(Seat::isPaid).count() : 0;
				return new SimpleStringProperty(countPaid + " / " + countReserved + " / " + count);
			}
			if (data instanceof Seat seat) {
				String value = seat.isPaid() == true ? "Bezahlt" : seat.isReserved() == true ? "Reserviert" : "Frei";
				value += seat.isCollected() == true ? " und abgeholt" : "";
				return new SimpleStringProperty(value);
			}
			return null;
		});

		// 5. Spalte: aktuelle Einnahmen, erwatete Einnahmen, potenzielle Einnahmen
		colRevenue.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			Double revenue = 0.0;
			Double expected = 0.0;
			Double potential = 0.0;
			if (data instanceof Event event) {
				double sum = event.getSeats().stream().mapToDouble(Seat::getPriceDouble).sum();
				potential += sum;
				sum = 0.0;
				sum = event.getSeats().stream().filter(Seat::isReserved).mapToDouble(Seat::getPriceDouble).sum();
				expected += sum;
				sum = 0.0;
				sum = event.getSeats().stream().filter(Seat::isPaid).mapToDouble(Seat::getPriceDouble).sum();
				revenue += sum;
				return new SimpleStringProperty(revenue + "€ / " + expected + "€ / " + potential + "€");
			}
			if (data instanceof Presentation presentation) {
				double sum = presentation.getSeats().stream().mapToDouble(Seat::getPriceDouble).sum();
				potential += sum;
				sum = 0.0;
				sum = presentation.getSeats().stream().filter(Seat::isReserved).mapToDouble(Seat::getPriceDouble).sum();
				expected += sum;
				sum = 0.0;
				sum = presentation.getSeats().stream().filter(Seat::isPaid).mapToDouble(Seat::getPriceDouble).sum();
				revenue += sum;
				return new SimpleStringProperty(revenue + "€ / " + expected + "€ / " + potential + "€");
			}
			if (data instanceof Table table) {
				double sum = table.getSeats().stream().mapToDouble(Seat::getPriceDouble).sum();
				potential += sum;
				sum = 0.0;
				sum = table.getSeats().stream().filter(Seat::isReserved).mapToDouble(Seat::getPriceDouble).sum();
				expected += sum;
				sum = 0.0;
				sum = table.getSeats().stream().filter(Seat::isPaid).mapToDouble(Seat::getPriceDouble).sum();
				revenue += sum;
				return new SimpleStringProperty(revenue + "€ / " + expected + "€ / " + potential + "€");
			}
			if (data instanceof Seat seat) {
				potential = seat.getPriceDouble();
				expected = (seat.isReserved() == true ? seat.getPriceDouble() : 0.0);
				revenue = (seat.isPaid() == true ? seat.getPriceDouble() : 0.0);
				return new SimpleStringProperty(revenue + "€ / " + expected + "€ / " + potential + "€");
			}
			return null;
		});

		// 6. Spalte: Archiviert
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

	private void setupContextMenu() {
		treeTableView.setRowFactory(ttv -> {
			TreeTableRow<Object> tableRow = new TreeTableRow<>();
			ContextMenu contextMenu = new ContextMenu();

			MenuItem addPresItem = new MenuItem("+ Vorstellung hinzufügen");
			addPresItem.setOnAction(e -> {
				Object item = tableRow.getItem();
				if (item instanceof Event event) {
					// Logik: neue Vorstellung zu Event hinzufügen
				}
			});

			MenuItem addTableItem = new MenuItem("+ Tisch hinzufügen");
			addTableItem.setOnAction(e -> {
				Object item = tableRow.getItem();
				if (item instanceof Event event) {
					// Logik: neuen Tisch zu Vorstellung hinzufügen
				}
			});

			MenuItem addSeatItem = new MenuItem("+ Sitz hinzufügen");
			addSeatItem.setOnAction(e -> {
				Object item = tableRow.getItem();
				if (item instanceof Event event) {
					// Logik: neue Sitz zu Tisch hinzufügen
				}
			});

			MenuItem deleteItem = new MenuItem("Löschen");
			deleteItem.setOnAction(e -> {
				TreeItem<Object> selectedItem = treeTableView.getSelectionModel().getSelectedItem();
				if (selectedItem != null && selectedItem.getParent() != null) {
					selectedItem.getParent().getChildren().remove(selectedItem);
				}
			});

			// Menü-Einträge zusammenstellen
			contextMenu.getItems().addAll(addPresItem, addTableItem, addSeatItem, deleteItem);

			contextMenu.setOnShowing(e -> {
				Object data = tableRow.getItem();
				addPresItem.setVisible(data instanceof Event || data instanceof Presentation || data instanceof Table || data instanceof Seat);
				addTableItem.setVisible(data instanceof Presentation || data instanceof Table || data instanceof Seat);
				addSeatItem.setVisible(data instanceof Table || data instanceof Seat);
				deleteItem.setVisible(data != null);
			});

			// Event-Listener: Menü nur anzeigen, wenn die Zeile nicht leer ist
			tableRow.emptyProperty().addListener((obs, wasEmpty, isEmpty) -> {
				if (isEmpty) {
					tableRow.setContextMenu(null);
				} else {
					tableRow.setContextMenu(contextMenu);
				}
			});

			return tableRow;
		});

	}

	private void loadEventTree() {
		TreeItem<Object> dummyRoot = new TreeItem<>("Root");
		List<Event> events = repository.loadEvents();

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

		treeTableView.setRoot(dummyRoot);
	}

	@FXML
	private void handleBackToMenu() {
		MainApp.showMenuView();
	}

	@FXML
	private void handleCreateNewEvent() {
		MainApp.showEventCreateView();
	}

	private void showAlert(String title, String content) {
		Alert alert = new Alert(Alert.AlertType.WARNING);
		alert.setTitle(title);
		alert.setHeaderText(null);
		alert.setContentText(content);
		alert.showAndWait();
	}
}