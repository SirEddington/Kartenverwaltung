package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class NumberInputTest {

	private static void assertDecimal(double expected, String text) {
		OptionalDouble result = NumberInput.parseDecimal(text);
		assertTrue(result.isPresent(), "„" + text + "“ sollte lesbar sein");
		assertEquals(expected, result.getAsDouble(), 1e-9, text);
	}

	private static void assertUnreadableDecimal(String text) {
		assertTrue(NumberInput.parseDecimal(text).isEmpty(), "„" + text + "“ sollte nicht lesbar sein");
	}

	@Test
	void decimalAcceptsCommaAndDotAndSpacesAtTheEdges() {
		assertDecimal(12.5, "12,5");
		assertDecimal(12.5, "12.5");
		assertDecimal(7.0, " 7 ");
		assertDecimal(-5.0, "-5");
		assertDecimal(0.5, "0,5");
	}

	@Test
	void decimalTreatsDotBeforeCommaAsThousandsSeparator() {
		assertDecimal(1234.5, "1.234,5");
		assertDecimal(1234567.0, "1.234.567,0");
	}

	@Test
	void decimalRejectsUnreadableText() {
		assertUnreadableDecimal("");
		assertUnreadableDecimal("   ");
		assertUnreadableDecimal(null);
		assertUnreadableDecimal("abc");
		assertUnreadableDecimal("1e9");
		assertUnreadableDecimal("NaN");
		assertUnreadableDecimal("Infinity");
		assertUnreadableDecimal("12.5.3");
		assertUnreadableDecimal("1,2,3");
		assertUnreadableDecimal("1,234.5");
		assertUnreadableDecimal("1.234.567");
		assertUnreadableDecimal("12,");
		assertUnreadableDecimal("--5");
	}

	@Test
	void integerAcceptsPlainDigitsAndSpacesAtTheEdges() {
		assertEquals(42, NumberInput.parseInteger("42").getAsInt());
		assertEquals(7, NumberInput.parseInteger(" 7 ").getAsInt());
		assertEquals(-3, NumberInput.parseInteger("-3").getAsInt());
	}

	@Test
	void integerRejectsEverythingElse() {
		for (String text : new String[] { "3,5", "abc", "", "  ", "1.000", "2147483648", "+" }) {
			OptionalInt result = NumberInput.parseInteger(text);
			assertTrue(result.isEmpty(), "„" + text + "“ sollte nicht lesbar sein");
		}
		assertTrue(NumberInput.parseInteger(null).isEmpty());
	}

	@Test
	void formatUsesGermanNotationWithoutSuperfluousDecimals() {
		assertEquals("1000", NumberInput.format(1000.0));
		assertEquals("0,5", NumberInput.format(0.5));
		assertEquals("12,25", NumberInput.format(12.25));
		assertEquals("-5", NumberInput.format(-5.0));
	}
}
