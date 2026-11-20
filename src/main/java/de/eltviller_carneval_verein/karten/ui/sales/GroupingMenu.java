package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;

/**
 * Das Menü "Gruppieren" der Kartentabelle (KARV-193, Spec Abschnitt 7): je Spalte ein Eintrag zum Anhaken, in
 * Spaltenreihenfolge; Anhaken hängt die Spalte als unterste Ebene an, Abhaken nimmt sie heraus. Darunter die
 * Gruppenordnung und "Gruppierung aufheben". Neben dem Knopf steht die aktive Gruppierung als Text.
 *
 * <p>Die Einträge zum Anhaken und zur Ordnung lassen das Menü offen: {@code CheckMenuItem} und {@code RadioMenuItem}
 * schlössen es nach jedem Klick, deshalb {@link CustomMenuItem} mit {@code hideOnClick=false} und einer
 * {@link CheckBox} bzw. einem {@link RadioButton}. Die Properties gelten für die ganze App ({@code DisplayPreferences}),
 * das Menü nur so lange wie seine Kartentabelle; es hört deshalb nur schwach auf sie und hält seine Listener selbst
 * (am Knopf, siehe {@link SummaryPlacementMenu}).
 */
final class GroupingMenu {

	private final Label summary;
	private final ObjectProperty<List<TicketColumn>> grouping;
	private final List<CheckBox> columns = new ArrayList<>();
	private final ToggleGroup orders = new ToggleGroup();
	private final ChangeListener<List<TicketColumn>> followGrouping = (obs, before, now) -> showGrouping();
	private final ChangeListener<GroupOrder> followOrder = (obs, before, now) -> RadioMenus.select(orders, now);

	GroupingMenu(MenuButton button, Label summary, ObjectProperty<List<TicketColumn>> grouping, ObjectProperty<GroupOrder> order) {
		this.summary = summary;
		this.grouping = grouping;
		for (TicketColumn column : TicketColumn.values()) {
			CheckBox box = new CheckBox(column.header());
			box.setUserData(column);
			box.setOnAction(event -> toggle(column, box.isSelected()));
			columns.add(box);
			button.getItems().add(new CustomMenuItem(box, false));
		}
		button.getItems().add(new SeparatorMenuItem());
		button.getItems().add(orderItem("Gruppen nach Wert ordnen", GroupOrder.VALUE, order));
		button.getItems().add(orderItem("Gruppen nach Größe ordnen", GroupOrder.SIZE, order));
		// ein gewöhnlicher Eintrag schließt das Menü schon beim Klick; hide() auch für fire() ohne Maus
		MenuItem clear = new MenuItem("Gruppierung aufheben");
		clear.setOnAction(event -> {
			grouping.set(List.of());
			button.hide();
		});
		button.getItems().add(clear);

		RadioMenus.select(orders, order.get());
		showGrouping();
		grouping.addListener(new WeakChangeListener<>(followGrouping));
		order.addListener(new WeakChangeListener<>(followOrder));
		button.getProperties().put(GroupingMenu.class, this);
	}

	private CustomMenuItem orderItem(String text, GroupOrder value, ObjectProperty<GroupOrder> order) {
		RadioButton radio = new RadioButton(text);
		radio.setUserData(value);
		radio.setToggleGroup(orders);
		radio.setOnAction(event -> order.set(value));
		return new CustomMenuItem(radio, false);
	}

	private void toggle(TicketColumn column, boolean selected) {
		List<TicketColumn> next = new ArrayList<>(grouping.get());
		next.remove(column);
		if (selected) {
			next.add(column);
		}
		grouping.set(List.copyOf(next));
	}

	/** Häkchen und Text folgen der Gruppierung, auch einer Änderung von außen (etwa beim Start). */
	private void showGrouping() {
		List<TicketColumn> current = grouping.get() == null ? List.of() : grouping.get();
		// setSelected löst kein onAction aus, also kein Anhaken
		columns.forEach(box -> box.setSelected(current.contains(box.getUserData())));
		summary.setText(current.isEmpty() ? "" : "Gruppiert nach: " + current.stream().map(TicketColumn::header).collect(Collectors.joining(" › ")));
	}
}
