package de.eltviller_carneval_verein.karten.ui.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.CashRegisters.CashState;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.LockedCashRegister;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.ui.event.CashRegisterSection.Row;
import de.eltviller_carneval_verein.karten.ui.input.InputIssue;
import de.eltviller_carneval_verein.karten.ui.input.ParsingTextFieldTableCell;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.util.converter.DefaultStringConverter;

/**
 * Der Abschnitt „Kassen" der Event-Verwaltung (KARV-144), der JavaFX-Teil zu {@link CashRegisterSection}: die Tabelle mit
 * „Name" und „gilt für", der Schalter zwischen „je Vorstellung eine Kasse" und „eine gemeinsame Kasse", „+ Kasse
 * hinzufügen" und „Kasse löschen" im Kontextmenü einer Zeile. Jede Änderung prüft vorher Sperre und „Kassen noch nicht
 * abgeglichen" am Repository (Ablehnung in der Fußzeile) und meldet sich danach beim {@link ChangeTracker}. Fremde Stände,
 * Bearbeiten-Modus und Speichern bindet {@link EventEditController} ein.
 */
final class CashRegisterTable {

	/** Eine Rückfrage, während der {@code model} für fremde Stände als in Bearbeitung gilt ({@code EventEditController}). */
	interface Confirmation {
		boolean confirm(Object model, BooleanSupplier dialog);
	}

	private static final double ROW_HEIGHT = 25.0;
	private static final double HEADER_HEIGHT = 28.0;
	private static final int MIN_ROWS = 2;
	private static final int MAX_ROWS = 6;

	private final StoreEventRepository repository;
	private final TableView<Row> table;
	private final TableColumn<Row, String> nameColumn;
	private final ToggleGroup mode;
	private final RadioButton perPresentation;
	private final RadioButton shared;
	private final Button add;
	private final Label hint;
	private final Confirmation confirmation;
	private final ObservableList<Row> rows = FXCollections.observableArrayList();

	private Event event;
	private boolean editMode;
	// true, während der Schalter aus dem Modell gesetzt wird: Das ist keine Bedienung
	private boolean syncing;
	// Eine Änderung aus einer Zelle steht im Modell; neu gezeichnet wird erst nach dem Ende der Eingabe
	private boolean refreshAfterEdit;

	CashRegisterTable(StoreEventRepository repository, TableView<Row> table, TableColumn<Row, String> nameColumn,
			TableColumn<Row, String> scopeColumn, RadioButton perPresentation, RadioButton shared, Button add, Label hint,
			Confirmation confirmation) {
		this.repository = repository;
		this.table = table;
		this.nameColumn = nameColumn;
		this.mode = shared.getToggleGroup();
		this.perPresentation = perPresentation;
		this.shared = shared;
		this.add = add;
		this.hint = hint;
		this.confirmation = confirmation;

		nameColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().name()));
		nameColumn.setCellFactory(ParsingTextFieldTableCell.forTableColumn(new DefaultStringConverter(), InputIssue.NOT_A_NUMBER, "Name",
				row -> null, Row::nameEditable));
		nameColumn.setOnEditCommit(e -> rename(e.getRowValue(), e.getNewValue()));
		scopeColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().scope()));
		scopeColumn.setCellFactory(column -> new ScopeCell());
		table.setItems(rows);
		table.setFixedCellSize(ROW_HEIGHT);
		table.setRowFactory(view -> new DeletableRow());
		table.editingCellProperty().addListener((obs, before, cell) -> {
			if (cell == null && refreshAfterEdit) {
				// erst nach dem laufenden Commit: Tab kann gleich die nächste Zelle bearbeiten (dann nach deren Ende)
				Platform.runLater(this::refreshAfterEdit);
			}
		});
		mode.selectedToggleProperty().addListener((obs, before, now) -> {
			if (!syncing && now != null) {
				switchShared(now == shared);
			}
		});
		add.setOnAction(e -> addFree());
		hint.setText(CashRegisterSection.PENDING);
		show(null);
	}

	/** Die Tabelle, für {@code TableInput}, {@code EditingUnits.watch} und den Blick auf eine offene Eingabe. */
	TableView<Row> table() {
		return table;
	}

	/** Das Objekt im Modell, das eine Eingabe in der Zeile verändert ({@link CashRegisterSection#modelOf}). */
	Object modelOf(Row row) {
		return CashRegisterSection.modelOf(event, row.id());
	}

	/** Bearbeiten-Modus an oder aus; die Zeilen und Knöpfe richten sich danach. */
	void setEditMode(boolean editMode) {
		this.editMode = editMode;
		show(event);
	}

	/**
	 * Zeigt die Kassen des Events ({@code null}: leer und gesperrt), mit dem Stand der Sperren aus dem Repository. Die
	 * gewählte Zeile bleibt über die Kennung gewählt, auch wenn ein fremder Stand das Event ersetzt hat. Nicht während
	 * einer offenen Eingabe in dieser Tabelle aufrufen.
	 */
	void show(Event shownEvent) {
		boolean sameEvent = shownEvent != null && event != null && shownEvent.getId().equals(event.getId());
		String selectedId = sameEvent ? selectedId() : null;
		event = shownEvent;
		refreshAfterEdit = false;
		Map<String, LockedCashRegister> locked = event == null ? Map.of() : repository.lockedCashRegisters(event.getId());
		boolean pending = event != null && repository.cashRegistersPending(event.getId());
		rows.setAll(event == null ? List.of()
				: CashRegisterSection.rows(event, locked, repository::presentationUnavailable, pending, editMode));
		select(selectedId);

		syncing = true;
		try {
			mode.selectToggle(event == null ? null : CashRegisterSection.sharedSelected(event, locked) ? shared : perPresentation);
		} finally {
			syncing = false;
		}
		boolean switchable = event != null && CashRegisterSection.switchEnabled(locked, pending, editMode);
		perPresentation.setDisable(!switchable);
		shared.setDisable(!switchable);
		add.setDisable(event == null || !editMode || pending);
		hint.setVisible(pending);
		hint.setManaged(pending);
		table.setEditable(editMode && !pending);
		table.setPrefHeight(HEADER_HEIGHT + ROW_HEIGHT * Math.clamp(rows.size(), MIN_ROWS, MAX_ROWS) + 2);
	}

	/**
	 * Eine Eingabe in einer Zelle hat das Modell geändert: Die Zeilen werden neu aufgebaut (Name, Reihenfolge, „gilt für"),
	 * sobald die laufende Bestätigung vorbei und keine Zelle mehr im Bearbeiten ist; sonst nach deren Ende.
	 */
	private void refreshSoon() {
		refreshAfterEdit = true;
		Platform.runLater(this::refreshAfterEdit);
	}

	private void refreshAfterEdit() {
		if (refreshAfterEdit && table.getEditingCell() == null) {
			show(event);
		}
	}

	/** Die Kennung der gewählten Kasse; {@code null} ohne Auswahl. */
	String selectedId() {
		Row selected = table.getSelectionModel().getSelectedItem();
		return selected == null ? null : selected.id();
	}

	/**
	 * Wählt die Kasse mit der Kennung, sonst keine. Der Controller gibt so die Auswahl über einen Neuaufbau ohne Event
	 * hinweg weiter (fremde Änderungen wählen das Event erst ab), wie bei der Vorstellung.
	 */
	void select(String id) {
		table.getSelectionModel().clearSelection();
		int index = indexOf(id);
		if (index >= 0) {
			table.getSelectionModel().select(index);
		}
	}

	private int indexOf(String id) {
		return CashRegisterSection.indexOf(rows, id);
	}

	/** Ob die Kasse sich jetzt nicht ändern lässt ({@link CashRegisterSection#refusal}); dann steht die Meldung in der Fußzeile. */
	private boolean refuses(String id) {
		String message = CashRegisterSection.refusal(CashRegisterSection.displayName(event, id),
				repository.lockedCashRegisters(event.getId()).get(id), repository.cashRegistersPending(event.getId()));
		if (message != null) {
			LockedEntryGuard.showError(message);
			return true;
		}
		return false;
	}

	/** Das Modell hat sich geändert: „Speichern *" und die Zeilen nachführen. */
	private void changed() {
		ChangeTracker.events().check(event);
		show(event);
	}

	private void rename(Row row, String name) {
		refreshSoon();
		if (event == null || refuses(row.id())) {
			return;
		}
		if (CashRegisterSection.rename(event, row.id(), name)) {
			ChangeTracker.events().check(event);
		}
	}

	/** Der Schalter: „eine gemeinsame Kasse" legt sie an, „je Vorstellung eine Kasse" entfernt sie, wenn sie leer ist. */
	private void switchShared(boolean toShared) {
		if (event == null) {
			return;
		}
		if (!refuses(CashRegister.SHARED_ID) && !CashRegisters.selectShared(event, toShared)) {
			LockedEntryGuard.showError(CashRegisterSection.SHARED_KEPT);
		}
		// erst nach der Benachrichtigung der Gruppe: Ein abgelehnter Wechsel springt dabei zurück
		Platform.runLater(this::changed);
	}

	/** Eine freie Kasse „Neue Kasse" für alle Vorstellungen; die Eingabe ihres Namens beginnt gleich. */
	private void addFree() {
		if (event == null || !editMode) {
			return;
		}
		if (repository.cashRegistersPending(event.getId())) {
			LockedEntryGuard.showError(CashRegisterSection.PENDING);
			return;
		}
		String id = CashRegisters.addFree(event).getId();
		changed();
		select(id);
		Platform.runLater(() -> {
			int index = indexOf(id);
			if (index >= 0 && table.isEditable()) {
				table.scrollTo(index);
				table.requestFocus();
				table.layout();
				table.edit(index, nameColumn);
			}
		});
	}

	/** „Kasse löschen" nach Rückfrage; mit Ist-Betrag oder Kommentar bleibt die Kasse, die Fußzeile sagt warum. */
	private void delete(Row row) {
		CashRegister register = event == null || !row.deletable() ? null : event.findCashRegister(row.id());
		if (register == null || refuses(row.id())) {
			return;
		}
		String name = CashRegisterSection.displayName(event, row.id());
		if (!new CashState(register.getActualCashAmount(), register.getCashDifferenceComment()).isEmpty()) {
			LockedEntryGuard.showError(CashRegisterSection.deleteRefused(name));
			return;
		}
		if (!confirmation.confirm(register, () -> ConfirmDialog.confirmDelete("Kasse löschen", CashRegisterSection.deleteConfirm(name)))
				|| refuses(row.id())) {
			return;
		}
		if (!CashRegisters.removeFree(event, row.id())) {
			LockedEntryGuard.showError(CashRegisterSection.deleteRefused(name));
		}
		changed();
	}

	/** Eine Zeile mit „Kasse löschen" im Kontextmenü, wenn die Kasse sich löschen lässt (nur freie, nicht gesperrte). */
	private final class DeletableRow extends TableRow<Row> {
		private final ContextMenu menu;

		DeletableRow() {
			MenuItem delete = new MenuItem("Kasse löschen");
			delete.setOnAction(e -> {
				if (getItem() != null) {
					delete(getItem());
				}
			});
			menu = new ContextMenu(delete);
		}

		@Override
		protected void updateItem(Row item, boolean empty) {
			super.updateItem(item, empty);
			setContextMenu(!empty && item != null && item.deletable() ? menu : null);
		}
	}

	/**
	 * „gilt für" einer freien Kasse: Das Bearbeiten öffnet unter der Zelle eine Auswahl aus „alle Vorstellungen" und den
	 * Vorstellungen des Events. Jede Wahl geht sofort ins Modell; solange die Auswahl offen ist, ist die Zelle im Bearbeiten
	 * (fremde Stände warten). Sie schließt mit einem Klick daneben oder Esc.
	 */
	private final class ScopeCell extends TableCell<Row, String> {
		private ContextMenu menu;

		@Override
		protected void updateItem(String item, boolean empty) {
			super.updateItem(item, empty);
			setText(empty ? null : item);
		}

		@Override
		public void startEdit() {
			Row row = getTableRow() == null ? null : getTableRow().getItem();
			CashRegister register = row == null || !row.scopeEditable() || event == null ? null : event.findCashRegister(row.id());
			if (register == null) {
				// Die Tabelle hat die Zelle schon als bearbeitet vermerkt: Bliebe das stehen, hielte sie fremde Stände zurück
				if (getTableView() != null && getTableView().getEditingCell() != null && getTableView().getEditingCell().getRow() == getIndex()) {
					getTableView().edit(-1, null);
				}
				return;
			}
			super.startEdit();
			if (isEditing()) {
				menu = scopeMenu(row, register);
				menu.setOnHidden(e -> {
					if (isEditing()) {
						cancelEdit();
					}
				});
				menu.show(this, Side.BOTTOM, 0, 0);
			}
		}

		@Override
		public void cancelEdit() {
			boolean wasEditing = isEditing();
			super.cancelEdit();
			if (wasEditing) {
				refreshSoon();
			}
			if (menu != null) {
				ContextMenu open = menu;
				menu = null;
				open.hide();
			}
		}

		/** Die Auswahl: „alle Vorstellungen", dann je Vorstellung des Events ein Haken; mehrere lassen sich wählen. */
		private ContextMenu scopeMenu(Row row, CashRegister register) {
			ContextMenu choice = new ContextMenu();
			CheckBox all = new CheckBox(CashRegisterSection.ALL_PRESENTATIONS);
			List<CheckBox> boxes = new ArrayList<>();
			List<Presentation> presentations = List.copyOf(event.getPresentations());
			Runnable showModel = () -> {
				all.setSelected(register.isAllPresentations());
				for (int i = 0; i < boxes.size(); i++) {
					boxes.get(i).setSelected(register.getPresentationIds().contains(presentations.get(i).getId()));
				}
				setText(CashRegisterSection.freeScope(event, register));
			};
			all.setOnAction(e -> changeScope(row, choice, showModel,
					() -> CashRegisterSection.selectAllPresentations(event, row.id(), all.isSelected())));
			choice.getItems().addAll(new CustomMenuItem(all, false), new SeparatorMenuItem());
			for (Presentation presentation : presentations) {
				CheckBox box = new CheckBox(EntityLabels.nameOrPlaceholder(presentation.getName()));
				box.setOnAction(e -> changeScope(row, choice, showModel,
						() -> CashRegisterSection.selectPresentation(event, row.id(), presentation.getId(), box.isSelected())));
				boxes.add(box);
				choice.getItems().add(new CustomMenuItem(box, false));
			}
			showModel.run();
			return choice;
		}

		private void changeScope(Row row, ContextMenu choice, Runnable showModel, Runnable change) {
			if (refuses(row.id())) {
				choice.hide();
				return;
			}
			change.run();
			ChangeTracker.events().check(event);
			showModel.run();
		}
	}
}
