package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Zeigt die aktuelle {@link StatusMessage} an. Wird in der Footer-Zeile eines Screens links
 * vor den Buttons eingebunden ({@code <StatusLabel/>} gefolgt von einer wachsenden Region) und
 * ist unsichtbar, solange keine Meldung ansteht. Die Farben kommen aus style.css
 * ({@code .status-error}, {@code .status-warning}, {@code .status-info}, {@code .status-success}).
 */
public class StatusLabel extends Label {

	private static final String BASE_STYLE_CLASS = "status-label";

	private final Label icon = new Label();
	private final Tooltip tooltip = new Tooltip();

	// Als Feld gehalten, damit der schwache Listener nicht vorzeitig eingesammelt wird.
	private final ChangeListener<StatusMessage.Entry> listener = (obs, oldEntry, newEntry) -> apply(newEntry);

	public StatusLabel() {
		getStyleClass().add(BASE_STYLE_CLASS);
		icon.getStyleClass().add("status-icon");
		setGraphic(icon);
		setGraphicTextGap(8);
		setMinWidth(0);
		setTextOverrun(OverrunStyle.ELLIPSIS);
		// Der Tooltip besteht aus farbigen Zeilen (eine je Meldung) und bleibt lange genug sichtbar zum Lesen
		tooltip.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
		tooltip.getStyleClass().add("status-tooltip");
		tooltip.setShowDelay(Duration.millis(300));
		tooltip.setShowDuration(Duration.seconds(60));

		StatusMessage message = StatusMessage.getInstance();
		message.register(this);
		// Schwach, weil das Label nach einem Screen-Wechsel nicht mehr abgemeldet wird.
		message.currentProperty().addListener(new WeakChangeListener<>(listener));
		apply(message.currentProperty().get());
	}

	/** Das Label ist Teil des Fensters, das gerade angezeigt wird (nicht Rest eines früheren Screens). */
	boolean isDisplayed() {
		if (getScene() == null) {
			return false;
		}
		Window window = getScene().getWindow();
		return window != null && window.isShowing() && window.getScene() == getScene();
	}

	private void apply(StatusMessage.Entry entry) {
		getStyleClass().removeIf(styleClass -> styleClass.startsWith("status-") && !styleClass.equals(BASE_STYLE_CLASS));
		if (entry == null) {
			setText("");
			setTooltip(null);
			setVisible(false);
			setManaged(false);
			return;
		}
		getStyleClass().add("status-" + entry.severity().name().toLowerCase());
		icon.setText(symbolFor(entry.severity()));
		setText(entry.text());
		tooltip.setGraphic(buildTooltipContent(entry));
		setTooltip(tooltip);
		setVisible(true);
		setManaged(true);
	}

	/** Eine Zeile je Meldung, jede in den Farben ihres Schweregrads wie die Meldung in der Fußzeile. */
	private static VBox buildTooltipContent(StatusMessage.Entry entry) {
		VBox rows = new VBox(4);
		for (StatusMessage.Line line : entry.lines()) {
			Label icon = new Label(symbolFor(line.severity()));
			icon.getStyleClass().add("status-icon");
			Label row = new Label(line.text(), icon);
			row.getStyleClass().addAll(BASE_STYLE_CLASS, "status-" + line.severity().name().toLowerCase());
			row.setGraphicTextGap(8);
			// Kein Zeilenumbruch: umbrechende Labels liefern im Tooltip eine viel zu große Höhe
			row.setMinWidth(Region.USE_PREF_SIZE);
			rows.getChildren().add(row);
		}
		return rows;
	}

	private static String symbolFor(Severity severity) {
		return switch (severity) {
		case ERROR -> "✖";
		case WARNING -> "⚠";
		case INFO -> "ℹ";
		case SUCCESS -> "✔";
		};
	}
}
