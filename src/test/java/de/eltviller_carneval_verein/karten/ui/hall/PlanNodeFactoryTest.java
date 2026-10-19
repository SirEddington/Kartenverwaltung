package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlanNodeFactoryTest {

	@Test
	void seatNumberAppearsOnlyWhenTheSeatIsBigEnoughOnScreen() {
		// Ein Sitz von 40 cm: ab Zoom 0,35 sind es 14 Pixel
		assertFalse(PlanNodeFactory.isSeatNumberVisible(40, 0.2));
		assertTrue(PlanNodeFactory.isSeatNumberVisible(40, 0.35));
		assertTrue(PlanNodeFactory.isSeatNumberVisible(40, 1.0));
	}

	@Test
	void smallSeatsNeedMoreZoomForTheirNumber() {
		assertFalse(PlanNodeFactory.isSeatNumberVisible(20, 0.5));
		assertTrue(PlanNodeFactory.isSeatNumberVisible(20, 0.7));
	}

	@Test
	void haloIsAtLeastAMinimumWideAndGrowsWithBigSeats() {
		assertEquals(4, PlanNodeFactory.haloWidth(5), 1e-9, "Bei kleinen Sitzen bleibt das Halo sichtbar");
		assertEquals(7, PlanNodeFactory.haloWidth(20), 1e-9, "35 Prozent des Radius");
	}
}
