package de.eltviller_carneval_verein.karten.ui.hall;

import javafx.beans.property.BooleanProperty;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuButton;

/**
 * Füllt das Menü "Anzeige" (links in der Fußzeile) mit je einem Haken pro Beschriftung des Hallenplans und bindet die
 * Haken an die {@link PlanLabelOptions}.
 */
public final class PlanLabelsMenu {

	private PlanLabelsMenu() {
	}

	public static void bind(MenuButton menu, PlanLabelOptions options) {
		menu.getItems().setAll(item("Reihen", options.rowsProperty()), item("Tische", options.tablesProperty()), item("Sitznummern", options.seatNumbersProperty()),
				item("Hallenobjekte", options.hallObjectsProperty()));
	}

	private static CheckMenuItem item(String text, BooleanProperty property) {
		CheckMenuItem item = new CheckMenuItem(text);
		item.selectedProperty().bindBidirectional(property);
		return item;
	}
}
