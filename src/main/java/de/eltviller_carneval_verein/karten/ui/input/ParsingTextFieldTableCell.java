package de.eltviller_carneval_verein.karten.ui.input;

import java.time.LocalDate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.event.Event;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumn.CellEditEvent;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.util.Callback;
import javafx.util.StringConverter;
import javafx.util.converter.DefaultStringConverter;

/**
 * Textzelle, die eine nicht lesbare Eingabe an der Eingabestelle behandelt, statt eine Exception in den
 * {@code GlobalErrorHandler} (SYS-001) laufen zu lassen: Die Bearbeitung wird abgebrochen, der alte Wert bleibt, die
 * Fußzeile nennt Code und Grund, und die Zelle wird kurz rot ({@link InputFeedback}). Der {@code OnEditCommit}-Handler
 * der Spalte läuft dann gar nicht und bleibt ein einzeiliger Setter-Aufruf.
 *
 * <p>
 * Der Converter wird in einen {@link ParsingConverter} gepackt, der den Fehler merkt. Die Zelle prüft den Merker in
 * {@link #commitEdit}, unabhängig davon, ob JavaFX das Bestätigen per Enter oder beim Fokusverlust anstößt.
 *
 * <p>
 * Sie ist die gemeinsame Textzelle aller Tabellen mit Eingabe (auch für Spalten ohne Format, siehe
 * {@link #forTextColumn()}): Enter, Fokusverlust, Tab und ein Klick auf eine andere Zeile übernehmen die Eingabe, Tab
 * bearbeitet gleich die nächste Zelle, Esc verwirft. Wie das Bearbeiten beginnt (Tippen, Enter, F2, Doppelklick), regelt
 * {@link TableInput}. Die Logik teilt sie mit {@link ParsingTextFieldTreeTableCell} ({@link ParsingEditing}).
 *
 * @param <S> Typ der Zeilen
 * @param <T> Typ des Zellwerts
 */
public class ParsingTextFieldTableCell<S, T> extends TextFieldTableCell<S, T> implements ParsingCell {

	private final ParsingEditing<S, T> editing;

	/**
	 * @param converter      liest den Text, darf bei unlesbarem Text eine {@link RuntimeException} werfen
	 * @param issueOnFailure Fehlerart der Meldung
	 * @param fieldName      Name des Feldes für die Meldung, z. B. "Preis"
	 * @param rowLabel       Bezeichnung der Zeile für die Meldung, z. B. "Tisch 4, Sitz 2" (darf {@code null} liefern)
	 */
	public ParsingTextFieldTableCell(StringConverter<T> converter, InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel) {
		this(converter, issueOnFailure, fieldName, rowLabel, null);
	}

	/**
	 * @param fallbackIfEmpty liefert den Wert, den ein <em>leeres</em> Feld nach nicht lesbarer Eingabe übernimmt (bei einem
	 *                        Datum der heutige Tag); {@code null}: die Eingabe wird immer verworfen. Die Meldung erscheint
	 *                        in beiden Fällen.
	 */
	public ParsingTextFieldTableCell(StringConverter<T> converter, InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel,
			Supplier<T> fallbackIfEmpty) {
		this(converter, issueOnFailure, fieldName, rowLabel, fallbackIfEmpty, null);
	}

	private ParsingTextFieldTableCell(StringConverter<T> converter, InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel,
			Supplier<T> fallbackIfEmpty, Predicate<S> editableRow) {
		this.editing = new Editing(converter, issueOnFailure, fieldName, rowLabel, fallbackIfEmpty, editableRow);
		setConverter(editing.converter());
	}

	/** Spaltenfabrik für {@code TableColumn.setCellFactory}. */
	public static <S, T> Callback<TableColumn<S, T>, TableCell<S, T>> forTableColumn(StringConverter<T> converter, InputIssue issueOnFailure,
			String fieldName, Function<S, String> rowLabel) {
		return forTableColumn(converter, issueOnFailure, fieldName, rowLabel, null);
	}

	/**
	 * Wie {@link #forTableColumn(StringConverter, InputIssue, String, Function)}, aber nur Zeilen, die {@code editableRow}
	 * erfüllen, lassen sich bearbeiten (etwa der gezahlte Betrag nur bei bezahlten Buchungen); {@code null}: alle. Gefragt
	 * wird erst, wenn das Bearbeiten beginnen soll ({@link #canEdit()}).
	 */
	public static <S, T> Callback<TableColumn<S, T>, TableCell<S, T>> forTableColumn(StringConverter<T> converter, InputIssue issueOnFailure,
			String fieldName, Function<S, String> rowLabel, Predicate<S> editableRow) {
		return column -> new ParsingTextFieldTableCell<>(converter, issueOnFailure, fieldName, rowLabel, null, editableRow);
	}

	/** Ob die Zeile der Zelle sich bearbeiten lässt (ohne Zeilenbedingung immer); Tabelle und Spalte prüft JavaFX selbst. */
	@Override
	public boolean canEdit() {
		return editing.canEdit();
	}

	/**
	 * Spaltenfabrik für eine Datumsspalte (Format {@code dd.MM.yyyy}, leere Eingabe = kein Datum). Ein nicht lesbarer Text
	 * (Meldung {@code INP-002}) trägt in einem leeren Feld {@code today} ein, in einem gefüllten bleibt der alte Wert.
	 */
	public static <S> Callback<TableColumn<S, LocalDate>, TableCell<S, LocalDate>> forDateColumn(String fieldName, Function<S, String> rowLabel,
			Supplier<LocalDate> today) {
		return column -> new ParsingTextFieldTableCell<>(InputConverters.date(), InputIssue.NOT_A_DATE, fieldName, rowLabel, today);
	}

	/** Spaltenfabrik für Textspalten ohne Format (Namen, Beschreibungen, Kommentare): Es gibt nichts Unlesbares. */
	public static <S> Callback<TableColumn<S, String>, TableCell<S, String>> forTextColumn() {
		return forTableColumn(new DefaultStringConverter(), InputIssue.NOT_A_NUMBER, "Text", row -> null);
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

	/** Was die gemeinsame Logik von einer Zelle in einer {@link TableView} braucht. */
	private final class Editing extends ParsingEditing<S, T> {

		Editing(StringConverter<T> converter, InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel,
				Supplier<T> fallbackIfEmpty, Predicate<S> editableRow) {
			super(converter, issueOnFailure, fieldName, rowLabel, fallbackIfEmpty, editableRow);
		}

		@Override
		IndexedCell<T> cell() {
			return ParsingTextFieldTableCell.this;
		}

		@Override
		S rowValue() {
			return getTableRow() == null ? null : getTableRow().getItem();
		}

		@Override
		boolean tableEdits(int row) {
			TableView<S> table = getTableView();
			TablePosition<S, ?> edited = table == null ? null : table.getEditingCell();
			return edited != null && edited.getRow() == row && edited.getTableColumn() == getTableColumn();
		}

		@Override
		void endTableEdit() {
			getTableView().edit(-1, null);
		}

		@Override
		boolean showsRow(int row, S value) {
			TableView<S> table = getTableView();
			return table != null && row < table.getItems().size() && table.getItems().get(row) == value;
		}

		@Override
		void fireCommit(int row, T value) {
			TableView<S> table = getTableView();
			Event.fireEvent(getTableColumn(),
					new CellEditEvent<>(table, new TablePosition<>(table, row, getTableColumn()), TableColumn.editCommitEvent(), value));
		}

		@Override
		boolean moveEditing(int row, boolean forward) {
			return TableInput.moveEditing(getTableView(), row, getTableColumn(), forward);
		}

		@Override
		void focusTable() {
			getTableView().requestFocus();
		}

		@Override
		void superStartEdit() {
			ParsingTextFieldTableCell.super.startEdit();
		}

		@Override
		void superCancelEdit() {
			ParsingTextFieldTableCell.super.cancelEdit();
		}

		@Override
		void superCommitEdit(T value) {
			ParsingTextFieldTableCell.super.commitEdit(value);
		}

		@Override
		void showValue(T value) {
			updateItem(value, false);
		}
	}
}
