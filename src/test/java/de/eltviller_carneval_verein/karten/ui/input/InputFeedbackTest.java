package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Der Teil der Rückmeldung ohne JavaFX-Toolkit; Fußzeile und Markierung prüft das Scratch-Programm. */
class InputFeedbackTest {

	@Test
	void labelCombinesFieldAndRow() {
		assertEquals("Preis von Tisch 4, Sitz 2", InputFeedback.label("Preis", "Tisch 4, Sitz 2"));
	}

	@Test
	void labelWithoutRowIsTheFieldName() {
		assertEquals("Breite", InputFeedback.label("Breite", null));
		assertEquals("Breite", InputFeedback.label("Breite", ""));
		assertEquals("Breite", InputFeedback.label("Breite", "  "));
	}

	@Test
	void blankTextIsShownAsEmpty() {
		assertEquals("(leer)", InputFeedback.displayText(""));
		assertEquals("(leer)", InputFeedback.displayText("   "));
		assertEquals("(leer)", InputFeedback.displayText(null));
		assertEquals("abc", InputFeedback.displayText("abc"));
	}
}
