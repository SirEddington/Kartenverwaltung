package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/** Das Menü "Gruppieren" der Kartentabelle (KARV-193, Spec Abschnitt 7). */
class GroupingMenuTest {

	private final ObjectProperty<List<TicketColumn>> grouping = new SimpleObjectProperty<>(List.of());
	private final ObjectProperty<GroupOrder> order = new SimpleObjectProperty<>(GroupOrder.VALUE);
	private MenuButton button;
	private Label summary;
	private GroupingMenu menu;
	private Stage stage;

	@BeforeAll
	static void startFx() {
		try {
			Platform.startup(() -> {
			});
		} catch (IllegalStateException alreadyStarted) {
			// der Toolkit läuft schon (anderer Test im selben Lauf)
		}
		Platform.setImplicitExit(false);
	}

	private static <T> T onFx(Supplier<T> action) throws Exception {
		CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.get());
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		});
		return result.get(10, TimeUnit.SECONDS);
	}

	private static void run(Runnable action) throws Exception {
		onFx(() -> {
			action.run();
			return null;
		});
	}

	@AfterEach
	void closeStage() throws Exception {
		if (stage != null) {
			run(() -> {
				button.hide();
				stage.hide();
			});
		}
	}

	private void create() throws Exception {
		run(() -> {
			button = new MenuButton("Gruppieren");
			summary = new Label();
			menu = new GroupingMenu(button, summary, grouping, order);
		});
	}

	/** Zeigt den Knopf in einem Fenster und öffnet sein Menü. */
	private void open() throws Exception {
		run(() -> {
			stage = new Stage();
			stage.setScene(new Scene(new HBox(button, summary), 400, 100));
			stage.show();
			button.show();
		});
	}

	private CheckBox checkBox(TicketColumn column) throws Exception {
		return onFx(() -> {
			for (MenuItem item : button.getItems()) {
				if (item instanceof CustomMenuItem custom && custom.getContent() instanceof CheckBox box && box.getUserData() == column) {
					return box;
				}
			}
			throw new AssertionError("kein Eintrag für " + column);
		});
	}

	private RadioButton radio(GroupOrder value) throws Exception {
		return onFx(() -> {
			for (MenuItem item : button.getItems()) {
				if (item instanceof CustomMenuItem custom && custom.getContent() instanceof RadioButton radio && radio.getUserData() == value) {
					return radio;
				}
			}
			throw new AssertionError("kein Eintrag für " + value);
		});
	}

	private MenuItem clearItem() throws Exception {
		return onFx(() -> button.getItems().stream().filter(item -> "Gruppierung aufheben".equals(item.getText())).findFirst()
				.orElseThrow(() -> new AssertionError("kein Eintrag „Gruppierung aufheben“")));
	}

	private void fire(javafx.scene.control.ButtonBase control) throws Exception {
		run(control::fire);
	}

	@Test
	void eintraegeInSpaltenreihenfolge() throws Exception {
		create();

		List<Object> entries = onFx(() -> {
			List<Object> result = new ArrayList<>();
			for (MenuItem item : button.getItems()) {
				if (item instanceof CustomMenuItem custom && custom.getContent() instanceof CheckBox box) {
					result.add(box.getUserData());
					assertEquals(((TicketColumn) box.getUserData()).header(), box.getText());
				} else if (item instanceof SeparatorMenuItem) {
					result.add("|");
				} else if (item instanceof CustomMenuItem custom && custom.getContent() instanceof RadioButton radio) {
					result.add(radio.getText());
				} else {
					result.add(item.getText());
				}
			}
			return result;
		});

		List<Object> expected = new ArrayList<>(List.of(TicketColumn.values()));
		expected.addAll(List.of("|", "Gruppen nach Wert ordnen", "Gruppen nach Größe ordnen", "Gruppierung aufheben"));
		assertEquals(expected, entries);
	}

	@Test
	void anhakenHaengtUntersteEbeneAn() throws Exception {
		create();

		fire(checkBox(TicketColumn.LAST_NAME));
		fire(checkBox(TicketColumn.FIRST_NAME));
		fire(checkBox(TicketColumn.PRESENTATION));

		assertEquals(List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME, TicketColumn.PRESENTATION), grouping.get(),
				"in der Reihenfolge des Anhakens, nicht der Spalten");
		assertTrue(onFx(() -> checkBoxSelected(TicketColumn.FIRST_NAME)));
	}

	private boolean checkBoxSelected(TicketColumn column) {
		for (MenuItem item : button.getItems()) {
			if (item instanceof CustomMenuItem custom && custom.getContent() instanceof CheckBox box && box.getUserData() == column) {
				return box.isSelected();
			}
		}
		throw new AssertionError("kein Eintrag für " + column);
	}

	@Test
	void abhakenBehaeltReihenfolge() throws Exception {
		grouping.set(List.of(TicketColumn.COMMENT, TicketColumn.LAST_NAME, TicketColumn.TABLE));
		create();
		assertTrue(onFx(() -> checkBoxSelected(TicketColumn.LAST_NAME)), "die Häkchen folgen der Gruppierung");
		assertFalse(onFx(() -> checkBoxSelected(TicketColumn.FIRST_NAME)));

		fire(checkBox(TicketColumn.LAST_NAME));

		assertEquals(List.of(TicketColumn.COMMENT, TicketColumn.TABLE), grouping.get());
	}

	@Test
	void menueBleibtOffen() throws Exception {
		create();
		open();

		fire(checkBox(TicketColumn.LAST_NAME));
		fire(radio(GroupOrder.SIZE));

		assertTrue(onFx(() -> button.isShowing()), "Anhaken und Ordnung wählen schließen das Menü nicht");
		assertTrue(onFx(() -> button.getItems().stream().filter(item -> item instanceof CustomMenuItem)
				.noneMatch(item -> ((CustomMenuItem) item).isHideOnClick())), "kein Eintrag zum Anhaken schließt beim Klick");
	}

	@Test
	void aufhebenLeertUndSchliesst() throws Exception {
		grouping.set(List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME));
		order.set(GroupOrder.SIZE);
		create();
		open();

		MenuItem clear = clearItem();
		run(clear::fire);

		assertEquals(List.of(), grouping.get());
		assertFalse(onFx(() -> button.isShowing()), "„Gruppierung aufheben“ schließt das Menü");
		assertFalse(onFx(() -> checkBoxSelected(TicketColumn.LAST_NAME)));
		assertEquals(GroupOrder.SIZE, order.get(), "die Gruppenordnung bleibt");
	}

	@Test
	void zusammenfassungstext() throws Exception {
		create();
		assertEquals("", onFx(() -> summary.getText()), "ohne Gruppierung steht dort nichts");

		fire(checkBox(TicketColumn.LAST_NAME));
		fire(checkBox(TicketColumn.FIRST_NAME));
		assertEquals("Gruppiert nach: Nachname › Vorname", onFx(() -> summary.getText()));

		run(() -> grouping.set(List.of(TicketColumn.TABLE))); // von außen, etwa beim Start
		assertEquals("Gruppiert nach: Tisch", onFx(() -> summary.getText()));
		assertTrue(onFx(() -> checkBoxSelected(TicketColumn.TABLE)));
		assertFalse(onFx(() -> checkBoxSelected(TicketColumn.LAST_NAME)));

		run(() -> grouping.set(List.of()));
		assertEquals("", onFx(() -> summary.getText()));
	}

	@Test
	void ordnungUmschalten() throws Exception {
		create();
		assertTrue(isSelected(radio(GroupOrder.VALUE)));

		fire(radio(GroupOrder.SIZE));
		assertEquals(GroupOrder.SIZE, order.get());
		assertFalse(isSelected(radio(GroupOrder.VALUE)));

		fire(radio(GroupOrder.SIZE)); // ein zweiter Klick wählt nicht ab
		assertEquals(GroupOrder.SIZE, order.get());
		assertTrue(isSelected(radio(GroupOrder.SIZE)));

		run(() -> order.set(GroupOrder.VALUE)); // von außen
		assertTrue(isSelected(radio(GroupOrder.VALUE)));
		assertFalse(isSelected(radio(GroupOrder.SIZE)));
	}

	private static boolean isSelected(RadioButton radio) throws Exception {
		return onFx(radio::isSelected);
	}
}
