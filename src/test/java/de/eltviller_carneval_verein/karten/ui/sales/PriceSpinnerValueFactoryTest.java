package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.validation.ValidationIssue;

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

	/** Factory mit Startwert 7 Euro, die abgelehnte Eingaben in die Liste schreibt. */
	private static PriceSpinnerValueFactory factoryWithRejections(List<ValidationIssue> rejected) {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(7.0);
		factory.setFieldLabel("Preis");
		factory.setRejectionHandler(rejected::add);
		return factory;
	}

	@Test
	void parsesGermanAndEnglishDecimals() {
		List<ValidationIssue> rejected = new ArrayList<>();
		PriceSpinnerValueFactory factory = factoryWithRejections(rejected);

		assertEquals(12.5, factory.getConverter().fromString("12,5"));
		assertEquals(12.5, factory.getConverter().fromString(" 12.50 "));
		assertEquals(1000.0, factory.getConverter().fromString("1.000,00"));
		assertEquals(0.0, factory.getConverter().fromString("0"));
		assertTrue(rejected.isEmpty());
	}

	@Test
	void outOfRangeIsRejectedInsteadOfClamped() {
		List<ValidationIssue> rejected = new ArrayList<>();
		PriceSpinnerValueFactory factory = factoryWithRejections(rejected);

		assertEquals(7.0, factory.getConverter().fromString("5000"));
		assertEquals(7.0, factory.getConverter().fromString("-3"));

		assertEquals(2, rejected.size());
		assertEquals("INP-004", rejected.get(0).code());
		assertEquals("Preis: 5000 liegt nicht zwischen 0 und 1000.", rejected.get(0).message());
		assertEquals("INP-004", rejected.get(1).code());
	}

	@Test
	void parseQuietlyNeverReportsAndKeepsTheCurrentValueForBadInput() {
		List<ValidationIssue> rejected = new ArrayList<>();
		PriceSpinnerValueFactory factory = factoryWithRejections(rejected);

		assertEquals(12.5, factory.parseQuietly("12,5"));
		assertEquals(12.0, factory.parseQuietly("12,"), "Ein Komma am Ende gilt: 12 Euro");
		assertEquals(7.0, factory.parseQuietly("12,5,"), "Halb getippt und ungültig: bisheriger Wert");
		assertEquals(7.0, factory.parseQuietly("5000"));
		assertNull(factory.parseQuietly(""));
		assertTrue(rejected.isEmpty(), "Die Live-Berechnung beim Tippen darf nichts melden");
	}

	@Test
	void blankTextMeansNoValue() {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(7.0);

		assertNull(factory.getConverter().fromString(""));
		assertNull(factory.getConverter().fromString("   "));
		assertNull(factory.getConverter().fromString(null));
	}

	@Test
	void unreadableTextKeepsTheCurrentValueAndIsReported() {
		List<ValidationIssue> rejected = new ArrayList<>();
		PriceSpinnerValueFactory factory = factoryWithRejections(rejected);

		assertEquals(7.0, factory.getConverter().fromString("abc"));
		assertEquals(7.0, factory.getConverter().fromString("1,2,3"));

		assertEquals(2, rejected.size());
		assertEquals("INP-001", rejected.get(0).code());
		assertEquals("Preis: „abc“ ist keine gültige Zahl.", rejected.get(0).message());
	}

	@Test
	void withoutAHandlerRejectionsAreSilent() {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(7.0);

		assertEquals(7.0, factory.getConverter().fromString("abc"));
	}

	@Test
	void showsGermanFormatAndNothingForNoValue() {
		PriceSpinnerValueFactory factory = new PriceSpinnerValueFactory(null);

		assertEquals("12,50", factory.getConverter().toString(12.5));
		assertEquals("", factory.getConverter().toString(null));
	}
}
