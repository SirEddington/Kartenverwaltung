package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class OperatorNamesTest {

	@Test
	void newNameGoesFirst() {
		assertEquals(List.of("Clara", "Anna", "Bernd"), OperatorNames.remember(List.of("Anna", "Bernd"), "Clara"));
	}

	@Test
	void duplicateIgnoringCaseIsReplacedByNewSpelling() {
		assertEquals(List.of("Anna", "Bernd"), OperatorNames.remember(List.of("anna", "Bernd"), "Anna"));
	}

	@Test
	void duplicateFurtherDownMovesToTheFront() {
		assertEquals(List.of("Bernd", "Anna", "Clara"), OperatorNames.remember(List.of("Anna", "Bernd", "Clara"), "Bernd"));
	}

	@Test
	void listIsCappedAtEight() {
		List<String> recent = List.of("n1", "n2", "n3", "n4", "n5", "n6", "n7", "n8");

		List<String> result = OperatorNames.remember(recent, "neu");

		assertEquals(List.of("neu", "n1", "n2", "n3", "n4", "n5", "n6", "n7"), result);
	}

	@Test
	void rememberedNameIsTrimmed() {
		assertEquals(List.of("Anna", "Bernd"), OperatorNames.remember(List.of("Bernd"), "  Anna \t"));
	}

	@Test
	void blankNameLeavesTheListUnchanged() {
		assertEquals(List.of("Anna"), OperatorNames.remember(List.of("Anna"), "   "));
		assertEquals(List.of("Anna"), OperatorNames.remember(List.of("Anna"), null));
	}

	@Test
	void nullListIsTreatedAsEmpty() {
		assertEquals(List.of("Anna"), OperatorNames.remember(null, "Anna"));
	}

	@Test
	void givenListIsNotChanged() {
		List<String> recent = new ArrayList<>(List.of("Anna"));

		OperatorNames.remember(recent, "Bernd");

		assertEquals(List.of("Anna"), recent);
	}

	@Test
	void resultCanBeChangedByTheCaller() {
		List<String> result = OperatorNames.remember(List.of(), "Anna");

		result.add("Bernd");

		assertEquals(List.of("Anna", "Bernd"), result);
	}

	@Test
	void whitespaceIsTrimmedAndBlankIsInvalid() {
		assertEquals(Optional.of("Anna"), OperatorNames.valid("  Anna  "));
		assertEquals(Optional.of("Anna Maria"), OperatorNames.valid("Anna Maria"));
		assertTrue(OperatorNames.valid("").isEmpty());
		assertTrue(OperatorNames.valid("   \t ").isEmpty());
		assertTrue(OperatorNames.valid(null).isEmpty());
	}
}
