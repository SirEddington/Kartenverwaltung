package de.eltviller_carneval_verein.karten.ui.input;

import java.time.LocalDate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.event.Event;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableColumn.CellEditEvent;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.cell.TextFieldTreeTableCell;
import javafx.util.Callback;
import javafx.util.StringConverter;
import javafx.util.converter.DefaultStringConverter;

/**
 * {@link ParsingTextFieldTableCell} für eine {@link TreeTableView}: dieselbe Behandlung unlesbarer Eingabe, dieselbe
 * Übernahme bei Enter, Fokusverlust, Tab und Klick auf eine andere Zeile, Esc verwirft ({@link ParsingEditing}). Zeilen
 * sind die Werte der {@link TreeItem}s.
 *
 * <p>
 * Jede Fabrik gibt es zusätzlich mit einer Bedingung {@code rowEditable}: Nur Zeilen, die sie erfüllen, lassen sich
 * bearbeiten. Die Zelle kennt die Art der Zeilen nicht; dass Gruppenzeilen nie bearbeitbar sind, gibt die Tabelle über
 * diese Bedingung mit. Weil die Meldung zu unlesbarer Eingabe nur aus einer bearbeiteten Zeile kommt, ruft die Zelle
 * {@code rowLabel} nur für Zeilen, die die Bedingung erfüllen.
 *
 * @param <S> Typ der Zeilen (Wert des {@code TreeItem})
 * @param <T> Typ des Zellwerts
 */
public class ParsingTextFieldTreeTableCell<S, T> extends TextFieldTreeTableCell<S, T> implements ParsingCell {

	private final ParsingEditing<S, T> editing;

	/**
	 * @param converter      liest den Text, darf bei unlesbarem Text eine {@link RuntimeException} werfen
	 * @param issueOnFailure Fehlerart der Meldung
	 * @param fieldName      Name des Feldes für die Meldung, z. B. "Preis"
	 * @param rowLabel       Bezeichnung der Zeile für die Meldung, z. B. "Tisch 4, Sitz 2" (darf {@code null} liefern)
	 */
	public ParsingTextFieldTreeTableCell(StringConverter<T> converter, InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel) {
		this(converter, issueOnFailure, fieldName, rowLabel, null);
	}

	/**
	 * @param fallbackIfEmpty liefert den Wert, den ein <em>leeres</em> Feld nach nicht lesbarer Eingabe übernimmt (bei einem
	 *                        Datum der heutige Tag); {@code null}: die Eingabe wird immer verworfen. Die Meldung erscheint
	 *                        in beiden Fällen.
	 */
	public ParsingTextFieldTreeTableCell(StringConverter<T> converter, InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel,
			Supplier<T> fallbackIfEmpty) {
		this(converter, issueOnFailure, fieldName, rowLabel, fallbackIfEmpty, null);
	}

	private ParsingTextFieldTreeTableCell(StringConverter<T> converter, InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel,
			Supplier<T> fallbackIfEmpty, Predicate<S> editableRow) {
		this.editing = new Editing(converter, issueOnFailure, fieldName, rowLabel, fallbackIfEmpty, editableRow);
		setConverter(editing.converter());
	}

	/** Spaltenfabrik für {@code TreeTableColumn.setCellFactory}. */
	public static <S, T> Callback<TreeTableColumn<S, T>, TreeTableCell<S, T>> forTableColumn(StringConverter<T> converter,
			InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel) {
		return forTableColumn(converter, issueOnFailure, fieldName, rowLabel, null);
	}

	/**
	 * Wie {@link #forTableColumn(StringConverter, InputIssue, String, Function)}, aber nur Zeilen, die {@code editableRow}
	 * erfüllen, lassen sich bearbeiten (etwa der gezahlte Betrag nur bei bezahlten Buchungen); {@code null}: alle. Gefragt
	 * wird erst, wenn das Bearbeiten beginnen soll ({@link #canEdit()}).
	 */
	public static <S, T> Callback<TreeTableColumn<S, T>, TreeTableCell<S, T>> forTableColumn(StringConverter<T> converter,
			InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel, Predicate<S> editableRow) {
		return forTableColumn(converter, issueOnFailure, fieldName, rowLabel, editableRow, null);
	}

	/**
	 * Wie {@link #forTableColumn(StringConverter, InputIssue, String, Function, Predicate)}; bearbeitbar sind nur Zeilen, die
	 * beide Bedingungen erfüllen ({@code null}: keine Einschränkung). {@code rowEditable} ist die Bedingung der Tabelle
	 * (etwa: keine Gruppenzeile), {@code editableRow} die der Spalte.
	 */
	public static <S, T> Callback<TreeTableColumn<S, T>, TreeTableCell<S, T>> forTableColumn(StringConverter<T> converter,
			InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel, Predicate<S> editableRow, Predicate<S> rowEditable) {
		Predicate<S> editable = ParsingEditing.both(rowEditable, editableRow);
		return column -> new ParsingTextFieldTreeTableCell<>(converter, issueOnFailure, fieldName, rowLabel, null, editable);
	}

	/**
	 * Spaltenfabrik für eine Datumsspalte (Format {@code dd.MM.yyyy}, leere Eingabe = kein Datum). Ein nicht lesbarer Text
	 * (Meldung {@code INP-002}) trägt in einem leeren Feld {@code today} ein, in einem gefüllten bleibt der alte Wert.
	 */
	public static <S> Callback<TreeTableColumn<S, LocalDate>, TreeTableCell<S, LocalDate>> forDateColumn(String fieldName,
			Function<S, String> rowLabel, Supplier<LocalDate> today) {
		return forDateColumn(fieldName, rowLabel, today, null);
	}

	/** Wie {@link #forDateColumn(String, Function, Supplier)}, nur Zeilen, die {@code rowEditable} erfüllen, lassen sich bearbeiten. */
	public static <S> Callback<TreeTableColumn<S, LocalDate>, TreeTableCell<S, LocalDate>> forDateColumn(String fieldName,
			Function<S, String> rowLabel, Supplier<LocalDate> today, Predicate<S> rowEditable) {
		return column -> new ParsingTextFieldTreeTableCell<>(InputConverters.date(), InputIssue.NOT_A_DATE, fieldName, rowLabel, today,
				rowEditable);
	}

	/** Spaltenfabrik für Textspalten ohne Format (Namen, Beschreibungen, Kommentare): Es gibt nichts Unlesbares. */
	public static <S> Callback<TreeTableColumn<S, String>, TreeTableCell<S, String>> forTextColumn() {
		return forTextColumn(null);
	}

	/** Wie {@link #forTextColumn()}, nur Zeilen, die {@code rowEditable} erfüllen, lassen sich bearbeiten. */
	public static <S> Callback<TreeTableColumn<S, String>, TreeTableCell<S, String>> forTextColumn(Predicate<S> rowEditable) {
		return forTableColumn(new DefaultStringConverter(), InputIssue.NOT_A_NUMBER, "Text", row -> null, null, rowEditable);
	}

	/** Ob die Zeile der Zelle sich bearbeiten lässt (ohne Zeilenbedingung immer); Tabelle und Spalte prüft JavaFX selbst. */
	@Override
	public boolean canEdit() {
		return editing.canEdit();
	}

	@Override
	public void startEdit() {
		editing.startEdit();
	}

	/** Siehe {@link ParsingEditing#cancelEdit()}: Beendet die Tabelle das Bearbeiten, übernimmt die Zelle die Eingabe. */
	@Override
	public void cancelEdit() {
		editing.cancelEdit();
	}

	@Override
	public void commitEdit(T newValue) {
		editing.commitEdit(newValue);
	}

	/** Was die gemeinsame Logik von einer Zelle in einer {@link TreeTableView} braucht. */
	private final class Editing extends ParsingEditing<S, T> {

		Editing(StringConverter<T> converter, InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel,
				Supplier<T> fallbackIfEmpty, Predicate<S> editableRow) {
			super(converter, issueOnFailure, fieldName, rowLabel, fallbackIfEmpty, editableRow);
		}

		@Override
		IndexedCell<T> cell() {
			return ParsingTextFieldTreeTableCell.this;
		}

		@Override
		S rowValue() {
			return getTableRow() == null ? null : getTableRow().getItem();
		}

		@Override
		boolean tableEdits(int row) {
			TreeTableView<S> table = getTreeTableView();
			TreeTablePosition<S, ?> edited = table == null ? null : table.getEditingCell();
			return edited != null && edited.getRow() == row && edited.getTableColumn() == getTableColumn();
		}

		@Override
		void endTableEdit() {
			getTreeTableView().edit(-1, null);
		}

		@Override
		boolean showsRow(int row, S value) {
			TreeTableView<S> table = getTreeTableView();
			TreeItem<S> item = table == null ? null : table.getTreeItem(row);
			return item != null && item.getValue() == value;
		}

		@Override
		void fireCommit(int row, T value) {
			TreeTableView<S> table = getTreeTableView();
			Event.fireEvent(getTableColumn(),
					new CellEditEvent<>(table, new TreeTablePosition<>(table, row, getTableColumn()), TreeTableColumn.editCommitEvent(), value));
		}

		@Override
		boolean moveEditing(int row, boolean forward) {
			return TableInput.moveEditing(getTreeTableView(), row, getTableColumn(), forward);
		}

		@Override
		void focusTable() {
			getTreeTableView().requestFocus();
		}

		@Override
		void superStartEdit() {
			ParsingTextFieldTreeTableCell.super.startEdit();
		}

		@Override
		void superCancelEdit() {
			ParsingTextFieldTreeTableCell.super.cancelEdit();
		}

		@Override
		void superCommitEdit(T value) {
			ParsingTextFieldTreeTableCell.super.commitEdit(value);
		}

		@Override
		void showValue(T value) {
			updateItem(value, false);
		}

		@Override
		void markEditing(boolean on) {
			pseudoClassStateChanged(ParsingEditing.EDITING, on);
		}
	}
}
