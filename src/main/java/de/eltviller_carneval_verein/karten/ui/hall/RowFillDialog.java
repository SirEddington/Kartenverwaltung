package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.Optional;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Der Dialog "Reihe füllen": fragt Tischzahl und Sitze je Tisch ab, mit denen der Aufrufer
 * {@link de.eltviller_carneval_verein.karten.model.SeatingPlan#fillRow} aufruft. Eine Zahl unter 1 legt nichts an, mehr als
 * {@value #MAX_TABLES} Tische oder {@value #MAX_SEATS_PER_TABLE} Sitze je Tisch lehnt der Dialog ab; ein unlesbarer Text hält den Dialog offen, die Fußzeile nennt den Grund (INP-001).
 */
public final class RowFillDialog {

	/** Die Eingaben des Dialogs. */
	public record Fill(int tableCount, int seatsPerTable) {
	}

	// Startwerte: wie bisher eine Spalte der Saalübersicht (7 Tische) und die Sitze je Tisch der Event-Anlage
	private static final int DEFAULT_TABLE_COUNT = 7;
	private static final int DEFAULT_SEATS_PER_TABLE = 12;
	// Obergrenzen je Aufruf, damit eine Tippfehler-Eingabe (z. B. 100000) die Oberfläche nicht einfriert
	static final int MAX_TABLES = 200;
	static final int MAX_SEATS_PER_TABLE = 100;

	private RowFillDialog() {
	}

	/**
	 * Zeigt den Dialog und wartet auf das Schließen.
	 *
	 * @param rowName z. B. "Reihe A", für Text und Meldungen
	 * @param owner   Fenster, über dem der Dialog erscheint; {@code null} für das Standardverhalten
	 * @return die Eingaben bei "OK", leer bei "Abbrechen"
	 */
	public static Optional<Fill> show(String rowName, Window owner) {
		Spinner<Integer> tableCount = spinner(DEFAULT_TABLE_COUNT, "Tischzahl", () -> rowName);
		Spinner<Integer> seatsPerTable = spinner(DEFAULT_SEATS_PER_TABLE, "Sitze je Tisch", () -> rowName);

		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		grid.addRow(0, new Label("Tischzahl:"), tableCount);
		grid.addRow(1, new Label("Sitze je Tisch:"), seatsPerTable);
		Label hint = new Label("Die neuen Tische kommen hinten an die " + rowName + ".");
		hint.setWrapText(true);
		VBox content = new VBox(12, hint, grid);
		content.setPadding(new Insets(15, 20, 5, 20));

		ButtonType ok = new ButtonType("OK", ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);
		Alert dialog = new Alert(AlertType.NONE, null, ok, cancel);
		dialog.setTitle("Reihe füllen");
		dialog.setHeaderText(null);
		dialog.getDialogPane().setContent(content);
		if (owner != null) {
			dialog.initOwner(owner);
		}
		MainApp.applyAppIcon(dialog);
		Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
		okButton.getStyleClass().add("button-primary");
		((Button) dialog.getDialogPane().lookupButton(cancel)).getStyleClass().add("button-default");

		// Getippter Text gilt auch ohne Verlassen des Feldes; ein unlesbarer hält den Dialog offen (alter Wert bleibt stehen)
		okButton.addEventFilter(ActionEvent.ACTION, event -> {
			tableCount.commitValue();
			seatsPerTable.commitValue();
			if (SpinnerInput.blockSaveIfInputRejected(tableCount, seatsPerTable)) {
				event.consume();
				return;
			}
			// Anzahlen sind keine Modellfelder, der Validator kann sie nicht prüfen: Zu große Werte würden die Oberfläche blockieren
			if (tableCount.getValue() > MAX_TABLES || seatsPerTable.getValue() > MAX_SEATS_PER_TABLE) {
				StatusMessage.getInstance().show(Severity.ERROR,
						"Reihe füllen: höchstens " + MAX_TABLES + " Tische mit je " + MAX_SEATS_PER_TABLE + " Sitzen auf einmal.");
				event.consume();
			}
		});
		dialog.setOnShown(event -> Platform.runLater(() -> {
			tableCount.requestFocus();
			tableCount.getEditor().selectAll();
		}));

		Optional<ButtonType> pressed = dialog.showAndWait();
		if (pressed.isEmpty() || pressed.get() != ok) {
			return Optional.empty();
		}
		return Optional.of(new Fill(tableCount.getValue(), seatsPerTable.getValue()));
	}

	private static Spinner<Integer> spinner(int initial, String fieldName, Supplier<String> rowLabel) {
		Spinner<Integer> spinner = new Spinner<>();
		spinner.setValueFactory(SpinnerInput.integerFactory(initial, 1));
		spinner.setEditable(true);
		spinner.setPrefWidth(100);
		SpinnerInput.installInteger(spinner, fieldName, rowLabel);
		return spinner;
	}
}
