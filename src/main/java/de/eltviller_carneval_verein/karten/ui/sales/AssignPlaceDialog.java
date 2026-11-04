package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;
import java.util.Optional;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Der Dialog "Platz zuweisen…" für eine Buchung ohne Platz: zeigt die freien Sitze der Bestuhlung ("Tisch A3, Sitz 2")
 * zur Auswahl. Zugewiesen wird nicht hier, sondern vom Aufrufer über {@code BookingSync.assign}. Welche Sitze frei sind und
 * warum sich kein Platz zuweisen lässt, steht in {@link PlaceAssignment}.
 */
public final class AssignPlaceDialog {

	private AssignPlaceDialog() {
	}

	/**
	 * Zeigt den Dialog und wartet auf das Schließen.
	 *
	 * @param presentation die Vorstellung der Buchung
	 * @param plan         ihre Bestuhlung
	 * @param owner        Fenster, über dem der Dialog erscheint; {@code null} für das Standardverhalten
	 * @return der gewählte freie Sitz, leer bei "Abbrechen" oder wenn kein Sitz frei ist
	 */
	public static Optional<Seat> choose(Presentation presentation, SeatingPlan plan, Window owner) {
		List<Seat> free = PlaceAssignment.freeSeats(presentation, plan);
		if (free.isEmpty()) {
			return Optional.empty();
		}
		ListView<Seat> list = new ListView<>();
		list.getItems().setAll(free);
		list.setCellFactory(view -> new ListCell<>() {
			@Override
			protected void updateItem(Seat seat, boolean empty) {
				super.updateItem(seat, empty);
				setText(empty || seat == null ? null : EntityLabels.placeOf(seat));
			}
		});
		list.setPrefSize(260, 320);
		list.getSelectionModel().selectFirst();
		Label hint = new Label("Freie Sitze der Bestuhlung:");
		VBox content = new VBox(8, hint, list);
		content.setPadding(new Insets(15, 20, 5, 20));

		ButtonType ok = new ButtonType("Zuweisen", ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType("Abbrechen", ButtonData.CANCEL_CLOSE);
		Alert dialog = new Alert(AlertType.NONE, null, ok, cancel);
		dialog.setTitle("Platz zuweisen");
		dialog.setHeaderText(null);
		dialog.getDialogPane().setContent(content);
		if (owner != null) {
			dialog.initOwner(owner);
		}
		MainApp.applyAppIcon(dialog);
		Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
		okButton.getStyleClass().add("button-primary");
		okButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
		((Button) dialog.getDialogPane().lookupButton(cancel)).getStyleClass().add("button-default");
		// Doppelklick auf einen Sitz wählt ihn wie "Zuweisen"
		list.setOnMouseClicked(event -> {
			if (event.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
				okButton.fire();
			}
		});

		Optional<ButtonType> answer = dialog.showAndWait();
		if (answer.isPresent() && answer.get() == ok) {
			return Optional.ofNullable(list.getSelectionModel().getSelectedItem());
		}
		return Optional.empty();
	}
}
