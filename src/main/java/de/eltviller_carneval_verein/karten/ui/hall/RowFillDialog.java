package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.Optional;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
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
 * Der Dialog "Reihe füllen": fragt Tischzahl, Sitze je Tisch und die Maße von Tischen und Sitzen ab, mit denen der
 * Aufrufer {@link SeatingPlan#fillRow(de.eltviller_carneval_verein.karten.model.TableRow, int, int, double, double, double)}
 * aufruft. Vorbelegt sind die Standardmaße der Bestuhlung ({@link Sizes#of}). Eine Anzahl unter 1 legt nichts an; mehr
 * als {@value #MAX_TABLES} Tische oder {@value #MAX_SEATS_PER_TABLE} Sitze je Tisch und Maße außerhalb der Grenzen lehnt
 * der Dialog ab ({@link #rejection}); ein unlesbarer Text hält den Dialog offen, die Fußzeile nennt den Grund (INP-001).
 */
public final class RowFillDialog {

	/** Die Eingaben des Dialogs; Maße in cm, der Sitz mit seinem Durchmesser (Sitze sind Kreise). */
	public record Fill(int tableCount, int seatsPerTable, int tableWidth, int tableHeight, int seatWidth) {
	}

	/** Die Vorbelegung der Maße in ganzen cm. */
	public record Sizes(int tableWidth, int tableHeight, int seatWidth) {

		/** Die Standardmaße der Bestuhlung, gerundet; fehlt eines (0 oder kleiner), gilt das Standardmaß neuer Bestuhlungen. */
		public static Sizes of(SeatingPlan plan) {
			return new Sizes(rounded(plan.getDefaultTableWidth(), SeatingPlan.STANDARD_TABLE_WIDTH),
					rounded(plan.getDefaultTableHeight(), SeatingPlan.STANDARD_TABLE_HEIGHT),
					rounded(plan.getDefaultSeatWidth(), SeatingPlan.STANDARD_SEAT_WIDTH));
		}

		private static int rounded(double value, double standard) {
			return (int) Math.round(value > 0 ? value : standard);
		}
	}

	// Startwerte der Anzahlen: eine Spalte der Saalübersicht (6 Tische) und die Sitze je Tisch der Event-Anlage
	private static final int DEFAULT_TABLE_COUNT = 6;
	private static final int DEFAULT_SEATS_PER_TABLE = 12;
	// Obergrenzen je Aufruf, damit eine Tippfehler-Eingabe (z. B. 100000) die Oberfläche nicht einfriert
	static final int MAX_TABLES = 200;
	static final int MAX_SEATS_PER_TABLE = 100;
	// Grenzen der Maße wie in TableValidator und SeatValidator, aber ohne 0: Neue Tische und Sitze brauchen eine Größe
	static final int MAX_TABLE_SIZE = 1_000;
	static final int MAX_SEAT_SIZE = 100;

	private RowFillDialog() {
	}

	/**
	 * Warum der Dialog diese Eingaben ablehnt (Text für die Fußzeile), oder {@code null}, wenn sie gelten. Die Anzahlen
	 * sind keine Modellfelder, und eine Bestuhlung mit Maßen außerhalb der Grenzen würde der Validator erst danach melden.
	 */
	static String rejection(Fill fill) {
		if (fill.tableCount() > MAX_TABLES || fill.seatsPerTable() > MAX_SEATS_PER_TABLE) {
			return "Reihe füllen: höchstens " + MAX_TABLES + " Tische mit je " + MAX_SEATS_PER_TABLE + " Sitzen auf einmal.";
		}
		if (outside(fill.tableWidth(), MAX_TABLE_SIZE) || outside(fill.tableHeight(), MAX_TABLE_SIZE) || outside(fill.seatWidth(), MAX_SEAT_SIZE)) {
			return "Reihe füllen: Tischbreite und Tischhöhe zwischen 1 und " + MAX_TABLE_SIZE + " cm, Sitzdurchmesser zwischen 1 und "
					+ MAX_SEAT_SIZE + " cm.";
		}
		return null;
	}

	private static boolean outside(int value, int max) {
		return value < 1 || value > max;
	}

	/**
	 * Zeigt den Dialog und wartet auf das Schließen.
	 *
	 * @param rowName z. B. "Reihe A", für Text und Meldungen
	 * @param sizes   die Vorbelegung der Maße
	 * @param owner   Fenster, über dem der Dialog erscheint; {@code null} für das Standardverhalten
	 * @return die Eingaben bei "OK", leer bei "Abbrechen"
	 */
	public static Optional<Fill> show(String rowName, Sizes sizes, Window owner) {
		Spinner<Integer> tableCount = spinner(DEFAULT_TABLE_COUNT, "Tischzahl", () -> rowName);
		Spinner<Integer> seatsPerTable = spinner(DEFAULT_SEATS_PER_TABLE, "Sitze je Tisch", () -> rowName);
		Spinner<Integer> tableWidth = spinner(sizes.tableWidth(), "Tischbreite", () -> rowName);
		Spinner<Integer> tableHeight = spinner(sizes.tableHeight(), "Tischhöhe", () -> rowName);
		Spinner<Integer> seatWidth = spinner(sizes.seatWidth(), "Sitzdurchmesser", () -> rowName);
		@SuppressWarnings("unchecked")
		Spinner<Integer>[] all = new Spinner[] { tableCount, seatsPerTable, tableWidth, tableHeight, seatWidth };

		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		grid.addRow(0, new Label("Tischzahl:"), tableCount);
		grid.addRow(1, new Label("Sitze je Tisch:"), seatsPerTable);
		grid.addRow(2, new Label("Tischbreite (cm):"), tableWidth);
		grid.addRow(3, new Label("Tischhöhe (cm):"), tableHeight);
		grid.addRow(4, new Label("Sitzdurchmesser (cm):"), seatWidth);
		Label hint = new Label("Die neuen Tische kommen hinten an die " + rowName + ". Die Maße gelten danach auch für einzeln hinzugefügte Tische und Sitze.");
		hint.setWrapText(true);
		hint.setMaxWidth(320);
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
			for (Spinner<Integer> spinner : all) {
				spinner.commitValue();
			}
			if (SpinnerInput.blockSaveIfInputRejected(all)) {
				event.consume();
				return;
			}
			String rejection = rejection(fillOf(all));
			if (rejection != null) {
				StatusMessage.getInstance().show(Severity.ERROR, rejection);
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
		return Optional.of(fillOf(all));
	}

	private static Fill fillOf(Spinner<Integer>[] all) {
		return new Fill(all[0].getValue(), all[1].getValue(), all[2].getValue(), all[3].getValue(), all[4].getValue());
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
