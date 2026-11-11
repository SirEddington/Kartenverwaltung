package de.eltviller_carneval_verein.karten.validation;

import static de.eltviller_carneval_verein.karten.validation.Footprint.circle;
import static de.eltviller_carneval_verein.karten.validation.Footprint.rect;
import static de.eltviller_carneval_verein.karten.validation.Footprint.union;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Geometrie der Überlappungs- und Lageprüfungen: gedrehte Rechtecke und Kreise mit der Toleranz von 1 cm. Die Werte
 * stehen in der Spec (Abschnitt "Tests (TDD)"); die Zahlen in den Kommentaren sind dort nachgerechnet.
 */
class FootprintTest {

	/** Halle 1000 × 800 für die Prüfungen mit {@code insideHall}. */
	private static final double HALL_WIDTH = 1000;
	private static final double HALL_HEIGHT = 800;

	private static Table table(double posX, double posY, double width, double height, double rotation) {
		Table table = new Table();
		table.setPosX(posX);
		table.setPosY(posY);
		table.setWidth(width);
		table.setHeight(height);
		table.setRotation(rotation);
		return table;
	}

	private static Seat seat(Table table, double posX, double posY, double width) {
		Seat seat = table.addSeat();
		seat.setWidth(width);
		seat.setPosX(posX);
		seat.setPosY(posY);
		return seat;
	}

	private static Footprint of(Table table) {
		return Footprint.of(table).orElseThrow();
	}

	// --- Kanten (Rechtecke 100 × 50 nebeneinander in x) ---

	@Test
	void edgeTouchingDoesNotOverlap() {
		assertFalse(rect(50, 25, 100, 50, 0).overlaps(rect(150, 25, 100, 50, 0)));
	}

	@Test
	void edgeOverlapBelowToleranceIsIgnored() {
		assertFalse(rect(50, 25, 100, 50, 0).overlaps(rect(149.1, 25, 100, 50, 0)));
	}

	@Test
	void edgeOverlapAboveToleranceCounts() {
		assertTrue(rect(50, 25, 100, 50, 0).overlaps(rect(148.9, 25, 100, 50, 0)));
	}

	@Test
	void containedRectOverlaps() {
		assertTrue(rect(50, 25, 100, 50, 0).overlaps(rect(50, 25, 10, 10, 0)));
	}

	// --- Toleranz im genauen Test (der Vorfilter lässt das Paar durch) ---

	/** Kante an Kante 0,9 cm Überlappung (100 − 70,07 · √2). */
	@Test
	void diagonalSquaresJustApart() {
		Footprint a = rect(0, 0, 100, 100, 45);
		Footprint b = rect(70.07, 70.07, 100, 100, 45);
		assertTrue(a.boundsOverlap(b));
		assertFalse(a.overlaps(b));
	}

	/** Kante an Kante 1,1 cm Überlappung. */
	@Test
	void diagonalSquaresJustOverlapping() {
		Footprint a = rect(0, 0, 100, 100, 45);
		Footprint b = rect(69.93, 69.93, 100, 100, 45);
		assertTrue(a.boundsOverlap(b));
		assertTrue(a.overlaps(b));
	}

	/** Abstand 19,1 bei Radiensumme 20. */
	@Test
	void diagonalCirclesJustApart() {
		Footprint a = circle(0, 0, 10);
		Footprint b = circle(13.51, 13.51, 10);
		assertTrue(a.boundsOverlap(b));
		assertFalse(a.overlaps(b));
	}

	/** Abstand 18,9 bei Radiensumme 20. */
	@Test
	void diagonalCirclesJustOverlapping() {
		Footprint a = circle(0, 0, 10);
		Footprint b = circle(13.36, 13.36, 10);
		assertTrue(a.boundsOverlap(b));
		assertTrue(a.overlaps(b));
	}

	// --- Drehrichtung (Tisch 50 × 320 bei (0 | 0), keine Stühle) ---

	/** Im Uhrzeigersinn liegt die oberste Ecke bei (83,35 | 8,94), der Kreis knapp darunter in der Platte. */
	@Test
	void rotationIsClockwise() {
		assertTrue(of(table(0, 0, 50, 320, 30)).overlaps(circle(83.35, 14, 5)));
	}

	/** Bei −30° liegt die oberste Ecke gespiegelt bei (−33,35 | 8,94). */
	@Test
	void rotationMinus30IsMirrored() {
		assertFalse(of(table(0, 0, 50, 320, -30)).overlaps(circle(83.35, 14, 5)));
	}

	// --- Hüllen überschneiden sich, Flächen nicht ---

	/** Hüllen je 31,4 cm übereinander, Flächen 55,6 cm auseinander. */
	@Test
	void rotatedSquaresWithOverlappingBounds() {
		Footprint a = rect(0, 0, 100, 100, 45);
		Footprint b = rect(110, 110, 100, 100, 45);
		assertTrue(a.boundsOverlap(b));
		assertFalse(a.overlaps(b));
	}

	@Test
	void rotatedSquaresOverlapping() {
		assertTrue(rect(0, 0, 100, 100, 45).overlaps(rect(60, 60, 100, 100, 45)));
	}

	/** Hüllen je 2 cm übereinander, Abstand zur Ecke 11,3. */
	@Test
	void circleBeforeCornerDoesNotOverlap() {
		Footprint square = rect(50, 50, 100, 100, 0);
		Footprint c = circle(-8, -8, 10);
		assertTrue(square.boundsOverlap(c));
		assertFalse(square.overlaps(c));
	}

	/** Abstand zur Ecke 4,2. */
	@Test
	void circleOnCornerOverlaps() {
		assertTrue(rect(50, 50, 100, 100, 0).overlaps(circle(-3, -3, 10)));
	}

	@Test
	void circlesOnAxisTouching() {
		assertFalse(circle(0, 0, 10).overlaps(circle(20, 0, 10)));
	}

	@Test
	void circlesOnAxisOverlapping() {
		assertTrue(circle(0, 0, 10).overlaps(circle(18.9, 0, 10)));
	}

	// --- Vorfilter direkt ---

	@Test
	void boundsOverlapPassesEveryRealOverlap() {
		assertTrue(of(table(0, 0, 50, 320, 30)).boundsOverlap(circle(83.35, 14, 5)));
		assertTrue(rect(50, 50, 100, 100, 0).boundsOverlap(circle(-3, -3, 10)));
		assertTrue(rect(50, 25, 100, 50, 0).boundsOverlap(rect(148.9, 25, 100, 50, 0)));
		assertTrue(circle(0, 0, 10).boundsOverlap(circle(18.9, 0, 10)));
	}

	@Test
	void boundsOverlapRejectsSmallOverlap() {
		assertFalse(rect(50, 25, 100, 50, 0).boundsOverlap(rect(149.1, 25, 100, 50, 0)));
	}

	// --- Kleine Teile ---

	/** Hüllen 10,4 cm übereinander; der Kreis mit r 0,4 nimmt nicht teil (ohne die Ausschlussregel: ja). */
	@Test
	void tinyPartTakesNoPartInOverlaps() {
		Footprint withTiny = union(rect(25, 160, 50, 320, 0), circle(500, 500, 0.4));
		Footprint other = circle(500, 500, 10);
		assertTrue(withTiny.boundsOverlap(other));
		assertFalse(withTiny.overlaps(other));
	}

	// --- within ---

	@Test
	void smallRectWithinLarge() {
		assertTrue(rect(50, 50, 10, 10, 0).within(rect(50, 50, 100, 100, 0)));
	}

	@Test
	void withinToleratesUpToOneCentimetre() {
		Footprint area = rect(50, 50, 100, 100, 0);
		assertTrue(rect(4.1, 50, 10, 10, 0).within(area), "0,9 cm über dem Rand");
		assertFalse(rect(3.9, 50, 10, 10, 0).within(area), "1,1 cm über dem Rand");
	}

	@Test
	void circleWithinCircle() {
		assertTrue(circle(0, 0, 5).within(circle(0, 0, 50)));
	}

	/** Ecke bei 42,4 > 41. */
	@Test
	void squareCornerOutsideCircle() {
		assertFalse(rect(0, 0, 60, 60, 0).within(circle(0, 0, 40)));
	}

	@Test
	void withinRotatedContainer() {
		assertTrue(circle(0, 60, 5).within(rect(0, 0, 100, 100, 45)), "lokal 42,4 + 5 ≤ 51");
		assertFalse(circle(0, 60, 5).within(rect(0, 0, 100, 100, 0)), "60 + 5 > 51");
		assertFalse(circle(0, 68, 5).within(rect(0, 0, 100, 100, 45)), "lokal 48,1 + 5 > 51");
	}

	@Test
	void tableWithSeatsOnAreaIsWithin() {
		Footprint area = rect(125, 150, 200, 200, 0);
		Table table = table(100, 100, 50, 100, 0);
		Seat first = seat(table, 85, 150, 20);
		seat(table, 165, 150, 20);
		assertTrue(of(table).within(area));

		first.setPosX(30);
		assertFalse(of(table).within(area));
	}

	// --- insideHall (1000 × 800) ---

	@Test
	void insideHallAtEdge() {
		assertTrue(rect(50, 400, 100, 100, 0).insideHall(HALL_WIDTH, HALL_HEIGHT));
	}

	@Test
	void insideHallToleratesOneCentimetre() {
		assertTrue(rect(49.1, 400, 100, 100, 0).insideHall(HALL_WIDTH, HALL_HEIGHT), "Kante bei x −0,9");
		assertFalse(rect(48.9, 400, 100, 100, 0).insideHall(HALL_WIDTH, HALL_HEIGHT), "Kante bei x −1,1");
	}

	/** Die linke Ecke liegt bei x −76,65. */
	@Test
	void rotatedTableCornerOutside() {
		assertFalse(of(table(0, 0, 50, 320, 30)).insideHall(HALL_WIDTH, HALL_HEIGHT));
	}

	@Test
	void seatOutsideWhilePlateInside() {
		Table table = table(100, 100, 50, 100, 0);
		seat(table, -15, 150, 20);
		assertFalse(of(table).insideHall(HALL_WIDTH, HALL_HEIGHT));
	}

	// --- Fabriken ---

	@Test
	void tableWithoutSizeHasOnlySeats() {
		Table table = table(0, 0, 0, 0, 0);
		seat(table, 100, 100, 20);
		seat(table, 130, 100, 20);
		seat(table, 160, 100, 20);
		assertEquals(3, of(table).partCount());
	}

	/** Ohne Breite rechnet der Stuhl mit Radius 10. */
	@Test
	void seatWithoutWidthUsesFallback() {
		Table table = table(0, 0, 0, 0, 0);
		Seat seat = seat(table, 10.5, 400, 0);
		assertTrue(of(table).insideHall(HALL_WIDTH, HALL_HEIGHT));

		seat.setPosX(8.9);
		assertFalse(of(table).insideHall(HALL_WIDTH, HALL_HEIGHT));
	}

	/** Ohne das Weglassen machte der NaN-Vergleich {@code insideHall} falsch. */
	@Test
	void seatWithNaNPositionIsDropped() {
		Table table = table(100, 100, 50, 100, 0);
		seat(table, 85, 150, 20);
		seat(table, Double.NaN, 150, 20);
		Footprint footprint = of(table);
		assertEquals(2, footprint.partCount());
		assertTrue(footprint.insideHall(HALL_WIDTH, HALL_HEIGHT));
	}

	@Test
	void circleObjectIgnoresHeight() {
		HallObject object = new HallObject();
		object.setShape(Shape.CIRCLE);
		object.setWidth(100);
		object.setHeight(5000);
		assertTrue(Footprint.of(object).orElseThrow().insideHall(HALL_WIDTH, HALL_HEIGHT));
	}

	@Test
	void objectWithoutWidthHasNoFootprint() {
		HallObject object = new HallObject();
		object.setWidth(0);
		object.setHeight(50);
		assertTrue(Footprint.of(object).isEmpty());
	}
}
