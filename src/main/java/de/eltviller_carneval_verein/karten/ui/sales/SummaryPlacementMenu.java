package de.eltviller_carneval_verein.karten.ui.sales;

import javafx.beans.property.ObjectProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;

/**
 * Hängt an das Menü "Anzeige" das Untermenü "Kaufzusammenfassung" mit je einem Eintrag pro {@link SummaryPlacement} und
 * bindet die Auswahl an die Property.
 */
final class SummaryPlacementMenu {

	private SummaryPlacementMenu() {
	}

	/** Fügt hinter den vorhandenen Einträgen einen Trenner und das Untermenü an. */
	static void append(MenuButton menu, ObjectProperty<SummaryPlacement> placement) {
		ToggleGroup group = new ToggleGroup();
		Menu submenu = new Menu("Kaufzusammenfassung");
		for (SummaryPlacement value : SummaryPlacement.values()) {
			RadioMenuItem item = new RadioMenuItem(value.label());
			item.setToggleGroup(group);
			item.setUserData(value);
			item.setSelected(value == placement.get());
			submenu.getItems().add(item);
		}
		group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
			if (newToggle != null) {
				placement.set((SummaryPlacement) newToggle.getUserData());
			}
		});
		// Auch eine Änderung von außen zeigt sich im Menü
		placement.addListener((obs, oldValue, newValue) -> group.getToggles().stream().filter(toggle -> toggle.getUserData() == newValue).findFirst()
				.ifPresent(toggle -> toggle.setSelected(true)));
		menu.getItems().addAll(new SeparatorMenuItem(), submenu);
	}
}
