package de.eltviller_carneval_verein.karten.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.store.ConflictKey;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.ui.ConflictActions.Availability;
import de.eltviller_carneval_verein.karten.ui.ConflictList.FieldRow;
import de.eltviller_carneval_verein.karten.ui.ConflictList.Row;
import de.eltviller_carneval_verein.karten.ui.sales.AssignPlaceDialog;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Der Sammel-Dialog für die offenen Konflikte des Rechners (Spec KARV-86): eine Tabelle mit einer Zeile je Konflikt und den
 * Spalten Art, Eintrag, Gegenseite, Zeitpunkt, Werte, Aktion und "Erledigt". Nicht modal, höchstens ein Fenster je App:
 * Ein erneutes {@link #show} holt es nach vorn und frischt es auf. Die Zeilen liefert {@link ConflictList}, ob und was eine
 * Aktion tut, {@link ConflictActions}; hier ist nur die Anzeige. Scheitert eine Aktion, bleibt die Zeile offen und der Grund
 * steht in der Zeile. Texte vorläufig (Überarbeitung in KARV-87). Alle Aufrufe im JavaFX-Thread.
 */
public final class ConflictDialog {

	private static final Logger LOG = Logger.getLogger(ConflictDialog.class.getName());
	/** Das Format des Zeitpunkts wie im Wiederherstellen-Dialog. */
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
	/** Was von der Breite einer Spalte für den Rand der Zelle abgeht. */
	private static final double CELL_PADDING = 24;
	private static final String TAKE_VALUE = "Meinen Wert übernehmen";
	private static final String TAKE_HALL = "Meine Fassung übernehmen";

	/** Das eine Fenster der App; {@code null}, solange keins offen war. */
	private static ConflictDialog open;

	private final ConflictList list;
	private final ConflictActions actions;
	private final Stage stage = new Stage();
	private final TableView<Row> table = new TableView<>();

	/** Zu jeder Zeile, wie {@link #refresh} sie vorfand: ob die Aktion möglich ist (einmal je Auffrischen, nicht je Zeichnen). */
	private final Map<ConflictKey, Availability> availability = new HashMap<>();
	/** Zusatzzeilen zu Zeilen ohne Feldgruppen (Platz einer Doppelverkaufs-Buchung, Erklärung), ebenfalls je Auffrischen. */
	private final Map<ConflictKey, List<String>> details = new HashMap<>();
	/** Wie viele Zeilen ein Speichervorgang (Doppelverkauf) hat. */
	private final Map<String, Integer> groupSizes = new HashMap<>();
	/** Warum die Aktion einer Zeile scheiterte, bis sie gelingt oder die Zeile verschwindet. */
	private final Map<ConflictKey, String> errors = new HashMap<>();

	private ConflictDialog(Window owner, ConflictList list, ConflictActions actions) {
		this.list = list;
		this.actions = actions;
		stage.setTitle("Offene Konflikte");
		if (owner != null) {
			stage.initOwner(owner);
		}
		try {
			stage.getIcons().add(new Image(ConflictDialog.class.getResourceAsStream("images/harlekin_logo.png")));
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Symbol des Konflikt-Dialogs nicht ladbar", e);
		}

		Label intro = new Label("Diese Änderungen von dir haben gegen die eines anderen Rechners verloren; es gilt der Stand der "
				+ "anderen Seite. Übernimm deinen Wert oder bestätige mit „Erledigt“.");
		intro.setWrapText(true);
		table.setPlaceholder(new Label("Keine offenen Konflikte"));
		table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
		table.getColumns().add(column("Art", 110, (row, col) -> wrapped(kindText(row), col)));
		table.getColumns().add(column("Eintrag", 190, (row, col) -> wrapped(row.entry(), col)));
		table.getColumns().add(column("Gegenseite", 120, (row, col) -> wrapped(row.counterpart(), col)));
		table.getColumns().add(column("Zeitpunkt", 115, (row, col) -> wrapped(
				TIME_FORMAT.format(row.at().atZone(ZoneId.systemDefault())), col)));
		table.getColumns().add(column("Werte", 290, this::valuesCell));
		table.getColumns().add(column("Aktion", 225, this::actionCell));
		table.getColumns().add(column("Erledigt", 95, (row, col) -> doneButton(row)));

		VBox root = new VBox(10, intro, table);
		root.setPadding(new Insets(12));
		VBox.setVgrow(table, Priority.ALWAYS);
		Scene scene = new Scene(root, 1230, 520);
		scene.getStylesheets().add(ConflictDialog.class.getResource("style.css").toExternalForm());
		stage.setScene(scene);
		// Was der Nutzer in den Editoren tat, während der Dialog offen war, ändert, was möglich ist
		stage.focusedProperty().addListener((observable, was, now) -> {
			if (now) {
				refresh();
			}
		});
		stage.setOnHidden(event -> {
			if (open == this) {
				open = null;
			}
		});
	}

	/**
	 * Zeigt den Dialog; ist er schon offen, wird er nach vorn geholt und aufgefrischt. Was die Tabelle zeigt, gilt als gesehen
	 * ({@link ConflictList#markSeen}).
	 *
	 * @param owner das Hauptfenster; {@code null} ohne Elternfenster
	 */
	@SuppressWarnings("exports")
	public static void show(Window owner, ConflictList list, ConflictActions actions) {
		if (open != null && open.stage.isShowing()) {
			open.refresh();
			open.stage.toFront();
			return;
		}
		open = new ConflictDialog(owner, list, actions);
		open.refresh();
		open.stage.show();
	}

	/** Frischt den Dialog auf, wenn er offen ist; sonst nichts. */
	public static void refreshIfOpen() {
		if (open != null && open.stage.isShowing()) {
			open.refresh();
		}
	}

	// Auffrischen -->

	private void refresh() {
		List<Row> rows = list.rows();
		availability.clear();
		details.clear();
		groupSizes.clear();
		Set<ConflictKey> keys = new HashSet<>();
		for (Row row : rows) {
			ConflictKey key = row.conflict().key();
			keys.add(key);
			availability.put(key, availabilityOf(row));
			details.put(key, detailsOf(row));
			if (row.group() != null) {
				groupSizes.merge(row.group(), 1, Integer::sum);
			}
		}
		errors.keySet().retainAll(keys);
		table.getItems().setAll(rows);
		// die Tabelle zeigt jetzt alles, was die Liste geliefert hat
		list.markSeen();
	}

	private Availability availabilityOf(Row row) {
		try {
			return actions.availability(row);
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Die Aktion eines Konflikts ließ sich nicht prüfen", e);
			return new Availability(false, "Nicht prüfbar");
		}
	}

	/** Die Zeilen unter den Werten, soweit die Zeile keine Feldgruppen hat. */
	private List<String> detailsOf(Row row) {
		List<String> lines = new ArrayList<>();
		ConflictNote note = row.conflict().note();
		if (note == null) {
			int count = row.conflict().deletion().affected().size();
			lines.add("Darunter standen " + count + (count == 1 ? " Eintrag" : " Einträge")
					+ " von dir, die der andere Rechner nicht kannte. Der Stand lässt sich aus dem Verlauf wiederherstellen.");
			return lines;
		}
		switch (note.kind()) {
		case DOUBLE_SALE -> {
			Booking booking = actions.bookingToAssign(row);
			String place = ConflictList.placeText(note);
			if (booking != null) {
				String name = ((booking.getFirstName() == null ? "" : booking.getFirstName().trim()) + " "
						+ (booking.getLastName() == null ? "" : booking.getLastName().trim())).trim();
				lines.add("Deine Buchung" + (name.isEmpty() ? "" : " (" + name + ")") + " steht jetzt ohne Platz.");
			} else {
				lines.add("Deine Buchung steht nicht mehr ohne Platz im Event.");
			}
			if (place != null) {
				lines.add("Bisheriger Platz: " + place);
			}
		}
		case DELETE_WINS -> {
			lines.add("Der andere Rechner hat gelöscht; die Löschung gilt.");
			if (row.action() == ConflictList.Action.NONE) {
				lines.add("Der Stand lässt sich aus dem Verlauf wiederherstellen.");
			}
		}
		case UNMERGED -> lines.add("Die Änderungen ließen sich nicht zusammenführen; der Stand der anderen Seite gilt.");
		case FIELDS -> {
			// die Gruppen stehen in den Feldzeilen
		}
		}
		return lines;
	}

	// <-- Auffrischen

	// Spalten -->

	/** Die Art; bei einem Verkauf mit mehreren Plätzen steht dabei, dass die Zeilen zusammengehören. */
	private String kindText(Row row) {
		Integer size = row.group() == null ? null : groupSizes.get(row.group());
		return size != null && size > 1 ? row.kindText() + " (" + size + " Plätze eines Verkaufs)" : row.kindText();
	}

	private TableColumn<Row, Row> column(String title, double width, BiFunction<Row, TableColumn<Row, Row>, Node> content) {
		TableColumn<Row, Row> column = new TableColumn<>(title);
		column.setPrefWidth(width);
		column.setSortable(false);
		column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
		column.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(Row row, boolean empty) {
				super.updateItem(row, empty);
				setGraphic(empty || row == null ? null : content.apply(row, column));
				setText(null);
			}

			/**
			 * Die Höhe nach der Breite der Spalte: Die Tabelle fragt sonst bei einer noch nicht gesetzten Breite, und die
			 * umbrechenden Texte würden Buchstabe für Buchstabe umbrechen (Zeilen von über tausend Pixeln).
			 */
			@Override
			protected double computePrefHeight(double width) {
				Node graphic = getGraphic();
				if (graphic == null) {
					return super.computePrefHeight(width);
				}
				return graphic.prefHeight(Math.max(column.getWidth() - CELL_PADDING, 60)) + snappedTopInset() + snappedBottomInset();
			}
		});
		return column;
	}

	/** Ein Text, der umbricht und die Zeile höher macht, statt abgeschnitten zu werden. */
	private static Label wrapped(String text, TableColumn<Row, Row> column) {
		Label label = new Label(text);
		label.setWrapText(true);
		label.setMinHeight(Region.USE_PREF_SIZE);
		label.maxWidthProperty().bind(column.widthProperty().subtract(CELL_PADDING));
		return label;
	}

	private Node valuesCell(Row row, TableColumn<Row, Row> column) {
		VBox box = new VBox(4);
		ConflictKey key = row.conflict().key();
		for (FieldRow field : row.fields()) {
			String text = field.label() + ": dein Wert: " + field.mine() + " / gültig: " + field.valid();
			box.getChildren().add(wrapped(text, column));
		}
		for (String line : details.getOrDefault(key, List.of())) {
			box.getChildren().add(wrapped(line, column));
		}
		String error = errors.get(key);
		if (error != null) {
			Label label = wrapped(error, column);
			label.getStyleClass().add("conflict-error");
			box.getChildren().add(label);
		}
		return box;
	}

	private Node actionCell(Row row, TableColumn<Row, Row> column) {
		VBox box = new VBox(4);
		box.setAlignment(Pos.CENTER_LEFT);
		Availability available = availability.getOrDefault(row.conflict().key(), new Availability(false, ""));
		switch (row.action()) {
		case ASSIGN_PLACE -> box.getChildren().add(actionButton("Platz zuweisen", available, () -> assignPlace(row)));
		case TAKE_MY_HALL -> box.getChildren().add(actionButton(TAKE_HALL, available, () -> takeMyHall(row)));
		case RESTORE_BOOKING -> box.getChildren()
				.add(actionButton("Buchung wiederherstellen", available, () -> run(row, () -> actions.restoreBooking(row))));
		case TAKE_MY_VALUE -> {
			boolean several = row.fields().size() > 1;
			for (FieldRow field : row.fields()) {
				box.getChildren().add(valueAction(row, field, available, several, column));
			}
		}
		case NONE -> {
			// nichts zu tun, außer zu bestätigen
		}
		}
		if (row.action() != ConflictList.Action.NONE && !available.enabled() && !available.reason().isBlank()) {
			Label reason = wrapped(available.reason(), column);
			reason.getStyleClass().add("field-caption");
			box.getChildren().add(reason);
		}
		return box;
	}

	/** Die Aktion einer Gruppe: der Knopf, oder "übernommen", wenn das Modell den eigenen Wert schon hat. */
	private Node valueAction(Row row, FieldRow field, Availability available, boolean several, TableColumn<Row, Row> column) {
		if (field.adopted()) {
			Label adopted = new Label(several ? field.label() + ": übernommen" : "übernommen");
			adopted.getStyleClass().add("conflict-adopted");
			return adopted;
		}
		Button button = actionButton(TAKE_VALUE, available, () -> takeMyValue(row, field));
		if (!several) {
			return button;
		}
		Label caption = new Label(field.label() + ":");
		caption.getStyleClass().add("field-caption");
		VBox box = new VBox(2, caption, button);
		return box;
	}

	private static Button actionButton(String text, Availability available, Runnable action) {
		Button button = new Button(text);
		button.getStyleClass().add("button-secondary");
		button.setDisable(!available.enabled());
		if (!available.enabled() && !available.reason().isBlank()) {
			button.setTooltip(new Tooltip(available.reason()));
		}
		button.setOnAction(event -> action.run());
		return button;
	}

	private Node doneButton(Row row) {
		Button button = new Button("Erledigt");
		button.getStyleClass().add("button-default");
		button.setTooltip(new Tooltip("Bestätigt den Konflikt, ohne etwas zu ändern; er verschwindet aus der Liste."));
		button.setOnAction(event -> run(row, () -> actions.dismiss(row)));
		return button;
	}

	// <-- Spalten

	// Aktionen -->

	/** Führt die Aktion aus; scheitert sie, steht der Grund in der Zeile, die offen bleibt. Danach wird aufgefrischt. */
	private void run(Row row, Runnable action) {
		ConflictKey key = row.conflict().key();
		try {
			action.run();
			errors.remove(key);
		} catch (IllegalStateException e) {
			errors.put(key, e.getMessage() != null ? e.getMessage() : StoreMessages.describe(e));
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Die Aktion eines Konflikts ist gescheitert", e);
			errors.put(key, StoreMessages.describe(e));
		}
		refresh();
	}

	private void takeMyValue(Row row, FieldRow field) {
		String impact = actions.bookingImpact(row, field.group());
		if (impact != null && !ConfirmDialog.confirm("Bestuhlung wechseln", impact, "Wechseln", "button-primary", stage)) {
			return;
		}
		run(row, () -> actions.takeMyValue(row, field.group()));
	}

	private void takeMyHall(Row row) {
		String impact = actions.bookingImpact(row, null);
		if (impact != null && !ConfirmDialog.confirm("Meine Fassung übernehmen", impact, "Übernehmen", "button-primary", stage)) {
			return;
		}
		run(row, () -> actions.takeMyHall(row));
	}

	/**
	 * "Platz zuweisen": Der Nutzer wählt einen freien Sitz ({@link AssignPlaceDialog}), die Buchung "ohne Platz" bekommt ihn
	 * ({@link ConflictActions#assignPlace}). Geht das nicht (keine Bestuhlung, kein Sitz frei, Sitz inzwischen belegt), nennt
	 * die Zeile den Grund, wie die Fußzeile im Verkauf.
	 */
	private void assignPlace(Row row) {
		Booking booking = actions.bookingToAssign(row);
		Presentation presentation = booking == null ? null : booking.getParent();
		if (presentation == null) {
			refresh();
			return;
		}
		SeatingPlan plan = actions.planOf(presentation);
		if (plan == null) {
			run(row, () -> {
				throw new IllegalStateException(
						"Platz zuweisen nicht möglich: " + EntityLabels.presentation(presentation) + " hat keine Bestuhlung.");
			});
			return;
		}
		if (AssignPlaceDialog.freeSeats(presentation, plan).isEmpty()) {
			run(row, () -> {
				throw new IllegalStateException("Platz zuweisen nicht möglich: In der Bestuhlung ist kein Sitz frei.");
			});
			return;
		}
		Optional<Seat> seat = AssignPlaceDialog.choose(presentation, plan, stage);
		if (seat.isEmpty()) {
			return;
		}
		run(row, () -> actions.assignPlace(row, seat.get()));
	}

	// <-- Aktionen
}
