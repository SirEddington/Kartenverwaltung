package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class SeatValidatorTest {

	@Test
	void acceptsRegularPrice() {
		assertTrue(SeatValidator.validatePrice(12.5).isValid());
	}

	@Test
	void acceptsZeroAndMaximum() {
		assertTrue(SeatValidator.validatePrice(0.0).isValid());
		assertTrue(SeatValidator.validatePrice(SeatValidator.MAX_PRICE_EUR).isValid());
	}

	@Test
	void rejectsNegativePrice() {
		ValidationResult result = SeatValidator.validatePrice(-5.0);

		assertFalse(result.isValid());
		assertEquals("SEA-001", result.mostSevere().orElseThrow().code());
	}

	@Test
	void rejectsUnreadablePrice() {
		assertEquals("SEA-002", SeatValidator.validatePrice(null).mostSevere().orElseThrow().code());
		assertEquals("SEA-002", SeatValidator.validatePrice(Double.NaN).mostSevere().orElseThrow().code());
		assertEquals("SEA-002", SeatValidator.validatePrice(Double.POSITIVE_INFINITY).mostSevere().orElseThrow().code());
	}

	@Test
	void rejectsPriceAboveMaximum() {
		ValidationResult result = SeatValidator.validatePrice(SeatValidator.MAX_PRICE_EUR + 0.01);

		assertFalse(result.isValid());
		assertEquals("SEA-003", result.mostSevere().orElseThrow().code());
	}

	@Test
	void issueCodesAreUnique() {
		long distinct = Arrays.stream(SeatIssue.values()).map(SeatIssue::getCode).distinct().count();

		assertEquals(SeatIssue.values().length, distinct);
	}
}
