package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.ForeignBatch;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.ui.DirtyIndicator;
import de.eltviller_carneval_verein.karten.ui.EditingUnits;
import de.eltviller_carneval_verein.karten.ui.HallSaver;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard.Outcome;
import de.eltviller_carneval_verein.karten.ui.LiveViews;
import de.eltviller_carneval_verein.karten.ui.LiveViews.Registration;
import de.eltviller_carneval_verein.karten.ui.PlanFreezer;
import de.eltviller_carneval_verein.karten.ui.SaveShortcut;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;

/**
 * Detail-Screen einer Halle (Gegenstück zur EventEditView): oben die
 * Halleneigenschaften, darunter der Hallenplan-Editor ({@link HallPlanController},
 * per fx:include eingebunden). Gespeichert wird mit dem Speichern-Knopf; wer den
 * Screen ohne Speichern verlässt, verwirft die Änderungen an neuen Hallen.
 */
public class HallEditController {

	// Standardmaße neuer Bestuhlungen (cm), wie bisher in der Event-Anlage
	private static final double DEFAULT_TABLE_WIDTH = 70;
	private static final double DEFAULT_TABLE_HEIGHT = 300;
	private static final double DEFAULT_SEAT_SIZE = 30;

	private Hall hall;
	// Die Bestuhlung, deren Name im Namensfeld steht (Ziel des Umbenennens)
	private SeatingPlan shownPlan;
	private boolean editMode = false;
	// Verhindert, dass das Befüllen der Felder die Listener auslöst und zurückschreibt
	private boolean updating = false;
	// true, solange die Ansicht in einem gezeigten Fenster steht (nur dann ist die Halle bei EditingUnits gemeldet)
	private boolean shown = false;

	private final StoreHallRepository hallRepository = StoreHallRepository.getInstance();
	// Fremde Änderungen an der gezeigten Halle (nur im Anzeigemodus: im Bearbeitungsmodus hält EditingUnits sie zurück)
	private final RepositoryListener<Hall> foreignChanges = new RepositoryListener<>() {
		// Nicht mitten in der Benachrichtigung der Listener, sondern im nächsten Takt: Das Laden der neuen Ansicht meldet diese ab
		private final RepositoryListener<Hall> reloading = LiveViews.coalescing(Platform::runLater,
				units -> !editMode && hall != null && LiveViews.touchesHall(units, hall.getId()), () -> {
					if (!editMode && hall != null) {
						reloadReplacedHall();
					}
				});

		@Override
		public void changedByOthers(Hall changed, ForeignBatch<Hall> batch) {
			reloading.changedByOthers(changed, batch);
		}

		// Keine Prüfung des Bearbeitungsmodus nötig: Im Bearbeitungsmodus ist die Halle in Bearbeitung, und das Repository stellt
		// auch das Löschen zurück (wie jeden fremden Stand); es kommt erst, wenn der Modus oder die Ansicht verlassen ist
		@Override
		public void removedByOthers(Hall removed) {
			if (hall != null && hall.getId().equals(removed.getId())) {
				// Der Wechsel kann eine Rückfrage öffnen (andere ungespeicherte Hallen): nicht mitten in der Benachrichtigung
				Platform.runLater(() -> leaveDeletedHall(removed));
			}
		}
	};

	@FXML
	private Button btnToggleEdit;
	@FXML
	private Button btnSave;
	@FXML
	private TextField txtName;
	@FXML
	private TextField txtDescription;
	@FXML
	private Spinner<Double> spnHallWidth;
	@FXML
	private Spinner<Double> spnHallHeight;
	@FXML
	private ComboBox<SeatingPlan> cmbSeatingPlan;
	@FXML
	private Button btnNewPlan;
	@FXML
	private Button btnCopyPlan;
	@FXML
	private Button btnDeletePlan;
	@FXML
	private TextField txtPlanName;
	@FXML
	private MenuButton menuDisplay;

	// Vom FXMLLoader aus fx:id="hallPlan" des fx:include injiziert (Name + "Controller")
	@FXML
	private HallPlanController hallPlanController;

	@FXML
	public void initialize() {
		DirtyIndicator.bind(btnSave, ChangeTracker.halls());
		SaveShortcut.install(btnSave);

		// Beschriftungen des Plans (Reihen, Tische, Sitznummern, Hallenobjekte), nicht gespeichert
		PlanLabelOptions planLabels = new PlanLabelOptions();
		PlanLabelsMenu.bind(menuDisplay, planLabels);
		hallPlanController.setLabelOptions(planLabels);

		setupSpinner(spnHallWidth, "Hallenbreite", 10, value -> {
			hall.setHallWidth(value);
			hallPlanController.refresh();
		});
		setupSpinner(spnHallHeight, "Hallenhöhe", 10, value -> {
			hall.setHallHeight(value);
			hallPlanController.refresh();
		});

		txtName.setOnAction(e -> commitName());
		txtName.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
			if (!isFocused) {
				commitName();
			}
		});
		txtDescription.textProperty().addListener((obs, oldVal, newVal) -> {
			if (!updating && hall != null) {
				hall.setDescription(newVal);
			}
		});
		setupSeatingPlanSelection();

		// Fremde Änderungen nur, solange die Ansicht zu sehen ist; im Bearbeitungsmodus ist die Halle dann in Bearbeitung
		LiveViews.whileShown(btnSave, Registration.of(hallRepository::addListener, hallRepository::removeListener, foreignChanges), new Registration(() -> {
			shown = true;
			reportEditing();
		}, () -> {
			shown = false;
			reportEditing();
		}));
	}

	/**
	 * Ein anderer Rechner hat die Halle ersetzt (neues Objekt mit derselben ID; ein Speichern der alten würde abgelehnt): Die
	 * Ansicht öffnet sich mit der Halle des Repositorys neu, ohne Rückfrage (im Anzeigemodus gibt es hier nichts Ungespeichertes)
	 * und mit der gewählten Bestuhlung, wenn der Reiter "Bestuhlung" offen ist (sie zu übergeben öffnet ihn).
	 */
	private void reloadReplacedHall() {
		Hall current = hallRepository.findById(hall.getId());
		if (!LiveViews.isShown(btnSave) || editMode || current == null || current == hall) {
			return;
		}
		SeatingPlan plan = shownPlan != null && hallPlanController.isSeatingTabActive() ? current.findSeatingPlan(shownPlan.getId()) : null;
		MainApp.showHallEditViewWithChanges(current, null, plan, false);
	}

	/**
	 * Die gezeigte Halle hat ein anderer Rechner gelöscht: zurück zur Hallenverwaltung, die einen Dialog mit dem Grund zeigt.
	 * Bleibt die Ansicht stehen (Abbruch der Rückfrage zu anderen ungespeicherten Änderungen), zeigt sie die Halle nicht mehr.
	 */
	private void leaveDeletedHall(Hall removed) {
		if (!LiveViews.isShown(btnSave) || hall == null || !hall.getId().equals(removed.getId())) {
			return;
		}
		MainApp.showHallManagementView();
		if (LiveViews.isShown(btnSave)) {
			showNoHall();
		}
		// Die Hallenverwaltung hat keine Fußzeile: Die Meldung öffnet dort einen Dialog
		StatusMessage.getInstance().show(Severity.WARNING, "„" + EntityLabels.nameOrPlaceholder(removed.getName()) + "“ wurde auf einem anderen Rechner gelöscht.");
	}

	/**
	 * Die gezeigte Halle gibt es nicht mehr, die Ansicht bleibt aber stehen: Sie zeigt keine Halle und lässt sich nicht mehr
	 * bearbeiten (Speichern lehnte das alte Objekt ohnehin ab). Weiter geht es mit "Zurück".
	 */
	private void showNoHall() {
		setEditable(false);
		hall = null;
		reportEditing();
		btnToggleEdit.setDisable(true);
		updating = true;
		try {
			txtName.setText("");
			txtDescription.setText("");
			cmbSeatingPlan.getItems().clear();
			cmbSeatingPlan.setValue(null);
		} finally {
			updating = false;
		}
		showPlan(null);
		hallPlanController.setHall(null);
	}

	/**
	 * Im Bearbeitungsmodus (und nur in einem gezeigten Fenster) ist die Halle in Bearbeitung: Ein fremder Stand für sie wird
	 * zurückgestellt und kommt, sobald der Bearbeitungsmodus oder die Ansicht verlassen ist. Die Meldung geschieht erst, wenn die
	 * Ansicht wirklich zu sehen ist, und endet auf jedem Weg hinaus, sonst bliebe die Halle für fremde Stände gesperrt.
	 */
	private void reportEditing() {
		EditingUnits.getInstance().report(this, shown && editMode, hall);
	}

	/** Auswahl der Bestuhlung: Die Auswahl selbst ist keine Änderung an der Halle, Neu/Kopieren/Löschen/Name schon. */
	private void setupSeatingPlanSelection() {
		cmbSeatingPlan.setConverter(new StringConverter<>() {
			@Override
			public String toString(SeatingPlan plan) {
				return plan == null ? "" : EntityLabels.nameOrPlaceholder(plan.getName());
			}

			@Override
			public SeatingPlan fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		});
		cmbSeatingPlan.setPromptText("Keine Bestuhlung");
		cmbSeatingPlan.valueProperty().addListener((obs, oldPlan, newPlan) -> {
			if (!updating) {
				showPlan(newPlan);
			}
		});
		txtPlanName.setOnAction(e -> commitPlanName());
		txtPlanName.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
			if (!isFocused) {
				commitPlanName();
			}
		});
	}

	private void setupSpinner(Spinner<Double> spinner, String fieldName, double step, Consumer<Double> onChange) {
		spinner.setValueFactory(SpinnerInput.doubleFactory(0.0, step));
		spinner.setEditable(true);
		SpinnerInput.install(spinner, fieldName, () -> hall == null ? null : EntityLabels.hall(hall));
		// Getippte Werte beim Verlassen des Feldes übernimmt der Spinner selbst (ein zweites Bestätigen hier brächte nichts)
		spinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (!updating && hall != null && newVal != null) {
				onChange.accept(newVal);
			}
		});
	}

	/**
	 * @param selectedObject optional: Hallenobjekt, das im Plan direkt markiert werden soll
	 * @param selectedPlan   optional: Bestuhlung, die gleich gewählt ist (der Reiter "Bestuhlung" ist dann offen)
	 */
	public void initData(Hall hall, HallObject selectedObject, SeatingPlan selectedPlan, boolean editable) {
		this.hall = hall;

		updating = true;
		try {
			txtName.setText(hall.getName());
			txtDescription.setText(hall.getDescription() != null ? hall.getDescription() : "");
			spnHallWidth.getValueFactory().setValue(hall.getHallWidth());
			spnHallHeight.getValueFactory().setValue(hall.getHallHeight());
		} finally {
			updating = false;
		}

		hallPlanController.setHall(hall);
		if (selectedObject != null) {
			hallPlanController.setSelectedObject(selectedObject);
		}
		// Anordnen vor dem Verfolgen (unten): Was das Öffnen an den Positionen zurechtrückt, zählt nicht als Änderung.
		// Die Anordnung ist idempotent, bei einer schon angeordneten Bestuhlung ändert sich nichts
		hall.getSeatingPlans().forEach(SeatingPlanLayout::apply);
		boolean planGiven = selectedPlan != null && hall.getSeatingPlans().contains(selectedPlan);
		reloadPlans(planGiven ? selectedPlan : hall.getSeatingPlans().isEmpty() ? null : hall.getSeatingPlans().get(0));
		if (planGiven) {
			hallPlanController.showSeatingTab();
		}
		setEditable(editable);

		// Erst jetzt, damit alles, was das Befüllen der Ansicht am Modell zurechtrückt, nicht als Änderung zählt. Eine schon
		// verfolgte Halle (gerade in der Übersicht hinzugefügtes Objekt oder Bestuhlung, noch nicht gespeichert) behält ihren
		// gespeicherten Stand als Baseline und gilt weiter als geändert.
		ChangeTracker.halls().ensureTracked(hall);
	}

	private void commitName() {
		if (updating || hall == null) {
			return;
		}
		// Ein leerer Name wird übernommen und vom HallValidator gemeldet (HLL-001)
		hall.setName(txtName.getText());
	}

	// --- Bestuhlung -->

	/** Füllt die Auswahl neu (z. B. nach Umbenennen, damit der Name im Feld stimmt) und zeigt {@code select}. */
	private void reloadPlans(SeatingPlan select) {
		updating = true;
		try {
			cmbSeatingPlan.getItems().setAll(hall.getSeatingPlans());
			// Über null, damit das Feld einen geänderten Namen neu anzeigt, auch wenn dieselbe Bestuhlung gewählt bleibt
			cmbSeatingPlan.setValue(null);
			cmbSeatingPlan.setValue(select);
		} finally {
			updating = false;
		}
		showPlan(select);
	}

	private void showPlan(SeatingPlan plan) {
		boolean otherPlan = plan != shownPlan;
		shownPlan = plan;
		updating = true;
		try {
			txtPlanName.setText(plan == null ? "" : plan.getName());
		} finally {
			updating = false;
		}
		// Nur bei einer anderen Bestuhlung: Nach dem Umbenennen bleibt die markierte Reihe markiert
		if (otherPlan) {
			hallPlanController.setSeatingPlan(plan);
		}
		updatePlanControls();
	}

	private void updatePlanControls() {
		boolean hasPlan = shownPlan != null;
		btnNewPlan.setDisable(!editMode || hall == null);
		btnCopyPlan.setDisable(!editMode || !hasPlan);
		btnDeletePlan.setDisable(!editMode || !hasPlan);
		txtPlanName.setEditable(editMode && hasPlan);
		txtPlanName.setDisable(!hasPlan);
	}

	private void commitPlanName() {
		if (updating || !editMode || shownPlan == null || Objects.equals(txtPlanName.getText(), shownPlan.getName())) {
			return;
		}
		// Ein leerer Name wird übernommen und vom SeatingPlanValidator gemeldet (SPL-001)
		shownPlan.setName(txtPlanName.getText());
		reloadPlans(shownPlan);
	}

	@FXML
	private void handleNewPlan() {
		if (hall == null || !editMode) {
			return;
		}
		reloadPlans(addPlanWithDefaultSizes(hall));
	}

	/**
	 * Neue Bestuhlung mit den Standardmaßen, die bisher die Event-Anlage vorgab (Tisch 70 x 300 cm, Sitz 30 x 30 cm). Mit den
	 * Modell-Standardwerten (0) bekämen neue Sitze keine Größe.
	 */
	static SeatingPlan addPlanWithDefaultSizes(Hall hall) {
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setDefaultTableWidth(DEFAULT_TABLE_WIDTH);
		plan.setDefaultTableHeight(DEFAULT_TABLE_HEIGHT);
		plan.setDefaultSeatWidth(DEFAULT_SEAT_SIZE);
		plan.setDefaultSeatHeight(DEFAULT_SEAT_SIZE);
		return plan;
	}

	@FXML
	private void handleCopyPlan() {
		if (hall == null || !editMode || shownPlan == null) {
			return;
		}
		SeatingPlan copy = shownPlan.copy();
		copy.setName((shownPlan.getName() == null ? "" : shownPlan.getName()) + " (Kopie)");
		copy.setParent(hall);
		hall.getSeatingPlans().add(copy);
		SeatingPlanLayout.apply(copy);
		reloadPlans(copy);
	}

	@FXML
	private void handleDeletePlan() {
		if (hall == null || !editMode || shownPlan == null) {
			return;
		}
		SeatingPlan plan = shownPlan;
		if (!ConfirmDialog.confirmDelete("Bestuhlung löschen", deletePlanMessage(plan, StoreEventRepository.getInstance().loadEvents()))) {
			return;
		}
		// Solange die Bestuhlung noch da ist: "Ohne Platz" und Momentaufnahmen der betroffenen Events festhalten
		PlanFreezer.beforePlanDeletion(hall, plan);
		int index = hall.getSeatingPlans().indexOf(plan);
		hall.getSeatingPlans().remove(plan);
		List<SeatingPlan> remaining = hall.getSeatingPlans();
		reloadPlans(remaining.isEmpty() ? null : remaining.get(Math.min(index, remaining.size() - 1)));
	}

	/**
	 * Die Rückfrage vor dem Löschen einer Bestuhlung. Sie nennt die Vorstellungen, die die Bestuhlung verwenden (nur ein
	 * Hinweis, kein Schutz: deren Buchungen bleiben erhalten).
	 */
	static String deletePlanMessage(SeatingPlan plan, List<Event> events) {
		StringBuilder message = new StringBuilder("Soll die Bestuhlung \"" + EntityLabels.nameOrPlaceholder(plan.getName())
				+ "\" wirklich gelöscht werden?");
		List<String> users = new ArrayList<>();
		for (Event event : events) {
			if (event.getPresentations() == null) {
				continue;
			}
			for (Presentation presentation : event.getPresentations()) {
				if (plan.getId().equals(presentation.getSeatingPlanId())) {
					users.add(EntityLabels.nameOrPlaceholder(event.getName()) + ": " + EntityLabels.nameOrPlaceholder(presentation.getName()));
				}
			}
		}
		if (!users.isEmpty()) {
			message.append("\n\nSie wird von ").append(users.size() == 1 ? "dieser Vorstellung" : "diesen Vorstellungen").append(" verwendet:");
			users.forEach(user -> message.append("\n- ").append(user));
			message.append("\n\nDie Buchungen bleiben erhalten.");
		}
		return message.toString();
	}
	// <-- Bestuhlung

	@FXML
	private void toggleEditMode() {
		// Keine ungespeicherten Änderungen im Anzeigemodus: erst nachfragen
		if (editMode) {
			Outcome outcome = LeaveGuard.confirmSwitchToViewMode();
			if (outcome == Outcome.STAY) {
				return;
			}
			if (outcome == Outcome.LEAVE_RESTORED) {
				reloadRestoredData();
				return;
			}
		}
		setEditable(!editMode);
	}

	/**
	 * Nach "Nicht speichern" ist die Halle ein neues Objekt (der gespeicherte Stand wurde wiederhergestellt): Der Screen
	 * öffnet sich mit ihr neu, im Anzeigemodus.
	 */
	private void reloadRestoredData() {
		Hall restored = hall == null ? null : StoreHallRepository.getInstance().findById(hall.getId());
		if (restored == null) {
			// Eine nie gespeicherte Halle ist mit dem Verwerfen verschwunden
			MainApp.showHallManagementView();
			return;
		}
		MainApp.showHallEditView(restored, null, false);
	}

	private void setEditable(boolean editable) {
		editMode = editable;
		btnToggleEdit.setText(editMode ? "Anzeigen" : "Bearbeiten");
		btnSave.setDisable(!editMode);

		txtName.setEditable(editMode);
		txtDescription.setEditable(editMode);
		for (Spinner<Double> spinner : List.of(spnHallWidth, spnHallHeight)) {
			spinner.setDisable(!editMode);
		}
		hallPlanController.setEditMode(editMode);
		updatePlanControls();
		reportEditing();
	}

	@FXML
	private void handleBackToHallOverview() {
		boolean wasEditable = editMode;
		setEditable(false);
		MainApp.showHallManagementView();
		// Hat der Nutzer im Dialog "Abbrechen" gewählt, bleibt dieser Screen stehen: Bearbeitungsmodus zurückgeben,
		// sonst wäre der Speichern-Button gesperrt, obwohl ungespeicherte Änderungen da sind
		if (LiveViews.isShown(btnSave)) {
			setEditable(wasEditable);
		}
	}

	@FXML
	private void handleSave() {
		if (hall == null) {
			return;
		}
		// Der Klick auf Speichern nimmt einem Spinner den Fokus: Eine abgelehnte Eingabe nicht mit "Gespeichert" überschreiben
		if (SpinnerInput.blockSaveIfScreenInputRejected(txtName)) {
			return;
		}
		hall.setName(txtName.getText());
		HallSaver.save(hall);
	}
}
