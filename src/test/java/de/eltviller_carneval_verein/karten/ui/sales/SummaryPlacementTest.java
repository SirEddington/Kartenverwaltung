package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import javafx.geometry.Side;

class SummaryPlacementTest {

	@Test
	void automaticGoesBelowThePlanOnlyInPortrait() {
		assertEquals(Side.BOTTOM, SummaryPlacement.AUTO.sideFor(1080, 1920));
		assertEquals(Side.RIGHT, SummaryPlacement.AUTO.sideFor(1920, 1080));
		assertEquals(Side.RIGHT, SummaryPlacement.AUTO.sideFor(1000, 1000));
	}

	@Test
	void fixedPlacementIgnoresTheWindowShape() {
		for (double[] size : new double[][] { { 1080, 1920 }, { 1920, 1080 }, { 1000, 1000 } }) {
			assertEquals(Side.TOP, SummaryPlacement.TOP.sideFor(size[0], size[1]));
			assertEquals(Side.BOTTOM, SummaryPlacement.BOTTOM.sideFor(size[0], size[1]));
			assertEquals(Side.LEFT, SummaryPlacement.LEFT.sideFor(size[0], size[1]));
			assertEquals(Side.RIGHT, SummaryPlacement.RIGHT.sideFor(size[0], size[1]));
		}
	}

	@Test
	void menuLabelsAreGerman() {
		assertEquals("Automatisch", SummaryPlacement.AUTO.label());
		assertEquals("Oben", SummaryPlacement.TOP.label());
		assertEquals("Unten", SummaryPlacement.BOTTOM.label());
		assertEquals("Links", SummaryPlacement.LEFT.label());
		assertEquals("Rechts", SummaryPlacement.RIGHT.label());
	}
}
