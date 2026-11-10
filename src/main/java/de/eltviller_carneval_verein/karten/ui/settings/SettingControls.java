package de.eltviller_carneval_verein.karten.ui.settings;

import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;

/** Bedienelemente, die mehrere Abschnitte des Einstellungsbildschirms brauchen. */
final class SettingControls {

	private SettingControls() {
	}

	/** Ein Häkchen für eine gewöhnliche Einstellung: Es ändert sie über {@code setter}, gespeichert wird mit "Speichern". */
	static Node checkBox(boolean selected, Consumer<Boolean> setter) {
		CheckBox box = new CheckBox();
		box.setSelected(selected);
		box.selectedProperty().addListener((observable, was, now) -> setter.accept(now));
		return box;
	}
}
