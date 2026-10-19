package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.ui.input.RotationInput;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;

/**
 * Eigenschaften des im Plan markierten Tischs und Sitzes im Reiter "Bestuhlung" (eingebunden in
 * {@code SeatingPlanPanel.fxml}). Jede Eingabe geht sofort ins Modell, ohne Prüfung: Wertebereiche und doppelte
 * Nummern meldet der Validator in der Fußzeile. Eine von Hand gesetzte Position setzt {@code manualPos}, "Position
 * zurücksetzen" nimmt es zurück. Nach jeder Änderung ordnet der {@link SeatingPlanController} an und zeichnet neu
 * ({@link Host#edited()}).
 */
public class TableSeatPanelController {

	/** Der {@link SeatingPlanController}, der das Panel einbindet. */
	interface Host {
		/** Nach einer Änderung am Modell: anordnen, neu zeichnen, Panel auffrischen. */
		void edited();

		/**
		 * Nach Anlegen oder Löschen: markiert einen anderen Tisch bzw. Sitz (beides darf {@code null} sein, die Reihe
		 * bleibt dann markiert) und behandelt das wie {@link #edited()}.
		 */
		void select(Table table, Seat seat);
	}

	private Host host;
	private Table table;
	private Seat seat;
	private boolean editMode = false;
	// Verhindert, dass das Befüllen der Felder die Listener auslöst und zurückschreibt
	private boolean updating = false;

	@FXML
	private TitledPane tableSectionPane;
	@FXML
	private VBox tableFields;
	@FXML
	private Spinner<Integer> tableNumberSpinner;
	@FXML
	private TextField tableDescField;
	@FXML
	private TextField tableCategoryField;
	@FXML
	private Spinner<Double> tableWidthSpinner;
	@FXML
	private Spinner<Double> tableHeightSpinner;
	@FXML
	private Spinner<Double> tablePosXSpinner;
	@FXML
	private Spinner<Double> tablePosYSpinner;
	@FXML
	private Spinner<Double> rotationSpinner;
	@FXML
	private Label tablePositionLabel;
	@FXML
	private Button btnResetTablePosition;
	@FXML
	private Button btnAddSeat;
	@FXML
	private Button btnDeleteTable;

	@FXML
	private TitledPane seatSectionPane;
	@FXML
	private VBox seatFields;
	@FXML
	private Spinner<Integer> seatNumberSpinner;
	@FXML
	private CheckBox wheelchairCheck;
	@FXML
	private Spinner<Double> seatWidthSpinner;
	@FXML
	private Spinner<Double> seatHeightSpinner;
	@FXML
	private Spinner<Double> seatPosXSpinner;
	@FXML
	private Spinner<Double> seatPosYSpinner;
	@FXML
	private Label seatPositionLabel;
	@FXML
	private Button btnResetSeatPosition;
	@FXML
	private Button btnDeleteSeat;

	@FXML
	public void initialize() {
		setupTableFields();
		setupSeatFields();
		for (Spinner<?> spinner : List.of(tableNumberSpinner, tableWidthSpinner, tableHeightSpinner, tablePosXSpinner, tablePosYSpinner, rotationSpinner,
				seatNumberSpinner, seatWidthSpinner, seatHeightSpinner, seatPosXSpinner, seatPosYSpinner)) {
			spinner.setEditable(true);
		}
		show(null, null, false);
	}

	void setHost(Host host) {
		this.host = host;
	}

	/** Die Abschnitte Tisch und Sitz; Titel, Sichtbarkeit und Zustand setzt {@link SelectionSections}. */
	TitledPane tableSectionPane() {
		return tableSectionPane;
	}

	TitledPane seatSectionPane() {
		return seatSectionPane;
	}

	private void setupTableFields() {
		// Wertebereiche prüfen TableValidator und SeatingPlanValidator, die Spinner haben keine Grenzen
		tableNumberSpinner.setValueFactory(SpinnerInput.integerFactory(1, 1));
		tableWidthSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 5));
		tableHeightSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 5));
		tablePosXSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 5));
		tablePosYSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 5));
		// Die Drehung läuft im Kreis von 0 bis unter 360 (360 = 0, -10 = 350)
		rotationSpinner.setValueFactory(RotationInput.factory(15));
		Supplier<String> tableLabel = () -> table == null ? null : EntityLabels.table(table);
		SpinnerInput.installInteger(tableNumberSpinner, "Tischnummer", tableLabel);
		SpinnerInput.install(tableWidthSpinner, "Breite", tableLabel);
		SpinnerInput.install(tableHeightSpinner, "Höhe", tableLabel);
		SpinnerInput.install(tablePosXSpinner, "Position X", tableLabel);
		SpinnerInput.install(tablePosYSpinner, "Position Y", tableLabel);
		RotationInput.install(rotationSpinner, "Drehung", tableLabel);

		onTableValue(tableNumberSpinner, (t, value) -> t.setTableNumber(value));
		onTableValue(tableWidthSpinner, (t, value) -> t.setWidth(value));
		onTableValue(tableHeightSpinner, (t, value) -> t.setHeight(value));
		// Eine von Hand gesetzte Position ist manuell: Die Anordnung lässt den Tisch dann stehen
		onTableValue(tablePosXSpinner, (t, value) -> {
			t.setPosX(value);
			t.setManualPos(true);
		});
		onTableValue(tablePosYSpinner, (t, value) -> {
			t.setPosY(value);
			t.setManualPos(true);
		});
		onTableValue(rotationSpinner, (t, value) -> t.setRotation(value));
		// Beschreibung und Kategorie ändern die Zeichnung nicht: direkt ins Modell, ohne Neuzeichnen
		tableDescField.textProperty().addListener((obs, oldVal, newVal) -> {
			if (isEditing(table)) {
				table.setDesc(newVal);
			}
		});
		tableCategoryField.textProperty().addListener((obs, oldVal, newVal) -> {
			if (isEditing(table)) {
				table.setCategory(newVal);
			}
		});
	}

	private void setupSeatFields() {
		seatNumberSpinner.setValueFactory(SpinnerInput.integerFactory(1, 1));
		seatWidthSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 5));
		seatHeightSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 5));
		seatPosXSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 5));
		seatPosYSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 5));
		Supplier<String> seatLabel = () -> seat == null ? null : EntityLabels.seat(seat);
		SpinnerInput.installInteger(seatNumberSpinner, "Sitznummer", seatLabel);
		SpinnerInput.install(seatWidthSpinner, "Breite", seatLabel);
		SpinnerInput.install(seatHeightSpinner, "Höhe", seatLabel);
		SpinnerInput.install(seatPosXSpinner, "Position X", seatLabel);
		SpinnerInput.install(seatPosYSpinner, "Position Y", seatLabel);

		onSeatValue(seatNumberSpinner, (s, value) -> s.setSeatNumber(value));
		onSeatValue(seatWidthSpinner, (s, value) -> s.setWidth(value));
		onSeatValue(seatHeightSpinner, (s, value) -> s.setHeight(value));
		onSeatValue(seatPosXSpinner, (s, value) -> {
			s.setPosX(value);
			s.setManualPos(true);
		});
		onSeatValue(seatPosYSpinner, (s, value) -> {
			s.setPosY(value);
			s.setManualPos(true);
		});
		wheelchairCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
			if (isEditing(seat)) {
				seat.setWheelchairAccessible(newVal);
				host.edited();
			}
		});
	}

	private interface Change<E, T> {
		void apply(E entity, T value);
	}

	private <T> void onTableValue(Spinner<T> spinner, Change<Table, T> change) {
		spinner.valueProperty().addListener(valueListener(() -> table, change));
	}

	private <T> void onSeatValue(Spinner<T> spinner, Change<Seat, T> change) {
		spinner.valueProperty().addListener(valueListener(() -> seat, change));
	}

	private <E, T> ChangeListener<T> valueListener(Supplier<E> target, Change<E, T> change) {
		return (obs, oldVal, newVal) -> {
			E entity = target.get();
			if (isEditing(entity) && newVal != null) {
				change.apply(entity, newVal);
				host.edited();
			}
		};
	}

	private boolean isEditing(Object entity) {
		return !updating && editMode && entity != null && host != null;
	}

	// --- Anzeige -->

	/** "Sitz 2 an Tisch A3" (für Rückfragen; der Titel des Abschnitts ist kürzer, siehe {@link SelectionSections}). */
	static String seatName(Seat seat) {
		Table parent = seat.getParent();
		return "Sitz " + seat.getSeatNumber() + (parent == null ? "" : " an " + parent.getDisplayName());
	}

	static String positionText(boolean manual) {
		return manual ? "Position: manuell" : "Position: automatisch";
	}

	static String deleteTableMessage(Table table) {
		int seats = table.getSeats().size();
		String seatText = seats == 0 ? "" : seats == 1 ? " mit 1 Sitz" : " mit " + seats + " Sitzen";
		return "Soll " + table.getDisplayName() + seatText + " wirklich gelöscht werden?";
	}

	static String deleteSeatMessage(Seat seat) {
		return "Soll " + seatName(seat) + " wirklich gelöscht werden?";
	}

	/**
	 * Befüllt die Felder von Tisch und Sitz; im Anzeigemodus gesperrt. Welche Abschnitte zu sehen sind, entscheidet
	 * {@link SelectionSections}. Wird auch nach jeder Änderung und während des Ziehens aufgerufen, um die Felder
	 * nachzuführen.
	 */
	void show(Table table, Seat seat, boolean editMode) {
		this.table = table;
		this.seat = table == null ? null : seat;
		this.editMode = editMode;
		updating = true;
		try {
			if (this.table != null) {
				fillTable(this.table);
			}
			if (this.seat != null) {
				fillSeat(this.seat);
			}
		} finally {
			updating = false;
		}
		tableFields.setDisable(!editMode);
		seatFields.setDisable(!editMode);
	}

	private void fillTable(Table t) {
		tableNumberSpinner.getValueFactory().setValue(t.getTableNumber());
		setText(tableDescField, t.getDesc());
		setText(tableCategoryField, t.getCategory());
		tableWidthSpinner.getValueFactory().setValue(t.getWidth());
		tableHeightSpinner.getValueFactory().setValue(t.getHeight());
		tablePosXSpinner.getValueFactory().setValue(t.getPosX());
		tablePosYSpinner.getValueFactory().setValue(t.getPosY());
		rotationSpinner.getValueFactory().setValue(t.getRotation());
		tablePositionLabel.setText(positionText(t.isManualPos()));
		// Die Drehung eines automatisch angeordneten Tischs gibt die Reihe vor
		rotationSpinner.setDisable(!t.isManualPos());
		btnResetTablePosition.setDisable(!t.isManualPos());
	}

	private void fillSeat(Seat s) {
		seatNumberSpinner.getValueFactory().setValue(s.getSeatNumber());
		wheelchairCheck.setSelected(s.isWheelchairAccessible());
		seatWidthSpinner.getValueFactory().setValue(s.getWidth());
		seatHeightSpinner.getValueFactory().setValue(s.getHeight());
		seatPosXSpinner.getValueFactory().setValue(s.getPosX());
		seatPosYSpinner.getValueFactory().setValue(s.getPosY());
		seatPositionLabel.setText(positionText(s.isManualPos()));
		btnResetSeatPosition.setDisable(!s.isManualPos());
	}

	/** Nur bei anderem Text: Neu gesetzter gleicher Text würde die Schreibmarke beim Tippen zurücksetzen. */
	private static void setText(TextField field, String text) {
		String value = text == null ? "" : text;
		if (!value.equals(field.getText())) {
			field.setText(value);
		}
	}
	// <-- Anzeige

	// --- Knöpfe -->

	@FXML
	private void handleResetTablePosition() {
		if (isEditing(table)) {
			table.setManualPos(false);
			host.edited();
		}
	}

	@FXML
	private void handleResetSeatPosition() {
		if (isEditing(seat)) {
			seat.setManualPos(false);
			host.edited();
		}
	}

	@FXML
	private void handleAddSeat() {
		if (isEditing(table)) {
			Seat added = table.addSeat();
			host.select(table, added);
		}
	}

	@FXML
	private void handleDeleteTable() {
		if (!isEditing(table) || !ConfirmDialog.confirmDelete("Tisch löschen",
				deleteTableMessage(table) + PlanImpact.inEvents(table.getPlan(), PlanImpact.seatIds(List.of(table))).deleteNote())) {
			return;
		}
		Table deleted = table;
		if (deleted.getPlan() != null) {
			deleted.getPlan().deleteTable(deleted);
		}
		host.select(null, null);
	}

	@FXML
	private void handleDeleteSeat() {
		if (!isEditing(seat) || !ConfirmDialog.confirmDelete("Sitz löschen",
				deleteSeatMessage(seat) + PlanImpact.inEvents(seat.getParent().getPlan(), Set.of(seat.getId())).deleteNote())) {
			return;
		}
		Table parent = seat.getParent();
		parent.deleteSeat(seat);
		host.select(parent, null);
	}
	// <-- Knöpfe
}
