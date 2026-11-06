package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TextAlignH;
import de.eltviller_carneval_verein.karten.model.TextAlignV;
import de.eltviller_carneval_verein.karten.ui.hall.PlanText.Layout;

/**
 * Rechenlogik der Plantexte (Hallenobjekt und Tisch), ohne JavaFX: Textmaße werden als Breite/Höhe je 1 cm Schriftgröße
 * übergeben.
 */
class PlanTextTest {

	private static final double DELTA = 1e-9;

	private static HallObject object(Shape shape, double width, double height) {
		HallObject object = new HallObject();
		object.setName("Bühne");
		object.setShape(shape);
		object.setWidth(width);
		object.setHeight(height);
		return object;
	}

	// --- Auto: Drehung ---

	@Test
	void auto_wideRectangle_isNotRotated() {
		Layout layout = PlanText.layout(object(Shape.RECTANGLE, 300, 100), 5, 1.2);
		assertEquals(0, layout.rotation(), DELTA);
	}

	@Test
	void auto_tallRectangle_readsTopToBottom() {
		Layout layout = PlanText.layout(object(Shape.RECTANGLE, 100, 300), 5, 1.2);
		assertEquals(90, layout.rotation(), DELTA);
	}

	@Test
	void auto_rotationSwitchesAtOneAndAHalfTimesTheWidth() {
		assertEquals(0, PlanText.layout(object(Shape.RECTANGLE, 100, 149), 5, 1.2).rotation(), DELTA);
		assertEquals(90, PlanText.layout(object(Shape.RECTANGLE, 100, 150), 5, 1.2).rotation(), DELTA);
	}

	@Test
	void auto_circle_isNeverRotated() {
		Layout layout = PlanText.layout(object(Shape.CIRCLE, 100, 400), 5, 1.2);
		assertEquals(0, layout.rotation(), DELTA);
	}

	@Test
	void auto_isCenteredBothWays() {
		Layout layout = PlanText.layout(object(Shape.RECTANGLE, 300, 100), 5, 1.2);
		assertEquals(TextAlignH.CENTER, layout.alignH());
		assertEquals(TextAlignV.CENTER, layout.alignV());
	}

	// --- Auto: Größe ---

	@Test
	void auto_size_isLimitedByTheLength() {
		// Länge 200 * 0,9 / 5 = 36, Breite 60 * 0,8 / 1,2 = 40
		Layout layout = PlanText.layout(object(Shape.RECTANGLE, 200, 60), 5, 1.2);
		assertEquals(36, layout.fontSize(), DELTA);
	}

	@Test
	void auto_size_isLimitedByTheThickness() {
		// Länge 200 * 0,9 / 5 = 36, Breite 30 * 0,8 / 1,2 = 20
		Layout layout = PlanText.layout(object(Shape.RECTANGLE, 200, 30), 5, 1.2);
		assertEquals(20, layout.fontSize(), DELTA);
	}

	@Test
	void auto_size_ofRotatedTextUsesTheHeightAsLength() {
		// Gedreht (90 Grad): Länge ist die Höhe 300 * 0,9 / 5 = 54, Dicke ist die Breite 60 * 0,8 / 1,2 = 40
		Layout layout = PlanText.layout(object(Shape.RECTANGLE, 60, 300), 5, 1.2);
		assertEquals(90, layout.rotation(), DELTA);
		assertEquals(40, layout.fontSize(), DELTA);
	}

	@Test
	void auto_size_isCapped() {
		Layout layout = PlanText.layout(object(Shape.RECTANGLE, 5000, 5000), 5, 1.2);
		assertEquals(PlanText.MAX_AUTO_SIZE, layout.fontSize(), DELTA);
	}

	@Test
	void auto_size_ofCircleUsesTheInscribedSquare() {
		// Quadrat mit Seite 100 / wurzel(2) = 70,71; Länge 70,71 * 0,9 / 5, Dicke 70,71 * 0,8 / 1,2 = 47,1
		Layout layout = PlanText.layout(object(Shape.CIRCLE, 100, 100), 5, 1.2);
		assertEquals(100 / Math.sqrt(2) * 0.9 / 5, layout.fontSize(), DELTA);
	}

	@Test
	void auto_size_withoutTextFallsBackToTheDefault() {
		Layout layout = PlanText.layout(object(Shape.RECTANGLE, 200, 60), 0, 0);
		assertEquals(PlanText.DEFAULT_SIZE, layout.fontSize(), DELTA);
	}

	// --- Manuell ---

	@Test
	void manual_takesTheValuesOfTheObject() {
		HallObject object = object(Shape.RECTANGLE, 300, 100);
		object.setTextAuto(false);
		object.setTextSize(25);
		object.setTextRotation(30);
		object.setTextAlignH(TextAlignH.RIGHT);
		object.setTextAlignV(TextAlignV.BOTTOM);

		Layout layout = PlanText.layout(object, 5, 1.2);

		assertEquals(25, layout.fontSize(), DELTA);
		assertEquals(30, layout.rotation(), DELTA);
		assertEquals(TextAlignH.RIGHT, layout.alignH());
		assertEquals(TextAlignV.BOTTOM, layout.alignV());
	}

	@Test
	void manual_rotationIsNormalizedToZeroUpTo360() {
		// Die Rechnung selbst prüft RotationInputTest, hier nur, dass das Layout sie nutzt
		HallObject object = object(Shape.RECTANGLE, 300, 100);
		object.setTextAuto(false);
		object.setTextRotation(-90);
		assertEquals(270, PlanText.layout(object, 5, 1.2).rotation(), DELTA);
		object.setTextRotation(450);
		assertEquals(90, PlanText.layout(object, 5, 1.2).rotation(), DELTA);
	}

	@Test
	void manual_tooSmallSizeIsRaisedToTheMinimum() {
		HallObject object = object(Shape.RECTANGLE, 300, 100);
		object.setTextAuto(false);
		object.setTextSize(0);
		assertEquals(PlanText.MIN_SIZE, PlanText.layout(object, 5, 1.2).fontSize(), DELTA);
	}

	// --- Platz der gedrehten Box ---

	@Test
	void rotatedBox_quarterTurnSwapsWidthAndHeight() {
		double[] box = PlanText.rotatedBox(100, 20, 90);
		assertEquals(20, box[0], 1e-6);
		assertEquals(100, box[1], 1e-6);
	}

	@Test
	void rotatedBox_halfTurnKeepsTheSize() {
		double[] box = PlanText.rotatedBox(100, 20, 180);
		assertEquals(100, box[0], 1e-6);
		assertEquals(20, box[1], 1e-6);
	}

	@Test
	void rotatedBox_eighthTurnIsWiderThanTheText() {
		double[] box = PlanText.rotatedBox(100, 20, 45);
		assertEquals((100 + 20) / Math.sqrt(2), box[0], 1e-6);
		assertEquals((100 + 20) / Math.sqrt(2), box[1], 1e-6);
	}

	// --- Ausrichtung ---

	@Test
	void origin_centered() {
		HallObject object = object(Shape.RECTANGLE, 300, 100);
		object.setPosX(1000);
		object.setPosY(500);
		double[] origin = PlanText.boxOrigin(object, 100, 20, TextAlignH.CENTER, TextAlignV.CENTER);
		assertEquals(1000 + 100, origin[0], DELTA);
		assertEquals(500 + 40, origin[1], DELTA);
	}

	@Test
	void origin_topLeftKeepsThePadding() {
		HallObject object = object(Shape.RECTANGLE, 300, 100);
		object.setPosX(1000);
		object.setPosY(500);
		double[] origin = PlanText.boxOrigin(object, 100, 20, TextAlignH.LEFT, TextAlignV.TOP);
		assertEquals(1000 + PlanText.PADDING, origin[0], DELTA);
		assertEquals(500 + PlanText.PADDING, origin[1], DELTA);
	}

	@Test
	void origin_bottomRightKeepsThePadding() {
		HallObject object = object(Shape.RECTANGLE, 300, 100);
		object.setPosX(1000);
		object.setPosY(500);
		double[] origin = PlanText.boxOrigin(object, 100, 20, TextAlignH.RIGHT, TextAlignV.BOTTOM);
		assertEquals(1000 + 300 - PlanText.PADDING - 100, origin[0], DELTA);
		assertEquals(500 + 100 - PlanText.PADDING - 20, origin[1], DELTA);
	}

	@Test
	void origin_ofCircleLiesInTheInscribedSquare() {
		HallObject object = object(Shape.CIRCLE, 100, 100);
		object.setPosX(0);
		object.setPosY(0);
		double side = 100 / Math.sqrt(2);
		double[] origin = PlanText.boxOrigin(object, 20, 10, TextAlignH.LEFT, TextAlignV.TOP);
		assertEquals(50 - side / 2 + PlanText.PADDING, origin[0], 1e-6);
		assertEquals(50 - side / 2 + PlanText.PADDING, origin[1], 1e-6);
	}

	@Test
	void origin_ofTooBigBoxIsCenteredInsteadOfLeavingTheObject() {
		HallObject object = object(Shape.RECTANGLE, 50, 50);
		object.setPosX(0);
		object.setPosY(0);
		double[] origin = PlanText.boxOrigin(object, 200, 20, TextAlignH.LEFT, TextAlignV.TOP);
		assertTrue(origin[0] < 0, "Ein zu breiter Text ragt auf beiden Seiten gleich weit hinaus");
		assertEquals(25 - 100, origin[0], DELTA);
	}

	// --- Tische (KARV-128): gerechnet im ungedrehten Tisch, die Drehung zählt ab der Tischachse ---

	/** Tisch 50 x 320 mit der linken oberen Ecke (100, 50), Mitte (125, 210). */
	private static Table table(double rotation) {
		Table table = new Table();
		table.setPosX(100);
		table.setPosY(50);
		table.setWidth(50);
		table.setHeight(320);
		table.setRotation(rotation);
		return table;
	}

	private static Layout tableLayout(Table table) {
		return PlanText.layout(PlanText.areaOf(table), table.getTextSettings(), 5, 1.2);
	}

	@Test
	void readable_turnsTextThatWouldBeUpsideDownByHalfATurn() {
		assertEquals(0, PlanText.readable(0), DELTA);
		assertEquals(90, PlanText.readable(90), DELTA);
		assertEquals(-89.9, PlanText.readable(90.1), 1e-6);
		assertEquals(0, PlanText.readable(180), DELTA);
		assertEquals(89.9, PlanText.readable(269.9), 1e-6);
		assertEquals(270, PlanText.readable(270), DELTA);
		assertEquals(270, PlanText.readable(-90), DELTA);
	}

	@Test
	void autoOnALongTable_runsAlongTheLongSide() {
		Layout layout = tableLayout(table(0));
		assertEquals(90, layout.rotation(), DELTA);
		assertEquals(TextAlignH.CENTER, layout.alignH());
		assertEquals(TextAlignV.CENTER, layout.alignV());
	}

	@Test
	void screenRotation_addsTheTableRotationAndIsNeverUpsideDown() {
		assertEquals(90, PlanText.screenRotation(table(0), tableLayout(table(0))), DELTA);
		assertEquals(270, PlanText.screenRotation(table(180), tableLayout(table(180))), DELTA);
		assertEquals(0, PlanText.screenRotation(table(90), tableLayout(table(90))), DELTA);
		assertEquals(0, PlanText.screenRotation(table(270), tableLayout(table(270))), DELTA);
	}

	@Test
	void centeredTableText_staysInTheTableCenterForEveryRotation() {
		Layout centered = new Layout(20, 90, TextAlignH.CENTER, TextAlignV.CENTER);
		for (double rotation : new double[] { 0, 45, 90, 180, 270 }) {
			double[] center = PlanText.tableTextCenter(table(rotation), 30, 100, centered);
			assertEquals(125, center[0], 1e-6, "x bei " + rotation);
			assertEquals(210, center[1], 1e-6, "y bei " + rotation);
		}
	}

	@Test
	void topAlignedTableText_liesAtTheEndOfTheFirstSeatsAndTurnsWithTheTable() {
		Layout top = new Layout(20, 90, TextAlignH.CENTER, TextAlignV.TOP);

		// Ungedreht: Box 30 x 100 oben im Tisch (y ab 50 + Rand 5), Mitte also bei y = 105
		double[] upright = PlanText.tableTextCenter(table(0), 30, 100, top);
		assertEquals(125, upright[0], 1e-6);
		assertEquals(105, upright[1], 1e-6);

		// Um 180 Grad gedreht liegt dasselbe Ende unten: 210 + (210 - 105)
		double[] turned = PlanText.tableTextCenter(table(180), 30, 100, top);
		assertEquals(125, turned[0], 1e-6);
		assertEquals(315, turned[1], 1e-6);
	}

	@Test
	void manualTableText_keepsItsValues() {
		Table table = table(0);
		table.setTextAuto(false);
		table.setTextSize(30);
		table.setTextRotation(0);
		table.setTextAlignH(TextAlignH.LEFT);

		Layout layout = tableLayout(table);

		assertEquals(new Layout(30, 0, TextAlignH.LEFT, TextAlignV.CENTER), layout);
	}
}
