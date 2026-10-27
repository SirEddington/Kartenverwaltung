package de.eltviller_carneval_verein.karten.ui.backup;

import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.repository.SavePoint;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Entry;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Callback;

/**
 * Der Dialog zum Wiederherstellen aus den Speicherständen: links die Events (bzw. Hallen), auch gelöschte, rechts
 * deren Speicherstände nach Tagen gruppiert, mit Uhrzeit, Rechner, Bediener und Zahl der Änderungen. Für Events und
 * Hallen derselbe, über einen {@link BackupCatalog}. Die Speicherstände werden im Hintergrund gelesen, und das
 * Wiederherstellen läuft im Hintergrund; währenddessen ist der Dialog gesperrt.
 */
public final class BackupRestoreDialog {

	private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	static final String BUSY_TEXT = "Wird wiederhergestellt …";

	private BackupRestoreDialog() {
	}

	static String entryText(Entry entry) {
		return entry.deleted() ? entry.name() + " (gelöscht)" : entry.name();
	}

	static String dayText(LocalDate day) {
		return day.format(DAY_FORMAT);
	}

	/**
	 * Gruppiert die Speicherstände nach dem Tag, an dem sie in der Zone liegen. Die Tage stehen neueste zuerst, die
	 * Reihenfolge innerhalb eines Tages bleibt wie in der Eingabe (neueste zuerst).
	 */
	static Map<LocalDate, List<SavePoint>> groupByDay(List<SavePoint> points, ZoneId zone) {
		Map<LocalDate, List<SavePoint>> days = new TreeMap<>(Comparator.reverseOrder());
		for (SavePoint point : points) {
			days.computeIfAbsent(point.at().atZone(zone).toLocalDate(), day -> new ArrayList<>()).add(point);
		}
		return days;
	}

	/** {@code HH:mm:ss · <Rechnername> · <Bediener> · <n> Änderungen}; ohne Bediener entfällt dieser Teil. */
	static String savePointText(SavePoint point, ZoneId zone) {
		StringBuilder text = new StringBuilder(point.at().atZone(zone).format(CLOCK_FORMAT)).append(" · ").append(point.machineName());
		if (point.operator() != null && !point.operator().isBlank()) {
			text.append(" · ").append(point.operator());
		}
		return text.append(" · ").append(point.unitCount()).append(point.unitCount() == 1 ? " Änderung" : " Änderungen").toString();
	}

	/** Die Rückfrage vor dem Wiederherstellen. */
	static String confirmText(String label, Entry entry, SavePoint point, String restoreWarning, ZoneId zone) {
		return label + " \"" + entry.name() + "\" auf den Stand vom " + AbstractBackupCatalog.timeText(point.at(), zone) + " zurücksetzen?\n\n"
				+ restoreWarning + " Der aktuelle Stand bleibt als Speicherstand erhalten.";
	}

	/** Eine Zellfabrik, die jeden Eintrag nur als Text anzeigt. */
	private static <T> Callback<ListView<T>, ListCell<T>> textCells(Function<T, String> text) {
		return list -> new ListCell<>() {
			@Override
			protected void updateItem(T item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : text.apply(item));
			}
		};
	}

	/** Rechts im Dialog: die Speicherstände nach Tagen, oder ein Platzhaltertext, solange es keine zu zeigen gibt. */
	private static final class SavePointPane extends StackPane {

		private final TreeView<Object> tree = new TreeView<>();
		private final Label placeholder = new Label();
		private final Label busyText = new Label(BUSY_TEXT);

		SavePointPane(ZoneId zone) {
			tree.setShowRoot(false);
			tree.setPrefSize(340, 320);
			tree.setCellFactory(view -> new TreeCell<>() {
				@Override
				protected void updateItem(Object item, boolean empty) {
					super.updateItem(item, empty);
					if (empty || item == null) {
						setText(null);
					} else if (item instanceof LocalDate day) {
						setText(dayText(day));
					} else if (item instanceof SavePoint point) {
						setText(savePointText(point, zone));
					}
				}
			});
			placeholder.setWrapText(true);
			placeholder.setMaxWidth(300);
			placeholder.setMouseTransparent(true);
			busyText.setVisible(false);
			busyText.setMouseTransparent(true);
			getChildren().addAll(tree, placeholder, busyText);
		}

		/**
		 * Zeigt, solange etwas wiederhergestellt wird, nur den Platzhalter {@value BackupRestoreDialog#BUSY_TEXT}. Die Liste
		 * bleibt dabei unverändert (auch die Auswahl) und erscheint danach wieder.
		 */
		void setBusy(boolean busy) {
			busyText.setVisible(busy);
			tree.setVisible(!busy);
			placeholder.setVisible(!busy && tree.getRoot() == null);
		}

		/** Zeigt statt der Liste den Text. */
		void showText(String text) {
			tree.setRoot(null);
			placeholder.setText(text);
			placeholder.setVisible(true);
		}

		/** Zeigt die Speicherstände nach Tagen; der neueste Tag ist aufgeklappt. */
		void showSavePoints(List<SavePoint> points, ZoneId zone) {
			if (points.isEmpty()) {
				showText("Keine Speicherstände vorhanden.");
				return;
			}
			TreeItem<Object> root = new TreeItem<>();
			boolean newest = true;
			for (Map.Entry<LocalDate, List<SavePoint>> day : groupByDay(points, zone).entrySet()) {
				TreeItem<Object> dayItem = new TreeItem<>(day.getKey());
				for (SavePoint point : day.getValue()) {
					dayItem.getChildren().add(new TreeItem<>(point));
				}
				dayItem.setExpanded(newest);
				newest = false;
				root.getChildren().add(dayItem);
			}
			tree.setRoot(root);
			placeholder.setVisible(false);
		}

		/** Der gewählte Speicherstand; {@code null}, wenn nichts oder ein Tag gewählt ist. */
		SavePoint selectedSavePoint() {
			TreeItem<Object> item = tree.getSelectionModel().getSelectedItem();
			return item != null && item.getValue() instanceof SavePoint point ? point : null;
		}

		TreeView<Object> tree() {
			return tree;
		}
	}

	/**
	 * Liest die Speicherstände des Eintrags im Hintergrund. Das Ergebnis erscheint nur, wenn links noch derselbe
	 * Eintrag gewählt ist.
	 */
	private static void loadSavePoints(BackupCatalog catalog, Entry entry, ListView<Entry> entryList, SavePointPane pane, ZoneId zone) {
		if (entry == null) {
			pane.showText("Links einen Eintrag wählen.");
			return;
		}
		pane.showText("Speicherstände werden geladen …");
		Task<List<SavePoint>> task = new Task<>() {
			@Override
			protected List<SavePoint> call() {
				return catalog.versions(entry.id());
			}
		};
		task.setOnSucceeded(event -> {
			if (entry.equals(entryList.getSelectionModel().getSelectedItem())) {
				pane.showSavePoints(task.getValue(), zone);
			}
		});
		task.setOnFailed(event -> {
			if (entry.equals(entryList.getSelectionModel().getSelectedItem())) {
				pane.showText("Speicherstände konnten nicht geladen werden: " + messageOf(task.getException()));
			}
		});
		Thread thread = new Thread(task, "savepoint-loader");
		thread.setDaemon(true);
		thread.start();
	}

	private static String messageOf(Throwable failure) {
		Throwable shown = failure instanceof UncheckedIOException && failure.getCause() != null ? failure.getCause() : failure;
		return shown.getMessage() != null ? shown.getMessage() : shown.toString();
	}

	/**
	 * Zeigt den Dialog und wartet auf das Schließen.
	 *
	 * @return {@code true}, wenn etwas wiederhergestellt wurde (die Übersicht muss neu laden)
	 */
	public static boolean show(BackupCatalog catalog, Window owner) {
		ZoneId zone = ZoneId.systemDefault();

		ListView<Entry> entryList = new ListView<>();
		entryList.setPrefSize(260, 320);
		entryList.setPlaceholder(new Label("Keine Sicherungen vorhanden."));
		entryList.setCellFactory(textCells(BackupRestoreDialog::entryText));
		entryList.getItems().setAll(catalog.entries());

		SavePointPane savePoints = new SavePointPane(zone);
		savePoints.showText("Links einen Eintrag wählen.");
		entryList.getSelectionModel().selectedItemProperty().addListener((obs, oldEntry, entry) -> loadSavePoints(catalog, entry, entryList, savePoints, zone));

		HBox content = new HBox(15, new VBox(5, new Label(catalog.entityLabel() + ":"), entryList),
				new VBox(5, new Label("Speicherstände (neueste zuerst):"), savePoints));
		content.setPadding(new Insets(15, 20, 5, 20));

		ButtonType restore = new ButtonType("Wiederherstellen", ButtonData.OK_DONE);
		ButtonType close = new ButtonType("Schließen", ButtonData.CANCEL_CLOSE);
		Alert dialog = new Alert(AlertType.NONE, null, restore, close);
		dialog.setTitle(catalog.entityLabel() + " wiederherstellen");
		dialog.setHeaderText(null);
		dialog.getDialogPane().setContent(content);
		if (owner != null) {
			dialog.initOwner(owner);
		}
		MainApp.applyAppIcon(dialog);

		Button restoreButton = (Button) dialog.getDialogPane().lookupButton(restore);
		Button closeButton = (Button) dialog.getDialogPane().lookupButton(close);
		restoreButton.setDefaultButton(false);
		restoreButton.getStyleClass().add("button-primary");
		closeButton.getStyleClass().add("button-default");
		// Nur ein Speicherstand lässt sich wiederherstellen, ein Tag nicht; währenddessen auch nichts
		BooleanProperty restoring = new SimpleBooleanProperty();
		restoreButton.disableProperty().bind(Bindings.createBooleanBinding(
				() -> restoring.get() || savePoints.selectedSavePoint() == null,
				savePoints.tree().getSelectionModel().selectedItemProperty(), restoring));
		// Auch das Fenster lässt sich währenddessen nicht schließen (Kreuz, Escape)
		dialog.setOnCloseRequest(event -> {
			if (restoring.get()) {
				event.consume();
			}
		});

		RestoreResult[] done = new RestoreResult[1];
		// Der Dialog bleibt bei "Wiederherstellen" offen, bis es geklappt hat: Fehler zeigt er, ohne die Auswahl zu verlieren
		restoreButton.addEventFilter(ActionEvent.ACTION, event -> {
			event.consume();
			Entry entry = entryList.getSelectionModel().getSelectedItem();
			SavePoint point = savePoints.selectedSavePoint();
			if (entry == null || point == null || !confirm(dialog, catalog, entry, point, zone)) {
				return;
			}
			setRestoring(true, restoring, entryList, savePoints, closeButton);
			// Lesen und Zusammensetzen im Hintergrund, Übernehmen (Cache, Speichern, Listener) auf dem Oberflächen-Thread
			Task<BackupCatalog.Prepared> task = new Task<>() {
				@Override
				protected BackupCatalog.Prepared call() {
					return catalog.prepare(entry, point);
				}
			};
			task.setOnSucceeded(finished -> {
				RestoreResult result;
				try {
					result = catalog.commit(task.getValue());
				} finally {
					setRestoring(false, restoring, entryList, savePoints, closeButton);
				}
				if (result.success()) {
					done[0] = result;
					dialog.close();
				} else {
					MainApp.showAlert("Wiederherstellen fehlgeschlagen", result.message(), AlertType.ERROR);
				}
			});
			task.setOnFailed(finished -> {
				setRestoring(false, restoring, entryList, savePoints, closeButton);
				MainApp.showAlert("Wiederherstellen fehlgeschlagen", messageOf(task.getException()), AlertType.ERROR);
			});
			Thread thread = new Thread(task, "restore");
			thread.setDaemon(true);
			thread.start();
		});

		dialog.showAndWait();

		RestoreResult result = done[0];
		if (result != null && !result.notes().isEmpty()) {
			MainApp.showAlert("Wiederhergestellt", result.message() + "\n\n" + String.join("\n", result.notes()), AlertType.INFORMATION);
		}
		return result != null;
	}

	/** Sperrt den Dialog, solange wiederhergestellt wird: beide Listen und beide Knöpfe, rechts der Platzhalter. */
	private static void setRestoring(boolean restoring, BooleanProperty flag, ListView<Entry> entryList, SavePointPane pane,
			Button closeButton) {
		flag.set(restoring);
		entryList.setDisable(restoring);
		pane.setDisable(restoring);
		closeButton.setDisable(restoring);
		pane.setBusy(restoring);
	}

	/** Die Rückfrage mit dem Hinweis, was mit zurückgesetzt wird und dass der aktuelle Stand erhalten bleibt; Fokus auf "Abbrechen". */
	private static boolean confirm(Alert parent, BackupCatalog catalog, Entry entry, SavePoint point, ZoneId zone) {
		String message = confirmText(catalog.entityLabel(), entry, point, catalog.restoreWarning(), zone);
		return ConfirmDialog.confirm("Wiederherstellen", message, "Wiederherstellen", "button-primary", parent.getDialogPane().getScene().getWindow());
	}
}
