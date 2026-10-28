package de.eltviller_carneval_verein.karten.ui.sales;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.ui.EditingUnits;
import de.eltviller_carneval_verein.karten.ui.input.DateTimeInput;
import de.eltviller_carneval_verein.karten.ui.input.InputFeedback;
import de.eltviller_carneval_verein.karten.ui.input.InputIssue;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.StringConverter;

/**
 * Kartendetails einer Buchung oder mehrerer Buchungen in einem Popup. Der Dialog ändert das Modell erst mit
 * "Anwenden"; "Abbrechen" (oder Esc) verwirft die Eingaben. Bei mehreren Buchungen stehen ihre Plätze oben (kurz,
 * Tooltip mit jedem Platz), die Felder zeigen den gemeinsamen Wert oder "verschieden", und nur geänderte Felder gelten
 * für alle (siehe {@link SeatBulkEdit}). Der Preis ist der effektive Preis; der Rollstuhlplatz gehört zur Bestuhlung
 * und steht nur als Hinweis da. Ohne Bearbeitungsmodus sind die Felder gesperrt und der Dialog hat nur "Schließen".
 */
public final class SeatDetailPopup {

	private static final String MIXED = "verschieden";
	private static final double SUMMARY_MAX_HEIGHT = 90;

	private final SeatBulkEdit edit;
	private final boolean editMode;
	private final Runnable onApplied;
	private final Popup popup = new Popup();
	/** Je Datumsfeld: löst einen nicht lesbaren Text auf (siehe {@link #dateField}); läuft vor "Anwenden". */
	private final List<Runnable> dateResolvers = new ArrayList<>();
	private Label totalValue;

	/**
	 * @param bookings  die Buchungen, die der Dialog zeigt und ändert (mindestens eine)
	 * @param editMode  {@code false}: nur ansehen
	 * @param onApplied wird nach "Anwenden" aufgerufen, damit die Ansicht sich neu zeichnet
	 */
	public SeatDetailPopup(List<Booking> bookings, boolean editMode, Runnable onApplied) {
		this.edit = new SeatBulkEdit(bookings);
		this.editMode = editMode;
		this.onApplied = onApplied;
		popup.setAutoHide(!editMode); // Mit Eingaben im Dialog soll ein Klick daneben sie nicht verwerfen
		popup.setHideOnEscape(true);
		popup.getContent().add(buildContent());
	}

	public Popup popup() {
		return popup;
	}

	/**
	 * Zeigt den Dialog. Mit Eingabemöglichkeit zählen seine Buchungen als in Bearbeitung ({@link EditingUnits}), bis der
	 * Dialog schließt (Anwenden, Abbrechen, Esc, Fenster zu): Ein fremder Stand dafür wird zurückgestellt, statt die
	 * Eingaben zu überschreiben. Ohne Bearbeitungsmodus gibt es nichts einzugeben.
	 */
	public void show(Window owner, double screenX, double screenY) {
		if (editMode) {
			EditingUnits.getInstance().begin(this, edit.bookings());
			// Ein Handler statt setOnHidden: Die Saalansicht setzt den Handler beim Schließen auf null
			popup.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> EditingUnits.getInstance().end(this));
		}
		try {
			popup.show(owner, screenX, screenY);
		} catch (RuntimeException e) {
			// Nie gezeigt, also nie geschlossen: Die Buchungen blieben sonst bis zum Neustart "in Bearbeitung"
			EditingUnits.getInstance().end(this);
			throw e;
		}
		// Ein Popup bekommt die Stylesheets der Haupt-Szene nicht mit: Buttons, Felder usw. sähen sonst anders aus
		popup.getScene().getStylesheets().add(HallOverviewController.APP_STYLESHEET);
	}

	private GridPane buildContent() {
		GridPane grid = new GridPane();
		grid.setHgap(8);
		grid.setVgap(6);
		grid.setStyle(HallOverviewController.DETAIL_POPUP_STYLE);
		int row = 0;

		grid.add(buildHeader(), 0, row++, 2, 1);

		List<Booking> seats = edit.bookings();
		TextField lastNameField = textField(edit.lastName(), "Nachname");
		DateInput reservedOn = dateField(edit.reservationDate(), "Reservierungsdatum");
		ComboBox<PaymentStatus> paymentBox = paymentCombo();
		DateInput paidOn = dateField(edit.paymentDate(), "Bezahldatum");
		CheckBox collectedBox = checkBox("Abgeholt", edit.collected());
		DateInput collectedOn = dateField(edit.collectionDate(), "Abholdatum");
		// Wird eine Aktion im Dialog gesetzt, zeigt ihr Datum gleich den heutigen Tag (wie es "Anwenden" einträgt)
		prefillOnAction(reservedOn, seats.stream().noneMatch(Booking::isReserved), () -> !lastNameField.getText().isBlank(), lastNameField.textProperty());
		prefillOnAction(paidOn, seats.stream().noneMatch(Booking::isPaid), () -> paymentBox.getValue() != null && paymentBox.getValue().isPaid(),
				paymentBox.valueProperty());
		prefillOnAction(collectedOn, seats.stream().noneMatch(Booking::isCollected), () -> collectedBox.isSelected() && !collectedBox.isIndeterminate(),
				collectedBox.selectedProperty());

		grid.addRow(row++, new Label("Nachname:"), lastNameField);
		grid.addRow(row++, new Label("Vorname:"), textField(edit.firstName(), "Vorname"));
		grid.addRow(row++, new Label("Reserviert am:"), reservedOn.box());
		grid.addRow(row++, new Label("Zahlung:"), paymentBox);
		grid.addRow(row++, new Label("Bezahlt am:"), paidOn.box());
		grid.addRow(row++, new Label("Preis (€):"), priceSpinner());

		if (edit.bookings().size() > 1) {
			totalValue = new Label();
			totalValue.setStyle("-fx-font-weight: bold;");
			refreshTotal();
			grid.addRow(row++, new Label("Gesamt:"), totalValue);
		}

		HBox checkboxRow = new HBox(12, collectedBox);
		checkboxRow.setAlignment(Pos.CENTER_LEFT);
		// Rollstuhlplatz: nur lesbar aus der Momentaufnahme (geändert wird er im Hallen-Editor)
		if (edit.bookings().stream().allMatch(Booking::isWheelchairAccessible)) {
			checkboxRow.getChildren().add(new Label(edit.bookings().size() == 1 ? "Rollstuhlplatz" : "Alle Rollstuhlplätze"));
		}
		grid.add(checkboxRow, 0, row++, 2, 1);
		grid.addRow(row++, new Label("Abgeholt am:"), collectedOn.box());

		grid.addRow(row++, new Label("Kommentar:"), textField(edit.comment(), "Kommentar"));
		grid.add(buildButtonBar(), 0, row, 2, 1);
		return grid;
	}

	private Node buildHeader() {
		List<Booking> bookings = edit.bookings();
		if (bookings.size() == 1) {
			Booking booking = bookings.get(0);
			return boldLabel(booking.getTableLabel() + " · Sitz " + booking.getSeatNumber() + (booking.isUnassigned() ? " (ohne Platz)" : ""));
		}
		Label summary = new Label(SeatListSummary.headline(bookings));
		summary.setWrapText(true);
		summary.setMaxWidth(340);
		summary.setMaxHeight(SUMMARY_MAX_HEIGHT); // Bei der ganzen Halle wäre der Text höher als der Dialog: abgeschnitten mit "…"
		Tooltip.install(summary, new Tooltip(SeatListSummary.tooltip(bookings)));
		return new VBox(2, boldLabel(bookings.size() + " Sitze"), summary);
	}

	private static Label boldLabel(String text) {
		Label label = new Label(text);
		label.setStyle("-fx-font-weight: bold;");
		return label;
	}

	private TextField textField(SeatBulkEdit.Field<String> field, String prompt) {
		Optional<String> common = field.common();
		TextField textField = new TextField(common.orElse(""));
		textField.setPromptText(common.isPresent() ? prompt : MIXED);
		textField.setDisable(!editMode);
		textField.textProperty().addListener((obs, oldVal, newVal) -> field.change(newVal));
		return textField;
	}

	/**
	 * Datumsfeld einer Aktion (Reservierung, Bezahlung, Abholung) mit Knopf "×" zum Löschen. Der Text ist streng im Format
	 * {@code dd.MM.yyyy}. Ein leeres Feld bei vorhandenem gemeinsamem Datum löscht es; bei "verschieden" heißt leer
	 * "nicht geändert", gelöscht wird dort nur mit "×". Ein nicht lesbarer Text meldet {@code INP-002} (bei Fokusverlust und
	 * vor "Anwenden"): In einem Feld, in dem alle Buchungen noch kein Datum haben, gilt dann heute, sonst springt der Text
	 * auf den bisherigen Stand zurück.
	 */
	private DateInput dateField(SeatBulkEdit.Field<LocalDate> field, String name) {
		TextField textField = new TextField(field.common().map(DateTimeInput::formatDate).orElse(""));
		textField.setPromptText(field.common().isEmpty() && !field.allEmpty() ? MIXED : "tt.mm.jjjj");
		textField.setPrefWidth(115); // breit genug für "verschieden"
		textField.setAlignment(Pos.CENTER_LEFT); // Text senkrecht mittig, waagerecht links
		textField.setDisable(!editMode);
		String initialText = textField.getText();
		// Der Text, der zum Stand des Feldes gehört: Dahin springt ein nicht lesbarer Text zurück
		String[] accepted = { textField.getText() };
		List<Booking> bookings = edit.bookings();
		String rowLabel = bookings.size() == 1 ? EntityLabels.booking(bookings.get(0)) : bookings.size() + " Sitzen";

		textField.textProperty().addListener((obs, oldVal, newVal) -> {
			if (newVal == null || newVal.isBlank()) {
				if (field.common().isPresent()) {
					field.clear();
				} else {
					field.change(null);
				}
				return; // Leer ist nur ein Zwischenstand beim Tippen: "accepted" bleibt das letzte lesbare Datum
			}
			Optional<LocalDate> date = DateTimeInput.parseDate(newVal);
			if (date.isPresent()) {
				field.change(date.get());
				accepted[0] = newVal;
			}
		});

		Runnable resolve = () -> {
			String text = textField.getText();
			if (text == null || text.isBlank() || DateTimeInput.parseDate(text).isPresent()) {
				return;
			}
			ValidationIssue issue = InputIssue.NOT_A_DATE.toIssue(InputFeedback.label(name, rowLabel), InputFeedback.displayText(text));
			// Wie in den Tabellen: Hatte das Feld noch nie ein Datum (keine Buchung hat eins und es wurde auch keins gelesen),
			// gilt heute; sonst springt der Text auf das letzte lesbare Datum zurück ("" nach "×")
			if (accepted[0].isEmpty() && field.allEmpty()) {
				InputFeedback.reportAfterChange(issue, textField);
				textField.setText(DateTimeInput.formatDate(LocalDate.now()));
			} else {
				InputFeedback.report(issue, textField);
				textField.setText(accepted[0]);
			}
		};
		dateResolvers.add(resolve);
		textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
			if (!isFocused) {
				resolve.run();
			}
		});

		// Das Kreuz ist gezeichnet, nicht als Zeichen "×" gesetzt: Das Zeichen sitzt in der Schrift tiefer als die Mitte
		Button clearButton = new Button();
		SVGPath cross = new SVGPath();
		cross.setContent("M0,0 L8,8 M8,0 L0,8");
		cross.setFill(null);
		cross.setStrokeWidth(1.6);
		cross.setStrokeLineCap(StrokeLineCap.BUTT);
		cross.strokeProperty().bind(clearButton.textFillProperty()); // Farbe wie der Text des Knopfes (auch wenn gesperrt)
		clearButton.setGraphic(cross);
		clearButton.getStyleClass().add("button-default");
		clearButton.setTooltip(new Tooltip("Datum löschen"));
		clearButton.setDisable(!editMode);
		// Genauso hoch wie das Eingabefeld daneben (der Knopf wäre sonst mit seinem Innenabstand höher)
		clearButton.setStyle("-fx-padding: 0 10px;");
		clearButton.minHeightProperty().bind(textField.heightProperty());
		clearButton.prefHeightProperty().bind(textField.heightProperty());
		clearButton.maxHeightProperty().bind(textField.heightProperty());
		clearButton.setOnAction(event -> {
			textField.clear(); // zuerst: der Text-Listener meldet "leer" und darf das Löschen nicht wieder zurücknehmen
			field.clear();
			accepted[0] = ""; // bewusst gelöscht: Dahin springt ein nicht lesbarer Text danach zurück
			textField.setPromptText("tt.mm.jjjj");
		});
		HBox box = new HBox(4, textField, clearButton);
		box.setAlignment(Pos.CENTER_LEFT);
		return new DateInput(box, textField, initialText);
	}

	/** Ein Datumsfeld des Dialogs: die Zeile, das Textfeld und der Text beim Öffnen. */
	private record DateInput(HBox box, TextField text, String initialText) {
	}

	/**
	 * Zeigt im Datumsfeld gleich den heutigen Tag, sobald die zugehörige Aktion im Dialog gesetzt wird (Name eingetragen,
	 * Zahlung gewählt, "Abgeholt" angehakt), und stellt den Text von vorhin wieder her, wenn sie zurückgenommen wird.
	 * Das gilt nur, wenn keine der Buchungen die Aktion schon hat (sonst trägt "Anwenden" das Datum je Buchung ein) und
	 * nur, solange der Nutzer das Datum nicht selbst geändert hat. Das Datum bleibt wie jedes Datum änderbar.
	 */
	private void prefillOnAction(DateInput date, boolean nothingDoneYet, BooleanSupplier actionDone, ObservableValue<?> trigger) {
		if (!editMode || !nothingDoneYet) {
			return;
		}
		String[] prefilled = { null };
		trigger.addListener((obs, oldVal, newVal) -> {
			if (actionDone.getAsBoolean()) {
				if (prefilled[0] == null && date.text().getText().equals(date.initialText())) {
					prefilled[0] = DateTimeInput.formatDate(LocalDate.now());
					date.text().setText(prefilled[0]);
				}
			} else if (prefilled[0] != null) {
				if (prefilled[0].equals(date.text().getText())) {
					date.text().setText(date.initialText());
				}
				prefilled[0] = null;
			}
		});
	}

	private ComboBox<PaymentStatus> paymentCombo() {
		SeatBulkEdit.Field<PaymentStatus> field = edit.paymentStatus();
		ComboBox<PaymentStatus> combo = new ComboBox<>();
		combo.getItems().setAll(PaymentStatus.values());
		combo.setConverter(new StringConverter<PaymentStatus>() {
			@Override
			public String toString(PaymentStatus status) {
				return status == null ? "" : status.getDisplayName();
			}

			@Override
			public PaymentStatus fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		});
		combo.setValue(field.common().orElse(null));
		combo.setPromptText(field.common().isPresent() ? "" : MIXED);
		combo.setDisable(!editMode);
		combo.valueProperty().addListener((obs, oldVal, newVal) -> field.change(newVal));
		return combo;
	}

	private Spinner<Double> priceSpinner() {
		SeatBulkEdit.Field<Integer> field = edit.price();
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(field.common().map(cents -> cents / 100.0).orElse(null));
		Spinner<Double> spinner = new Spinner<>();
		spinner.setValueFactory(factory);
		spinner.setEditable(true);
		// Nicht übernommene Eingaben (unlesbar): Meldung in der Fußzeile, Editor-Text zurück. Die Meldung nennt den Platz,
		// bei mehreren die Anzahl ("Preis von 12 Sitzen")
		List<Booking> bookings = edit.bookings();
		factory.setFieldLabel(InputFeedback.label("Preis", bookings.size() == 1 ? EntityLabels.booking(bookings.get(0)) : bookings.size() + " Sitzen"));
		factory.setRejectionHandler(issue -> SpinnerInput.reject(spinner, issue));
		spinner.setPrefWidth(115); // breit genug für "verschieden"
		spinner.setDisable(!editMode);
		spinner.getEditor().setPromptText(field.common().isPresent() ? "" : MIXED);
		// Auf den Text hören (nicht auf den bestätigten Wert): So stimmen Gesamtpreis und "Anwenden" auch mit
		// noch nicht bestätigter Eingabe. Still lesen: Halb getippte Preise sollen keine Meldung auslösen.
		spinner.getEditor().textProperty().addListener((obs, oldVal, newVal) -> {
			Double euros = factory.parseQuietly(newVal);
			field.change(euros == null ? null : MoneyFormat.toCents(euros));
			refreshTotal();
		});
		return spinner;
	}

	private CheckBox checkBox(String text, SeatBulkEdit.Field<Boolean> field) {
		CheckBox box = new CheckBox(text);
		Optional<Boolean> common = field.common();
		if (common.isPresent()) {
			box.setSelected(common.get());
		} else {
			box.setAllowIndeterminate(true);
			box.setIndeterminate(true); // "verschieden"
		}
		box.setDisable(!editMode);
		InvalidationListener sync = obs -> field.change(box.isIndeterminate() ? null : box.isSelected());
		box.selectedProperty().addListener(sync);
		box.indeterminateProperty().addListener(sync);
		return box;
	}

	private void refreshTotal() {
		if (totalValue != null) {
			totalValue.setText(MoneyFormat.formatCents(edit.totalCents()));
		}
	}

	private HBox buildButtonBar() {
		HBox buttonBar = new HBox(8);
		buttonBar.setAlignment(Pos.CENTER_RIGHT);
		if (editMode) {
			Button cancelButton = new Button("Abbrechen");
			cancelButton.getStyleClass().add("button-default");
			cancelButton.setOnAction(event -> popup.hide());
			Button applyButton = new Button("Anwenden");
			applyButton.getStyleClass().add("button-primary");
			applyButton.setOnAction(event -> {
				// Eine abgelehnte Eingabe (der Klick auf Anwenden löst sie aus): nichts übernehmen, der Dialog und die Auswahl bleiben
				if (SpinnerInput.blockSaveIfScreenInputRejected(applyButton)) {
					return;
				}
				// Nicht lesbare Daten zuerst auflösen (heute bzw. alter Stand), der Klick löst den Fokusverlust nicht immer aus
				dateResolvers.forEach(Runnable::run);
				edit.apply();
				popup.hide();
				onApplied.run();
			});
			buttonBar.getChildren().addAll(cancelButton, applyButton);
		} else {
			Button closeButton = new Button("Schließen");
			closeButton.getStyleClass().add("button-primary");
			closeButton.setOnAction(event -> popup.hide());
			buttonBar.getChildren().add(closeButton);
		}
		return buttonBar;
	}
}
