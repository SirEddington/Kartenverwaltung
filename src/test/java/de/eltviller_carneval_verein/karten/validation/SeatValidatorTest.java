package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
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

	private static ValidationIssue only(ValidationResult result) {
		assertEquals(1, result.getIssues().size(), "Genau eine Meldung erwartet: " + result.getIssues());
		return result.getIssues().get(0);
	}

	// --- Preis (SEA-001, SEA-003) ---

	@Test
	void acceptsRegularPrice() {
		assertTrue(SeatValidator.validate(seatWithPriceCents(1250)).isEmpty());
	}

	@Test
	void acceptsZeroAndMaximumForAnOpenSeat() {
		assertTrue(SeatValidator.validate(seatWithPriceCents(0)).isEmpty());
		assertTrue(SeatValidator.validate(seatWithPriceCents((int) SeatValidator.MAX_PRICE_CENTS)).isEmpty());
	}

	@Test
	void rejectsNegativePriceAndNamesTheSeat() {
		ValidationIssue issue = only(SeatValidator.validate(seatWithPriceCents(-500)));

		assertEquals("SEA-001", issue.code());
		assertEquals("SEA-001: Der Preis für Prunksitzung, Tisch 4, Sitz 2 darf nicht negativ sein.", issue.toDisplayText());
	}

	@Test
	void rejectsPriceAboveMaximumWithValuesInMessage() {
		ValidationIssue issue = only(SeatValidator.validate(seatWithPriceCents(120_000)));

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

	// --- abgeholt, aber nicht bezahlt (SEA-004) ---

	@Test
	void collectedButNotPaidIsAWarning() {
		Seat seat = seatWithPriceCents(1000);
		seat.setCollected(true);

		ValidationResult result = SeatValidator.validate(seat);

		assertTrue(result.isValid(), "Warnungen blockieren nicht");
		assertEquals("SEA-004", only(result).code());
		assertEquals(Severity.WARNING, only(result).severity());
		assertEquals("Prunksitzung, Tisch 4, Sitz 2 ist als abgeholt markiert, aber nicht bezahlt.", only(result).message());
	}

	@Test
	void collectedAndPaidIsFine() {
		Seat seat = seatWithPriceCents(1000);
		seat.setCollected(true);
		seat.setPaymentStatus(PaymentStatus.CASH);
		seat.setLastName("Müller");

		assertTrue(SeatValidator.validate(seat).isEmpty());
	}

	// --- E-Mail (SEA-005) ---

	@Test
	void invalidEmailIsAWarningWithTheAddress() {
		Seat seat = seatWithPriceCents(1000);
		seat.setEMail("max.mustermann");

		ValidationIssue issue = only(SeatValidator.validate(seat));

		assertEquals("SEA-005", issue.code());
		assertEquals("Die E-Mail-Adresse max.mustermann bei Prunksitzung, Tisch 4, Sitz 2 ist nicht gültig.", issue.message());
	}

	@Test
	void validOrEmptyEmailIsAccepted() {
		Seat seat = seatWithPriceCents(1000);

		for (String email : new String[] { null, "", "   ", "max@example.de", " max.mustermann@mail.example.de " }) {
			seat.setEMail(email);
			assertTrue(SeatValidator.validate(seat).isEmpty(), "Adresse sollte akzeptiert werden: '" + email + "'");
		}
	}

	@Test
	void obviouslyBrokenEmailsAreRejected() {
		Seat seat = seatWithPriceCents(1000);

		for (String email : new String[] { "max@", "@example.de", "max@example", "max example@mail.de", "max@@example.de" }) {
			seat.setEMail(email);
			assertEquals("SEA-005", only(SeatValidator.validate(seat)).code(), "Adresse sollte abgelehnt werden: '" + email + "'");
		}
	}

	// --- bezahlt, aber Preis 0 (SEA-006) ---

	@Test
	void paidWithoutPriceIsAWarning() {
		Seat seat = seatWithPriceCents(0);
		seat.setPaymentStatus(PaymentStatus.CASH);
		seat.setLastName("Müller");

		ValidationResult result = SeatValidator.validate(seat);

		assertTrue(result.isValid());
		assertEquals("SEA-006", only(result).code());
		assertEquals("Prunksitzung, Tisch 4, Sitz 2 ist als bezahlt markiert, der Preis beträgt aber 0,00 €.", only(result).message());
	}

	@Test
	void paidWithPriceOrOpenWithoutPriceIsFine() {
		Seat paid = seatWithPriceCents(500);
		paid.setPaymentStatus(PaymentStatus.CARD);
		paid.setLastName("Müller");
		assertTrue(SeatValidator.validate(paid).isEmpty());

		assertTrue(SeatValidator.validate(seatWithPriceCents(0)).isEmpty());
	}

	@Test
	void severalProblemsAreAllReportedMostSevereFirst() {
		Seat seat = seatWithPriceCents(-100);
		seat.setCollected(true);
		seat.setEMail("kaputt");

		ValidationResult result = SeatValidator.validate(seat);

		assertEquals(3, result.getIssues().size());
		assertEquals("SEA-001", result.mostSevere().orElseThrow().code());
		assertFalse(result.isValid());
	}

	// --- bezahlt, aber kein Nachname (SEA-007) ---

	@Test
	void paidWithoutLastNameIsAWarning() {
		Seat seat = seatWithPriceCents(1000);
		seat.setPaymentStatus(PaymentStatus.CASH);

		ValidationResult result = SeatValidator.validate(seat);

		assertTrue(result.isValid(), "Warnungen blockieren nicht");
		assertEquals("SEA-007", only(result).code());
		assertEquals(Severity.WARNING, only(result).severity());
		assertEquals("Prunksitzung, Tisch 4, Sitz 2 ist als bezahlt markiert, es ist aber kein Nachname eingetragen.", only(result).message());
	}

	@Test
	void blankLastNameCountsAsMissing() {
		Seat seat = seatWithPriceCents(1000);
		seat.setPaymentStatus(PaymentStatus.TRANSFER);

		for (String lastName : new String[] { null, "", "   " }) {
			seat.setLastName(lastName);
			assertEquals("SEA-007", only(SeatValidator.validate(seat)).code(), "Nachname '" + lastName + "' sollte fehlen");
		}
	}

	@Test
	void firstNameAloneDoesNotCount() {
		// Der Nachname entscheidet auch bei Seat.isReserved(); nur ein Vorname ergibt keinen reservierten Sitz
		Seat seat = seatWithPriceCents(1000);
		seat.setPaymentStatus(PaymentStatus.CASH);
		seat.setFirstName("Anna");

		assertEquals("SEA-007", only(SeatValidator.validate(seat)).code());
	}

	@Test
	void paidWithLastNameOrOpenWithoutNameIsFine() {
		Seat paid = seatWithPriceCents(1000);
		paid.setPaymentStatus(PaymentStatus.CASH);
		paid.setLastName("Müller");
		assertTrue(SeatValidator.validate(paid).isEmpty());

		// Offener Sitz ohne Namen (noch nicht verkauft) ist normal
		assertTrue(SeatValidator.validate(seatWithPriceCents(1000)).isEmpty());
	}
}
