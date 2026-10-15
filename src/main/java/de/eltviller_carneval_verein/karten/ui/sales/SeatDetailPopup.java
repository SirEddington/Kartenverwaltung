package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;
import java.util.Optional;

import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import javafx.beans.InvalidationListener;
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
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * Kartendetails eines Sitzes oder mehrerer Sitze in einem Popup. Der Dialog ändert das Modell erst mit "Anwenden";
 * "Abbrechen" (oder Esc) verwirft die Eingaben. Bei mehreren Sitzen stehen sie oben (kurz, Tooltip mit jedem Sitz),
 * die Felder zeigen den gemeinsamen Wert oder "verschieden", und nur geänderte Felder gelten für alle Sitze (siehe
 * {@link SeatBulkEdit}). Ohne Bearbeitungsmodus sind die Felder gesperrt und der Dialog hat nur "Schließen".
 */
public final class SeatDetailPopup {

	private static final String MIXED = "verschieden";
	private static final double SUMMARY_MAX_HEIGHT = 90;

	private final SeatBulkEdit edit;
	private final boolean editMode;
	private final Runnable onApplied;
	private final Popup popup = new Popup();
	private Label totalValue;

	/**
	 * @param seats     die Sitze, die der Dialog zeigt und ändert (mindestens einer)
	 * @param editMode  {@code false}: nur ansehen
	 * @param onApplied wird nach "Anwenden" aufgerufen, damit die Ansicht sich neu zeichnet
	 */
	public SeatDetailPopup(List<Seat> seats, boolean editMode, Runnable onApplied) {
		this.edit = new SeatBulkEdit(seats);
		this.editMode = editMode;
		this.onApplied = onApplied;
		popup.setAutoHide(!editMode); // Mit Eingaben im Dialog soll ein Klick daneben sie nicht verwerfen
		popup.setHideOnEscape(true);
		popup.getContent().add(buildContent());
	}

	public Popup popup() {
		return popup;
	}

	public void show(Window owner, double screenX, double screenY) {
		popup.show(owner, screenX, screenY);
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

		grid.addRow(row++, new Label("Nachname:"), textField(edit.lastName(), "Nachname"));
		grid.addRow(row++, new Label("Vorname:"), textField(edit.firstName(), "Vorname"));
		grid.addRow(row++, new Label("Zahlung:"), paymentCombo());
		grid.addRow(row++, new Label("Preis (€):"), priceSpinner());

		if (edit.seats().size() > 1) {
			totalValue = new Label();
			totalValue.setStyle("-fx-font-weight: bold;");
			refreshTotal();
			grid.addRow(row++, new Label("Gesamt:"), totalValue);
		}

		HBox checkboxRow = new HBox(12, checkBox("Abgeholt", edit.collected()), checkBox("Rollstuhlgeeignet", edit.wheelchairAccessible()));
		grid.add(checkboxRow, 0, row++, 2, 1);

		grid.addRow(row++, new Label("Kommentar:"), textField(edit.comment(), "Kommentar"));
		grid.add(buildButtonBar(), 0, row, 2, 1);
		return grid;
	}

	private Node buildHeader() {
		List<Seat> seats = edit.seats();
		if (seats.size() == 1) {
			Seat seat = seats.get(0);
			return boldLabel("Tisch " + seat.getParent().getTableNumber() + " · Sitz " + seat.getSeatNumber());
		}
		Label summary = new Label(SeatListSummary.headline(seats));
		summary.setWrapText(true);
		summary.setMaxWidth(340);
		summary.setMaxHeight(SUMMARY_MAX_HEIGHT); // Bei der ganzen Halle wäre der Text höher als der Dialog: abgeschnitten mit "…"
		Tooltip.install(summary, new Tooltip(SeatListSummary.tooltip(seats)));
		return new VBox(2, boldLabel(seats.size() + " Sitze"), summary);
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
		// Nicht übernommene Eingaben (unlesbar, außerhalb von 0 bis 1.000 €): Meldung in der Fußzeile, Editor-Text zurück
		factory.setRejectionHandler(issue -> SpinnerInput.reject(spinner, issue));
		spinner.setPrefWidth(115); // breit genug für "verschieden"
		spinner.setDisable(!editMode);
		spinner.getEditor().setPromptText(field.common().isPresent() ? "" : MIXED);
		// Auf den Text hören (nicht auf den bestätigten Wert): So stimmen Gesamtpreis und "Anwenden" auch mit
		// noch nicht bestätigter Eingabe. Still lesen: Halb getippte Preise sollen keine Meldung auslösen.
		spinner.getEditor().textProperty().addListener((obs, oldVal, newVal) -> {
			Double euros = factory.parseQuietly(newVal);
			field.change(euros == null ? null : (int) Math.round(euros * 100));
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
