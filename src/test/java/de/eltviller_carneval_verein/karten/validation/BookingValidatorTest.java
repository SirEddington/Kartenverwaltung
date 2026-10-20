package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;

class BookingValidatorTest {

	/** Buchung für Tisch A3, Sitz 2 der Vorstellung "Prunksitzung" (Standardpreis 12,50 €), ohne jede Meldung. */
	private static Booking booking() {
		Event event = new Event();
		event.setName("Kampagne");
		Presentation presentation = event.addPresentation("Prunksitzung");
		presentation.setPrice(1250);
		Booking booking = presentation.addBooking();
		booking.setSeatId("sitz-1");
		booking.setRowLabel("A");
		booking.setTableNumber(3);
		booking.setSeatNumber(2);
		return booking;
	}

	private static ValidationIssue only(ValidationResult result) {
		assertEquals(1, result.getIssues().size(), "Genau eine Meldung erwartet: " + result.getIssues());
		return result.getIssues().get(0);
	}

	@Test
	void validBookingHasNoIssues() {
		assertTrue(BookingValidator.validate(booking()).isEmpty());
	}

	@Test
	void negativePriceIsAnError() {
		Booking booking = booking();
		booking.setPrice(-1);

		ValidationIssue issue = only(BookingValidator.validate(booking));

		assertEquals("SEA-001", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Der Preis für Prunksitzung, Tisch A3, Sitz 2 darf nicht negativ sein.", issue.message());
	}

	@Test
	void badStandardPriceIsNotReportedAgainPerBooking() {
		for (int bad : new int[] { -100, 100_001 }) {
			Booking booking = booking();
			booking.getParent().setPrice(bad);

			assertTrue(BookingValidator.validate(booking).isEmpty(), "Standardpreis meldet nur PRS-008: " + bad);
		}
	}

	@Test
	void ownPriceOverrideIsCheckedEvenWhenTheStandardPriceIsBad() {
		Booking negative = booking();
		negative.getParent().setPrice(-100);
		negative.setPrice(-5);
		assertEquals("SEA-001", only(BookingValidator.validate(negative)).code());

		Booking high = booking();
		high.getParent().setPrice(100_001);
		high.setPrice(100_001);
		assertEquals("SEA-003", only(BookingValidator.validate(high)).code());
	}

	@Test
	void priceAboveTheMaximumIsAnError() {
		Booking booking = booking();
		booking.setPrice(100_001);

		ValidationIssue issue = only(BookingValidator.validate(booking));

		assertEquals("SEA-003", issue.code());
		assertTrue(issue.message().startsWith("Der Preis "), issue.message());
		assertTrue(issue.message().contains("für Prunksitzung, Tisch A3, Sitz 2 ist zu hoch"), issue.message());

		booking.setPrice((int) BookingValidator.MAX_PRICE_CENTS);
		assertTrue(BookingValidator.validate(booking).isEmpty());
	}

	@Test
	void priceAboveTheMaximumNamesBothValues() {
		Booking booking = booking();
		booking.setPrice(120_000);

		assertEquals("Der Preis 1.200,00 € für Prunksitzung, Tisch A3, Sitz 2 ist zu hoch (maximal 1.000,00 €).",
				only(BookingValidator.validate(booking)).message());
	}

	@Test
	void zeroAndMaximumAreAcceptedForAnOpenBooking() {
		Booking booking = booking();
		booking.setPrice(0);
		assertTrue(BookingValidator.validate(booking).isEmpty(), "Offen ohne Preis ist normal");

		booking.setPrice((int) BookingValidator.MAX_PRICE_CENTS);
		assertTrue(BookingValidator.validate(booking).isEmpty());
	}

	@Test
	void paidWithOwnPriceIsFine() {
		Booking booking = booking();
		booking.setPrice(500);
		booking.setPaymentStatus(PaymentStatus.CARD);
		booking.setLastName("Müller");
		datedForActions(booking);

		assertTrue(BookingValidator.validate(booking).isEmpty());
	}

	@Test
	void collectedAndPaidIsFine() {
		Booking booking = booking();
		booking.setCollected(true);
		booking.setPaymentStatus(PaymentStatus.CASH);
		booking.setLastName("Müller");
		datedForActions(booking);

		assertTrue(BookingValidator.validate(booking).isEmpty());
	}

	@Test
	void validOrEmptyEmailIsAccepted() {
		Booking booking = booking();

		for (String email : new String[] { null, "", "   ", "max@example.de", " max.mustermann@mail.example.de " }) {
			booking.setEMail(email);
			assertTrue(BookingValidator.validate(booking).isEmpty(), "Adresse sollte akzeptiert werden: '" + email + "'");
		}
	}

	@Test
	void obviouslyBrokenEmailsAreRejected() {
		Booking booking = booking();

		for (String email : new String[] { "max@", "@example.de", "max@example", "max example@mail.de", "max@@example.de" }) {
			booking.setEMail(email);
			assertEquals("SEA-005", only(BookingValidator.validate(booking)).code(), "Adresse sollte abgelehnt werden: '" + email + "'");
		}
	}

	@Test
	void severalProblemsAreAllReportedMostSevereFirst() {
		Booking booking = booking();
		booking.setPrice(-100);
		booking.setCollected(true);
		booking.setEMail("kaputt");
		datedForActions(booking);

		ValidationResult result = BookingValidator.validate(booking);

		assertEquals(3, result.getIssues().size());
		assertEquals("SEA-001", result.mostSevere().orElseThrow().code());
		assertFalse(result.isValid());
	}

	@Test
	void collectedWithoutPaymentIsAWarning() {
		Booking booking = booking();
		booking.setCollected(true);
		datedForActions(booking);

		ValidationIssue issue = only(BookingValidator.validate(booking));

		assertEquals("SEA-004", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Prunksitzung, Tisch A3, Sitz 2 ist als abgeholt markiert, aber nicht bezahlt.", issue.message());
	}

	@Test
	void invalidEmailIsAWarningButEmptyIsFine() {
		Booking booking = booking();
		booking.setEMail("");
		assertTrue(BookingValidator.validate(booking).isEmpty());

		booking.setEMail("  keine-adresse ");
		ValidationIssue issue = only(BookingValidator.validate(booking));

		assertEquals("SEA-005", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Die E-Mail-Adresse keine-adresse bei Prunksitzung, Tisch A3, Sitz 2 ist nicht gültig.", issue.message());

		booking.setEMail("anna@beispiel.de");
		assertTrue(BookingValidator.validate(booking).isEmpty());
	}

	@Test
	void paidWithoutPriceIsAWarningAndUsesTheEffectivePrice() {
		Booking booking = booking();
		booking.setPaymentStatus(PaymentStatus.CASH);
		booking.setLastName("Muster");
		datedForActions(booking);
		assertTrue(BookingValidator.validate(booking).isEmpty(), "Standardpreis 12,50 € zählt");

		booking.setPrice(0);
		ValidationIssue issue = only(BookingValidator.validate(booking));

		assertEquals("SEA-006", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Prunksitzung, Tisch A3, Sitz 2 ist als bezahlt markiert, der Preis beträgt aber 0,00 €.", issue.message());
	}

	@Test
	void paidWithoutLastNameIsAWarningNamingThePlace() {
		Booking booking = booking();
		booking.setPaymentStatus(PaymentStatus.CARD);
		datedForActions(booking);

		ValidationIssue issue = only(BookingValidator.validate(booking));

		assertEquals("SEA-007", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Prunksitzung, Tisch A3, Sitz 2 ist als bezahlt markiert, es ist aber kein Nachname eingetragen.",
				issue.message());
	}

	@Test
	void blankLastNameCountsAsMissing() {
		Booking booking = booking();
		booking.setPaymentStatus(PaymentStatus.TRANSFER);

		for (String lastName : new String[] { null, "", "   " }) {
			booking.setLastName(lastName);
			datedForActions(booking);
			assertEquals("SEA-007", only(BookingValidator.validate(booking)).code(), "Nachname '" + lastName + "' sollte fehlen");
		}
	}

	@Test
	void firstNameAloneDoesNotCount() {
		// Der Nachname entscheidet auch bei Booking.isReserved(); nur ein Vorname ergibt keine Reservierung
		Booking booking = booking();
		booking.setPaymentStatus(PaymentStatus.CASH);
		booking.setFirstName("Anna");
		datedForActions(booking);

		assertEquals("SEA-007", only(BookingValidator.validate(booking)).code());
	}

	@Test
	void reservationDateWithoutReservationIsAWarning() {
		Booking booking = booking();
		booking.setReservationDate(LocalDate.of(2026, 10, 1));

		ValidationIssue issue = only(BookingValidator.validate(booking, TODAY));

		assertEquals("SEA-012", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Bei Prunksitzung, Tisch A3, Sitz 2 ist ein Reservierungsdatum eingetragen, die Buchung ist aber nicht reserviert.",
				issue.message());
	}

	@Test
	void paymentDateWithoutPaymentIsAWarning() {
		Booking booking = booking();
		booking.setPaymentDate(LocalDate.of(2026, 10, 1));

		ValidationIssue issue = only(BookingValidator.validate(booking, TODAY));

		assertEquals("SEA-013", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Bei Prunksitzung, Tisch A3, Sitz 2 ist ein Bezahldatum eingetragen, die Buchung ist aber nicht bezahlt.",
				issue.message());
	}

	@Test
	void collectionDateWithoutCollectionIsAWarning() {
		Booking booking = booking();
		booking.setCollectionDate(LocalDate.of(2026, 10, 1));

		ValidationIssue issue = only(BookingValidator.validate(booking, TODAY));

		assertEquals("SEA-014", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Bei Prunksitzung, Tisch A3, Sitz 2 ist ein Abholdatum eingetragen, die Buchung ist aber nicht als abgeholt markiert.",
				issue.message());
	}

	@Test
	void reservedWithoutDateIsAWarning() {
		Booking booking = booking();
		booking.setLastName("Müller");

		ValidationIssue issue = only(BookingValidator.validate(booking, TODAY));

		assertEquals("SEA-015", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Prunksitzung, Tisch A3, Sitz 2 ist reserviert, es fehlt aber das Reservierungsdatum.", issue.message());
	}

	@Test
	void paidWithoutDateIsAWarning() {
		Booking booking = soldBooking();
		booking.setPaymentDate(null);
		booking.setCollected(false);
		booking.setCollectionDate(null);

		ValidationIssue issue = only(BookingValidator.validate(booking, TODAY));

		assertEquals("SEA-016", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Prunksitzung, Tisch A3, Sitz 2 ist als bezahlt markiert, es fehlt aber das Bezahldatum.", issue.message());
	}

	@Test
	void collectedWithoutDateIsAWarning() {
		Booking booking = soldBooking();
		booking.setCollectionDate(null);

		ValidationIssue issue = only(BookingValidator.validate(booking, TODAY));

		assertEquals("SEA-017", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Prunksitzung, Tisch A3, Sitz 2 ist als abgeholt markiert, es fehlt aber das Abholdatum.", issue.message());
	}

	@Test
	void soldBookingWithAllDatesInOrderHasNoIssues() {
		assertTrue(BookingValidator.validate(soldBooking(), TODAY).isEmpty());
	}

	@Test
	void freeBookingWithoutDatesHasNoIssuesAtAnyDay() {
		assertTrue(BookingValidator.validate(booking(), TODAY).isEmpty());
	}

	@Test
	void dateTodayIsNotInTheFutureButTomorrowIs() {
		Booking today = soldBooking();
		today.setCollectionDate(TODAY);
		assertTrue(BookingValidator.validate(today, TODAY).isEmpty());

		Booking tomorrow = booking();
		tomorrow.setLastName("Müller");
		tomorrow.setReservationDate(TODAY.plusDays(1));
		ValidationIssue issue = only(BookingValidator.validate(tomorrow, TODAY));
		assertEquals("SEA-018", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Das Reservierungsdatum (21.10.2026) bei Prunksitzung, Tisch A3, Sitz 2 liegt in der Zukunft.", issue.message());
	}

	@Test
	void paymentBeforeReservationBreaksTheOrder() {
		Booking booking = soldBooking();
		booking.setPaymentDate(LocalDate.of(2026, 9, 30));

		ValidationIssue issue = only(BookingValidator.validate(booking, TODAY));

		assertEquals("SEA-019", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Bei Prunksitzung, Tisch A3, Sitz 2 liegt das Bezahldatum vor dem Reservierungsdatum.", issue.message());
	}

	@Test
	void equalDatesAreInOrder() {
		Booking booking = soldBooking();
		booking.setReservationDate(LocalDate.of(2026, 10, 2));
		booking.setCollectionDate(LocalDate.of(2026, 10, 2));

		assertTrue(BookingValidator.validate(booking, TODAY).isEmpty());
	}

	@Test
	void collectionBeforePaymentAndReservationReportsBothPairs() {
		Booking booking = soldBooking();
		booking.setReservationDate(LocalDate.of(2026, 10, 10));
		booking.setPaymentDate(LocalDate.of(2026, 10, 12));
		booking.setCollectionDate(LocalDate.of(2026, 10, 5));

		ValidationResult result = BookingValidator.validate(booking, TODAY);

		assertEquals(2, result.getIssues().size(), result.getIssues().toString());
		assertEquals("SEA-019", result.getIssues().get(0).code());
		assertEquals("SEA-019", result.getIssues().get(1).code());
		String messages = result.getIssues().get(0).message() + " | " + result.getIssues().get(1).message();
		assertTrue(messages.contains("das Abholdatum vor dem Bezahldatum"), messages);
		assertTrue(messages.contains("das Abholdatum vor dem Reservierungsdatum"), messages);
	}

	@Test
	void aMissingDateOfAPairIsNotAnOrderProblem() {
		Booking booking = soldBooking();
		booking.setReservationDate(null);

		assertEquals("SEA-015", only(BookingValidator.validate(booking, TODAY)).code());
	}

	@Test
	void dateWithoutActionIsAlsoCheckedForTheFuture() {
		Booking booking = booking();
		booking.setPaymentDate(TODAY.plusDays(1));

		ValidationResult result = BookingValidator.validate(booking, TODAY);

		assertEquals(2, result.getIssues().size(), result.getIssues().toString());
		assertEquals("SEA-013", result.getIssues().get(0).code());
		assertEquals("SEA-018", result.getIssues().get(1).code());
	}

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
	private static final LocalDate PAST = LocalDate.of(2026, 10, 1);

	/** Trägt für jede erfolgte Aktion, der das Datum fehlt, einen Tag in der Vergangenheit ein (SEA-015 bis SEA-017 sollen die älteren Fälle nicht stören). */
	private static void datedForActions(Booking booking) {
		if (booking.isReserved() && booking.getReservationDate() == null) {
			booking.setReservationDate(PAST);
		}
		if (booking.isPaid() && booking.getPaymentDate() == null) {
			booking.setPaymentDate(PAST);
		}
		if (booking.isCollected() && booking.getCollectionDate() == null) {
			booking.setCollectionDate(PAST);
		}
	}

	/** Reserviert, bezahlt (bar) und abgeholt, die Daten vollständig und in der richtigen Reihenfolge. */
	private static Booking soldBooking() {
		Booking booking = booking();
		booking.setLastName("Müller");
		booking.setPaymentStatus(PaymentStatus.CASH);
		booking.setCollected(true);
		booking.setReservationDate(LocalDate.of(2026, 10, 1));
		booking.setPaymentDate(LocalDate.of(2026, 10, 2));
		booking.setCollectionDate(LocalDate.of(2026, 10, 3));
		return booking;
	}

	@Test
	void unassignedBookingIsAWarning() {
		Booking booking = booking();
		booking.setUnassigned(true);

		ValidationIssue issue = only(BookingValidator.validate(booking));

		assertEquals("SEA-010", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Prunksitzung, Tisch A3, Sitz 2 hat keinen Platz in der Bestuhlung und muss neu zugewiesen werden.",
				issue.message());
	}
}
