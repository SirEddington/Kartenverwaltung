package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;

class PresentationAndTableValidatorTest {

	/** Vollständig ausgefüllte Vorstellung "Prunksitzung", damit nur die zu prüfenden Fehler auftreten. */
	private static Presentation presentation() {
		Event event = new Event();
		event.setName("Kampagne");
		Presentation presentation = event.addPresentation("Prunksitzung");
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.setHallId("halle-1");
		presentation.setSeatingPlanId("plan-1");
		return presentation;
	}

	/** Bestuhlung "Prunksitzung" in der Halle "Festhalle" mit einer Reihe ohne Buchstaben, noch ohne Tische. */
	private static SeatingPlan plan() {
		Hall hall = new Hall();
		hall.setName("Festhalle");
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Prunksitzung");
		plan.addRow();
		return plan;
	}

	/** Tisch {@code number} ("Tisch 4 in Bestuhlung Prunksitzung in Halle Festhalle"), noch ohne Sitze. */
	private static Table table(SeatingPlan plan, int number) {
		Table table = plan.addTable(plan.getRows().get(0));
		table.setTableNumber(number);
		return table;
	}

	private static Table table(int number) {
		return table(plan(), number);
	}

	private static ValidationIssue only(ValidationResult result) {
		assertEquals(1, result.getIssues().size(), "Genau eine Meldung erwartet: " + result.getIssues());
		return result.getIssues().get(0);
	}

	@Test
	void presentationWithoutBookingListHasNoIssues() {
		Presentation presentation = presentation();
		presentation.setBookings(null);

		assertTrue(PresentationValidator.validate(presentation).isEmpty());
	}

	// --- Namen und Nummern (PRS-004, TBL-002) ---

	@Test
	void presentationWithoutNameIsAnError() {
		Presentation presentation = presentation();
		presentation.setName("");

		assertEquals("PRS-004", only(PresentationValidator.validate(presentation)).code());
	}

	@Test
	void tableNumberBelowOneIsAnErrorAndNamesTheTable() {
		for (int number : new int[] { 0, -3 }) {
			Table table = table(4);
			table.setTableNumber(number);

			ValidationIssue issue = only(TableValidator.validate(table));

			assertEquals("TBL-002", issue.code());
			assertTrue(issue.message().contains("Tisch " + number), issue.message());
		}
	}

	// --- Wertebereiche (TBL-003, TBL-004, PRS-005), früher Grenzen der Spinner ---

	@Test
	void tableSizeMustBeBetweenZeroAndOneThousand() {
		for (double ok : new double[] { 0, 70, 1000 }) {
			Table table = table(4);
			table.setWidth(ok);
			table.setHeight(ok);
			assertTrue(TableValidator.validate(table).isEmpty(), "Gültig: " + ok);
		}

		Table wide = table(4);
		wide.setWidth(1000.5);
		ValidationIssue width = only(TableValidator.validate(wide));
		assertEquals("TBL-003", width.code());
		assertEquals("Breite von Tisch 4 in Bestuhlung Prunksitzung in Halle Festhalle liegt mit 1000,5 nicht zwischen 0 und 1000.",
				width.message());

		Table negative = table(4);
		negative.setHeight(-1);
		ValidationIssue height = only(TableValidator.validate(negative));
		assertEquals("TBL-003", height.code());
		assertTrue(height.message().startsWith("Höhe von Tisch 4 in Bestuhlung Prunksitzung"), height.message());
	}

	@Test
	void tablePositionMustBeWithinPlusMinusTenThousand() {
		for (double ok : new double[] { -10_000, 0, 10_000 }) {
			Table table = table(4);
			table.setPosX(ok);
			table.setPosY(ok);
			assertTrue(TableValidator.validate(table).isEmpty(), "Gültig: " + ok);
		}

		Table far = table(4);
		far.setPosX(10_001);
		far.setPosY(-10_001);
		ValidationResult result = TableValidator.validate(far);
		assertEquals(2, result.getIssues().size(), result.getIssues().toString());
		assertTrue(result.getIssues().stream().allMatch(i -> i.code().equals("TBL-004")));
		assertTrue(result.getIssues().stream().anyMatch(i -> i.message().startsWith("Position X von Tisch 4 in Bestuhlung Prunksitzung")));
	}

	@Test
	void actualCashMustBeBetweenZeroAndOneMillionEuro() {
		for (int ok : new int[] { 0, 2500, 100_000_000 }) {
			Presentation presentation = presentation();
			presentation.setActualCashAmount(ok);
			assertTrue(PresentationValidator.validate(presentation).isEmpty(), "Gültig: " + ok + " Cent");
		}

		Presentation tooMuch = presentation();
		tooMuch.setActualCashAmount(100_000_001);
		ValidationIssue high = only(PresentationValidator.validate(tooMuch));
		assertEquals("PRS-005", high.code());
		assertEquals("Ist-Betrag von Vorstellung Prunksitzung liegt mit 1.000.000,01 € nicht zwischen 0,00 € und 1.000.000,00 €.", high.message());

		Presentation negative = presentation();
		negative.setActualCashAmount(-1);
		assertEquals("PRS-005", only(PresentationValidator.validate(negative)).code());
	}

	// --- Weitergabe an die Kinder ---

	@Test
	void tableValidatorChecksEverySeatOfTheTable() {
		Table table = table(4);
		table.addSeat(1).setWidth(-1);
		table.addSeat(2).setWidth(30);
		table.addSeat(3).setWidth(101);

		ValidationResult result = TableValidator.validate(table);

		assertEquals(2, result.getIssues().size());
		assertTrue(result.getIssues().stream().allMatch(i -> i.code().equals("SEA-009")));
	}

	@Test
	void planValidatorChecksEveryTable() {
		SeatingPlan plan = plan();
		table(plan, 1).addSeat(1).setWidth(-1);
		table(plan, 2).addSeat(1).setWidth(-1);
		table(plan, 3).addSeat(1).setWidth(30);

		assertEquals(2, SeatingPlanValidator.validate(plan).getIssues().size());
	}

	@Test
	void emptyTableAndCompletePresentationHaveNoIssues() {
		Table table = table(1);

		assertTrue(TableValidator.validate(table).isEmpty());
		assertTrue(PresentationValidator.validate(presentation()).isEmpty());
	}

	// --- doppelte Sitznummern (TBL-001) ---

	@Test
	void duplicateSeatNumberIsReportedOncePerNumber() {
		Table table = table(4);
		table.addSeat(1);
		table.addSeat(2).setSeatNumber(1);
		table.addSeat(3).setSeatNumber(1);

		ValidationIssue issue = only(TableValidator.validate(table));

		assertEquals("TBL-001", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Sitznummer 1 kommt an Tisch 4 in Bestuhlung Prunksitzung in Halle Festhalle mehrfach vor.", issue.message());
	}

	@Test
	void differentSeatNumbersAreFine() {
		Table table = table(4);
		table.addSeat(1);
		table.addSeat(2);

		assertTrue(TableValidator.validate(table).isEmpty());
	}

	// --- Tischnummern: doppelte meldet die Bestuhlung (SPL-002, siehe SeatingPlanValidatorTest), PRS-001 entfällt ---

	@Test
	void differentTableNumbersAreFine() {
		SeatingPlan plan = plan();
		table(plan, 1);
		table(plan, 2);

		assertTrue(SeatingPlanValidator.validate(plan).isEmpty());
	}

	// --- Datum/Uhrzeit (PRS-002) ---

	@Test
	void missingDateOrTimeIsAWarning() {
		Presentation noDate = presentation();
		noDate.setDate(null);
		Presentation noTime = presentation();
		noTime.setTime(null);

		for (Presentation presentation : new Presentation[] { noDate, noTime }) {
			ValidationResult result = PresentationValidator.validate(presentation);

			assertTrue(result.isValid(), "Warnungen blockieren nicht");
			assertEquals("PRS-002", only(result).code());
			assertEquals("Für Vorstellung Prunksitzung fehlen Datum oder Uhrzeit.", only(result).message());
		}
	}

	@Test
	void bothDateAndTimeMissingGiveOneMessage() {
		Presentation presentation = presentation();
		presentation.setDate(null);
		presentation.setTime(null);

		assertEquals("PRS-002", only(PresentationValidator.validate(presentation)).code());
	}

	// --- Halle (PRS-003) ---

	@Test
	void missingHallIsAWarning() {
		for (String hallId : new String[] { null, "", "  " }) {
			Presentation presentation = presentation();
			presentation.setHallId(hallId);

			ValidationResult result = PresentationValidator.validate(presentation);

			assertTrue(result.isValid());
			assertEquals("PRS-003", only(result).code());
			assertEquals("Für Vorstellung Prunksitzung ist keine Halle ausgewählt.", only(result).message());
		}
	}

	// --- Bestuhlung (PRS-006, PRS-007), Standardpreis (PRS-008), Buchungen (SEA-011) ---

	@Test
	void hallWithoutSeatingPlanIsAWarning() {
		for (String planId : new String[] { null, "", "  " }) {
			Presentation presentation = presentation();
			presentation.setSeatingPlanId(planId);

			ValidationIssue issue = only(PresentationValidator.validate(presentation));

			assertEquals("PRS-006", issue.code());
			assertEquals(Severity.WARNING, issue.severity());
			assertEquals("Für Vorstellung Prunksitzung ist keine Bestuhlung ausgewählt.", issue.message());
		}
	}

	@Test
	void noSeatingPlanWithoutHallGivesOnlyTheHallWarning() {
		Presentation presentation = presentation();
		presentation.setHallId(null);
		presentation.setSeatingPlanId(null);

		assertEquals("PRS-003", only(PresentationValidator.validate(presentation)).code());
	}

	@Test
	void selectedSeatingPlanIsFine() {
		Presentation presentation = presentation();
		presentation.setSeatingPlanId("plan-1");

		assertTrue(PresentationValidator.validate(presentation).isEmpty());
	}

	@Test
	void vanishedSeatingPlanIsAWarningOnlyWithPlanMissing() {
		Presentation presentation = presentation();
		presentation.setSeatingPlanId("plan-1");
		assertTrue(PresentationValidator.validate(presentation).isEmpty());

		presentation.setPlanMissing(true);
		ValidationIssue issue = only(PresentationValidator.validate(presentation));

		assertEquals("PRS-007", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Die Bestuhlung von Vorstellung Prunksitzung existiert nicht mehr. Die Buchungen bleiben erhalten, neue können nicht angelegt werden.",
				issue.message());
	}

	@Test
	void standardPriceMustBeBetweenZeroAndOneThousandEuros() {
		for (int ok : new int[] { 0, 1250, 100_000 }) {
			Presentation presentation = presentation();
			presentation.setSeatingPlanId("plan-1");
			presentation.setPrice(ok);
			assertTrue(PresentationValidator.validate(presentation).isEmpty(), "Gültig: " + ok);
		}

		Presentation presentation = presentation();
		presentation.setSeatingPlanId("plan-1");
		presentation.setPrice(100_001);
		ValidationIssue issue = only(PresentationValidator.validate(presentation));
		assertEquals("PRS-008", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertTrue(issue.message().startsWith("Standardpreis von Vorstellung Prunksitzung liegt mit "), issue.message());
		assertTrue(issue.message().endsWith(" nicht zwischen 0,00 € und 1.000,00 €."), issue.message());

		presentation.setPrice(-1);
		assertEquals("PRS-008", only(PresentationValidator.validate(presentation)).code());
	}

	@Test
	void presentationValidationIncludesTheBookings() {
		Presentation presentation = presentation();
		presentation.setSeatingPlanId("plan-1");
		presentation.setPrice(1250);
		Booking booking = presentation.addBooking();
		booking.setSeatId("sitz-1");
		booking.setRowLabel("A");
		booking.setTableNumber(3);
		booking.setSeatNumber(2);
		booking.setPaymentStatus(PaymentStatus.CASH);
		booking.setPaymentDate(java.time.LocalDate.of(2026, 10, 1));

		ValidationIssue issue = only(PresentationValidator.validate(presentation));

		assertEquals("SEA-007", issue.code());
		assertTrue(issue.message().startsWith("Prunksitzung, Tisch A3, Sitz 2 "), issue.message());
	}

	@Test
	void badStandardPriceIsReportedOnceNotPerBooking() {
		Presentation presentation = presentation();
		presentation.setPrice(-100);
		for (int i = 1; i <= 3; i++) {
			Booking booking = presentation.addBooking();
			booking.setSeatId("sitz-" + i);
			booking.setTableNumber(1);
			booking.setSeatNumber(i);
		}

		assertEquals("PRS-008", only(PresentationValidator.validate(presentation)).code());
	}

	@Test
	void unassignedBookingIsReportedThroughThePresentation() {
		Presentation presentation = presentation();
		presentation.setPrice(1250);
		Booking booking = presentation.addBooking();
		booking.setSeatId("sitz-1");
		booking.setRowLabel("A");
		booking.setTableNumber(3);
		booking.setSeatNumber(2);
		booking.setUnassigned(true);

		ValidationIssue issue = only(PresentationValidator.validate(presentation));

		assertEquals("SEA-010", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Prunksitzung, Tisch A3, Sitz 2 hat keinen Platz in der Bestuhlung und muss neu zugewiesen werden.",
				issue.message());
	}

	@Test
	void sameSeatInSeveralBookingsGivesOneMessagePerSeat() {
		Presentation presentation = presentation();
		presentation.setSeatingPlanId("plan-1");
		for (String seatId : new String[] { "sitz-1", "sitz-1", "sitz-1", "sitz-2", "sitz-2", "sitz-3" }) {
			Booking booking = presentation.addBooking();
			booking.setSeatId(seatId);
			booking.setRowLabel("A");
			booking.setTableNumber(3);
			booking.setSeatNumber(seatId.charAt(5) - '0');
		}

		ValidationResult result = PresentationValidator.validate(presentation);

		assertEquals(2, result.getIssues().size(), result.getIssues().toString());
		assertTrue(result.getIssues().stream().allMatch(i -> i.code().equals("SEA-011") && i.severity() == Severity.ERROR));
		assertEquals("Der Sitz von Prunksitzung, Tisch A3, Sitz 1 ist noch einer zweiten Buchung zugeordnet.",
				result.getIssues().get(0).message());
		assertEquals("Der Sitz von Prunksitzung, Tisch A3, Sitz 2 ist noch einer zweiten Buchung zugeordnet.",
				result.getIssues().get(1).message());
	}

	@Test
	void bookingsWithoutSeatIdAreNotDoubleBookings() {
		Presentation presentation = presentation();
		presentation.setSeatingPlanId("plan-1");
		for (String seatId : new String[] { null, "", " " }) {
			presentation.addBooking().setSeatId(seatId);
		}

		assertTrue(PresentationValidator.validate(presentation).isEmpty());
	}

	@Test
	void presentationWithoutNameIsStillDescribed() {
		Presentation presentation = presentation();
		presentation.setHallId(null);
		presentation.setName(" ");

		ValidationIssue hallIssue = PresentationValidator.validate(presentation).getIssues().stream()
				.filter(issue -> issue.code().equals("PRS-003")).findFirst().orElseThrow();

		assertEquals("Für Vorstellung ohne Namen ist keine Halle ausgewählt.", hallIssue.message());
	}
}
