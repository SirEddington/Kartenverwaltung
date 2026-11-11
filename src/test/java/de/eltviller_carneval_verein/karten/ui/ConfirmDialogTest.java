package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/** KARV-125: die Rückfrage mit Link, ohne sie blockierend zu zeigen. */
class ConfirmDialogTest {

	private static final URI PAGE = URI.create("https://github.com/SirEddington/Kartenverwaltung/releases/tag/1.2.0");
	private static final String FAILED = "Die Seite ließ sich nicht öffnen: " + PAGE;

	private final List<String> calls = new ArrayList<>();

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

	private ConfirmDialog.Built build(String linkText, BiConsumer<URI, Consumer<String>> open) throws Exception {
		return onFx(() -> ConfirmDialog.build("Titel", "Eine lange Frage zum Update, die umbrechen soll, damit der "
				+ "Dialog nicht breiter wird als nötig.", "Aktualisieren", "button-primary", "Abbrechen", null, linkText,
				linkText == null ? null : PAGE, open));
	}

	private static Node find(ConfirmDialog.Built built, String id) {
		return built.alert().getDialogPane().lookup("#" + id);
	}

	@Test
	void withoutLinkLooksAsBefore() throws Exception {
		ConfirmDialog.Built built = build(null, null);
		assertTrue(built.alert().getDialogPane().getContentText().startsWith("Eine lange Frage"));
		assertNull(find(built, "confirm-link"));
	}

	@Test
	void linkWithTooltip() throws Exception {
		ConfirmDialog.Built built = build("Was ist neu in Version 1.2.0?", (p, e) -> calls.add("open " + p));
		Hyperlink link = (Hyperlink) find(built, "confirm-link");
		assertEquals("Was ist neu in Version 1.2.0?", link.getText());
		assertEquals(PAGE.toString(), link.getTooltip().getText());
		// das DialogPane gibt seinem Inhalt selbst die Stilklasse "content"; das Text-Label ist das erste Kind
		Label message = (Label) ((javafx.scene.layout.VBox) built.alert().getDialogPane().getContent()).getChildren().get(0);
		assertTrue(message.getStyleClass().contains("content"));
		assertTrue(message.isWrapText());
		assertEquals(360, message.getPrefWidth());
	}

	@Test
	void clickOpensAndKeepsDialog() throws Exception {
		ConfirmDialog.Built built = build("Link", (p, e) -> calls.add("open " + p));
		onFx(() -> {
			((Hyperlink) find(built, "confirm-link")).fire();
			return null;
		});
		assertEquals(List.of("open " + PAGE), calls);
		Node error = find(built, "confirm-link-error");
		assertFalse(error.isVisible());
		assertFalse(error.isManaged());
	}

	@Test
	void failureShowsErrorAndAddress() throws Exception {
		ConfirmDialog.Built built = build("Link", (p, e) -> e.accept(FAILED));
		onFx(() -> {
			((Hyperlink) find(built, "confirm-link")).fire();
			return null;
		});
		Node error = find(built, "confirm-link-error");
		assertTrue(error.isVisible());
		assertTrue(error.isManaged());
		assertTrue(((Label) error.lookup(".label")).getText().equals(FAILED));
		TextField address = (TextField) find(built, "confirm-link-address");
		assertFalse(address.isEditable());
		assertEquals(PAGE.toString(), address.getText());
	}

	@Test
	void errorLineGrowsTheShownDialog() throws Exception {
		ConfirmDialog.Built built = build("Link", (p, e) -> e.accept(FAILED));
		double before = onFx(() -> {
			built.alert().show();
			return built.alert().getDialogPane().getScene().getWindow().getHeight();
		});
		onFx(() -> {
			((Hyperlink) find(built, "confirm-link")).fire();
			return null;
		});
		double after = onFx(() -> built.alert().getDialogPane().getScene().getWindow().getHeight());
		onFx(() -> {
			built.alert().close();
			return null;
		});
		assertTrue(after > before, before + " -> " + after);
	}
}
