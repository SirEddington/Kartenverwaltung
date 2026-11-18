package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** Welche Fenster den Hinweis am Mauszeiger ausblenden (KARV-194): Menüs und Dialoge ja, Tooltips nicht. */
class SelectionHintPopupTest {

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

	@Test
	void menusBlockTooltipsAndOtherWindowsDoNot() throws Exception {
		List<Boolean> results = onFx(() -> {
			Stage main = new Stage();
			main.setScene(new Scene(new StackPane(), 200, 200));
			main.show();
			ContextMenu menu = new ContextMenu(new MenuItem("Verkaufen"));
			menu.show(main);
			Tooltip tooltip = new Tooltip("3 Treffer");
			tooltip.show(main);
			Stage customerDisplay = new Stage();
			customerDisplay.initOwner(main);
			customerDisplay.setScene(new Scene(new StackPane(), 100, 100));
			customerDisplay.show();
			try {
				return List.of(SelectionHint.blocks(menu, main), SelectionHint.blocks(tooltip, main), SelectionHint.blocks(customerDisplay, main));
			} finally {
				menu.hide();
				tooltip.hide();
				customerDisplay.hide();
				main.hide();
			}
		});

		assertEquals(List.of(true, false, false), results, "Kontextmenü, Tooltip, Kundenfenster");
	}
}
