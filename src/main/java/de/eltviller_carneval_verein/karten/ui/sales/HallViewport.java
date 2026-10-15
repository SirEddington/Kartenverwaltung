package de.eltviller_carneval_verein.karten.ui.sales;

import javafx.geometry.Rectangle2D;

/**
 * Rechenkern für den Ausschnitt eines Saalplans. Die Zeichenfläche wird wie bei {@code Node.setScaleX/setTranslateX}
 * um ihre Mitte skaliert und dann verschoben; Koordinaten der Fläche sind Hallenkoordinaten (cm).
 */
final class HallViewport {

	/** Zoomfaktor und Verschiebung der Zeichenfläche. */
	record View(double scale, double translateX, double translateY) {
	}

	private HallViewport() {
	}

	/**
	 * Zoom und Verschiebung, die {@code target} (Hallenkoordinaten) mittig in die Fläche einpassen. Der Faktor
	 * {@code margin} (z. B. 0,95) lässt einen Rand. Die Zoomgrenzen der Bedienung gelten hier nicht.
	 */
	static View fit(double viewWidth, double viewHeight, Rectangle2D target, double margin) {
		if (viewWidth <= 0 || viewHeight <= 0 || target.getWidth() <= 0 || target.getHeight() <= 0) {
			return new View(1, 0, 0);
		}
		double scale = margin * Math.min(viewWidth / target.getWidth(), viewHeight / target.getHeight());
		return centered(viewWidth, viewHeight, target, scale);
	}

	/** Die Verschiebung, die bei gegebenem Zoom die Mitte von {@code target} auf die Flächenmitte legt. */
	static View centered(double viewWidth, double viewHeight, Rectangle2D target, double scale) {
		double pivotX = viewWidth / 2.0;
		double pivotY = viewHeight / 2.0;
		double centerX = target.getMinX() + target.getWidth() / 2.0;
		double centerY = target.getMinY() + target.getHeight() / 2.0;
		return new View(scale, -scale * (centerX - pivotX), -scale * (centerY - pivotY));
	}

	/** Der Ausschnitt in Hallenkoordinaten, den die Fläche bei diesem Zoom und dieser Verschiebung zeigt. */
	static Rectangle2D visibleRect(double viewWidth, double viewHeight, View view) {
		double pivotX = viewWidth / 2.0;
		double pivotY = viewHeight / 2.0;
		double minX = pivotX + (0 - pivotX - view.translateX()) / view.scale();
		double minY = pivotY + (0 - pivotY - view.translateY()) / view.scale();
		return new Rectangle2D(minX, minY, viewWidth / view.scale(), viewHeight / view.scale());
	}
}
