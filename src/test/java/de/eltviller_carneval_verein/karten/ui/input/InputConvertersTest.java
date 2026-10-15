package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.Test;

class InputConvertersTest {

	@Test
	void dateConverterReadsAndFormats() {
		assertEquals(LocalDate.of(2027, 2, 12), InputConverters.date().fromString("12.02.2027"));
		assertEquals("12.02.2027", InputConverters.date().toString(LocalDate.of(2027, 2, 12)));
		assertEquals("", InputConverters.date().toString(null));
	}

	@Test
	void blankDateMeansNoDate() {
		assertNull(InputConverters.date().fromString(""));
		assertNull(InputConverters.date().fromString("   "));
		assertNull(InputConverters.date().fromString(null));
	}

	@Test
	void invalidDateThrows() {
		assertThrows(DateTimeParseException.class, () -> InputConverters.date().fromString("31.02.2026"));
		assertThrows(DateTimeParseException.class, () -> InputConverters.date().fromString("abc"));
	}

	@Test
	void timeConverterReadsFormatsAndAllowsBlank() {
		assertEquals(LocalTime.of(19, 30), InputConverters.time().fromString("19:30"));
		assertEquals("07:05", InputConverters.time().toString(LocalTime.of(7, 5)));
		assertEquals("", InputConverters.time().toString(null));
		assertNull(InputConverters.time().fromString(""));
		assertThrows(DateTimeParseException.class, () -> InputConverters.time().fromString("25:00"));
	}

	@Test
	void integerConverterReadsPlainNumbers() {
		assertEquals(42, InputConverters.integer().fromString("42"));
		assertEquals("42", InputConverters.integer().toString(42));
		assertEquals("", InputConverters.integer().toString(null));
	}

	@Test
	void integerConverterRejectsBlankAndNonIntegers() {
		// Leer darf nicht zu null werden: Ein Tisch- oder Sitznummer-Feld braucht eine Zahl
		assertThrows(NumberFormatException.class, () -> InputConverters.integer().fromString(""));
		assertThrows(NumberFormatException.class, () -> InputConverters.integer().fromString("3,5"));
		assertThrows(NumberFormatException.class, () -> InputConverters.integer().fromString("abc"));
	}
}
