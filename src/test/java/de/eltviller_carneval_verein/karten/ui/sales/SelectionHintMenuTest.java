package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;

/** Das Untermenü "Auswahl am Mauszeiger" (KARV-197): drei Einträge für Schalter und Lage, dazu "Am Rand ausweichen". */
class SelectionHintMenuTest {

	private final BooleanProperty enabled = new SimpleBooleanProperty(true);
	private final ObjectProperty<SelectionHintPlacement> placement = new SimpleObjectProperty<>(SelectionHintPlacement.ABOVE);
	private final BooleanProperty avoidEdges = new SimpleBooleanProperty(true);

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

	/** Baut das Menü auf dem FX-Thread und führt {@code check} damit dort aus. */
	private void withMenu(Consumer<Menu> check) throws Exception {
		onFx(() -> {
			check.accept(SelectionHintMenu.create(enabled, placement, avoidEdges));
			return null;
		});
	}

	private static RadioMenuItem entry(Menu menu, String text) {
		return radios(menu).filter(item -> text.equals(item.getText())).findFirst().orElseThrow();
	}

	private static String chosen(Menu menu) {
		return radios(menu).filter(RadioMenuItem::isSelected).map(MenuItem::getText).reduce((a, b) -> a + "+" + b).orElse("keiner");
	}

	private static Stream<RadioMenuItem> radios(Menu menu) {
		return menu.getItems().stream().filter(RadioMenuItem.class::isInstance).map(RadioMenuItem.class::cast);
	}

	private static CheckMenuItem avoidEdgesItem(Menu menu) {
		return menu.getItems().stream().filter(CheckMenuItem.class::isInstance).map(CheckMenuItem.class::cast).findFirst().orElseThrow();
	}

	@Test
	void offersOffAboveAndBelowThenTheEdgeTick() throws Exception {
		withMenu(menu -> {
			assertEquals("Auswahl am Mauszeiger", menu.getText());
			assertEquals(List.of("Aus", "Über dem Zeiger", "Unter dem Zeiger"), radios(menu).map(MenuItem::getText).toList());
			assertTrue(menu.getItems().get(3) instanceof SeparatorMenuItem, "Trenner vor dem Haken");
			assertEquals("Am Rand ausweichen", menu.getItems().get(4).getText());
			assertEquals(5, menu.getItems().size());
		});
	}

	@Test
	void theEdgeTickFollowsItsSettingBothWays() throws Exception {
		withMenu(menu -> {
			CheckMenuItem tick = avoidEdgesItem(menu);
			assertTrue(tick.isSelected(), "Standard an");
			tick.setSelected(false);
			assertFalse(avoidEdges.get());
			avoidEdges.set(true);
			assertTrue(tick.isSelected());
		});
	}

	@Test
	void theEdgeTickLeavesTheChoiceAlone() throws Exception {
		enabled.set(false);
		placement.set(SelectionHintPlacement.BELOW);
		withMenu(menu -> {
			avoidEdgesItem(menu).setSelected(false);
			assertEquals("Aus", chosen(menu));
		});

		assertFalse(enabled.get(), "der Haken schaltet den Hinweis nicht ein");
		assertEquals(SelectionHintPlacement.BELOW, placement.get());
		assertFalse(avoidEdges.get(), "auch bei Aus gespeichert");
	}

	@Test
	void showsTheCurrentChoice() throws Exception {
		withMenu(menu -> assertEquals("Über dem Zeiger", chosen(menu)));
		placement.set(SelectionHintPlacement.BELOW);
		withMenu(menu -> assertEquals("Unter dem Zeiger", chosen(menu)));
		enabled.set(false);
		withMenu(menu -> assertEquals("Aus", chosen(menu), "die Lage bleibt, gewählt ist aber Aus"));
		enabled.set(true);
		placement.set(null);
		withMenu(menu -> assertEquals("Über dem Zeiger", chosen(menu), "keine Lage gilt als oben"));
	}

	@Test
	void offKeepsThePlacement() throws Exception {
		placement.set(SelectionHintPlacement.BELOW);
		withMenu(menu -> entry(menu, "Aus").setSelected(true));

		assertFalse(enabled.get());
		assertEquals(SelectionHintPlacement.BELOW, placement.get(), "beim Wiedereinschalten gilt die Lage wieder");
	}

	@Test
	void choosingAPlacementSwitchesTheHintOn() throws Exception {
		enabled.set(false);
		withMenu(menu -> {
			entry(menu, "Unter dem Zeiger").setSelected(true);
			assertEquals("Unter dem Zeiger", chosen(menu), "kein Rücksprung auf Aus");
		});

		assertTrue(enabled.get());
		assertEquals(SelectionHintPlacement.BELOW, placement.get());
	}

	@Test
	void followsChangesFromOutside() throws Exception {
		withMenu(menu -> {
			placement.set(SelectionHintPlacement.BELOW);
			assertEquals("Unter dem Zeiger", chosen(menu));
			enabled.set(false);
			assertEquals("Aus", chosen(menu));
			placement.set(SelectionHintPlacement.ABOVE);
			assertEquals("Aus", chosen(menu), "eine andere Lage schaltet den Hinweis nicht ein");
			enabled.set(true);
			assertEquals("Über dem Zeiger", chosen(menu));
		});
	}
}
