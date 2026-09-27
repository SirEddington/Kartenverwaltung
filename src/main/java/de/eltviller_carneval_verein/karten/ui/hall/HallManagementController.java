package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.GermanDecimalStringConverter;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;

public class HallManagementController {

	private static final GermanDecimalStringConverter DOUBLE_CONVERTER = new GermanDecimalStringConverter();

	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();
	private final JsonEventRepository eventRepository = JsonEventRepository.getInstance();

	private final ObservableList<Hall> masterData = FXCollections.observableArrayList();

	@FXML
	private TableView<Hall> hallTable;
	@FXML
	private TableColumn<Hall, String> colName;
	@FXML
	private TableColumn<Hall, String> colDescription;
	@FXML
	private TableColumn<Hall, Double> colHallWidth;
	@FXML
	private TableColumn<Hall, Double> colHallHeight;
	@FXML
	private TableColumn<Hall, Double> colDefaultObjectWidth;
	@FXML
	private TableColumn<Hall, Double> colDefaultObjectHeight;
	@FXML
	private TableColumn<Hall, Integer> colObjectCount;

	@FXML
	public void initialize() {
		setupColumns();
		loadHalls();
	}

	private void setupColumns() {
		colName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getName()));
		colName.setCellFactory(TextFieldTableCell.forTableColumn());
		colName.setOnEditCommit(event -> {
			try {
				event.getRowValue().changeName(event.getNewValue());
			} catch (IllegalArgumentException e) {
				showAlert("Fehler", e.getMessage(), AlertType.WARNING);
			} finally {
				hallTable.refresh();
			}
		});

		colDescription.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDescription()));
		colDescription.setCellFactory(TextFieldTableCell.forTableColumn());
		colDescription.setOnEditCommit(event -> event.getRowValue().setDescription(event.getNewValue()));

		colHallWidth.setCellValueFactory(cell -> new SimpleDoubleProperty(cell.getValue().getHallWidth()).asObject());
		colHallWidth.setCellFactory(TextFieldTableCell.forTableColumn(DOUBLE_CONVERTER));
		colHallWidth.setOnEditCommit(event -> event.getRowValue().setHallWidth(event.getNewValue()));

		colHallHeight.setCellValueFactory(cell -> new SimpleDoubleProperty(cell.getValue().getHallHeight()).asObject());
		colHallHeight.setCellFactory(TextFieldTableCell.forTableColumn(DOUBLE_CONVERTER));
		colHallHeight.setOnEditCommit(event -> event.getRowValue().setHallHeight(event.getNewValue()));

		colDefaultObjectWidth.setCellValueFactory(cell -> new SimpleDoubleProperty(cell.getValue().getDefaultObjectWidth()).asObject());
		colDefaultObjectWidth.setCellFactory(TextFieldTableCell.forTableColumn(DOUBLE_CONVERTER));
		colDefaultObjectWidth.setOnEditCommit(event -> event.getRowValue().setDefaultObjectWidth(event.getNewValue()));

		colDefaultObjectHeight.setCellValueFactory(cell -> new SimpleDoubleProperty(cell.getValue().getDefaultObjectHeight()).asObject());
		colDefaultObjectHeight.setCellFactory(TextFieldTableCell.forTableColumn(DOUBLE_CONVERTER));
		colDefaultObjectHeight.setOnEditCommit(event -> event.getRowValue().setDefaultObjectHeight(event.getNewValue()));

		colObjectCount.setCellValueFactory(cell -> new SimpleIntegerProperty(cell.getValue().getHallObjects().size()).asObject());
	}

	private void loadHalls() {
		masterData.setAll(hallRepository.loadHalls());
		hallTable.setItems(masterData);
	}

	@FXML
	private void handleCreateNewHall() {
		Hall hall = new Hall();
		hall.changeName(createHallName());
		masterData.add(hall);
		hallTable.getSelectionModel().select(hall);
	}

	private String createHallName() {
		Set<String> existingNames = masterData.stream().map(Hall::getName).collect(Collectors.toSet());
		int i = 1;
		while (existingNames.contains("Halle " + i)) {
			i++;
		}
		return "Halle " + i;
	}

	@FXML
	private void handleDeleteHall() {
		Hall selected = hallTable.getSelectionModel().getSelectedItem();
		if (selected == null) {
			showAlert("Keine Auswahl", "Bitte zuerst eine Halle in der Tabelle auswählen.", AlertType.INFORMATION);
			return;
		}

		List<String> usages = findUsages(selected);
		if (!usages.isEmpty()) {
			showAlert("Halle wird noch verwendet",
					"Diese Halle ist noch folgenden Vorstellungen zugeordnet und kann nicht gelöscht werden:\n\n" + String.join("\n", usages),
					AlertType.WARNING);
			return;
		}

		Alert confirm = new Alert(AlertType.CONFIRMATION, "Halle '" + selected.getName() + "' wirklich löschen?", ButtonType.YES, ButtonType.NO);
		confirm.setHeaderText(null);
		MainApp.applyAppIcon(confirm);
		confirm.showAndWait().ifPresent(response -> {
			if (response == ButtonType.YES) {
				hallRepository.deleteHall(selected);
				masterData.remove(selected);
			}
		});
	}

	/** Liefert "Event > Vorstellung"-Beschreibungen aller Vorstellungen, die die übergebene Halle noch referenzieren. */
	private List<String> findUsages(Hall hall) {
		List<String> usages = new ArrayList<>();
		for (Event event : eventRepository.loadEvents()) {
			if (event.getPresentations() == null) {
				continue;
			}
			for (Presentation presentation : event.getPresentations()) {
				if (hall.getId().equals(presentation.getHallId())) {
					usages.add(event.getName() + " > " + presentation.getName());
				}
			}
		}
		return usages;
	}

	@FXML
	private void handleSave() {
		hallRepository.saveHalls(masterData);
	}

	@FXML
	private void handleBackToManagement() {
		MainApp.showManagementMenuView();
	}

	private void showAlert(String title, String content, AlertType alertType) {
		MainApp.showAlert(title, content, alertType);
	}
}
