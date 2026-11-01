package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Booking;
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
 * zur Auswahl. Zugewiesen wird nicht hier, sondern vom Aufrufer über {@code BookingSync.assign}. Die Gründe, warum das
 * nicht geht, stehen hier für Verkaufstabelle und Konflikt-Dialog gleich (KARV-109).
 */
public final class AssignPlaceDialog {

	/** Der Grund, wenn die Bestuhlung keinen freien Sitz hat. */
	public static final String NO_FREE_SEAT = "Kein Platz frei: In dieser Bestuhlung ist jeder Sitz belegt.";

	private AssignPlaceDialog() {
	}

	/** Der Grund, wenn die Vorstellung keine Bestuhlung hat, der eine Buchung zugewiesen werden könnte. */
	public static String noPlanText(Presentation presentation) {
		return EntityLabels.presentation(presentation) + " hat keine Bestuhlung; ein Platz lässt sich nicht zuweisen.";
	}

	/** Der Grund, wenn der gewählte Sitz inzwischen eine Buchung mit Daten hat. */
	public static String takenText(Seat seat) {
		return label(seat) + " ist inzwischen belegt. Wähle einen anderen Platz.";
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
		List<Seat> free = freeSeats(presentation, plan);
		if (free.isEmpty()) {
			return Optional.empty();
		}
		ListView<Seat> list = new ListView<>();
		list.getItems().setAll(free);
		list.setCellFactory(view -> new ListCell<>() {
			@Override
			protected void updateItem(Seat seat, boolean empty) {
				super.updateItem(seat, empty);
				setText(empty || seat == null ? null : label(seat));
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

	/**
	 * Die freien Sitze der Bestuhlung, sortiert nach Reihenbuchstabe ({@link Booking#ROW_LABEL_ORDER}), Tisch- und
	 * Sitznummer. Frei ist ein Sitz, wenn keine Buchung der Vorstellung auf ihn zeigt, die Daten trägt
	 * ({@link Booking#hasData()}); ein bloßer Einzelpreis belegt ihn nicht.
	 */
	public static List<Seat> freeSeats(Presentation presentation, SeatingPlan plan) {
		if (plan == null) {
			return List.of();
		}
		Set<String> taken = takenSeatIds(presentation);
		Comparator<Seat> order = Comparator.comparing((Seat seat) -> seat.getParent().getRowLabel(), Booking.ROW_LABEL_ORDER)
				.thenComparingInt(seat -> seat.getParent().getTableNumber()).thenComparingInt(Seat::getSeatNumber);
		return plan.getSeats().stream().filter(seat -> !taken.contains(seat.getId())).sorted(order).toList();
	}

	/** Ob der Sitz frei ist wie bei {@link #freeSeats}, ohne alle freien Sitze zu sammeln und zu sortieren. */
	public static boolean isFree(Presentation presentation, Seat seat) {
		return !takenSeatIds(presentation).contains(seat.getId());
	}

	/** Die Sitze, auf die eine Buchung der Vorstellung mit Daten zeigt. */
	private static Set<String> takenSeatIds(Presentation presentation) {
		Set<String> taken = new HashSet<>();
		if (presentation != null && presentation.getBookings() != null) {
			for (Booking booking : presentation.getBookings()) {
				if (booking.getSeatId() != null && booking.hasData()) {
					taken.add(booking.getSeatId());
				}
			}
		}
		return taken;
	}

	/** "Tisch A3, Sitz 2" bzw. "Tisch 3, Sitz 2" ohne Reihenbuchstaben. */
	public static String label(Seat seat) {
		return seat.getParent().getDisplayName() + ", Sitz " + seat.getSeatNumber();
	}
}
