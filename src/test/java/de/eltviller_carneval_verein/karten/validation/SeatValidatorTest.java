package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Seat;

class SeatValidatorTest {

	/** Sitz 2 an Tisch 4 der Vorstellung "Prunksitzung". */
	private static Seat seatWithPriceCents(int cents) {
		Event event = new Event();
		event.changeName("Kampagne");
		Seat seat = event.addPresentation("Prunksitzung").addTable(4).addSeat(2);
		seat.setPrice(cents);
		return seat;
	}

	@Test
	void acceptsRegularPrice() {
		assertTrue(SeatValidator.validate(seatWithPriceCents(1250)).isValid());
	}

	@Test
	void acceptsZeroAndMaximum() {
		assertTrue(SeatValidator.validate(seatWithPriceCents(0)).isValid());
		assertTrue(SeatValidator.validate(seatWithPriceCents((int) SeatValidator.MAX_PRICE_CENTS)).isValid());
	}

	@Test
	void rejectsNegativePriceAndNamesTheSeat() {
		ValidationIssue issue = SeatValidator.validate(seatWithPriceCents(-500)).mostSevere().orElseThrow();

		assertEquals("SEA-001", issue.code());
		assertEquals("SEA-001: Der Preis für Prunksitzung, Tisch 4, Sitz 2 darf nicht negativ sein.", issue.toDisplayText());
	}

	@Test
	void rejectsPriceAboveMaximumWithValuesInMessage() {
		ValidationIssue issue = SeatValidator.validate(seatWithPriceCents(120_000)).mostSevere().orElseThrow();

		assertEquals("SEA-003", issue.code());
		assertEquals("Der Preis 1.200,00 € für Prunksitzung, Tisch 4, Sitz 2 ist zu hoch (maximal 1.000,00 €).", issue.message());
	}

	@Test
	void hugeEnteredPricesAreClampedAndStillRejected() {
		Seat seat = seatWithPriceCents(0);

		seat.setPriceDouble(1e300);
		assertEquals("SEA-003", SeatValidator.validate(seat).mostSevere().orElseThrow().code());

		seat.setPriceDouble(-1e300);
		assertEquals("SEA-001", SeatValidator.validate(seat).mostSevere().orElseThrow().code());
	}

	@Test
	void priceEnteredInEuroIsStoredInCentsAndChecked() {
		Seat seat = seatWithPriceCents(0);

		seat.setPriceDouble(12.5);
		assertEquals(1250, seat.getPrice());
		assertTrue(SeatValidator.validate(seat).isValid());

		seat.setPriceDouble(-5);
		assertFalse(SeatValidator.validate(seat).isValid());
	}
}
