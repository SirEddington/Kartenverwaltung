package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class DateTimeInputTest {

	@Test
	void dateAcceptsWithAndWithoutLeadingZeros() {
		assertEquals(LocalDate.of(2027, 2, 12), DateTimeInput.parseDate("12.02.2027").orElseThrow());
		assertEquals(LocalDate.of(2027, 3, 5), DateTimeInput.parseDate("5.3.2027").orElseThrow());
		assertEquals(LocalDate.of(2027, 3, 5), DateTimeInput.parseDate(" 05.03.2027 ").orElseThrow());
		assertEquals(LocalDate.of(2028, 2, 29), DateTimeInput.parseDate("29.02.2028").orElseThrow());
	}

	@Test
	void dateRejectsInvalidDaysInsteadOfCorrectingThem() {
		// Der frühere Formatter machte aus 31.02.2026 still den 28.02.2026
		for (String text : new String[] { "31.02.2026", "29.02.2027", "32.01.2027", "12.13.2027", "12.02.27", "abc", "", "  ", "2027-02-12" }) {
			assertTrue(DateTimeInput.parseDate(text).isEmpty(), "„" + text + "“ sollte kein Datum sein");
		}
		assertTrue(DateTimeInput.parseDate(null).isEmpty());
	}

	@Test
	void timeAcceptsWithAndWithoutLeadingZero() {
		assertEquals(LocalTime.of(19, 30), DateTimeInput.parseTime("19:30").orElseThrow());
		assertEquals(LocalTime.of(7, 30), DateTimeInput.parseTime("7:30").orElseThrow());
		assertEquals(LocalTime.of(7, 5), DateTimeInput.parseTime(" 07:05 ").orElseThrow());
		assertEquals(LocalTime.of(0, 0), DateTimeInput.parseTime("0:00").orElseThrow());
	}

	@Test
	void timeAcceptsThreeOrFourDigitsWithoutColon() {
		assertEquals(LocalTime.of(15, 0), DateTimeInput.parseTime("1500").orElseThrow());
		assertEquals(LocalTime.of(9, 30), DateTimeInput.parseTime("930").orElseThrow());
		assertEquals(LocalTime.of(9, 30), DateTimeInput.parseTime("0930").orElseThrow());
		assertEquals(LocalTime.of(0, 0), DateTimeInput.parseTime("0000").orElseThrow());
		assertEquals(LocalTime.of(23, 59), DateTimeInput.parseTime(" 2359 ").orElseThrow());
	}

	@Test
	void timeWithoutColonRejectsInvalidValues() {
		for (String text : new String[] { "2500", "1560", "12345", "15", "9", "2400", "-1500" }) {
			assertTrue(DateTimeInput.parseTime(text).isEmpty(), "„" + text + "“ sollte keine Uhrzeit sein");
		}
	}

	@Test
	void timeRejectsInvalidValues() {
		for (String text : new String[] { "25:00", "19.30", "19:60", "abc", "", "  ", "19", "19:30:15" }) {
			assertTrue(DateTimeInput.parseTime(text).isEmpty(), "„" + text + "“ sollte keine Uhrzeit sein");
		}
		assertTrue(DateTimeInput.parseTime(null).isEmpty());
	}

	@Test
	void formattingKeepsTheFixedDisplayPatterns() {
		assertEquals("12.02.2027", DateTimeInput.formatDate(LocalDate.of(2027, 2, 12)));
		assertEquals("07:05", DateTimeInput.formatTime(LocalTime.of(7, 5)));
	}
}
