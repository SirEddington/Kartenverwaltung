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
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.AbstractOverviewController;
import de.eltviller_carneval_verein.karten.ui.GermanDecimalStringConverter;
import de.eltviller_carneval_verein.karten.ui.HallSaver;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog;
import de.eltviller_carneval_verein.karten.ui.backup.HallBackupCatalog;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
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
 * Hallen-Verwaltung: Baum-Tabelle Halle &rarr; (Knoten "Hallenobjekte" &rarr; Hallenobjekte, Knoten "Bestuhlungen"
 * &rarr; Bestuhlungen), aufgebaut wie die Event-Verwaltung (Basis: {@link AbstractOverviewController}). Bearbeitet wird
 * nicht in der Tabelle, sondern im Detail-Screen (siehe {@link HallEditController}). Was hier hinzugefügt wird (Halle,
 * Hallenobjekt, Bestuhlung), ist noch nicht gespeichert und öffnet sich im Editor mit "Speichern *".
 */
public class HallManagementController extends AbstractOverviewController {

	private static final GermanDecimalStringConverter DOUBLE_CONVERTER = new GermanDecimalStringConverter();

	/** Knoten über den Hallenobjekten einer Halle. */
	record ObjectsGroup(Hall hall) {
	}

	/** Knoten über den Bestuhlungen einer Halle. */
	record PlansGroup(Hall hall) {
	}

	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();
	private final JsonEventRepository eventRepository = JsonEventRepository.getInstance();

	// Anzahl der Vorstellungen je Halle (hallId) und je Bestuhlung (seatingPlanId), wird bei jedem Neuaufbau des Baums berechnet
	private Map<String, Integer> usageCounts = new HashMap<>();
	private Map<String, Integer> planUsageCounts = new HashMap<>();

	@FXML
	private TreeTableColumn<Object, String> colName;
	@FXML
	private TreeTableColumn<Object, String> colDescription;
	@FXML
	private TreeTableColumn<Object, String> colSize;
	@FXML
	private TreeTableColumn<Object, Integer> colObjectCount;
	@FXML
	private TreeTableColumn<Object, Integer> colPlanCount;
	@FXML
	private TreeTableColumn<Object, Integer> colRows;
	@FXML
	private TreeTableColumn<Object, Integer> colTables;
	@FXML
	private TreeTableColumn<Object, Integer> colSeats;
	@FXML
	private TreeTableColumn<Object, String> colUsage;
	@FXML
	private TreeTableColumn<Object, String> colShape;

	@Override
	protected void setupColumns() {
		colName.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Hall hall)
				return new SimpleStringProperty("Halle: " + EntityLabels.nameOrPlaceholder(hall.getName()));
			if (data instanceof ObjectsGroup)
				return new SimpleStringProperty("Hallenobjekte");
			if (data instanceof PlansGroup)
				return new SimpleStringProperty("Bestuhlungen");
			if (data instanceof HallObject object)
				return new SimpleStringProperty("Hallenobjekt: " + EntityLabels.nameOrPlaceholder(object.getName()));
			if (data instanceof SeatingPlan plan)
				return new SimpleStringProperty("Bestuhlung: " + EntityLabels.nameOrPlaceholder(plan.getName()));
			return new SimpleStringProperty("");
		});

		colDescription.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Hall hall)
				return new SimpleStringProperty(nullToEmpty(hall.getDescription()));
			if (data instanceof HallObject object)
				return new SimpleStringProperty(nullToEmpty(object.getDesc()));
			if (data instanceof SeatingPlan plan)
				return new SimpleStringProperty(nullToEmpty(plan.getDescription()));
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

		colObjectCount.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Hall hall)
				return count(hall.getHallObjects().size());
			if (data instanceof ObjectsGroup group)
				return count(group.hall().getHallObjects().size());
			return null;
		});

		colPlanCount.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Hall hall)
				return count(hall.getSeatingPlans().size());
			if (data instanceof PlansGroup group)
				return count(group.hall().getSeatingPlans().size());
			return null;
		});

		// Reihen, Tische und Sitze einer Bestuhlung (bei Halle und Knoten leer: Die Bestuhlungen sind Alternativen, eine Summe wäre sinnlos)
		colRows.setCellValueFactory(cell -> cell.getValue().getValue() instanceof SeatingPlan plan ? count(plan.getRows().size()) : null);
		colTables.setCellValueFactory(cell -> cell.getValue().getValue() instanceof SeatingPlan plan ? count(plan.getTables().size()) : null);
		colSeats.setCellValueFactory(cell -> cell.getValue().getValue() instanceof SeatingPlan plan ? count(plan.getSeats().size()) : null);

		colUsage.setCellValueFactory(cell -> {
			Object data = cell.getValue().getValue();
			if (data instanceof Hall hall)
				return new SimpleStringProperty(String.valueOf(usageCounts.getOrDefault(hall.getId(), 0)));
			if (data instanceof SeatingPlan plan)
				return new SimpleStringProperty(String.valueOf(planUsageCounts.getOrDefault(plan.getId(), 0)));
			return null;
		});

		colShape.setCellValueFactory(cell -> {
			if (cell.getValue().getValue() instanceof HallObject object)
				return new SimpleStringProperty(object.getShape() == Shape.CIRCLE ? "Kreis" : "Rechteck");
			return null;
		});
	}

	private static javafx.beans.value.ObservableValue<Integer> count(int value) {
		return new SimpleIntegerProperty(value).asObject();
	}

	private String formatSize(double width, double height) {
		return DOUBLE_CONVERTER.toString(width) + " × " + DOUBLE_CONVERTER.toString(height);
	}

	private String nullToEmpty(String text) {
		return text == null ? "" : text;
	}

	@Override
	protected BackupCatalog backupCatalog() {
		return HallBackupCatalog.create();
	}

	@Override
	protected TreeItem<Object> buildTree() {
		countUsages();

		TreeItem<Object> dummyRoot = new TreeItem<>("Root");
		for (Hall hall : hallRepository.loadHalls()) {
			TreeItem<Object> hallNode = new TreeItem<>(hall);

			TreeItem<Object> objectsNode = new TreeItem<>(new ObjectsGroup(hall));
			for (HallObject object : hall.getHallObjects()) {
				objectsNode.getChildren().add(new TreeItem<>(object));
			}
			TreeItem<Object> plansNode = new TreeItem<>(new PlansGroup(hall));
			for (SeatingPlan plan : hall.getSeatingPlans()) {
				plansNode.getChildren().add(new TreeItem<>(plan));
			}

			hallNode.getChildren().add(objectsNode);
			hallNode.getChildren().add(plansNode);
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
		if (data instanceof SeatingPlan plan)
			return plan.getName() + " " + plan.getDescription();
		return null;
	}

	@Override
	protected ContextMenu createContextMenu(TreeTableRow<Object> tableRow) {
		ContextMenu contextMenu = new ContextMenu();
		SeparatorMenuItem editDeleteSeparator = new SeparatorMenuItem();
		SeparatorMenuItem deleteDetailsSeparator = new SeparatorMenuItem();

		MenuItem addObjectItem = new MenuItem("+ Hallenobjekt hinzufügen");
		addObjectItem.setOnAction(e -> addObject());

		MenuItem addPlanItem = new MenuItem("+ Bestuhlung hinzufügen");
		addPlanItem.setOnAction(e -> addPlan());

		MenuItem editItem = new MenuItem("Bearbeiten");
		editItem.setOnAction(e -> openEditView(true));

		MenuItem seeDetails = new MenuItem("Details");
		seeDetails.setOnAction(e -> openEditView(false));

		MenuItem deleteItem = new MenuItem("Löschen");
		deleteItem.setOnAction(e -> handleDelete());

		contextMenu.getItems().addAll(addObjectItem, addPlanItem, editItem, editDeleteSeparator, deleteItem, deleteDetailsSeparator, seeDetails);

		contextMenu.setOnShowing(e -> {
			Object data = tableRow.getItem();
			// Jede Zeile gehört zu einer Halle: Objekt und Bestuhlung lassen sich überall in ihr hinzufügen
			addObjectItem.setVisible(data != null);
			addPlanItem.setVisible(data != null);
			editItem.setVisible(data != null);
			seeDetails.setVisible(data != null);
			// Gelöscht werden Hallen und Hallenobjekte; eine Bestuhlung löscht man im Hallen-Editor (dort mit dem Hinweis auf
			// die Vorstellungen, die sie verwenden)
			boolean deletable = data instanceof Hall || data instanceof HallObject;
			deleteItem.setVisible(deletable);
			editDeleteSeparator.setVisible(deletable);
			deleteDetailsSeparator.setVisible(deletable);
		});
		return contextMenu;
	}

	/** Die Halle, zu der die gewählte Zeile gehört. */
	private Hall selectedHall() {
		TreeItem<Object> selectedItem = selectedItem();
		return selectedItem == null ? null : findParentInTree(selectedItem, Hall.class);
	}

	/**
	 * Legt in der Halle ein neues Hallenobjekt an und öffnet es im Editor. Es ist noch nicht gespeichert: Die Halle gilt
	 * als geändert ("Speichern *"), ohne dass beim Wechsel in den Editor nachgefragt wird.
	 */
	private void addObject() {
		Hall hall = selectedHall();
		if (hall == null) {
			return;
		}
		ChangeTracker.halls().ensureTracked(hall); // Baseline ist der gespeicherte Stand vor dem Hinzufügen
		HallObject object = HallPlanController.addObjectWithDefaultSize(hall);
		ChangeTracker.halls().check(hall);
		MainApp.showHallEditViewWithChanges(hall, object, null, true);
	}

	/** Wie {@link #addObject()} mit einer neuen Bestuhlung: Sie ist im Editor gleich gewählt (Reiter "Bestuhlung"). */
	private void addPlan() {
		Hall hall = selectedHall();
		if (hall == null) {
			return;
		}
		ChangeTracker.halls().ensureTracked(hall);
		SeatingPlan plan = HallEditController.addPlanWithDefaultSizes(hall);
		ChangeTracker.halls().check(hall);
		MainApp.showHallEditViewWithChanges(hall, null, plan, true);
	}

	private void openEditView(boolean editable) {
		TreeItem<Object> selectedItem = selectedItem();
		if (selectedItem != null) {
			Hall hall = findParentInTree(selectedItem, Hall.class);
			HallObject object = findParentInTree(selectedItem, HallObject.class);
			SeatingPlan plan = findParentInTree(selectedItem, SeatingPlan.class);
			MainApp.showHallEditView(hall, object, plan, editable);
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
			HallSaver.saveStructureChange(hall);
		} else if (selectedItem.getValue() instanceof Hall) {
			List<String> usages = findUsages(hall);
			if (!usages.isEmpty()) {
				MainApp.showAlert("Halle wird noch verwendet", "Diese Halle ist noch folgenden Vorstellungen zugeordnet und kann nicht gelöscht werden:\n\n" + String.join("\n", usages), AlertType.WARNING);
				return;
			}
			if (!confirmDelete("Halle löschen", "Soll die Halle \"" + hall.getName() + "\" wirklich unwiderruflich gelöscht werden?")) {
				return;
			}
			hallRepository.deleteHall(hall);
		} else {
			return;
		}
		reload();
	}

	/** Liefert "Event > Vorstellung"-Beschreibungen aller Vorstellungen, die die übergebene Halle noch referenzieren. */
	private List<String> findUsages(Hall hall) {
		return eventRepository.loadEvents().stream().filter(event -> event.getPresentations() != null)
				.flatMap(event -> event.getPresentations().stream().filter(presentation -> hall.getId().equals(presentation.getHallId())).map(presentation -> event.getName() + " > " + presentation.getName()))
				.collect(Collectors.toList());
	}

	private void countUsages() {
		Map<String, Integer> halls = new HashMap<>();
		Map<String, Integer> plans = new HashMap<>();
		for (Event event : eventRepository.loadEvents()) {
			if (event.getPresentations() == null) {
				continue;
			}
			for (Presentation presentation : event.getPresentations()) {
				if (presentation.getHallId() != null) {
					halls.merge(presentation.getHallId(), 1, Integer::sum);
				}
				if (presentation.getSeatingPlanId() != null) {
					plans.merge(presentation.getSeatingPlanId(), 1, Integer::sum);
				}
			}
		}
		usageCounts = halls;
		planUsageCounts = plans;
	}

	@FXML
	private void handleCreateNewHall() {
		Hall hall = new Hall();
		hall.setName(createHallName());
		// Noch nicht gespeichert: Die Halle entsteht erst mit "Speichern" im Detail-Screen und gilt bis dahin als geändert
		ChangeTracker.halls().trackNew(hall);
		MainApp.showHallEditViewWithChanges(hall, null, null, true);
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
