package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PriceSpinnerValueFactoryTest {

	@Test
	void incrementsAndDecrementsByHalfAnEuro() {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(12.0);

		factory.increment(1);
		assertEquals(12.5, factory.getValue());
		factory.decrement(2);
		assertEquals(11.5, factory.getValue());
	}

	@Test
	void emptyValueDoesNotBreakTheArrowButtons() {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(null);

		factory.increment(1);
		assertEquals(0.5, factory.getValue());

		PriceSpinnerValueFactory other = new PriceSpinnerValueFactory(null);
		other.decrement(1);
		assertEquals(0.0, other.getValue());
	}

	@Test
	void staysWithinZeroAndOneThousandEuro() {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(999.8);
		factory.increment(1);
		assertEquals(1000.0, factory.getValue());

		PriceSpinnerValueFactory low = new PriceSpinnerValueFactory(0.2);
		low.decrement(1);
		assertEquals(0.0, low.getValue());
	}

	@Test
	void parsesGermanAndEnglishDecimalsAndClamps() {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(7.0);

		assertEquals(12.5, factory.getConverter().fromString("12,5"));
		assertEquals(12.5, factory.getConverter().fromString(" 12.50 "));
		assertEquals(0.0, factory.getConverter().fromString("-3"));
		assertEquals(1000.0, factory.getConverter().fromString("5000"));
	}

	@Test
	void blankTextMeansNoValue() {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(7.0);

		assertNull(factory.getConverter().fromString(""));
		assertNull(factory.getConverter().fromString("   "));
		assertNull(factory.getConverter().fromString(null));
	}

	@Test
	void unreadableTextKeepsTheCurrentValueInsteadOfFailing() {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(7.0);

		assertEquals(7.0, factory.getConverter().fromString("abc"));
		assertEquals(7.0, factory.getConverter().fromString("1,2,3"));
	}

	@Test
	void showsGermanFormatAndNothingForNoValue() {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(null);

		assertEquals("12,50", factory.getConverter().toString(12.5));
		assertEquals("", factory.getConverter().toString(null));
	}
}
