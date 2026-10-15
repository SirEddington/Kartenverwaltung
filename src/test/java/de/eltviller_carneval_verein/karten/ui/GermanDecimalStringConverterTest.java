package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GermanDecimalStringConverterTest {

	private final GermanDecimalStringConverter converter = new GermanDecimalStringConverter();

	@Test
	void parsesCommaAsDecimalSeparator() {
		assertEquals(12.5, converter.fromString("12,50"));
	}

	@Test
	void parsesDotAsDecimalSeparator() {
		assertEquals(12.5, converter.fromString("12.50"));
	}

	@Test
	void parsesDotAsThousandsSeparatorBeforeAComma() {
		assertEquals(1234.5, converter.fromString("1.234,50"));
	}

	@Test
	void unreadableTextThrowsNumberFormatException() {
		assertThrows(NumberFormatException.class, () -> converter.fromString("abc"));
		assertThrows(NumberFormatException.class, () -> converter.fromString("1e9"));
	}

	@Test
	void blankOrNullInputParsesAsZero() {
		assertEquals(0.0, converter.fromString(""));
		assertEquals(0.0, converter.fromString(null));
	}

	@Test
	void formatsWithGermanComma() {
		assertEquals("12,50", converter.toString(12.5));
	}
}
