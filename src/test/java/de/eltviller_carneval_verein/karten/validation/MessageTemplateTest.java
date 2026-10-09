package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.SortedSet;

import org.junit.jupiter.api.Test;

class MessageTemplateTest {

	@Test
	void replacesPlaceholdersByPosition() {
		assertEquals("Sitz 2 am Tisch 4", MessageTemplate.format("Sitz &1 am Tisch &2", 2, 4));
	}

	@Test
	void placeholderMayAppearMoreThanOnceAndInAnyOrder() {
		assertEquals("b a b", MessageTemplate.format("&2 &1 &2", "a", "b"));
	}

	@Test
	void missingParameterStaysVisibleAndDoesNotThrow() {
		assertEquals("A und &2", MessageTemplate.format("&1 und &2", "A"));
		assertEquals("&1", MessageTemplate.format("&1"));
	}

	@Test
	void surplusParametersAreIgnored() {
		assertEquals("A", MessageTemplate.format("&1", "A", "B"));
	}

	@Test
	void doubleAmpersandIsLiteralAmpersand() {
		assertEquals("Soll & Haben", MessageTemplate.format("Soll && Haben"));
		assertEquals("&1 bleibt", MessageTemplate.format("&&1 bleibt", "x"));
	}

	@Test
	void ampersandWithoutDigitIsKept() {
		assertEquals("A & B &", MessageTemplate.format("A & B &"));
		assertEquals("&0 &x", MessageTemplate.format("&0 &x"));
	}

	@Test
	void parametersAreConvertedToText() {
		assertEquals("12 true null", MessageTemplate.format("&1 &2 &3", 12, true, null));
	}

	@Test
	void valueContainingAmpersandIsNotReinterpreted() {
		assertEquals("a&2 b", MessageTemplate.format("&1 &2", "a&2", "b"));
	}

	@Test
	void placeholdersListsUsedNumbers() {
		SortedSet<Integer> numbers = MessageTemplate.placeholders("&2 und &1 und &2 und && und &x");

		assertEquals(List.of(1, 2), List.copyOf(numbers));
	}
}
