package de.eltviller_carneval_verein.karten.ui.input;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.StringConverter;

/**
 * Die gemeinsame Logik der Textzellen mit Fehlerbehandlung ({@link ParsingTextFieldTableCell} für {@code TableView},
 * {@link ParsingTextFieldTreeTableCell} für {@code TreeTableView}): Merker für unlesbare Eingabe, Ersatzwert, Übernahme
 * beim Beenden durch die Tabelle, Tab, Esc und Fokusverlust. Die Zellen leiten {@code startEdit}, {@code cancelEdit},
 * {@code commitEdit} und {@code canEdit} hierher und setzen nur die abstrakten Methoden um: was von der Tabellenart
 * abhängt und die Aufrufe der Oberklasse.
 *
 * @param <S> Typ der Zeilen (in einer Baumtabelle der Wert des {@code TreeItem})
 * @param <T> Typ des Zellwerts
 */
abstract class ParsingEditing<S, T> {

	/** JavaFX kennt keine Pseudoklasse für die bearbeitete Zelle; das Stylesheet (style.css) braucht sie für ihren Rahmen. */
	static final PseudoClass EDITING = PseudoClass.getPseudoClass("editing");

	/** Schlüssel in {@link TextField#getProperties()}: Die Handler hängen schon am Feld. */
	private static final Object EDITOR_BEHAVIOR_INSTALLED = new Object();

	private final ParsingConverter<T> parsing;
	private final String fieldName;
	private final Function<S, String> rowLabel;
	private final Supplier<T> fallbackIfEmpty;
	/** Welche Zeilen sich bearbeiten lassen; {@code null}: alle (die Spalte entscheidet). */
	private final Predicate<S> editableRow;
	private boolean commitFailed;
	/** Index, Zeile und Wert beim Beginn des Bearbeitens: Beendet die Tabelle es, geht die Eingabe dorthin ({@link #cancelEdit}). */
	private int editStartRow;
	private S editRow;
	private T editValue;

	/**
	 * @param converter       liest den Text, darf bei unlesbarem Text eine {@link RuntimeException} werfen
	 * @param issueOnFailure  Fehlerart der Meldung
	 * @param fieldName       Name des Feldes für die Meldung, z. B. "Preis"
	 * @param rowLabel        Bezeichnung der Zeile für die Meldung (darf {@code null} liefern)
	 * @param fallbackIfEmpty Wert, den ein leeres Feld nach unlesbarer Eingabe übernimmt; {@code null}: keiner
	 * @param editableRow     welche Zeilen sich bearbeiten lassen; {@code null}: alle
	 */
	ParsingEditing(StringConverter<T> converter, InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel,
			Supplier<T> fallbackIfEmpty, Predicate<S> editableRow) {
		this.parsing = new ParsingConverter<>(converter, issueOnFailure, () -> cell().getItem());
		this.fieldName = fieldName;
		this.rowLabel = rowLabel;
		this.fallbackIfEmpty = fallbackIfEmpty;
		this.editableRow = editableRow;
	}

	/** Beide Bedingungen zusammen; {@code null} steht für „alle Zeilen“. */
	static <S> Predicate<S> both(Predicate<S> first, Predicate<S> second) {
		if (first == null) {
			return second;
		}
		return second == null ? first : first.and(second);
	}

	/** Der Converter, den die Zelle setzt. */
	final ParsingConverter<T> converter() {
		return parsing;
	}

	// ---- von der Zelle umgesetzt ----

	/** Die Zelle selbst. */
	abstract IndexedCell<T> cell();

	/** Der Wert der Zeile, in der die Zelle steht; {@code null}: keine. */
	abstract S rowValue();

	/** Ob die Tabelle die Zelle in Zeile {@code row} und der Spalte dieser Zelle als bearbeitet führt. */
	abstract boolean tableEdits(int row);

	/** Beendet das Bearbeiten in der Tabelle ({@code edit(-1, null)}). */
	abstract void endTableEdit();

	/** Ob in Zeile {@code row} der Tabelle noch die Zeile {@code value} steht. */
	abstract boolean showsRow(int row, S value);

	/** Schickt {@code value} als Übernahme für Zeile {@code row} an die Spalte dieser Zelle. */
	abstract void fireCommit(int row, T value);

	/** {@link TableInput#moveEditing} für die Tabelle der Zelle, von Zeile {@code row} und der Spalte dieser Zelle aus. */
	abstract boolean moveEditing(int row, boolean forward);

	abstract void focusTable();

	abstract void superStartEdit();

	abstract void superCancelEdit();

	abstract void superCommitEdit(T value);

	/** Zeigt den Wert, bis die Tabelle die Zelle neu zeichnet ({@code updateItem(value, false)}). */
	abstract void showValue(T value);

	/** Setzt die Pseudoklasse {@link #EDITING}. */
	abstract void markEditing(boolean editing);

	// ---- gemeinsame Logik ----

	/** Ob die Zeile der Zelle sich bearbeiten lässt (ohne Zeilenbedingung immer); Tabelle und Spalte prüft JavaFX selbst. */
	final boolean canEdit() {
		if (editableRow == null) {
			return true;
		}
		S row = rowValue();
		return row != null && editableRow.test(row);
	}

	final void startEdit() {
		IndexedCell<T> cell = cell();
		if (!canEdit()) {
			// Die Tabelle hat diese Zelle schon als bearbeitet vermerkt (Klick, Enter, table.edit); bliebe das stehen, nähme sie
			// keine Taste mehr an und hielte fremde Änderungen zurück
			if (tableEdits(cell.getIndex())) {
				endTableEdit();
			}
			return;
		}
		// Ein Merker aus einer früheren Bearbeitung darf nicht an dieser hängen
		parsing.takeFailure();
		superStartEdit();
		markEditing(cell.isEditing());
		if (cell.isEditing()) {
			// JavaFX richtet die Zelle beim Bearbeiten oben aus: Das Feld und der Cursor sollen links und mittig stehen
			cell.setAlignment(Pos.CENTER_LEFT);
			editStartRow = cell.getIndex();
			editRow = rowValue();
			editValue = cell.getItem();
			if (cell.getGraphic() instanceof TextField field) {
				installEditorBehavior(field);
			}
		}
	}

	/**
	 * Verwirft die Eingabe, wenn die Zelle selbst abbricht (Esc, nichts geändert, unlesbar). Beendet dagegen die Tabelle das
	 * Bearbeiten, etwa beim Klick auf eine andere Zeile ({@code table.edit(-1, null)}, bevor das Feld den Fokus verliert),
	 * übernimmt die Zelle die Eingabe ({@link #commitEndedEdit}).
	 */
	final void cancelEdit() {
		IndexedCell<T> cell = cell();
		if (cell.isEditing() && !tableEdits(editStartRow) && cell.getGraphic() instanceof TextField field) {
			commitEndedEdit(field.getText());
			return;
		}
		superCancelEdit();
		markEditing(false);
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
		Node marked = cell().getIndex() == editStartRow ? cell() : null;
		boolean sameRow = showsRow(editStartRow, editRow);
		superCancelEdit();
		markEditing(false);
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
		fireCommit(editStartRow, value);
		if (marked != null) {
			// wie super.commitEdit: Die Zelle zeigt den neuen Wert, bis die Tabelle sie neu zeichnet
			showValue(value);
		}
	}

	final void commitEdit(T newValue) {
		IndexedCell<T> cell = cell();
		Optional<ParsingConverter.Failure> failure = parsing.takeFailure();
		if (failure.isEmpty()) {
			if (CellValues.isUnchanged(cell.getItem(), newValue)) {
				// Nichts geändert (ein leeres Feld ist auch "nichts"): Der Wert wird nicht erneut ins Modell geschrieben,
				// sonst würde aus einem fehlenden Text "" und der Änderungsanzeiger sprang ohne Änderung an
				cancelEdit();
				return;
			}
			superCommitEdit(newValue);
			markEditing(false);
			return;
		}
		Optional<T> replacement = CellValues.replacementAfterFailure(cell.getItem(), fallbackIfEmpty);
		if (replacement.isPresent()) {
			// Leeres Feld: Statt die Eingabe zu verwerfen, gilt der Ersatzwert (bei einem Datum der heutige Tag). Die Meldung
			// bleibt, damit man sieht, dass der getippte Text nicht gelesen wurde.
			InputFeedback.reportAfterChange(failure.get().issue().toIssue(label(), InputFeedback.displayText(failure.get().text())), cell);
			superCommitEdit(replacement.get());
			markEditing(false);
			return;
		}
		commitFailed = true;
		cancelEdit();
		InputFeedback.report(failure.get().issue().toIssue(label(), InputFeedback.displayText(failure.get().text())), cell);
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
			if (!isFocused && cell().isEditing()) {
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
			int row = cell().getIndex();
			commitFromField(field);
			if (!commitFailed && !moveEditing(row, !event.isShiftDown())) {
				focusTable();
			}
		});
	}

	/** Übernimmt den Text des Feldes auf demselben Weg wie Enter. */
	private void commitFromField(TextField field) {
		commitFailed = false;
		Event.fireEvent(field, new ActionEvent(field, field));
	}

	private String label() {
		return labelOf(rowValue());
	}

	private String labelOf(S row) {
		return InputFeedback.label(fieldName, row == null ? null : rowLabel.apply(row));
	}
}
