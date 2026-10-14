package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SeatSelectionEditorTest {

	@Test
	void menuTextForASingleSeatNamesNoCount() {
		assertEquals("Sitz verkaufen…", SeatSelectionEditor.menuText(1));
	}

	@Test
	void menuTextForSeveralSeatsShowsTheCount() {
		assertEquals("Ausgewählte Sitze verkaufen… (6)", SeatSelectionEditor.menuText(6));
	}
}
