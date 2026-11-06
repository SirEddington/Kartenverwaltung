package de.eltviller_carneval_verein.karten.ui.hall;

import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TextAlignH;
import de.eltviller_carneval_verein.karten.model.TextAlignV;
import de.eltviller_carneval_verein.karten.model.TextSettings;
import de.eltviller_carneval_verein.karten.util.Angles;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * Der Name eines Hallenobjekts oder Tischs als Text im Plan: Größe, Lage und Drehung kommen aus den Textfeldern des
 * Objekts ({@link TextSettings}) oder, mit Auto, aus Größe und Form des Objekts. Eine Stelle für Hallen-Editor,
 * Bestuhlungseditor und Saalansicht, damit alle gleich aussehen. Alle Maße sind cm wie im übrigen Plan.
 *
 * <p>
 * Ein Tisch wird im ungedrehten Tisch gerechnet ("oben" ist das Ende mit dem ersten Sitzpaar, die Textdrehung zählt ab
 * der Tischachse); danach dreht sich die Textbox mit dem Tisch um seine Mitte, und der Text steht nie auf dem Kopf
 * ({@link #readable}). Hallenobjekte sind nie gedreht und nehmen die eingestellte Drehung ungespiegelt.
 *
 * <p>
 * Die Rechnung ({@link #layout}, {@link #rotatedBox}, {@link #boxOrigin}, {@link #tableTextCenter},
 * {@link #screenRotation}) kennt kein JavaFX und ist einzeln testbar; nur {@link #create} und {@link #resolve} messen den
 * Text.
 */
public final class PlanText {

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

	/** Wie der Text eines Objekts gezeichnet wird; beim Tisch ist die Drehung relativ zur Tischachse. */
	public record Layout(double fontSize, double rotation, TextAlignH alignH, TextAlignV alignV) {
	}

	/**
	 * Der Bereich eines Objekts, in dem sein Text steht (linke obere Ecke, Breite, Höhe), beim Tisch ungedreht. Ein Kreis
	 * hat die Breite als Durchmesser, sein Text steht im einbeschriebenen Quadrat.
	 */
	public record Area(double x, double y, double width, double height, boolean circle) {
	}

	private PlanText() {
	}

	public static Area areaOf(HallObject hallObject) {
		return new Area(hallObject.getPosX(), hallObject.getPosY(), hallObject.getWidth(), hallObject.getHeight(),
				hallObject.getShape() == Shape.CIRCLE);
	}

	/** Das ungedrehte Tischrechteck. */
	public static Area areaOf(Table table) {
		return new Area(table.getPosX(), table.getPosY(), table.getWidth(), table.getHeight(), false);
	}

	/** Der Text des Hallenobjekts, fertig gesetzt, gedreht und positioniert. Er nimmt keine Mausereignisse an. */
	public static Text create(HallObject hallObject) {
		Area area = areaOf(hallObject);
		Text text = text(hallObject.getName());
		Layout layout = resolve(text.getText(), area, hallObject.getTextSettings());
		text.setFont(font(layout.fontSize()));
		text.setRotate(layout.rotation());
		Bounds bounds = text.getLayoutBounds();
		double[] box = rotatedBox(bounds.getWidth(), bounds.getHeight(), layout.rotation());
		double[] origin = boxOrigin(area, box[0], box[1], layout.alignH(), layout.alignV());
		centerAt(text, origin[0] + box[0] / 2, origin[1] + box[1] / 2);
		return text;
	}

	/**
	 * Der Name des Tischs ({@link Table#getDisplayName()}), fertig gesetzt, mit dem Tisch gedreht (nie auf dem Kopf) und
	 * positioniert. Er nimmt keine Mausereignisse an.
	 */
	public static Text create(Table table) {
		Text text = text(table.getDisplayName());
		Layout layout = resolve(table);
		text.setFont(font(layout.fontSize()));
		text.setRotate(screenRotation(table, layout));
		Bounds bounds = text.getLayoutBounds();
		double[] box = rotatedBox(bounds.getWidth(), bounds.getHeight(), layout.rotation());
		double[] center = tableTextCenter(table, box[0], box[1], layout);
		centerAt(text, center[0], center[1]);
		return text;
	}

	private static Text text(String name) {
		Text text = new Text(name == null ? "" : name);
		text.setTextOrigin(VPos.TOP);
		text.setMouseTransparent(true);
		return text;
	}

	/** Gedreht wird um die Mitte des Textes: Sie soll auf den Punkt fallen. */
	private static void centerAt(Text text, double x, double y) {
		Bounds bounds = text.getLayoutBounds();
		text.setX(x - bounds.getWidth() / 2 - bounds.getMinX());
		text.setY(y - bounds.getHeight() / 2 - bounds.getMinY());
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
		return resolve(hallObject.getName(), areaOf(hallObject), hallObject.getTextSettings());
	}

	/** Wie {@link #resolve(HallObject)} für den Tischnamen; die Drehung ist relativ zur Tischachse. */
	public static Layout resolve(Table table) {
		return resolve(table.getDisplayName(), areaOf(table), table.getTextSettings());
	}

	/**
	 * "Auto aus" am Tisch: Die Textfelder starten mit dem, was Auto gerade berechnet hat ({@link #resolve(Table)}). Ein Tisch
	 * ohne Auto bleibt unverändert.
	 */
	public static void freezeAuto(Table table) {
		if (table.isTextAuto()) {
			freeze(table, resolve(table));
		}
	}

	/** Schreibt die Werte von {@code layout} (auf eine Nachkommastelle, wie die Felder sie zeigen) und schaltet Auto aus. */
	static void freeze(Table table, Layout layout) {
		table.setTextSize(roundForDisplay(layout.fontSize()));
		table.setTextRotation(roundForDisplay(layout.rotation()));
		table.setTextAlignH(layout.alignH());
		table.setTextAlignV(layout.alignV());
		table.setTextAuto(false);
	}

	/** Auf eine Nachkommastelle, damit die Eingabefelder keine langen Dezimalzahlen zeigen. */
	static double roundForDisplay(double value) {
		return Math.round(value * 10) / 10.0;
	}

	private static Layout resolve(String name, Area area, TextSettings settings) {
		Text probe = new Text(name == null ? "" : name);
		probe.setFont(font(MEASURE_SIZE));
		Bounds bounds = probe.getLayoutBounds();
		return layout(area, settings, bounds.getWidth() / MEASURE_SIZE, bounds.getHeight() / MEASURE_SIZE);
	}

	/** Wie {@link #layout(Area, TextSettings, double, double)} für ein Hallenobjekt. */
	static Layout layout(HallObject hallObject, double textWidth, double textHeight) {
		return layout(areaOf(hallObject), hallObject.getTextSettings(), textWidth, textHeight);
	}

	/**
	 * @param textWidth  Breite des einzeiligen Namens bei Schriftgröße 1
	 * @param textHeight Höhe des Namens bei Schriftgröße 1
	 */
	static Layout layout(Area area, TextSettings settings, double textWidth, double textHeight) {
		if (!settings.auto()) {
			return new Layout(Math.max(MIN_SIZE, settings.size()), Angles.normalize(settings.rotation()), settings.alignH(), settings.alignV());
		}

		double[] usable = usableArea(area);
		boolean rotate = !area.circle() && area.height() >= AUTO_ROTATE_RATIO * area.width();
		double along = (rotate ? usable[3] : usable[2]) * AUTO_FILL_ALONG;
		double across = (rotate ? usable[2] : usable[3]) * AUTO_FILL_ACROSS;

		double size = DEFAULT_SIZE;
		if (textWidth > 0 && textHeight > 0) {
			size = Math.min(MAX_AUTO_SIZE, Math.min(along / textWidth, across / textHeight));
		}
		return new Layout(Math.max(MIN_SIZE, size), rotate ? 90 : 0, TextAlignH.CENTER, TextAlignV.CENTER);
	}

	/** Breite und Höhe der kleinsten achsenparallelen Box um einen um {@code degrees} gedrehten Text. */
	static double[] rotatedBox(double width, double height, double degrees) {
		double radians = Math.toRadians(degrees);
		double cos = Math.abs(Math.cos(radians));
		double sin = Math.abs(Math.sin(radians));
		return new double[] { width * cos + height * sin, width * sin + height * cos };
	}

	/** Wie {@link #boxOrigin(Area, double, double, TextAlignH, TextAlignV)} für ein Hallenobjekt. */
	static double[] boxOrigin(HallObject hallObject, double boxWidth, double boxHeight, TextAlignH alignH, TextAlignV alignV) {
		return boxOrigin(areaOf(hallObject), boxWidth, boxHeight, alignH, alignV);
	}

	/**
	 * Obere linke Ecke der Textbox (bereits gedreht gemessen) im nutzbaren Bereich. Passt die Box nicht hinein, wird sie
	 * zentriert, statt nur auf einer Seite hinauszuragen.
	 */
	static double[] boxOrigin(Area area, double boxWidth, double boxHeight, TextAlignH alignH, TextAlignV alignV) {
		double[] usable = usableArea(area);
		double x = place(usable[0], usable[2], boxWidth, alignH == TextAlignH.LEFT ? 0 : alignH == TextAlignH.RIGHT ? 2 : 1);
		double y = place(usable[1], usable[3], boxHeight, alignV == TextAlignV.TOP ? 0 : alignV == TextAlignV.BOTTOM ? 2 : 1);
		return new double[] { x, y };
	}

	/**
	 * Mitte der Textbox eines Tischs im Plan: im ungedrehten Tisch ausgerichtet ({@code boxWidth}/{@code boxHeight} schon
	 * um {@link Layout#rotation()} gedreht gemessen), danach mit der Tischdrehung um die Tischmitte gedreht.
	 */
	static double[] tableTextCenter(Table table, double boxWidth, double boxHeight, Layout layout) {
		double[] origin = boxOrigin(areaOf(table), boxWidth, boxHeight, layout.alignH(), layout.alignV());
		double tableCenterX = table.getPosX() + table.getWidth() / 2;
		double tableCenterY = table.getPosY() + table.getHeight() / 2;
		double dx = origin[0] + boxWidth / 2 - tableCenterX;
		double dy = origin[1] + boxHeight / 2 - tableCenterY;
		double radians = Math.toRadians(table.getRotation());
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		return new double[] { tableCenterX + dx * cos - dy * sin, tableCenterY + dx * sin + dy * cos };
	}

	/** Drehung des Tischtexts auf dem Bildschirm: Tischdrehung plus Textdrehung, nie auf dem Kopf. */
	static double screenRotation(Table table, Layout layout) {
		return readable(table.getRotation() + layout.rotation());
	}

	/** Dreht eine Beschriftung mit, aber nie auf den Kopf: echt zwischen 90 und 270 Grad um 180 Grad weiter. */
	static double readable(double rotation) {
		double normalized = Angles.normalize(rotation);
		return normalized > 90 && normalized < 270 ? normalized - 180 : normalized;
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
	private static double[] usableArea(Area area) {
		if (area.circle()) {
			double radius = area.width() / 2.0;
			double side = radius * Math.sqrt(2);
			return new double[] { area.x() + radius - side / 2, area.y() + radius - side / 2, side, side };
		}
		return new double[] { area.x(), area.y(), area.width(), area.height() };
	}
}
