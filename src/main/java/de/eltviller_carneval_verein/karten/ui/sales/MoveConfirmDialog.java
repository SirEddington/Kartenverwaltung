package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;
import java.util.Optional;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.BookingMove;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Der Bestätigungsdialog beim Verschieben von Buchungen (KARV-140): je Zug eine Zeile, bei abgeholten Buchungen die Frage,
 * ob die Markierung "Abgeholt" bleibt. Die Texte stehen in {@link MoveTexts}; verschoben wird nicht hier, sondern von
 * {@link MoveAction}.
 */
final class MoveConfirmDialog {

	private static final int MAX_VISIBLE_LINES = 8;
	private static final double LINE_HEIGHT = 26;

	private MoveConfirmDialog() {
	}

	/**
	 * Zeigt den Dialog und wartet auf das Schließen.
	 *
	 * @param plan  der ausführbare Plan
	 * @param owner Fenster, über dem der Dialog erscheint; {@code null} für das Standardverhalten
	 * @return leer bei "Abbrechen", sonst ob "Abgeholt" behalten wird ({@code true} auch ohne abgeholte Buchung)
	 */
	static Optional<Boolean> ask(BookingMove.Plan plan, Window owner) {
		List<String> lines = MoveTexts.confirmLines(plan);
		ListView<String> list = new ListView<>();
		list.getItems().setAll(lines);
		list.setFocusTraversable(false);
		list.setPrefWidth(440);
		list.setPrefHeight(Math.min(lines.size(), MAX_VISIBLE_LINES) * LINE_HEIGHT + 4);

		VBox content = new VBox(8, list);
		content.setPadding(new Insets(15, 20, 5, 20));
		CheckBox keep = new CheckBox("Markierung „Abgeholt“ behalten");
		keep.setSelected(true);
		String collectedLine = MoveTexts.collectedLine(plan);
		if (collectedLine != null) {
			content.getChildren().addAll(new Label(collectedLine), keep);
		}

		ButtonType ok = new ButtonType("Verschieben", ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);
		Alert dialog = new Alert(AlertType.NONE, null, ok, cancel);
		dialog.setTitle(MoveTexts.title(plan));
		dialog.setHeaderText(null);
		dialog.getDialogPane().setContent(content);
		if (owner != null) {
			dialog.initOwner(owner);
		}
		MainApp.applyAppIcon(dialog);
		Node okButton = dialog.getDialogPane().lookupButton(ok);
		okButton.getStyleClass().add("button-primary");
		((Button) dialog.getDialogPane().lookupButton(cancel)).getStyleClass().add("button-default");

		Optional<ButtonType> answer = dialog.showAndWait();
		if (answer.isPresent() && answer.get() == ok) {
			return Optional.of(keep.isSelected());
		}
		return Optional.empty();
	}
}
