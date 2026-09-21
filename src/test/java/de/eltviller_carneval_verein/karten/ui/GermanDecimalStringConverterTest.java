package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
	void blankOrNullInputParsesAsZero() {
		assertEquals(0.0, converter.fromString(""));
		assertEquals(0.0, converter.fromString(null));
	}

	@Test
	void formatsWithGermanComma() {
		assertEquals("12,50", converter.toString(12.5));
	}
}
