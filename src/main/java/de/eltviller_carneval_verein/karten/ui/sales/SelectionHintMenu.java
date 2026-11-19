package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Objects;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import de.eltviller_carneval_verein.karten.ui.hall.PlanLabelsMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;

/**
 * Das Untermenü "Auswahl am Mauszeiger" im Menü "Anzeige" (KARV-197): "Aus" oder eine {@link SelectionHintPlacement}.
 * Zwei Properties stehen dahinter, der Schalter und die Lage; "Aus" lässt die Lage stehen, damit sie beim
 * Wiedereinschalten gilt, und eine Lage zu wählen schaltet den Hinweis ein. Dahinter der Haken "Am Rand ausweichen".
 */
final class SelectionHintMenu {

	private SelectionHintMenu() {
	}

	/**
	 * @param enabled    ob der Hinweis zu sehen ist
	 * @param placement  wo er steht ({@code null} gilt als {@link SelectionHintPlacement#ABOVE})
	 * @param avoidEdges ob er am Rand die Seite wechselt; als eigener Haken nach den drei Einträgen, unabhängig von ihnen
	 */
	static Menu create(BooleanProperty enabled, ObjectProperty<SelectionHintPlacement> placement, BooleanProperty avoidEdges) {
		ToggleGroup group = new ToggleGroup();
		Menu submenu = new Menu("Auswahl am Mauszeiger");
		submenu.getItems().add(RadioMenus.item("Aus", null, group));
		for (SelectionHintPlacement value : SelectionHintPlacement.values()) {
			submenu.getItems().add(RadioMenus.item(value.label(), value, group));
		}
		// Der gewählte Eintrag als ein Wert (null = "Aus"). Das Menü folgt ihm statt den beiden Properties
		// einzeln: Beim Wechsel von "Aus" auf eine Lage ändert er sich erst mit dem Schalter, ein Zwischenstand (Lage
		// schon neu, Schalter noch aus) wählt also nicht kurz wieder "Aus".
		ObjectBinding<SelectionHintPlacement> shown = Bindings.createObjectBinding(
				() -> enabled.get() ? Objects.requireNonNullElse(placement.get(), SelectionHintPlacement.ABOVE) : null, enabled, placement);
		RadioMenus.select(group, shown.get());
		shown.addListener((obs, oldValue, newValue) -> RadioMenus.select(group, newValue));
		// Das Binding hängt nur schwach an den app-weiten Properties (DisplayPreferences); das Untermenü hält es, damit
		// es so lange lebt wie seine Shell und nicht länger
		submenu.getProperties().put(SelectionHintMenu.class, shown);
		group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
			if (newToggle == null) {
				return;
			}
			if (newToggle.getUserData() instanceof SelectionHintPlacement chosen) {
				placement.set(chosen);
				enabled.set(true);
			} else {
				enabled.set(false);
			}
		});
		// Bleibt auch bei "Aus" bedienbar und gilt dann beim Wiedereinschalten
		submenu.getItems().addAll(new SeparatorMenuItem(), PlanLabelsMenu.item("Am Rand ausweichen", avoidEdges));
		return submenu;
	}
}
