package de.eltviller_carneval_verein.karten.ui.input;

import java.util.Optional;
import java.util.function.Function;

import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.util.Callback;
import javafx.util.StringConverter;

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
 * @param <S> Typ der Zeilen
 * @param <T> Typ des Zellwerts
 */
public class ParsingTextFieldTableCell<S, T> extends TextFieldTableCell<S, T> {

	private final ParsingConverter<T> parsing;
	private final String fieldName;
	private final Function<S, String> rowLabel;

	/**
	 * @param converter      liest den Text, darf bei unlesbarem Text eine {@link RuntimeException} werfen
	 * @param issueOnFailure Fehlerart der Meldung
	 * @param fieldName      Name des Feldes für die Meldung, z. B. "Preis"
	 * @param rowLabel       Bezeichnung der Zeile für die Meldung, z. B. "Tisch 4, Sitz 2" (darf {@code null} liefern)
	 */
	public ParsingTextFieldTableCell(StringConverter<T> converter, InputIssue issueOnFailure, String fieldName, Function<S, String> rowLabel) {
		this.parsing = new ParsingConverter<>(converter, issueOnFailure, this::getItem);
		this.fieldName = fieldName;
		this.rowLabel = rowLabel;
		setConverter(parsing);
	}

	/** Spaltenfabrik für {@code TableColumn.setCellFactory}. */
	public static <S, T> Callback<TableColumn<S, T>, TableCell<S, T>> forTableColumn(StringConverter<T> converter, InputIssue issueOnFailure,
			String fieldName, Function<S, String> rowLabel) {
		return column -> new ParsingTextFieldTableCell<>(converter, issueOnFailure, fieldName, rowLabel);
	}

	@Override
	public void startEdit() {
		// Ein Merker aus einer früheren Bearbeitung darf nicht an dieser hängen
		parsing.takeFailure();
		super.startEdit();
	}

	@Override
	public void commitEdit(T newValue) {
		Optional<ParsingConverter.Failure> failure = parsing.takeFailure();
		if (failure.isEmpty()) {
			super.commitEdit(newValue);
			return;
		}
		cancelEdit();
		InputFeedback.report(failure.get().issue().toIssue(label(), InputFeedback.displayText(failure.get().text())), this);
	}

	private String label() {
		TableRow<S> row = getTableRow();
		String rowText = (row == null || row.getItem() == null) ? null : rowLabel.apply(row.getItem());
		return InputFeedback.label(fieldName, rowText);
	}
}
