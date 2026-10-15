package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import javafx.geometry.Rectangle2D;

/** Wahl des Bildschirms für das Kundenfenster. */
final class ScreenChoice {

	private ScreenChoice() {
	}

	/**
	 * Der Index des ersten Bildschirms, auf dem die Mitte des Hauptfensters nicht liegt, {@code -1}, wenn es keinen
	 * anderen Bildschirm gibt.
	 */
	static int indexOfOtherScreen(List<Rectangle2D> screens, Rectangle2D window) {
		double centerX = window.getMinX() + window.getWidth() / 2.0;
		double centerY = window.getMinY() + window.getHeight() / 2.0;
		for (int i = 0; i < screens.size(); i++) {
			if (!screens.get(i).contains(centerX, centerY)) {
				return i;
			}
		}
		return -1;
	}
}
