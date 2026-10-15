package de.eltviller_carneval_verein.karten.ui.sales;

import de.eltviller_carneval_verein.karten.model.Hall;
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

	/** Die Halle als Rechteck ab der oberen linken Ecke (0/0) in cm, {@code null} ohne Halle oder ohne Maße. */
	static Rectangle2D hallRect(Hall hall) {
		if (hall == null || hall.getHallWidth() <= 0 || hall.getHallHeight() <= 0) {
			return null;
		}
		return new Rectangle2D(0, 0, hall.getHallWidth(), hall.getHallHeight());
	}

	/** Rand, mit dem die ganze Halle eingepasst wird (Saalübersicht, Kundenfenster). */
	static final double HALL_FIT_MARGIN = 0.95;

	/**
	 * Der Ausschnitt für eine andere Fläche mit dem Seitenverhältnis {@code paneAspect} (Breite / Höhe), der
	 * <em>relativ</em> zur eigenen Einpassung dasselbe zeigt wie {@code mainVisible} im Hauptfenster: dieselbe Mitte und
	 * derselbe Zoom im Verhältnis zur ganz eingepassten Halle. Ist das Hauptfenster auf "ganzer Saal", ergibt das genau den
	 * eingepassten Saal der anderen Fläche; die Halle behält so ihre Größe, egal wie die Fenster geformt sind.
	 *
	 * @param mainVisible der sichtbare Ausschnitt des Hauptfensters (Hallenkoordinaten)
	 * @param hall        die Halle, {@code (0, 0, Breite, Höhe)}
	 * @param margin      der Rand der Einpassung ({@link #HALL_FIT_MARGIN}), im Hauptfenster derselbe wie hier
	 */
	static Rectangle2D relativeMirror(Rectangle2D mainVisible, Rectangle2D hall, double margin, double paneAspect) {
		// Zoom des Hauptfensters im Verhältnis zu seiner Einpassung: Dessen Fläche hat dasselbe Seitenverhältnis wie
		// mainVisible, die Einpassung zeigt einen Ausschnitt mit min(w / Halle.w, h / Halle.h) = 1 / margin.
		double relativeZoom = 1 / (margin * Math.min(mainVisible.getWidth() / hall.getWidth(), mainVisible.getHeight() / hall.getHeight()));
		// Der eingepasste Saal auf einer Fläche mit paneAspect, dann um den relativen Zoom enger
		double width;
		double height;
		if (paneAspect >= hall.getWidth() / hall.getHeight()) {
			height = hall.getHeight() / margin;
			width = paneAspect * height;
		} else {
			width = hall.getWidth() / margin;
			height = width / paneAspect;
		}
		width /= relativeZoom;
		height /= relativeZoom;
		double centerX = mainVisible.getMinX() + mainVisible.getWidth() / 2.0;
		double centerY = mainVisible.getMinY() + mainVisible.getHeight() / 2.0;
		return new Rectangle2D(centerX - width / 2.0, centerY - height / 2.0, width, height);
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
