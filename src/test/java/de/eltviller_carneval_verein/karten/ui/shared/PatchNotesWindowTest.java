package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.update.PatchNotes;
import de.eltviller_carneval_verein.karten.update.PatchNotes.Release;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/** KARV-125: das Fenster „Was ist neu“. */
class PatchNotesWindowTest {

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

	@AfterEach
	void close() throws Exception {
		onFx(() -> {
			PatchNotesWindow.closeForTest();
			return null;
		});
	}

	private static List<Release> releases(String... lines) {
		return PatchNotes.parse(String.join("\n", lines)).releases();
	}

	private static final List<Release> ONE = releases("## 1.1.0", "### Neu", "- Erster Eintrag", "### Behoben",
			"- Fehler weg");
	private static final List<Release> TWO = releases("## 1.1.0", "### Neu", "- A", "## 1.0.9", "### Neu", "- B");

	private static Stage show(Window owner, List<Release> releases) throws Exception {
		return onFx(() -> {
			PatchNotesWindow.show(owner, releases);
			return PatchNotesWindow.openStage();
		});
	}

	private static List<String> labels(Stage stage) throws Exception {
		return onFx(() -> stage.getScene().getRoot().lookupAll(".label").stream().map(n -> ((Label) n).getText())
				.toList());
	}

	@Test
	void titleForOneVersion() throws Exception {
		assertEquals("Was ist neu in Version 1.1.0", show(null, ONE).getTitle());
	}

	@Test
	void titleForSeveral() throws Exception {
		assertEquals("Was ist neu", show(null, TWO).getTitle());
	}

	@Test
	void showsVersionsSectionsAndEntries() throws Exception {
		Stage stage = show(null, ONE);
		List<String> texts = labels(stage);
		assertTrue(texts.contains("Version 1.1.0"), texts.toString());
		assertTrue(texts.contains("Neu"), texts.toString());
		assertTrue(texts.contains("Behoben"), texts.toString());
		assertTrue(texts.stream().anyMatch(t -> t.contains("Erster Eintrag")), texts.toString());
		Label neu = onFx(() -> (Label) stage.getScene().getRoot().lookupAll(".label").stream()
				.filter(n -> "Neu".equals(((Label) n).getText())).findFirst().orElseThrow());
		assertTrue(neu.getStyle().contains("bold") || neu.getStyleClass().contains("patch-notes-section"),
				neu.getStyle());
	}

	@Test
	void secondCallReplacesContent() throws Exception {
		Stage first = show(null, ONE);
		Stage second = show(null, TWO);
		assertTrue(first == second);
		long open = onFx(() -> Window.getWindows().stream().filter(w -> w instanceof Stage s && s.getTitle() != null
				&& s.getTitle().startsWith("Was ist neu")).count());
		assertEquals(1, open);
		assertTrue(labels(second).contains("Version 1.0.9"));
	}

	@Test
	void emptyListOpensNothing() throws Exception {
		assertNull(show(null, List.of()));
	}

	@Test
	void staysWithinScreen() throws Exception {
		StringBuilder lines = new StringBuilder();
		for (int i = 30; i > 15; i--) {
			lines.append("## 1.").append(i).append(".0\n### Neu\n- ").append("Sehr langer Eintrag ".repeat(100))
					.append("\n");
		}
		Stage stage = show(null, PatchNotes.parse(lines.toString()).releases());
		double limit = onFx(() -> Screen.getPrimary().getVisualBounds().getHeight() * 0.7);
		assertTrue(stage.getHeight() <= limit + 1, stage.getHeight() + " > " + limit);
		Node content = onFx(() -> stage.getScene().getRoot().lookup("#patch-notes-content"));
		double contentWidth = onFx(() -> content.getLayoutBounds().getWidth());
		assertTrue(contentWidth <= stage.getScene().getWidth(), contentWidth + " > " + stage.getScene().getWidth());
		Button close = onFx(() -> (Button) stage.getScene().getRoot().lookup("#patch-notes-close"));
		Bounds bounds = onFx(() -> close.localToScene(close.getBoundsInLocal()));
		assertTrue(bounds.getMaxY() <= stage.getScene().getHeight() + 1, bounds + " / " + stage.getScene().getHeight());
	}

	@Test
	void showWithoutOwnerUsesPrimaryScreen() throws Exception {
		Stage stage = show(null, ONE);
		assertNotNull(stage);
		double limit = onFx(() -> Screen.getPrimary().getVisualBounds().getHeight() * 0.7);
		assertTrue(stage.getMaxHeight() <= limit + 1, String.valueOf(stage.getMaxHeight()));
	}

	@Test
	void reshowKeepsTheSizeTheUserChose() throws Exception {
		Stage stage = show(null, ONE);
		onFx(() -> {
			stage.setWidth(900);
			return null;
		});
		show(null, TWO);
		assertEquals(900, onFx(stage::getWidth), 0.5);
	}

	@Test
	void ownerOutsideEveryScreenFallsBackToPrimary() throws Exception {
		double height = onFx(() -> {
			Stage away = new Stage();
			away.setX(-100000);
			away.setY(-100000);
			away.setWidth(200);
			away.setHeight(200);
			return PatchNotesWindow.screenHeight(away);
		});
		assertEquals(onFx(() -> Screen.getPrimary().getVisualBounds().getHeight()), height, 0.5);
		assertEquals(onFx(() -> Screen.getPrimary().getVisualBounds().getHeight()),
				onFx(() -> PatchNotesWindow.screenHeight(null)), 0.5);
	}

	@Test
	void escapeCloses() throws Exception {
		Stage stage = show(null, ONE);
		onFx(() -> {
			stage.getScene().getRoot().fireEvent(
					new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
			return null;
		});
		assertFalse(onFx(stage::isShowing));
	}

	@Test
	void closeButtonCloses() throws Exception {
		Stage stage = show(null, ONE);
		onFx(() -> {
			((Button) stage.getScene().getRoot().lookup("#patch-notes-close")).fire();
			return null;
		});
		assertFalse(onFx(stage::isShowing));
	}
}
