package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContextMenuGuardTest {

	// Menü bei x 100 bis 300, y 200 bis 260

	@Test
	void insideTheMenuTheDistanceIsZero() {
		assertEquals(0, ContextMenuGuard.distance(150, 230, 100, 200, 200, 60), 1e-9);
		assertEquals(0, ContextMenuGuard.distance(100, 200, 100, 200, 200, 60), 1e-9, "Rand zählt dazu");
	}

	@Test
	void besideTheMenuTheDistanceIsTheGapToTheNearestEdge() {
		assertEquals(30, ContextMenuGuard.distance(70, 230, 100, 200, 200, 60), 1e-9);
		assertEquals(50, ContextMenuGuard.distance(350, 230, 100, 200, 200, 60), 1e-9);
		assertEquals(40, ContextMenuGuard.distance(150, 160, 100, 200, 200, 60), 1e-9);
		assertEquals(25, ContextMenuGuard.distance(150, 285, 100, 200, 200, 60), 1e-9);
	}

	@Test
	void diagonallyTheDistanceIsMeasuredToTheNearestCorner() {
		assertEquals(5, ContextMenuGuard.distance(97, 196, 100, 200, 200, 60), 1e-9);
		assertEquals(5, ContextMenuGuard.distance(303, 264, 100, 200, 200, 60), 1e-9);
	}

	@Test
	void theMenuClosesBeyondTheLimitOnly() {
		assertFalse(ContextMenuGuard.isTooFar(ContextMenuGuard.MAX_DISTANCE));
		assertTrue(ContextMenuGuard.isTooFar(ContextMenuGuard.MAX_DISTANCE + 1));
		assertFalse(ContextMenuGuard.isTooFar(0));
	}
}
