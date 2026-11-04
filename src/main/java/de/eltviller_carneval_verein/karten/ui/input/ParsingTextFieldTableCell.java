package de.eltviller_carneval_verein.karten.ui.input;

import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumn.CellEditEvent;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
 * {@link TableInput}.
 *
 * @param <S> Typ der Zeilen
 * @param <T> Typ des Zellwerts
 */
public class ParsingTextFieldTableCell<S, T> extends TextFieldTableCell<S, T> {

	/** JavaFX kennt keine Pseudoklasse für die bearbeitete Zelle; das Stylesheet (style.css) braucht sie für ihren Rahmen. */
	private static final PseudoClass EDITING = PseudoClass.getPseudoClass("editing");

	/** Schlüssel in {@link TextField#getProperties()}: Die Handler hängen schon am Feld. */
	private static final Object EDITOR_BEHAVIOR_INSTALLED = new Object();

	private final ParsingConverter<T> parsing;
	private final String fieldName;
	private final Function<S, String> rowLabel;
	private final Supplier<T> fallbackIfEmpty;
	/** Welche Zeilen sich bearbeiten lassen; {@code null}: alle (die Spalte entscheidet). */
	private final Predicate<S> editableRow;
	private boolean commitFailed;
	/** Stelle, Zeile und Wert beim Beginn des Bearbeitens: Beendet die Tabelle es, geht die Eingabe dorthin ({@link #cancelEdit}). */
	private TablePosition<S, T> editStart;
	private S editRow;
	private T editValue;

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
		this.parsing = new ParsingConverter<>(converter, issueOnFailure, this::getItem);
		this.fieldName = fieldName;
		this.rowLabel = rowLabel;
		this.fallbackIfEmpty = fallbackIfEmpty;
		this.editableRow = editableRow;
		setConverter(parsing);
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
	boolean canEdit() {
		if (editableRow == null) {
			return true;
		}
		S row = getTableRow() == null ? null : getTableRow().getItem();
		return row != null && editableRow.test(row);
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
		if (!canEdit()) {
			// Die Tabelle hat diese Zelle schon als bearbeitet vermerkt (Klick, Enter, table.edit); bliebe das stehen, nähme sie
			// keine Taste mehr an und hielte fremde Änderungen zurück
			TableView<S> table = getTableView();
			if (table != null && table.getEditingCell() != null && table.getEditingCell().getRow() == getIndex()
					&& table.getEditingCell().getTableColumn() == getTableColumn()) {
				table.edit(-1, null);
			}
			return;
		}
		// Ein Merker aus einer früheren Bearbeitung darf nicht an dieser hängen
		parsing.takeFailure();
		super.startEdit();
		pseudoClassStateChanged(EDITING, isEditing());
		if (isEditing()) {
			// JavaFX richtet die Zelle beim Bearbeiten oben aus: Das Feld und der Cursor sollen links und mittig stehen
			setAlignment(Pos.CENTER_LEFT);
			editStart = new TablePosition<>(getTableView(), getIndex(), getTableColumn());
			editRow = getTableRow() == null ? null : getTableRow().getItem();
			editValue = getItem();
		}
		if (isEditing() && getGraphic() instanceof TextField field) {
			installEditorBehavior(field);
		}
	}

	/**
	 * Verwirft die Eingabe, wenn die Zelle selbst abbricht (Esc, nichts geändert, unlesbar). Beendet dagegen die Tabelle das
	 * Bearbeiten, etwa beim Klick auf eine andere Zeile ({@code table.edit(-1, null)}, bevor das Feld den Fokus verliert),
	 * übernimmt die Zelle die Eingabe ({@link #commitEndedEdit}).
	 */
	@Override
	public void cancelEdit() {
		if (isEditing() && endedByTable() && getGraphic() instanceof TextField field) {
			commitEndedEdit(field.getText());
			return;
		}
		super.cancelEdit();
		pseudoClassStateChanged(EDITING, false);
	}

	/** Ob die Tabelle diese Zelle nicht mehr als bearbeitet führt (keine oder eine andere Zelle im Bearbeiten). */
	private boolean endedByTable() {
		TableView<S> table = getTableView();
		TablePosition<S, ?> editing = table == null ? null : table.getEditingCell();
		return editing == null || editing.getRow() != editStart.getRow() || editing.getTableColumn() != editStart.getTableColumn();
	}

	/**
	 * Übernimmt die Eingabe eines Bearbeitens, das die Tabelle schon beendet hat, nach denselben Regeln wie
	 * {@link #commitEdit}. Die Zelle ruft dafür nicht {@code super.commitEdit}: Das setzte {@code table.edit(-1, null)} und
	 * beendete so eine Zelle, die die Tabelle schon bearbeitet. Der Wert geht an die Zeile, in der das Bearbeiten begann,
	 * und nur, solange dort dieselbe Zeile steht.
	 */
	private void commitEndedEdit(String text) {
		T value = parsing.fromString(text);
		// Die Meldung nennt die Zeile, in der das Bearbeiten begann; rot markiert wird nur, solange die Zelle sie noch zeigt
		Optional<ValidationIssue> issue = parsing.takeFailure()
				.map(failure -> failure.issue().toIssue(labelOf(editRow), InputFeedback.displayText(failure.text())));
		Node marked = getIndex() == editStart.getRow() ? this : null;
		TableView<S> table = getTableView();
		boolean sameRow = table != null && editStart.getRow() < table.getItems().size() && table.getItems().get(editStart.getRow()) == editRow;
		super.cancelEdit();
		pseudoClassStateChanged(EDITING, false);
		if (issue.isPresent()) {
			Optional<T> replacement = CellValues.replacementAfterFailure(editValue, fallbackIfEmpty);
			if (replacement.isEmpty()) {
				InputFeedback.report(issue.get(), marked);
				return;
			}
			// wie in commitEdit: Der Ersatzwert ändert das Modell, die Meldung muss die Live-Prüfung überdauern
			InputFeedback.reportAfterChange(issue.get(), marked);
			value = replacement.get();
		}
		if (!sameRow || CellValues.isUnchanged(editValue, value)) {
			return;
		}
		Event.fireEvent(getTableColumn(), new CellEditEvent<>(table, editStart, TableColumn.editCommitEvent(), value));
		if (marked != null) {
			// wie super.commitEdit: Die Zelle zeigt den neuen Wert, bis die Tabelle sie neu zeichnet
			updateItem(value, false);
		}
	}

	@Override
	public void commitEdit(T newValue) {
		Optional<ParsingConverter.Failure> failure = parsing.takeFailure();
		if (failure.isEmpty()) {
			if (CellValues.isUnchanged(getItem(), newValue)) {
				// Nichts geändert (ein leeres Feld ist auch "nichts"): Der Wert wird nicht erneut ins Modell geschrieben,
				// sonst würde aus einem fehlenden Text "" und der Änderungsanzeiger sprang ohne Änderung an
				cancelEdit();
				return;
			}
			super.commitEdit(newValue);
			pseudoClassStateChanged(EDITING, false);
			return;
		}
		Optional<T> replacement = CellValues.replacementAfterFailure(getItem(), fallbackIfEmpty);
		if (replacement.isPresent()) {
			// Leeres Feld: Statt die Eingabe zu verwerfen, gilt der Ersatzwert (bei einem Datum der heutige Tag). Die Meldung
			// bleibt, damit man sieht, dass der getippte Text nicht gelesen wurde.
			InputFeedback.reportAfterChange(failure.get().issue().toIssue(label(), InputFeedback.displayText(failure.get().text())), this);
			super.commitEdit(replacement.get());
			pseudoClassStateChanged(EDITING, false);
			return;
		}
		commitFailed = true;
		cancelEdit();
		InputFeedback.report(failure.get().issue().toIssue(label(), InputFeedback.displayText(failure.get().text())), this);
	}

	/**
	 * Zusätzlich zu Enter (das JavaFX selbst übernimmt): Der Fokusverlust übernimmt die Eingabe, Tab übernimmt und bearbeitet
	 * die nächste bearbeitbare Zelle, Umschalt+Tab die vorherige (siehe {@link TableInput#moveEditing}), Esc verwirft. Das Feld einer Zelle
	 * bleibt über viele Bearbeitungen dasselbe: Die Handler hängen nur einmal daran.
	 */
	private void installEditorBehavior(TextField field) {
		if (field.getProperties().putIfAbsent(EDITOR_BEHAVIOR_INSTALLED, Boolean.TRUE) != null) {
			return;
		}
		field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
			if (!isFocused && isEditing()) {
				commitFromField(field);
			}
		});
		field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
			if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
				return;
			}
			if (event.getCode() == KeyCode.ESCAPE) {
				// Esc verwirft über die Zelle selbst: Die Tabelle beendete sonst mit table.edit(-1, null), und das übernähme
				// die Eingabe wie ein Klick auf eine andere Zeile (cancelEdit)
				event.consume();
				cancelEdit();
				return;
			}
			if (event.getCode() != KeyCode.TAB) {
				return;
			}
			event.consume();
			TableView<S> table = getTableView();
			int row = getIndex();
			TableColumn<S, T> column = getTableColumn();
			commitFromField(field);
			if (!commitFailed) {
				if (!TableInput.moveEditing(table, row, column, !event.isShiftDown())) {
					table.requestFocus();
				}
			}
		});
	}

	/** Übernimmt den Text des Feldes auf demselben Weg wie Enter. */
	private void commitFromField(TextField field) {
		commitFailed = false;
		Event.fireEvent(field, new ActionEvent(field, field));
	}

	private String label() {
		TableRow<S> row = getTableRow();
		return labelOf(row == null ? null : row.getItem());
	}

	private String labelOf(S row) {
		return InputFeedback.label(fieldName, row == null ? null : rowLabel.apply(row));
	}
}
