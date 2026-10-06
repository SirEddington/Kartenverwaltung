package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class SalesValidatorTest {

	@Test
	void acceptsRegularPrice() {
		assertTrue(SalesValidator.validatePrice(12.5).isValid());
	}

	@Test
	void acceptsZeroAndMaximum() {
		assertTrue(SalesValidator.validatePrice(0.0).isValid());
		assertTrue(SalesValidator.validatePrice(SalesValidator.MAX_PRICE_EUR).isValid());
	}

	@Test
	void rejectsNegativePrice() {
		ValidationResult result = SalesValidator.validatePrice(-5.0);

		assertFalse(result.isValid());
		assertEquals("SLS-001", result.mostSevere().orElseThrow().code());
	}

	@Test
	void rejectsUnreadablePrice() {
		assertEquals("SLS-002", SalesValidator.validatePrice(null).mostSevere().orElseThrow().code());
		assertEquals("SLS-002", SalesValidator.validatePrice(Double.NaN).mostSevere().orElseThrow().code());
		assertEquals("SLS-002", SalesValidator.validatePrice(Double.POSITIVE_INFINITY).mostSevere().orElseThrow().code());
	}

	@Test
	void rejectsPriceAboveMaximum() {
		ValidationResult result = SalesValidator.validatePrice(SalesValidator.MAX_PRICE_EUR + 0.01);

		assertFalse(result.isValid());
		assertEquals("SLS-003", result.mostSevere().orElseThrow().code());
	}

	@Test
	void issueCodesAreUnique() {
		long distinct = Arrays.stream(SalesIssue.values()).map(SalesIssue::getCode).distinct().count();

		assertEquals(SalesIssue.values().length, distinct);
	}
}
