package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Layoutregeln am Sitz der Bestuhlung (SEA-008, SEA-009). Die Verkaufsregeln (SEA-001, SEA-003 bis SEA-007) prüft der
 * {@link BookingValidator}, siehe {@code BookingValidatorTest}.
 */
class SeatValidatorTest {

	/** Sitz 2 an Tisch 4 (Reihe ohne Buchstaben) der Bestuhlung "Prunksitzung" in der Halle "Festhalle". */
	private static Seat seat() {
		Hall hall = new Hall();
		hall.setName("Festhalle");
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Prunksitzung");
		Table table = plan.addTable(plan.addRow());
		table.setTableNumber(4);
		return table.addSeat(2);
	}

	private static ValidationIssue only(ValidationResult result) {
		assertEquals(1, result.getIssues().size(), "Genau eine Meldung erwartet: " + result.getIssues());
		return result.getIssues().get(0);
	}

	@Test
	void seatWithNumberAndSizeHasNoIssues() {
		assertTrue(SeatValidator.validate(seat()).isEmpty());
	}

	// --- Sitznummer (SEA-008) ---

	@Test
	void seatNumberBelowOneIsAnErrorAndNamesTheSeat() {
		for (int number : new int[] { 0, -2 }) {
			Seat seat = seat();
			seat.setSeatNumber(number);

			ValidationIssue issue = only(SeatValidator.validate(seat));

			assertEquals("SEA-008", issue.code());
			assertTrue(issue.message().contains("Sitz " + number), issue.message());
		}
	}

	// --- Größe (SEA-009), früher Grenzen der Spinner ---

	@Test
	void seatSizeMustBeBetweenZeroAndOneHundred() {
		for (double ok : new double[] { 0, 30, 100 }) {
			Seat seat = seat();
			seat.setWidth(ok);
			seat.setHeight(ok);
			assertTrue(SeatValidator.validate(seat).isEmpty(), "Gültig: " + ok);
		}

		Seat wide = seat();
		wide.setWidth(100.5);
		ValidationIssue width = only(SeatValidator.validate(wide));
		assertEquals("SEA-009", width.code());
		assertEquals("Breite von Sitz 2 an Tisch 4 in Bestuhlung Prunksitzung in Halle Festhalle liegt mit 100,5 nicht zwischen 0 und 100.",
				width.message());

		Seat negative = seat();
		negative.setHeight(-1);
		ValidationIssue height = only(SeatValidator.validate(negative));
		assertEquals("SEA-009", height.code());
		assertTrue(height.message().startsWith("Höhe von Sitz 2 an Tisch 4 in Bestuhlung Prunksitzung"), height.message());
	}
}
