package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.AbstractOverviewController;
import de.eltviller_carneval_verein.karten.ui.GermanDecimalStringConverter;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;

/**
 * Hallen-Verwaltung: Baum-Tabelle Halle &rarr; Hallenobjekte, aufgebaut wie die
 * Event-Verwaltung (Basis: {@link AbstractOverviewController}). Bearbeitet wird
 * nicht in der Tabelle, sondern im Detail-Screen (siehe {@link HallEditController}).
 */
public class HallManagementController extends AbstractOverviewController {

	private static final GermanDecimalStringConverter DOUBLE_CONVERTER = new GermanDecimalStringConverter();

	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();
	private final JsonEventRepository eventRepository = JsonEventRepository.getInstance();

	// Anzahl der Vorstellungen je Halle (hallId), wird bei jedem Neuaufbau des Baums berechnet
	private Map<String, Integer> usageCounts = new HashMap<>();

	@FXML private TreeTableColumn<Object, String> colName;
	@FXML private TreeTableColumn<Object, String> colDescription;
	@FXML private TreeTableColumn<Object, String> colSize;
	@FXML private TreeTableColumn<Object, String> colDefaultObjectSize;
	@FXML private TreeTableColumn<Object, Integer> colObjectCount;
	@FXML private TreeTableColumn<Object, String> colUsage;
	@FXML private TreeTableColumn<Object, String> colShape;

	@Override
	protected void setupColumns() {
		colName.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Hall hall)
				return new SimpleStringProperty("Halle: " + hall.getName());
			if (data instanceof HallObject object)
				return new SimpleStringProperty("Hallenobjekt: " + object.getName());
			return new SimpleStringProperty("");
		});

		colDescription.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Hall hall)
				return new SimpleStringProperty(nullToEmpty(hall.getDescription()));
			if (data instanceof HallObject object)
				return new SimpleStringProperty(nullToEmpty(object.getDesc()));
			return null;
		});

		// Saalmaße der Halle bzw. Maße des Objekts
		colSize.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Hall hall)
				return new SimpleStringProperty(formatSize(hall.getHallWidth(), hall.getHallHeight()));
			if (data instanceof HallObject object)
				return new SimpleStringProperty(formatSize(object.getWidth(), object.getHeight()));
			return null;
		});

		colDefaultObjectSize.setCellValueFactory(cell -> {
			if (cell.getValue().getValue() instanceof Hall hall)
				return new SimpleStringProperty(formatSize(hall.getDefaultObjectWidth(), hall.getDefaultObjectHeight()));
			return null;
		});

		colObjectCount.setCellValueFactory(cell -> {
			if (cell.getValue().getValue() instanceof Hall hall)
				return new SimpleIntegerProperty(hall.getHallObjects().size()).asObject();
			return null;
		});

		colUsage.setCellValueFactory(cell -> {
			if (cell.getValue().getValue() instanceof Hall hall)
				return new SimpleStringProperty(String.valueOf(usageCounts.getOrDefault(hall.getId(), 0)));
			return null;
		});

		colShape.setCellValueFactory(cell -> {
			if (cell.getValue().getValue() instanceof HallObject object)
				return new SimpleStringProperty(object.getShape() == Shape.CIRCLE ? "Kreis" : "Rechteck");
			return null;
		});
	}

	private String formatSize(double width, double height) {
		return DOUBLE_CONVERTER.toString(width) + " × " + DOUBLE_CONVERTER.toString(height);
	}

	private String nullToEmpty(String text) {
		return text == null ? "" : text;
	}

	@Override
	protected TreeItem<Object> buildTree() {
		usageCounts = countUsages();

		TreeItem<Object> dummyRoot = new TreeItem<>("Root");
		for (Hall hall : hallRepository.loadHalls()) {
			TreeItem<Object> hallNode = new TreeItem<>(hall);
			for (HallObject object : hall.getHallObjects()) {
				hallNode.getChildren().add(new TreeItem<>(object));
			}
			dummyRoot.getChildren().add(hallNode);
		}
		return dummyRoot;
	}

	@Override
	protected String searchText(Object data) {
		if (data instanceof Hall hall)
			return hall.getName() + " " + hall.getDescription();
		if (data instanceof HallObject object)
			return object.getName() + " " + object.getDesc();
		return null;
	}

	@Override
	protected ContextMenu createContextMenu(TreeTableRow<Object> tableRow) {
		ContextMenu contextMenu = new ContextMenu();
		SeparatorMenuItem editDeleteSeparator = new SeparatorMenuItem();
		SeparatorMenuItem deleteDetailsSeparator = new SeparatorMenuItem();

		MenuItem addObjectItem = new MenuItem("+ Hallenobjekt hinzufügen");
		addObjectItem.setOnAction(e -> {
			TreeItem<Object> selectedItem = selectedItem();
			if (selectedItem != null) {
				Hall hall = findParentInTree(selectedItem, Hall.class);
				HallObject object = hall.addHallObject();
				MainApp.showHallEditView(hall, object, true);
			}
		});

		MenuItem editItem = new MenuItem("Bearbeiten");
		editItem.setOnAction(e -> openEditView(true));

		MenuItem seeDetails = new MenuItem("Details");
		seeDetails.setOnAction(e -> openEditView(false));

		MenuItem deleteItem = new MenuItem("Löschen");
		deleteItem.setOnAction(e -> handleDelete());

		contextMenu.getItems().addAll(addObjectItem, editItem, editDeleteSeparator, deleteItem, deleteDetailsSeparator, seeDetails);

		contextMenu.setOnShowing(e -> {
			Object data = tableRow.getItem();
			addObjectItem.setVisible(data instanceof Hall || data instanceof HallObject);
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
			Hall hall = findParentInTree(selectedItem, Hall.class);
			HallObject object = findParentInTree(selectedItem, HallObject.class);
			MainApp.showHallEditView(hall, object, editable);
		}
	}

	/** Löscht den gewählten Eintrag nach Rückfrage und speichert die betroffene Halle sofort. */
	private void handleDelete() {
		TreeItem<Object> selectedItem = selectedItem();
		if (selectedItem == null || selectedItem.getParent() == null) {
			return;
		}
		Hall hall = findParentInTree(selectedItem, Hall.class);

		if (selectedItem.getValue() instanceof HallObject object) {
			if (!confirmDelete("Hallenobjekt löschen", "Soll das Hallenobjekt \"" + object.getName() + "\" wirklich unwiderruflich gelöscht werden?")) {
				return;
			}
			hall.getHallObjects().remove(object);
			hallRepository.saveHall(hall);
		} else {
			List<String> usages = findUsages(hall);
			if (!usages.isEmpty()) {
				MainApp.showAlert("Halle wird noch verwendet",
						"Diese Halle ist noch folgenden Vorstellungen zugeordnet und kann nicht gelöscht werden:\n\n" + String.join("\n", usages),
						AlertType.WARNING);
				return;
			}
			if (!confirmDelete("Halle löschen", "Soll die Halle \"" + hall.getName() + "\" wirklich unwiderruflich gelöscht werden?")) {
				return;
			}
			hallRepository.deleteHall(hall);
		}
		reload();
	}

	/** Liefert "Event > Vorstellung"-Beschreibungen aller Vorstellungen, die die übergebene Halle noch referenzieren. */
	private List<String> findUsages(Hall hall) {
		return eventRepository.loadEvents().stream()
				.filter(event -> event.getPresentations() != null)
				.flatMap(event -> event.getPresentations().stream()
						.filter(presentation -> hall.getId().equals(presentation.getHallId()))
						.map(presentation -> event.getName() + " > " + presentation.getName()))
				.collect(Collectors.toList());
	}

	private Map<String, Integer> countUsages() {
		Map<String, Integer> counts = new HashMap<>();
		for (Event event : eventRepository.loadEvents()) {
			if (event.getPresentations() == null) {
				continue;
			}
			for (Presentation presentation : event.getPresentations()) {
				if (presentation.getHallId() != null) {
					counts.merge(presentation.getHallId(), 1, Integer::sum);
				}
			}
		}
		return counts;
	}

	@FXML
	private void handleCreateNewHall() {
		Hall hall = new Hall();
		hall.changeName(createHallName());
		// Noch nicht gespeichert: Die Halle entsteht erst mit "Speichern" im Detail-Screen
		MainApp.showHallEditView(hall, null, true);
	}

	private String createHallName() {
		Set<String> existingNames = hallRepository.loadHalls().stream().map(Hall::getName).collect(Collectors.toSet());
		int i = 1;
		while (existingNames.contains("Halle " + i)) {
			i++;
		}
		return "Halle " + i;
	}
}
