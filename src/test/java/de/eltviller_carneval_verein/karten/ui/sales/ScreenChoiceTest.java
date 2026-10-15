package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import javafx.geometry.Rectangle2D;

class ScreenChoiceTest {

	private final Rectangle2D left = new Rectangle2D(0, 0, 1920, 1080);
	private final Rectangle2D right = new Rectangle2D(1920, 0, 1920, 1080);

	@Test
	void picksTheScreenTheMainWindowIsNotOn() {
		assertEquals(1, ScreenChoice.indexOfOtherScreen(List.of(left, right), new Rectangle2D(100, 100, 800, 600)));
	}

	@Test
	void picksTheFirstScreenWhenTheMainWindowIsOnTheOtherOne() {
		assertEquals(0, ScreenChoice.indexOfOtherScreen(List.of(left, right), new Rectangle2D(2000, 100, 800, 600)));
	}

	@Test
	void singleScreenHasNoOtherScreen() {
		assertEquals(-1, ScreenChoice.indexOfOtherScreen(List.of(left), new Rectangle2D(100, 100, 800, 600)));
	}

	@Test
	void aCenterExactlyOnTheSharedEdgeBelongsToTheRightScreenOnly() {
		// Mitte bei x = 1520 + 400 = 1920: genau auf der Kante, sie gehört zum rechten Bildschirm
		assertEquals(0, ScreenChoice.indexOfOtherScreen(List.of(left, right), new Rectangle2D(1520, 100, 800, 600)));
	}

	@Test
	void windowBetweenScreensIsDecidedByItsCenter() {
		// Mitte bei x = 1700 + 400 = 2100: liegt auf dem rechten Bildschirm
		assertEquals(0, ScreenChoice.indexOfOtherScreen(List.of(left, right), new Rectangle2D(1700, 100, 800, 600)));
		// Mitte bei x = 1400 + 400 = 1800: liegt auf dem linken Bildschirm
		assertEquals(1, ScreenChoice.indexOfOtherScreen(List.of(left, right), new Rectangle2D(1400, 100, 800, 600)));
	}
}
