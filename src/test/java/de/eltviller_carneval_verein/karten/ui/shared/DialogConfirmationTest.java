package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import javafx.stage.Window;

/** KARV-125: die Brücke von der Rückfrage zum Dialog gibt Abbruchtext und Link vollständig weiter. Ohne JavaFX. */
class DialogConfirmationTest {

	private static final URI PAGE = URI.create("https://github.com/SirEddington/Kartenverwaltung/releases/tag/1.2.0");
	private static final BiConsumer<URI, Consumer<String>> OPEN = (page, onError) -> {
	};

	private String style;
	private String cancel;
	private String linkText;
	private URI page;
	private BiConsumer<URI, Consumer<String>> open;
	private final List<String> texts = new ArrayList<>();
	private boolean answer = true;

	private final DialogConfirmation confirmation = new DialogConfirmation(
			(String title, String message, String actionLabel, String actionStyleClass, String cancelLabel, Window owner,
					String link, URI address, BiConsumer<URI, Consumer<String>> opener) -> {
				texts.add(title + "|" + message + "|" + actionLabel);
				style = actionStyleClass;
				cancel = cancelLabel;
				linkText = link;
				page = address;
				open = opener;
				return answer;
			}, null);

	private void assertNoLink() {
		assertNull(linkText);
		assertNull(page);
		assertNull(open);
	}

	@Test
	void threeArguments() {
		assertTrue(confirmation.ask("T", "X", "K"));
		assertEquals(List.of("T|X|K"), texts);
		assertEquals("button-primary", style);
		assertEquals("Abbrechen", cancel);
		assertNoLink();
	}

	@Test
	void fourArgumentsWithOwnCancel() {
		confirmation.ask("T", "X", "K", "Später");
		assertEquals("Später", cancel);
		assertNoLink();
	}

	@Test
	void fourArgumentsWithNullCancel() {
		confirmation.ask("T", "X", "K", null);
		assertEquals("Abbrechen", cancel);
	}

	@Test
	void fiveArgumentsWithLink() {
		confirmation.ask("T", "X", "K", "Später", Optional.of(new Confirmation.Link("Was ist neu?", PAGE, OPEN)));
		assertEquals("Später", cancel);
		assertEquals("Was ist neu?", linkText);
		assertEquals(PAGE, page);
		assertSame(OPEN, open);
	}

	@Test
	void fiveArgumentsWithoutLink() {
		confirmation.ask("T", "X", "K", "Später", Optional.empty());
		assertNoLink();
		assertEquals(List.of("T|X|K"), texts);
	}

	@Test
	void fiveArgumentsWithNullCancel() {
		confirmation.ask("T", "X", "K", null, Optional.empty());
		assertEquals("Abbrechen", cancel);
	}

	@Test
	void answerIsPassedThrough() {
		answer = false;
		assertFalse(confirmation.ask("T", "X", "K", null, Optional.empty()));
		answer = true;
		assertTrue(confirmation.ask("T", "X", "K", null, Optional.empty()));
	}

	@Test
	void confirmationDefaultsIgnoreTheLink() {
		List<String> asked = new ArrayList<>();
		Confirmation lambda = (titel, text, knopf) -> asked.add(titel + "|" + knopf);
		assertTrue(lambda.ask("T", "X", "K", null, Optional.of(new Confirmation.Link("L", PAGE, OPEN))));
		assertTrue(lambda.ask("T", "X", "K", "Später", Optional.empty()));
		assertEquals(List.of("T|K", "T|K"), asked);
	}
}
