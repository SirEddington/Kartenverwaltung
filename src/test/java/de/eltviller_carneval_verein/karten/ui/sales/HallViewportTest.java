package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.ui.sales.HallViewport.View;
import javafx.geometry.Rectangle2D;

class HallViewportTest {

	private static final double EPS = 1e-9;

	private static void assertRect(Rectangle2D expected, Rectangle2D actual) {
		assertEquals(expected.getMinX(), actual.getMinX(), EPS, "minX");
		assertEquals(expected.getMinY(), actual.getMinY(), EPS, "minY");
		assertEquals(expected.getWidth(), actual.getWidth(), EPS, "width");
		assertEquals(expected.getHeight(), actual.getHeight(), EPS, "height");
	}

	@Test
	void fitCentersTargetAndScalesToTheTighterSide() {
		View view = HallViewport.fit(500, 500, new Rectangle2D(0, 0, 1000, 500), 1.0);

		assertEquals(0.5, view.scale(), EPS);
		assertEquals(-125, view.translateX(), EPS);
		assertEquals(0, view.translateY(), EPS);
	}

	@Test
	void fitAppliesMarginToTheScale() {
		View view = HallViewport.fit(500, 500, new Rectangle2D(0, 0, 1000, 500), 0.95);

		assertEquals(0.475, view.scale(), EPS);
	}

	@Test
	void fitWithEmptyViewOrTargetIsIdentity() {
		View identity = new View(1, 0, 0);

		assertEquals(identity, HallViewport.fit(0, 300, new Rectangle2D(0, 0, 100, 100), 1.0));
		assertEquals(identity, HallViewport.fit(300, 0, new Rectangle2D(0, 0, 100, 100), 1.0));
		assertEquals(identity, HallViewport.fit(300, 300, new Rectangle2D(0, 0, 0, 100), 1.0));
		assertEquals(identity, HallViewport.fit(300, 300, new Rectangle2D(0, 0, 100, 0), 1.0));
	}

	@Test
	void visibleRectOfIdentityIsTheViewItself() {
		assertRect(new Rectangle2D(0, 0, 800, 600), HallViewport.visibleRect(800, 600, new View(1, 0, 0)));
	}

	@Test
	void visibleRectFollowsScaleAndTranslation() {
		assertRect(new Rectangle2D(150, 175, 400, 300), HallViewport.visibleRect(800, 600, new View(2, 100, -50)));
	}

	@Test
	void visibleRectOfFitEqualsTargetWhenAspectRatiosMatch() {
		Rectangle2D target = new Rectangle2D(100, 50, 2000, 1000);

		View view = HallViewport.fit(1000, 500, target, 1.0);

		assertRect(target, HallViewport.visibleRect(1000, 500, view));
	}
}
