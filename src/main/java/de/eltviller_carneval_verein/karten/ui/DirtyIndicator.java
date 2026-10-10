package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.Button;

/**
 * Zeigt an einem Speichern-Button, dass es ungespeicherte Änderungen gibt: "Speichern *" und ein auffälligerer
 * Stil (CSS-Klasse {@value #DIRTY_STYLE_CLASS}). Gebunden wird an den {@link ChangeTracker}, nicht an einzelne
 * Felder.
 */
public final class DirtyIndicator {

	static final String DIRTY_STYLE_CLASS = "button-dirty";

	private DirtyIndicator() {
	}

	/**
	 * Bindet den Button an {@link ChangeTracker#anyDirtyProperty()} des Trackers (Events oder Hallen, je nachdem,
	 * was der Screen bearbeitet); sein jetziger Text ist der Text im sauberen Zustand.
	 */
	public static void bind(Button button, ChangeTracker<?> tracker) {
		bind(button, tracker.anyDirtyProperty());
	}

	static void bind(Button button, ReadOnlyBooleanProperty dirty) {
		String cleanText = button.getText();
		button.textProperty().bind(Bindings.when(dirty).then(cleanText + " *").otherwise(cleanText));

		// Die Bindung hält nur schwach am Tracker; der Listener hängt deshalb am Button, nicht umgekehrt
		ChangeListener<Boolean> styler = (obs, wasDirty, isDirty) -> setDirtyStyle(button, isDirty);
		dirty.addListener(new WeakChangeListener<>(styler));
		button.getProperties().put(DirtyIndicator.class, styler);
		setDirtyStyle(button, dirty.get());
	}

	private static void setDirtyStyle(Button button, boolean isDirty) {
		button.getStyleClass().remove(DIRTY_STYLE_CLASS);
		if (isDirty) {
			button.getStyleClass().add(DIRTY_STYLE_CLASS);
		}
	}
}
