package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Objects;

import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;

/** Radio-Einträge der Untermenüs im Menü "Anzeige": je Eintrag ein Wert in {@code userData}. */
final class RadioMenus {

	private RadioMenus() {
	}

	/** Ein Eintrag der Gruppe mit {@code value} als {@code userData}. */
	static RadioMenuItem item(String text, Object value, ToggleGroup group) {
		RadioMenuItem item = new RadioMenuItem(text);
		item.setToggleGroup(group);
		item.setUserData(value);
		return item;
	}

	/** Wählt den Eintrag mit {@code value} als {@code userData}; gibt es keinen, bleibt die Auswahl. */
	static void select(ToggleGroup group, Object value) {
		for (Toggle toggle : group.getToggles()) {
			if (Objects.equals(toggle.getUserData(), value)) {
				toggle.setSelected(true);
				return;
			}
		}
	}
}
