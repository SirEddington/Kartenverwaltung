package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.BookingMove;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Die Platzliste beim Verschieben von Buchungen in der Kartentabelle (KARV-140): alle nicht gesperrten Sitze der
 * Bestuhlung mit dem, was dort ist, je Zeile ein Häkchen. Der Dialog zeigt den Stand beim Öffnen; ob er noch stimmt,
 * prüft der Plan, den der Aufrufer bei "Weiter" berechnet. Verschoben wird nicht hier, sondern vom Aufrufer über
 * {@link MoveAction}.
 */
final class MoveTargetDialog {

	/** Eine Zeile der Liste: der Sitz und sein Text ({@link MoveTexts#targetRow}). */
	record Row(String seatId, String text) {
	}

	private MoveTargetDialog() {
	}

	/**
	 * Alle nicht gesperrten Sitze der Bestuhlung in der Platzreihenfolge des Plans ({@link BookingMove#SEAT_ORDER}), mit
	 * {@link MoveTexts#targetRow}; ohne Bestuhlung leer.
	 */
	static List<Row> rows(Presentation presentation, SeatingPlan plan, List<BookingMove.Source> sources,
			Set<String> lockedSeatIds) {
		if (plan == null) {
			return List.of();
		}
		Set<String> sourceSeats = new HashSet<>();
		sources.forEach(source -> sourceSeats.add(source.seatId()));
		return plan.getSeats().stream().filter(seat -> !lockedSeatIds.contains(seat.getId()))
				.sorted(BookingMove.SEAT_ORDER)
				.map(seat -> new Row(seat.getId(), MoveTexts.targetRow(seat, BookingMove.occupantOf(presentation, seat),
						sourceSeats.contains(seat.getId()))))
				.toList();
	}

	/** Für wen gewählt wird: wie die Leiste der Saalansicht ({@link MoveTexts#barText}), ohne die Zahl der gewählten Plätze. */
	static String header(List<BookingMove.Source> sources) {
		return sources.size() == 1 ? MoveTexts.barText(sources, 0) : "Ziele wählen für " + sources.size() + " Buchungen";
	}

	/**
	 * Zeigt die Liste und wartet auf das Schließen. Ein Klick auf eine Zeile (oder die Leertaste) wählt sie oder wählt sie
	 * ab, ohne Strg. "Weiter" ist nur mit genau so vielen gewählten Plätzen aktiv, wie es Quellen gibt.
	 *
	 * @param onNext bei "Weiter" mit den gewählten Sitzkennungen in Platzreihenfolge gerufen; liefert einen Text, wenn die
	 *               Liste offen bleiben und ihn über den Knöpfen zeigen soll, "" wenn sie ohne Text offen bleiben soll,
	 *               {@code null} wenn sie schließen soll
	 * @param owner  Fenster, über dem der Dialog erscheint; {@code null} für das Standardverhalten
	 */
	static void show(Presentation presentation, SeatingPlan plan, List<BookingMove.Source> sources,
			Set<String> lockedSeatIds, Function<List<String>, String> onNext, Window owner) {
		int needed = sources.size();
		ObservableSet<String> chosen = FXCollections.observableSet();
		ListView<Row> list = new ListView<>();
		list.getItems().setAll(rows(presentation, plan, sources, lockedSeatIds));
		list.setCellFactory(view -> new ChoiceCell(chosen));
		list.setPrefSize(380, 360);
		list.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
			Row focused = list.getFocusModel().getFocusedItem();
			if (event.getCode() == KeyCode.SPACE && focused != null) {
				event.consume();
				toggle(chosen, focused.seatId());
			}
		});

		Label count = new Label();
		count.textProperty().bind(Bindings.createStringBinding(() -> chosen.size() + " von " + needed + " Plätzen gewählt", chosen));
		Label message = new Label();
		message.setWrapText(true);
		message.setMaxWidth(380);
		message.managedProperty().bind(message.textProperty().isEmpty().not());
		Label header = new Label(header(sources));
		header.setWrapText(true);
		header.setMaxWidth(380);
		VBox content = new VBox(8, header, list, count, message);
		content.setPadding(new Insets(15, 20, 5, 20));

		ButtonType next = new ButtonType("Weiter", ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);
		Alert dialog = new Alert(AlertType.NONE, null, next, cancel);
		dialog.setTitle(needed > 1 ? "Buchungen verschieben" : "Buchung verschieben");
		dialog.setHeaderText(null);
		dialog.getDialogPane().setContent(content);
		if (owner != null) {
			dialog.initOwner(owner);
		}
		MainApp.applyAppIcon(dialog);
		Button nextButton = (Button) dialog.getDialogPane().lookupButton(next);
		nextButton.getStyleClass().add("button-primary");
		nextButton.disableProperty().bind(Bindings.createBooleanBinding(() -> chosen.size() != needed, chosen));
		((Button) dialog.getDialogPane().lookupButton(cancel)).getStyleClass().add("button-default");
		// "Weiter" schließt nicht von selbst: Liefert onNext einen Text, bleibt die Liste mit ihrer Wahl offen
		nextButton.addEventFilter(ActionEvent.ACTION, event -> {
			List<String> targets = list.getItems().stream().map(Row::seatId).filter(chosen::contains).toList();
			String text = onNext.apply(targets);
			if (text != null) {
				event.consume();
				message.setText(text);
			}
		});

		dialog.showAndWait();
	}

	private static void toggle(Set<String> chosen, String seatId) {
		if (!chosen.remove(seatId)) {
			chosen.add(seatId);
		}
	}

	/** Eine Zeile mit Häkchen; der Klick auf die ganze Zeile schaltet es um. */
	private static final class ChoiceCell extends ListCell<Row> {

		private final ObservableSet<String> chosen;
		private final CheckBox box = new CheckBox();

		ChoiceCell(ObservableSet<String> chosen) {
			this.chosen = chosen;
			box.setMouseTransparent(true);
			box.setFocusTraversable(false);
			chosen.addListener((SetChangeListener<String>) change -> updateBox());
			setOnMouseClicked(event -> {
				if (event.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null) {
					toggle(chosen, getItem().seatId());
				}
			});
		}

		@Override
		protected void updateItem(Row row, boolean empty) {
			super.updateItem(row, empty);
			if (empty || row == null) {
				setText(null);
				setGraphic(null);
			} else {
				setText(row.text());
				setGraphic(box);
				updateBox();
			}
		}

		private void updateBox() {
			Row row = getItem();
			box.setSelected(row != null && !isEmpty() && chosen.contains(row.seatId()));
		}
	}
}
