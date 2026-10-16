package de.eltviller_carneval_verein.karten.ui.hall;

import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.model.TextAlignH;
import de.eltviller_carneval_verein.karten.model.TextAlignV;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * Der Name eines Hallenobjekts als Text im Plan: Größe, Lage und Drehung kommen aus den Textfeldern des Objekts oder,
 * mit {@link HallObject#isTextAuto() Auto}, aus Größe und Form des Objekts. Eine Stelle für den Hallen-Editor und die
 * Saalansicht, damit beide gleich aussehen. Alle Maße sind cm wie im übrigen Plan.
 *
 * <p>
 * Die Rechnung ({@link #layout}, {@link #rotatedBox}, {@link #boxOrigin}) kennt kein JavaFX und ist einzeln
 * testbar; nur {@link #create} und {@link #resolve} messen den Text.
 */
public final class HallObjectText {

	private static final String FONT_FAMILY = "Yanone Kaffeesatz Regular";

	/** Schriftgröße, wenn sich ein Text nicht messen lässt (leerer Name). */
	static final double DEFAULT_SIZE = 20;
	static final double MIN_SIZE = 1;
	/** Obergrenze der automatischen Größe, damit ein kurzer Name in einem großen Objekt nicht riesig wird. */
	static final double MAX_AUTO_SIZE = 180;
	/** Abstand des Textes zum Rand, wenn er links/rechts/oben/unten ausgerichtet ist. */
	static final double PADDING = 5;

	// Auto: Der Text füllt höchstens diesen Anteil der Länge bzw. der Dicke des nutzbaren Bereichs
	private static final double AUTO_FILL_ALONG = 0.9;
	private static final double AUTO_FILL_ACROSS = 0.8;
	// Auto: Ab diesem Verhältnis Höhe zu Breite läuft der Text von oben nach unten
	private static final double AUTO_ROTATE_RATIO = 1.5;
	private static final double MEASURE_SIZE = 100;

	/** Wie der Text eines Objekts gezeichnet wird. */
	public record Layout(double fontSize, double rotation, TextAlignH alignH, TextAlignV alignV) {
	}

	private HallObjectText() {
	}

	/** Der Text des Objekts, fertig gesetzt, gedreht und positioniert. Er nimmt keine Mausereignisse an. */
	public static Text create(HallObject hallObject) {
		Text text = new Text(hallObject.getName());
		Layout layout = resolve(hallObject);
		text.setFont(font(layout.fontSize()));
		text.setTextOrigin(VPos.TOP);
		text.setRotate(layout.rotation());
		text.setMouseTransparent(true);

		Bounds bounds = text.getLayoutBounds();
		double[] box = rotatedBox(bounds.getWidth(), bounds.getHeight(), layout.rotation());
		double[] origin = boxOrigin(hallObject, box[0], box[1], layout.alignH(), layout.alignV());
		// Gedreht wird um die Mitte des Textes: Sie soll in die Mitte der gewünschten Box fallen
		text.setX(origin[0] + box[0] / 2 - bounds.getWidth() / 2 - bounds.getMinX());
		text.setY(origin[1] + box[1] / 2 - bounds.getHeight() / 2 - bounds.getMinY());
		return text;
	}

	/**
	 * Yanone Kaffeesatz Regular, eine Schrift der App ({@code MainApp} lädt sie beim Start, {@code style.css} nutzt
	 * sie für Überschriften). Messen und Zeichnen müssen dieselbe Schrift verwenden, sonst stimmt die
	 * automatische Größe nicht. Ist sie nicht geladen, fällt JavaFX in beiden Fällen auf die Standardschrift zurück.
	 */
	private static Font font(double size) {
		return Font.font(FONT_FAMILY, FontWeight.NORMAL, size);
	}

	/** Größe, Drehung und Lage, mit denen der Text des Objekts gezeichnet wird (bei Auto die berechneten Werte). */
	public static Layout resolve(HallObject hallObject) {
		Text probe = new Text(hallObject.getName() == null ? "" : hallObject.getName());
		probe.setFont(font(MEASURE_SIZE));
		Bounds bounds = probe.getLayoutBounds();
		return layout(hallObject, bounds.getWidth() / MEASURE_SIZE, bounds.getHeight() / MEASURE_SIZE);
	}

	/**
	 * @param textWidth  Breite des einzeiligen Namens bei Schriftgröße 1
	 * @param textHeight Höhe des Namens bei Schriftgröße 1
	 */
	static Layout layout(HallObject hallObject, double textWidth, double textHeight) {
		if (!hallObject.isTextAuto()) {
			return new Layout(Math.max(MIN_SIZE, hallObject.getTextSize()), normalizeRotation(hallObject.getTextRotation()), hallObject.getTextAlignH(), hallObject.getTextAlignV());
		}

		double[] area = usableArea(hallObject);
		boolean rotate = hallObject.getShape() != Shape.CIRCLE && hallObject.getHeight() >= AUTO_ROTATE_RATIO * hallObject.getWidth();
		double along = (rotate ? area[3] : area[2]) * AUTO_FILL_ALONG;
		double across = (rotate ? area[2] : area[3]) * AUTO_FILL_ACROSS;

		double size = DEFAULT_SIZE;
		if (textWidth > 0 && textHeight > 0) {
			size = Math.min(MAX_AUTO_SIZE, Math.min(along / textWidth, across / textHeight));
		}
		return new Layout(Math.max(MIN_SIZE, size), rotate ? 90 : 0, TextAlignH.CENTER, TextAlignV.CENTER);
	}

	/** Drehung in Grad auf 0 bis unter 360 bringen. */
	static double normalizeRotation(double degrees) {
		double normalized = degrees % 360;
		return normalized < 0 ? normalized + 360 : normalized;
	}

	/** Breite und Höhe der kleinsten achsenparallelen Box um einen um {@code degrees} gedrehten Text. */
	static double[] rotatedBox(double width, double height, double degrees) {
		double radians = Math.toRadians(degrees);
		double cos = Math.abs(Math.cos(radians));
		double sin = Math.abs(Math.sin(radians));
		return new double[] { width * cos + height * sin, width * sin + height * cos };
	}

	/**
	 * Obere linke Ecke der Textbox (bereits gedreht gemessen) im nutzbaren Bereich des Objekts. Passt die Box nicht
	 * hinein, wird sie zentriert, statt nur auf einer Seite hinauszuragen.
	 */
	static double[] boxOrigin(HallObject hallObject, double boxWidth, double boxHeight, TextAlignH alignH, TextAlignV alignV) {
		double[] area = usableArea(hallObject);
		double x = place(area[0], area[2], boxWidth, alignH == TextAlignH.LEFT ? 0 : alignH == TextAlignH.RIGHT ? 2 : 1);
		double y = place(area[1], area[3], boxHeight, alignV == TextAlignV.TOP ? 0 : alignV == TextAlignV.BOTTOM ? 2 : 1);
		return new double[] { x, y };
	}

	/** Start der Box in einem Bereich; {@code position}: 0 = Anfang, 1 = Mitte, 2 = Ende. */
	private static double place(double start, double length, double size, int position) {
		double free = length - size;
		if (position == 1 || free < 2 * PADDING) {
			return start + free / 2;
		}
		return position == 0 ? start + PADDING : start + length - PADDING - size;
	}

	/** Bereich, in dem Text stehen soll: x, y, Breite, Höhe. Bei Kreisen das einbeschriebene Quadrat. */
	private static double[] usableArea(HallObject hallObject) {
		if (hallObject.getShape() == Shape.CIRCLE) {
			double radius = hallObject.getWidth() / 2.0;
			double side = radius * Math.sqrt(2);
			return new double[] { hallObject.getPosX() + radius - side / 2, hallObject.getPosY() + radius - side / 2, side, side };
		}
		return new double[] { hallObject.getPosX(), hallObject.getPosY(), hallObject.getWidth(), hallObject.getHeight() };
	}
}
