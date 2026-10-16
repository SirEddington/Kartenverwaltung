package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;

/**
 * Reiter "Bestuhlung" im Hallenplan-Editor: Liste der Tischreihen der gewählten Bestuhlung mit "+ Reihe" und
 * "Löschen", "Tisch hinzufügen", "Reihe füllen…" und "Nummern neu vergeben" für die markierte Reihe, darunter deren
 * Eigenschaften. Bei mehreren markierten Reihen gelten Winkel, Tischdrehung, Tischabstand und Mittelgang für alle
 * ({@link TableRowBulkEdit}); Buchstabe und Position nur für eine. Ist im Plan ein Tisch oder Sitz markiert, zeigt das
 * Panel statt der Reiheneigenschaften dessen Eigenschaften ({@link TableSeatPanelController}).
 *
 * <p>
 * Die Zeichnung der Bestuhlung liefert {@link #createLayer()} ({@link SeatingPlanDrawing}); der
 * {@link HallPlanController} legt sie über die Hallenobjekte. Reihen, Tische und Sitze lassen sich dort anklicken und
 * ziehen. Jede Änderung ordnet die Bestuhlung neu an ({@link SeatingPlanLayout#apply}) und zeichnet neu; die
 * Änderungserkennung bemerkt sie über den {@code ChangeTrigger}. Geprüft wird nichts, Wertebereiche und doppelte
 * Nummern melden die Validatoren.
 */
public class SeatingPlanController {

	private static final double NEW_ROW_START = 100;
	// Abstand einer neuen Reihe zur vorigen, quer zu deren Richtung (etwa eine Tischlänge plus Gang)
	private static final double NEW_ROW_SPACING = 400;
	private static final String MIXED = " verschieden";

	private SeatingPlan plan;
	private boolean editMode = false;
	// Verhindert, dass das Befüllen des Panels die Listener auslöst und zurückschreibt
	private boolean updatingPanel = false;
	/**
	 * Die Reihen, die das Panel gerade zeigt. Änderungen gehen an sie, nicht an die aktuelle Auswahl: Verlässt man ein
	 * Feld per Klick in die Liste, kann die Auswahl schon gewechselt haben, bevor das Feld seinen Wert übernimmt.
	 */
	private List<TableRow> panelRows = List.of();
	// Im Plan markierter Tisch und Sitz (der Sitz gehört immer zum markierten Tisch)
	private Table selectedTable;
	private Seat selectedSeat;

	private final SeatingPlanDrawing drawing = new SeatingPlanDrawing(new SeatingPlanDrawing.Callbacks() {
		@Override
		public void pressed(TableRow row, Table table, Seat seat) {
			select(row, table, seat);
		}

		@Override
		public void moving() {
			showPositions();
		}

		@Override
		public void moved() {
			afterEdit();
		}

		@Override
		public void clicked() {
			redraw();
		}
	});

	// Grundtexte der Beschriftungen, an die bei Mehrfachauswahl "verschieden" angehängt wird
	private final Map<Label, String> baseTexts = new LinkedHashMap<>();

	@FXML
	private ListView<TableRow> rowList;
	@FXML
	private Button btnAddRow;
	@FXML
	private Button btnDeleteRow;
	@FXML
	private Button btnAddTable;
	@FXML
	private Button btnFillRow;
	@FXML
	private Button btnRenumber;
	@FXML
	private Label selectionLabel;
	@FXML
	private VBox rowPanel;
	@FXML
	private TextField labelField;
	@FXML
	private Spinner<Double> posXSpinner;
	@FXML
	private Spinner<Double> posYSpinner;
	@FXML
	private Label angleLabel;
	@FXML
	private Spinner<Double> angleSpinner;
	@FXML
	private Label tableRotationLabel;
	@FXML
	private Spinner<Double> tableRotationSpinner;
	@FXML
	private Label tableGapLabel;
	@FXML
	private Spinner<Double> tableGapSpinner;
	@FXML
	private Label aisleAfterLabel;
	@FXML
	private Spinner<Integer> aisleAfterSpinner;
	@FXML
	private Label aisleWidthLabel;
	@FXML
	private Spinner<Double> aisleWidthSpinner;
	// Vom FXMLLoader aus fx:id="tableSeatPanel" des fx:include injiziert (Wurzel und Name + "Controller")
	@FXML
	private VBox tableSeatPanel;
	@FXML
	private TableSeatPanelController tableSeatPanelController;

	@FXML
	public void initialize() {
		tableSeatPanelController.setHost(new TableSeatPanelController.Host() {
			@Override
			public void edited() {
				afterEdit();
			}

			@Override
			public void select(Table table, Seat seat) {
				selectedTable = table;
				selectedSeat = seat;
				afterEdit();
			}
		});
		rowList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		rowList.setCellFactory(list -> new ListCell<>() {
			@Override
			protected void updateItem(TableRow row, boolean empty) {
				super.updateItem(row, empty);
				setText(empty || row == null ? null : rowText(row));
			}
		});
		rowList.getSelectionModel().getSelectedItems().addListener((ListChangeListener<TableRow>) change -> {
			if (updatingPanel) {
				return;
			}
			// Eine in der Liste gewählte Reihe zeigt deren Eigenschaften, nicht die eines Tischs
			selectedTable = null;
			selectedSeat = null;
			updatePanel();
			redraw();
		});
		// Auch ein Klick auf die schon markierte Reihe kehrt vom Tisch zu den Reiheneigenschaften zurück
		rowList.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
			if (event.getButton() == MouseButton.PRIMARY && selectedTable != null && !selectedRows().isEmpty()) {
				selectedTable = null;
				selectedSeat = null;
				updatePanel();
				redraw();
			}
		});
		setupContextMenu();
		setupFields();
		for (Label label : List.of(angleLabel, tableRotationLabel, tableGapLabel, aisleAfterLabel, aisleWidthLabel)) {
			baseTexts.put(label, label.getText());
		}
		updatePanel();
	}

	/** Kontextmenü der Reihenliste; im Anzeigemodus sichtbar, aber gesperrt. */
	private void setupContextMenu() {
		MenuItem addItem = new MenuItem("+ Reihe");
		addItem.setOnAction(e -> handleAddRow());
		MenuItem deleteItem = new MenuItem("Löschen");
		deleteItem.setOnAction(e -> handleDeleteRow());
		MenuItem addTableItem = new MenuItem("Tisch hinzufügen");
		addTableItem.setOnAction(e -> handleAddTable());
		MenuItem fillItem = new MenuItem("Reihe füllen…");
		fillItem.setOnAction(e -> handleFillRow());
		MenuItem renumberItem = new MenuItem("Nummern neu vergeben");
		renumberItem.setOnAction(e -> handleRenumber());
		ContextMenu menu = new ContextMenu(addItem, deleteItem, new SeparatorMenuItem(), addTableItem, fillItem, renumberItem);
		menu.setOnShowing(e -> {
			addItem.setDisable(!editMode || plan == null);
			deleteItem.setDisable(!editMode || selectedRows().isEmpty());
			boolean oneRow = editMode && selectedRows().size() == 1;
			addTableItem.setDisable(!oneRow);
			fillItem.setDisable(!oneRow);
			renumberItem.setDisable(!oneRow);
		});
		rowList.setContextMenu(menu);
	}

	private void setupFields() {
		// Wertebereiche prüft der TableRowValidator (ROW-002), die Spinner haben keine Grenzen
		posXSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 10));
		posYSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 10));
		angleSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 15));
		tableRotationSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 90));
		tableGapSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 10));
		aisleAfterSpinner.setValueFactory(SpinnerInput.integerFactory(0, 1));
		aisleWidthSpinner.setValueFactory(SpinnerInput.doubleFactory(0.0, 10));
		for (Spinner<?> spinner : List.of(posXSpinner, posYSpinner, angleSpinner, tableRotationSpinner, tableGapSpinner, aisleAfterSpinner,
				aisleWidthSpinner)) {
			spinner.setEditable(true);
		}
		// Tippfehler: alter Wert bleibt, Meldung in der Fußzeile; erst nach dem Setzen der Value-Factories
		Supplier<String> rowLabel = () -> panelRows.isEmpty() ? null
				: panelRows.size() == 1 ? EntityLabels.row(panelRows.get(0)) : panelRows.size() + " Reihen";
		SpinnerInput.install(posXSpinner, "Position X", rowLabel);
		SpinnerInput.install(posYSpinner, "Position Y", rowLabel);
		SpinnerInput.install(angleSpinner, "Winkel", rowLabel);
		SpinnerInput.install(tableRotationSpinner, "Tischdrehung", rowLabel);
		SpinnerInput.install(tableGapSpinner, "Tischabstand", rowLabel);
		SpinnerInput.installInteger(aisleAfterSpinner, "Gang nach Tisch", rowLabel);
		SpinnerInput.install(aisleWidthSpinner, "Gangbreite", rowLabel);

		labelField.setOnAction(e -> commitLabel());
		labelField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
			if (!isFocused) {
				commitLabel();
			}
		});
		posXSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (isEditingSingleRow() && newVal != null) {
				panelRows.get(0).setPosX(newVal);
				afterEdit();
			}
		});
		posYSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (isEditingSingleRow() && newVal != null) {
				panelRows.get(0).setPosY(newVal);
				afterEdit();
			}
		});
		angleSpinner.valueProperty().addListener((obs, oldVal, newVal) -> bulkChange(newVal, TableRowBulkEdit::angle));
		tableRotationSpinner.valueProperty().addListener((obs, oldVal, newVal) -> bulkChange(newVal, TableRowBulkEdit::tableRotation));
		tableGapSpinner.valueProperty().addListener((obs, oldVal, newVal) -> bulkChange(newVal, TableRowBulkEdit::tableGap));
		aisleAfterSpinner.valueProperty().addListener((obs, oldVal, newVal) -> bulkChange(newVal, TableRowBulkEdit::aisleAfter));
		aisleWidthSpinner.valueProperty().addListener((obs, oldVal, newVal) -> bulkChange(newVal, TableRowBulkEdit::aisleWidth));
	}

	private boolean isEditingSingleRow() {
		return !updatingPanel && editMode && panelRows.size() == 1;
	}

	/** Überträgt eine Eingabe über {@link TableRowBulkEdit} auf alle Reihen des Panels (auch bei nur einer Reihe). */
	private <T> void bulkChange(T value, Function<TableRowBulkEdit, TableRowBulkEdit.Field<T>> field) {
		if (updatingPanel || !editMode || value == null || panelRows.isEmpty()) {
			return;
		}
		TableRowBulkEdit edit = new TableRowBulkEdit(panelRows);
		field.apply(edit).change(value);
		if (!edit.hasChanges()) {
			return;
		}
		edit.apply();
		afterEdit();
	}

	private void commitLabel() {
		if (updatingPanel || !editMode || panelRows.size() != 1) {
			return;
		}
		TableRow row = panelRows.get(0);
		// Ein doppelter Buchstabe wird übernommen und vom SeatingPlanValidator gemeldet (ROW-001)
		if (!Objects.equals(labelField.getText(), row.getLabel())) {
			row.setLabel(labelField.getText());
			afterEdit();
		}
	}

	/** Nach jeder Änderung: neu anordnen, Liste, Hinweise und Tisch-/Sitzpanel auffrischen, neu zeichnen. */
	private void afterEdit() {
		SeatingPlanLayout.apply(plan);
		rowList.refresh();
		selectionLabel.setText(selectionText());
		refreshMixedHints();
		updateDetailPanel();
		redraw();
	}

	// --- Liste und Panel -->

	/** "Reihe A" bzw. "Reihe ohne Buchstaben". */
	private static String rowName(TableRow row) {
		String label = row.getLabel() == null ? "" : row.getLabel().trim();
		return label.isEmpty() ? "Reihe ohne Buchstaben" : "Reihe " + label;
	}

	/** Eintrag der Reihenliste, z. B. "2. Reihe B (4 Tische)"; die Nummer unterscheidet Reihen ohne Buchstaben. */
	private static String rowText(TableRow row) {
		SeatingPlan plan = row.getParent();
		int tables = plan == null ? 0 : plan.getTablesOf(row).size();
		String position = plan == null ? "" : (plan.getRows().indexOf(row) + 1) + ". ";
		return position + rowName(row) + " (" + tables + (tables == 1 ? " Tisch)" : " Tische)");
	}

	private List<TableRow> selectedRows() {
		return List.copyOf(rowList.getSelectionModel().getSelectedItems().filtered(Objects::nonNull));
	}

	private String selectionText() {
		if (panelRows.isEmpty()) {
			return plan == null ? "Keine Bestuhlung gewählt" : "Keine Reihe markiert";
		}
		return panelRows.size() == 1 ? rowText(panelRows.get(0)) : panelRows.size() + " Reihen: Mehrfachbearbeitung";
	}

	/** Befüllt das Panel mit den markierten Reihen (bei mehreren mit den Werten der ersten) und sperrt es im Anzeigemodus. */
	private void updatePanel() {
		panelRows = selectedRows();
		TableRow first = panelRows.isEmpty() ? null : panelRows.get(0);
		updatingPanel = true;
		try {
			labelField.setText(panelRows.size() == 1 ? first.getLabel() : "");
			posXSpinner.getValueFactory().setValue(first != null ? first.getPosX() : 0.0);
			posYSpinner.getValueFactory().setValue(first != null ? first.getPosY() : 0.0);
			angleSpinner.getValueFactory().setValue(first != null ? first.getAngle() : 0.0);
			tableRotationSpinner.getValueFactory().setValue(first != null ? first.getTableRotation() : 0.0);
			tableGapSpinner.getValueFactory().setValue(first != null ? first.getTableGap() : 0.0);
			aisleAfterSpinner.getValueFactory().setValue(first != null ? TableRowBulkEdit.aisleAfterOf(first) : 0);
			aisleWidthSpinner.getValueFactory().setValue(first != null ? first.getAisleWidth() : 0.0);
		} finally {
			updatingPanel = false;
		}
		selectionLabel.setText(selectionText());
		refreshMixedHints();

		boolean editable = editMode && !panelRows.isEmpty();
		rowPanel.setDisable(!editable);
		// Buchstabe und Position gehören je Reihe, die Mehrfachbearbeitung lässt sie aus
		boolean single = panelRows.size() == 1;
		labelField.setDisable(!single);
		posXSpinner.setDisable(!single);
		posYSpinner.setDisable(!single);
		btnAddRow.setDisable(!editMode || plan == null);
		btnDeleteRow.setDisable(!editable);
		// Tische anlegen und nummerieren gilt für genau eine Reihe
		btnAddTable.setDisable(!editMode || !single);
		btnFillRow.setDisable(!editMode || !single);
		btnRenumber.setDisable(!editMode || !single);
		updateDetailPanel();
	}

	/** Mit markiertem Tisch dessen Eigenschaften (und die des Sitzes) statt der Reiheneigenschaften. */
	private void updateDetailPanel() {
		// Ein gelöschter Tisch oder Sitz (z. B. mit seiner Reihe) ist nicht mehr markiert
		if (selectedTable != null && (plan == null || !plan.getTables().contains(selectedTable))) {
			selectedTable = null;
		}
		if (selectedSeat != null && (selectedTable == null || !selectedTable.getSeats().contains(selectedSeat))) {
			selectedSeat = null;
		}
		boolean tableShown = selectedTable != null;
		rowPanel.setVisible(!tableShown);
		rowPanel.setManaged(!tableShown);
		tableSeatPanel.setVisible(tableShown);
		tableSeatPanel.setManaged(tableShown);
		tableSeatPanelController.show(selectedTable, selectedSeat, editMode);
	}

	/** Während des Ziehens: Positionsfelder der Reihe bzw. des Tischs oder Sitzes nachführen. */
	private void showPositions() {
		if (panelRows.size() == 1) {
			updatingPanel = true;
			try {
				posXSpinner.getValueFactory().setValue(panelRows.get(0).getPosX());
				posYSpinner.getValueFactory().setValue(panelRows.get(0).getPosY());
			} finally {
				updatingPanel = false;
			}
		}
		tableSeatPanelController.show(selectedTable, selectedSeat, editMode);
	}

	/**
	 * Markiert im Plan Angeklicktes: die Reihe in der Liste (bei einem Tisch ohne gültige Reihe keine) und Tisch bzw. Sitz.
	 * Zeichnet nicht neu (der gedrückte Knoten muss die Geste überstehen).
	 */
	private void select(TableRow row, Table table, Seat seat) {
		updatingPanel = true;
		try {
			rowList.getSelectionModel().clearSelection();
			if (row != null) {
				rowList.getSelectionModel().select(row);
				rowList.scrollTo(row);
			}
		} finally {
			updatingPanel = false;
		}
		selectedTable = table;
		selectedSeat = seat;
		updatePanel();
	}

	/** Bei mehreren Reihen mit unterschiedlichen Werten steht "verschieden" an der Beschriftung. */
	private void refreshMixedHints() {
		TableRowBulkEdit edit = new TableRowBulkEdit(panelRows);
		boolean multiple = panelRows.size() > 1;
		markMixed(angleLabel, multiple && edit.angle().common().isEmpty());
		markMixed(tableRotationLabel, multiple && edit.tableRotation().common().isEmpty());
		markMixed(tableGapLabel, multiple && edit.tableGap().common().isEmpty());
		markMixed(aisleAfterLabel, multiple && edit.aisleAfter().common().isEmpty());
		markMixed(aisleWidthLabel, multiple && edit.aisleWidth().common().isEmpty());
	}

	private void markMixed(Label label, boolean mixed) {
		label.setText(baseTexts.getOrDefault(label, label.getText()) + (mixed ? MIXED : ""));
	}

	/** Neue Liste der Reihen aus der Bestuhlung, die Markierung wird auf {@code toSelect} gesetzt (kein Tisch markiert). */
	private void reloadRows(List<TableRow> toSelect) {
		selectedTable = null;
		selectedSeat = null;
		updatingPanel = true;
		try {
			rowList.getItems().setAll(plan == null ? List.of() : plan.getRows());
			rowList.getSelectionModel().clearSelection();
			for (TableRow row : toSelect) {
				rowList.getSelectionModel().select(row);
			}
		} finally {
			updatingPanel = false;
		}
		updatePanel();
	}
	// <-- Liste und Panel

	// --- Reihen anlegen und löschen -->

	@FXML
	private void handleAddRow() {
		if (plan == null || !editMode) {
			return;
		}
		List<TableRow> rows = plan.getRows();
		TableRow previous = rows.isEmpty() ? null : rows.get(rows.size() - 1);
		TableRow row = plan.addRow();
		if (previous == null) {
			row.setPosX(NEW_ROW_START);
			row.setPosY(NEW_ROW_START);
		} else {
			// Parallel zur vorigen Reihe, eine Reihenbreite daneben (quer zur Reihenrichtung)
			double angle = Math.toRadians(previous.getAngle());
			row.setPosX(previous.getPosX() - NEW_ROW_SPACING * Math.sin(angle));
			row.setPosY(previous.getPosY() + NEW_ROW_SPACING * Math.cos(angle));
			row.setAngle(previous.getAngle());
			row.setTableRotation(previous.getTableRotation());
			row.setTableGap(previous.getTableGap());
		}
		reloadRows(List.of(row));
		afterEdit();
	}

	@FXML
	private void handleDeleteRow() {
		List<TableRow> rows = selectedRows();
		if (plan == null || !editMode || rows.isEmpty()) {
			return;
		}
		int tables = rows.stream().mapToInt(row -> plan.getTablesOf(row).size()).sum();
		// Leere Reihen ohne Rückfrage, mit Tischen kostet das Löschen Daten
		if (tables > 0 && !ConfirmDialog.confirmDelete(rows.size() == 1 ? "Reihe löschen" : "Reihen löschen", deleteRowsMessage(rows, tables))) {
			return;
		}
		rows.forEach(plan::deleteRow);
		reloadRows(List.of());
		afterEdit();
	}

	private static String deleteRowsMessage(List<TableRow> rows, int tables) {
		String tableText = tables == 1 ? "1 Tisch" : tables + " Tischen";
		if (rows.size() == 1) {
			return "Soll die " + rowName(rows.get(0)) + " mit " + tableText + " wirklich gelöscht werden?";
		}
		return "Sollen " + rows.size() + " Reihen mit insgesamt " + tableText + " wirklich gelöscht werden?";
	}
	// <-- Reihen anlegen und löschen

	// --- Tische einer Reihe -->

	/** Die eine markierte Reihe, sonst {@code null} (auch im Anzeigemodus). */
	private TableRow singleEditableRow() {
		List<TableRow> rows = selectedRows();
		return plan == null || !editMode || rows.size() != 1 ? null : rows.get(0);
	}

	/** Neuer Tisch hinten in der Reihe (höchste Nummer + 1), danach markiert. */
	@FXML
	private void handleAddTable() {
		TableRow row = singleEditableRow();
		if (row == null) {
			return;
		}
		Table table = plan.addTable(row);
		selectedTable = table;
		selectedSeat = null;
		afterEdit();
	}

	@FXML
	private void handleFillRow() {
		TableRow row = singleEditableRow();
		if (row == null) {
			return;
		}
		RowFillDialog.show(rowName(row), rowList.getScene() == null ? null : rowList.getScene().getWindow()).ifPresent(fill -> {
			plan.fillRow(row, fill.tableCount(), fill.seatsPerTable());
			afterEdit();
		});
	}

	@FXML
	private void handleRenumber() {
		TableRow row = singleEditableRow();
		if (row == null) {
			return;
		}
		plan.renumber(row);
		afterEdit();
	}
	// <-- Tische einer Reihe

	// --- Zeichnung -->

	/**
	 * Die Zeichnung der Bestuhlung für den Hallenplan ({@link SeatingPlanDrawing}). Liefert immer dieselbe Gruppe neu
	 * befüllt; der Hallenplan legt sie über die Hallenobjekte und schaltet sie für den inaktiven Reiter mausdurchlässig.
	 */
	public Node createLayer() {
		redraw();
		return drawing.layer();
	}

	private void redraw() {
		drawing.redraw(plan, editMode, panelRows, selectedTable, selectedSeat);
	}
	// <-- Zeichnung

	/** Zeigt die Bestuhlung (oder mit {@code null} keine); ordnet nicht an, das geschieht beim Öffnen der Halle. */
	public void setPlan(SeatingPlan plan) {
		this.plan = plan;
		reloadRows(List.of());
		redraw();
	}

	public void setEditMode(boolean enabled) {
		this.editMode = enabled;
		updatePanel();
		redraw();
	}

	/** Liste, Panel und Zeichnung neu aus dem Modell, z. B. nach Änderungen von außen. */
	public void refresh() {
		rowList.refresh();
		updatePanel();
		redraw();
	}
}
