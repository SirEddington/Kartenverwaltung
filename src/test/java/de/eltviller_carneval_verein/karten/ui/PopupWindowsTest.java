package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Popup;
import javafx.stage.Stage;

/** Welche Popups zu einem Fenster gehören (KARV-194: Der Hinweis am Mauszeiger weicht ihnen). */
class PopupWindowsTest {

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
	void aPopupBelongsToItsOwnerDirectlyOrThroughAnotherPopup() throws Exception {
		List<Boolean> results = onFx(() -> {
			Stage main = new Stage();
			main.setScene(new Scene(new StackPane(), 200, 200));
			main.show();
			Stage other = new Stage();
			other.setScene(new Scene(new StackPane(), 100, 100));
			other.show();
			Popup dialog = new Popup();
			dialog.getContent().add(new Label("Sitz"));
			dialog.show(main);
			Popup nested = new Popup();
			nested.getContent().add(new Label("Auswahlliste"));
			nested.show(dialog);
			Popup foreign = new Popup();
			foreign.getContent().add(new Label("fremd"));
			foreign.show(other);
			try {
				return List.of(PopupWindows.belongsTo(dialog, main), PopupWindows.belongsTo(nested, main), PopupWindows.belongsTo(foreign, main),
						PopupWindows.belongsTo(other, main), PopupWindows.belongsTo(main, main));
			} finally {
				nested.hide();
				dialog.hide();
				foreign.hide();
				other.hide();
				main.hide();
			}
		});

		assertEquals(List.of(true, true, false, false, false), results, "direkt, über ein Popup, fremdes Fenster, Stage, das Fenster selbst");
	}
}
