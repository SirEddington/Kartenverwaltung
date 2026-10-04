package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.List;
import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import javafx.fxml.FXML;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;

/**
 * Detail-Screen einer Halle (Gegenstück zur EventEditView): oben die
 * Halleneigenschaften, darunter der Hallenplan-Editor ({@link HallPlanController},
 * per fx:include eingebunden). Gespeichert wird mit dem Speichern-Knopf; wer den
 * Screen ohne Speichern verlässt, verwirft die Änderungen an neuen Hallen.
 */
public class HallEditController {

	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();

	private Hall hall;
	private boolean editMode = false;
	// Verhindert, dass das Befüllen der Felder die Listener auslöst und zurückschreibt
	private boolean updating = false;

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

	// Vom FXMLLoader aus fx:id="hallPlan" des fx:include injiziert (Name + "Controller")
	@FXML
	private HallPlanController hallPlanController;

	@FXML
	public void initialize() {
		setupSpinner(spnHallWidth, 100000.0, 10, value -> {
			hall.setHallWidth(value);
			hallPlanController.refresh();
		});
		setupSpinner(spnHallHeight, 100000.0, 10, value -> {
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
	}

	private void setupSpinner(Spinner<Double> spinner, double max, double step, Consumer<Double> onChange) {
		spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, max, 0.0, step));
		spinner.setEditable(true);
		// Getippte Werte beim Verlassen des Feldes übernehmen
		spinner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
			if (!isFocused) {
				spinner.increment(0);
			}
		});
		spinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (!updating && hall != null && newVal != null) {
				onChange.accept(newVal);
			}
		});
	}

	/**
	 * @param selectedObject optional: Hallenobjekt, das im Plan direkt markiert werden soll
	 */
	public void initData(Hall hall, HallObject selectedObject, boolean editable) {
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
		setEditable(editable);
	}

	private void commitName() {
		if (updating || hall == null) {
			return;
		}
		try {
			hall.changeName(txtName.getText());
		} catch (IllegalArgumentException e) {
			// Leerer Name: alten Namen wiederherstellen
			txtName.setText(hall.getName());
		}
	}

	@FXML
	private void toggleEditMode() {
		setEditable(!editMode);
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
	}

	@FXML
	private void handleBackToHallOverview() {
		setEditable(false);
		MainApp.showHallManagementView();
	}

	@FXML
	private void handleSave() {
		if (hall == null) {
			return;
		}
		try {
			hall.changeName(txtName.getText());
		} catch (IllegalArgumentException e) {
			MainApp.showAlert("Fehler", e.getMessage(), AlertType.WARNING);
			return;
		}
		hallRepository.saveHall(hall);
	}
}
