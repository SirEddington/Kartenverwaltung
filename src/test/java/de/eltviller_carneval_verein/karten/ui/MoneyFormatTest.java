package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MoneyFormatTest {

	@Test
	void formatsWholeEuroAmounts() {
		assertEquals("12,00 €", MoneyFormat.formatCents(1200));
	}

	@Test
	void formatsCentsWithRounding() {
		assertEquals("12,50 €", MoneyFormat.formatCents(1250));
	}

	@Test
	void formatsThousandsWithGermanGroupingSeparator() {
		assertEquals("1.234,56 €", MoneyFormat.formatCents(123456));
	}

	@Test
	void formatsZero() {
		assertEquals("0,00 €", MoneyFormat.formatCents(0));
	}

	@Test
	void formatsNegativeAmounts() {
		assertEquals("-5,00 €", MoneyFormat.formatCents(-500));
	}
}
