package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ParsingConverterTest {

	private static ParsingConverter<Integer> integerConverter() {
		return new ParsingConverter<>(InputConverters.integer(), InputIssue.NOT_A_NUMBER, () -> 5);
	}

	@Test
	void unreadableTextGivesTheFallbackAndRemembersTheFailure() {
		ParsingConverter<Integer> converter = integerConverter();

		assertEquals(5, converter.fromString("abc"));

		ParsingConverter.Failure failure = converter.takeFailure().orElseThrow();
		assertEquals(InputIssue.NOT_A_NUMBER, failure.issue());
		assertEquals("abc", failure.text());
	}

	@Test
	void takingTheFailureClearsIt() {
		ParsingConverter<Integer> converter = integerConverter();
		converter.fromString("abc");
		converter.takeFailure();

		assertTrue(converter.takeFailure().isEmpty());
	}

	@Test
	void readableTextGivesTheValueAndNoFailure() {
		ParsingConverter<Integer> converter = integerConverter();

		assertEquals(7, converter.fromString("7"));
		assertTrue(converter.takeFailure().isEmpty());
	}

	@Test
	void aSuccessfulReadDropsAnUntakenFailure() {
		ParsingConverter<Integer> converter = integerConverter();
		converter.fromString("abc");

		converter.fromString("7");

		assertTrue(converter.takeFailure().isEmpty(), "Ein veralteter Fehler darf nicht an der nächsten, gültigen Eingabe hängen");
	}

	@Test
	void dateExceptionIsCaughtToo() {
		ParsingConverter<LocalDate> converter = new ParsingConverter<>(InputConverters.date(), InputIssue.NOT_A_DATE, () -> null);

		assertNull(converter.fromString("31.02.2026"));

		ParsingConverter.Failure failure = converter.takeFailure().orElseThrow();
		assertEquals(InputIssue.NOT_A_DATE, failure.issue());
		assertEquals("31.02.2026", failure.text());
	}

	@Test
	void blankTextKeepsItsRawFormInTheFailure() {
		ParsingConverter<Integer> converter = integerConverter();

		converter.fromString("");

		assertEquals("", converter.takeFailure().orElseThrow().text());
	}

	@Test
	void toStringIsDelegated() {
		assertEquals("42", integerConverter().toString(42));
	}
}
