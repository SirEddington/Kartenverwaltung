package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.ui.hall.RowFillDialog.Fill;
import de.eltviller_carneval_verein.karten.ui.hall.RowFillDialog.Sizes;

/** Vorbelegung und Prüfung des Dialogs "Reihe füllen" (KARV-128), ohne JavaFX. */
class RowFillDialogTest {

	private static SeatingPlan plan(double tableWidth, double tableHeight, double seatWidth) {
		SeatingPlan plan = new Hall().addSeatingPlan();
		plan.setDefaultTableWidth(tableWidth);
		plan.setDefaultTableHeight(tableHeight);
		plan.setDefaultSeatWidth(seatWidth);
		return plan;
	}

	@Test
	void sizesOfAPlanWithoutStandardSizes_areTheNewStandardSizes() {
		assertEquals(new Sizes(50, 320, 45), Sizes.of(plan(0, 0, 0)));
	}

	@Test
	void sizesOfAPlan_areItsRoundedStandardSizes() {
		assertEquals(new Sizes(60, 300, 40), Sizes.of(plan(60.4, 300, 40)));
		assertEquals(new Sizes(61, 320, 45), Sizes.of(plan(60.5, -1, 0)));
	}

	@Test
	void validFill_isAccepted() {
		assertNull(RowFillDialog.rejection(new Fill(6, 12, 50, 320, 45)));
		assertNull(RowFillDialog.rejection(new Fill(200, 100, 1000, 1, 100)));
	}

	@Test
	void fillOutsideTheLimits_isRejectedWithAReason() {
		assertNotNull(RowFillDialog.rejection(new Fill(201, 12, 50, 320, 45)));
		assertNotNull(RowFillDialog.rejection(new Fill(6, 101, 50, 320, 45)));
		assertNotNull(RowFillDialog.rejection(new Fill(6, 12, 0, 320, 45)));
		assertNotNull(RowFillDialog.rejection(new Fill(6, 12, 50, 1001, 45)));
		assertNotNull(RowFillDialog.rejection(new Fill(6, 12, 50, 320, 101)));
		assertNotNull(RowFillDialog.rejection(new Fill(6, 12, 50, 320, 0)));
	}
}
