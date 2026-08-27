package de.eltviller_carneval_verein.karten.ui;

import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;

public class EventOverviewController {

	private final JsonTicketRepository repository = new JsonTicketRepository();

	@FXML
	private TextField searchField;
	@FXML
	private Button btnToggleEdit = new Button();
	@FXML
	private Button btnSave = new Button();

	// TableView und Spalten
	@FXML private TreeTableView<Object> treeTableView;
    @FXML private TreeTableColumn<Object, String> colName;
    @FXML private TreeTableColumn<Object, Integer> colPresCount;
    @FXML private TreeTableColumn<Object, Integer> colTableCount;
    @FXML private TreeTableColumn<Object, Integer> colSeatCount;
    @FXML private TreeTableColumn<Object, Double> colRevenue;
    @FXML private TreeTableColumn<Object, Boolean> colArchive;

	@FXML
	public void initalize() {
		setupColumns();
		loadEventTree();
	}
	
	private void setupColumns() {
        // 1. Spalte: Name / Bezeichnung je nach Ebene
        colName.setCellValueFactory(param -> {
            Object data = param.getValue().getValue();
            if (data instanceof Event e) return new SimpleStringProperty("Event: " + e.getName());
            if (data instanceof Presentation p) return new SimpleStringProperty("Vorstellung: " + p.getName());
            if (data instanceof Table t) return new SimpleStringProperty("Tisch " + t.getTableNumber());
            if (data instanceof Seat s) return new SimpleStringProperty("Sitz " + s.getSeatNumber());
            return new SimpleStringProperty("");
        });

        // 2. Spalte: Kategorie (nur für Tisch relevant)
        colPresCount.setCellValueFactory(param -> {
            Object data = param.getValue().getValue();
            if (data instanceof Event e) {
            int count = data.getPresentations() != null ? e.getPresentations().size() : 0;
            return new SimpleIntegerProperty(count);
            }
        });

        // 3. Spalte: Status / Details je nach Ebene
        colStatus.setCellValueFactory(param -> {
            Object data = param.getValue().getValue();
            if (data instanceof Event e) {
                int count = e.getPresentations() != null ? e.getPresentations().size() : 0;
                return new SimpleStringProperty(count + " Vorstellung(en)");
            }
            if (data instanceof Presentation p) {
                int count = p.getTables() != null ? p.getTables().size() : 0;
                return new SimpleStringProperty(count + " Tische");
            }
            if (data instanceof Table t) {
                int count = t.getSeats() != null ? t.getSeats().size() : 0;
                return new SimpleStringProperty(count + " Plätze");
            }
            if (data instanceof Seat s) {
                String status = s.isReserved() ? "Reserviert" : "Frei";
                if (s.isPaid()) status += " | Bezahlt";
                if (s.isCollected()) status += " | Abgeholt";
                return new SimpleStringProperty(status);
            }
            return new SimpleStringProperty("");
        });
	}
	
    private void loadEventTree() {
        TreeItem<Object> dummyRoot = new TreeItem<>("Root");
        List<Event> events = repository.loadEvents(); // Bzw. deine entsprechende Methode

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