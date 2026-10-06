package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.stage.Window;

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
		tooltip.setText(entry.details());
		setTooltip(tooltip);
		setVisible(true);
		setManaged(true);
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
