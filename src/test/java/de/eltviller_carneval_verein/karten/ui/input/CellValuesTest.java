package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class CellValuesTest {

	@Test
	void sameTextIsUnchanged() {
		assertTrue(CellValues.isUnchanged("Müller", "Müller"));
	}

	@Test
	void aMissingTextAndAnEmptyOneAreTheSame() {
		assertTrue(CellValues.isUnchanged(null, ""), "Ein leeres Feld bestätigen darf null nicht zu \"\" machen");
		assertTrue(CellValues.isUnchanged("", null));
		assertTrue(CellValues.isUnchanged(null, null));
	}

	@Test
	void anotherTextIsAChange() {
		assertFalse(CellValues.isUnchanged("Müller", "Meier"));
		assertFalse(CellValues.isUnchanged(null, "Meier"));
		assertFalse(CellValues.isUnchanged("Meier", ""));
	}

	@Test
	void anEmptyCellTakesTheReplacementAfterAFailedInput() {
		assertEquals(Optional.of("heute"), CellValues.replacementAfterFailure(null, () -> "heute"));
	}

	@Test
	void aFilledCellKeepsItsValueAfterAFailedInput() {
		assertEquals(Optional.empty(), CellValues.replacementAfterFailure("alt", () -> "heute"));
	}

	@Test
	void withoutAReplacementNothingIsTakenAfterAFailedInput() {
		assertEquals(Optional.empty(), CellValues.replacementAfterFailure(null, null));
	}

	@Test
	void numbersAreComparedByValue() {
		assertTrue(CellValues.isUnchanged(17.0, 17.0));
		assertFalse(CellValues.isUnchanged(17.0, 18.0));
	}
}
