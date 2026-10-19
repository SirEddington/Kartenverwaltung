package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;

class SeatBulkEditTest {

	private final Presentation presentation = new Event().addPresentation("Prunksitzung");
	private final TestBookings bookings = new TestBookings();

	private Booking seat(int number, String lastName, int priceCents) {
		Booking booking = bookings.booking(presentation, 1, number);
		booking.setLastName(lastName);
		booking.setPrice(priceCents);
		return booking;
	}

	// --- gemeinsamer Wert ---

	@Test
	void commonValueIsPresentWhenAllSeatsAgree() {
		SeatBulkEdit edit = new SeatBulkEdit(List.of(seat(1, "Meier", 1000), seat(2, "Meier", 1000)));

		assertEquals(Optional.of("Meier"), edit.lastName().common());
		assertEquals(Optional.of(1000), edit.price().common());
	}

	@Test
	void commonValueIsEmptyWhenSeatsDiffer() {
		SeatBulkEdit edit = new SeatBulkEdit(List.of(seat(1, "Meier", 1000), seat(2, "Schulz", 1500)));

		assertEquals(Optional.empty(), edit.lastName().common());
		assertEquals(Optional.empty(), edit.price().common());
	}

	@Test
	void missingAndEmptyTextCountAsTheSameValue() {
		SeatBulkEdit edit = new SeatBulkEdit(List.of(seat(1, null, 0), seat(2, "", 0)));

		assertEquals(Optional.of(""), edit.lastName().common());
	}

	@Test
	void commonPriceIsTheEffectivePrice() {
		presentation.setPrice(2500);
		Booking standard = bookings.booking(presentation, 1, 1);
		Booking sameAsStandard = seat(2, "Meier", 2500);

		assertEquals(Optional.of(2500), new SeatBulkEdit(List.of(standard, sameAsStandard)).price().common());
	}

	// --- Änderungen übernehmen ---

	@Test
	void appliesAChangedFieldToEverySeat() {
		Booking first = seat(1, "Meier", 1000);
		Booking second = seat(2, "Schulz", 1500);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(first, second));

		edit.lastName().change("Müller");
		edit.apply();

		assertEquals("Müller", first.getLastName());
		assertEquals("Müller", second.getLastName());
	}

	@Test
	void leavesUntouchedFieldsOfEachSeatAlone() {
		Booking first = seat(1, "Meier", 1000);
		Booking second = seat(2, "Schulz", 1500);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(first, second));

		edit.paymentStatus().change(PaymentStatus.CASH);
		edit.apply();

		assertEquals(PaymentStatus.CASH, first.getPaymentStatus());
		assertEquals(PaymentStatus.CASH, second.getPaymentStatus());
		assertEquals("Meier", first.getLastName());
		assertEquals("Schulz", second.getLastName());
		assertEquals(1000, first.getEffectivePrice());
		assertEquals(1500, second.getEffectivePrice());
	}

	@Test
	void aDeviatingSinglePriceSurvivesEditingOtherFieldsOfSeveralBookings() {
		presentation.setPrice(2500);
		Booking deviating = bookings.booking(presentation, 1, 1);
		deviating.setPrice(1800);
		Booking standard = bookings.booking(presentation, 1, 2);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(deviating, standard));

		edit.lastName().change("Meier");
		edit.paymentStatus().change(PaymentStatus.CASH);
		edit.apply();

		assertEquals(Integer.valueOf(1800), deviating.getPrice());
		assertNull(standard.getPrice());
		assertEquals(2500, standard.getEffectivePrice());
	}

	@Test
	void settingTheStandardPriceStoresNoSinglePrice() {
		presentation.setPrice(2500);
		Booking first = seat(1, "Meier", 1800);
		Booking second = seat(2, "Schulz", 2000);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(first, second));

		edit.price().change(2500);
		edit.apply();

		assertNull(first.getPrice());
		assertNull(second.getPrice());
		assertEquals(2500, first.getEffectivePrice());
	}

	@Test
	void settingAnotherPriceStoresItAsSinglePrice() {
		presentation.setPrice(2500);
		Booking first = bookings.booking(presentation, 1, 1);
		Booking second = bookings.booking(presentation, 1, 2);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(first, second));

		edit.price().change(1200);
		edit.apply();

		assertEquals(Integer.valueOf(1200), first.getPrice());
		assertEquals(Integer.valueOf(1200), second.getPrice());
	}

	@Test
	void anEmptyTextFieldOverDifferingValuesChangesNothing() {
		Booking first = seat(1, "Meier", 1000);
		Booking second = seat(2, "Schulz", 1500);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(first, second));

		edit.lastName().change("");
		edit.apply();

		assertEquals("Meier", first.getLastName());
		assertEquals("Schulz", second.getLastName());
		assertFalse(edit.hasChanges());
	}

	@Test
	void clearingAFieldAllSeatsShareDoesChangeIt() {
		Booking first = seat(1, "Meier", 1000);
		Booking second = seat(2, "Meier", 1000);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(first, second));

		edit.lastName().change("");
		edit.apply();

		assertEquals("", first.getLastName());
		assertEquals("", second.getLastName());
	}

	@Test
	void changingBackToTheCommonValueIsNoChange() {
		SeatBulkEdit edit = new SeatBulkEdit(List.of(seat(1, "Meier", 1000), seat(2, "Meier", 1000)));

		edit.lastName().change("Müller");
		assertTrue(edit.hasChanges());
		edit.lastName().change("Meier");

		assertFalse(edit.hasChanges());
	}

	@Test
	void nullMeansNoInputAndWithdrawsEarlierInput() {
		Booking first = seat(1, "Meier", 1000);
		Booking second = seat(2, "Schulz", 1500);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(first, second));

		edit.price().change(2000);
		edit.price().change(null);
		edit.apply();

		assertFalse(edit.hasChanges());
		assertEquals(1000, first.getEffectivePrice());
		assertEquals(1500, second.getEffectivePrice());
	}

	@Test
	void appliesCheckboxAndComment() {
		Booking first = seat(1, "Meier", 1000);
		Booking second = seat(2, "Schulz", 1500);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(first, second));

		edit.collected().change(true);
		edit.firstName().change("Anna");
		edit.comment().change("Familie");
		edit.apply();

		assertTrue(first.isCollected() && second.isCollected());
		assertEquals("Anna", first.getFirstName());
		assertEquals("Familie", second.getComment());
	}

	@Test
	void unknownCheckboxStateIsNotApplied() {
		Booking first = seat(1, "Meier", 1000);
		first.setCollected(true);
		Booking second = seat(2, "Schulz", 1500);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(first, second));

		assertEquals(Optional.empty(), edit.collected().common());
		edit.collected().change(null);
		edit.apply();

		assertTrue(first.isCollected());
		assertFalse(second.isCollected());
	}

	// --- Gesamtpreis ---

	@Test
	void totalIsTheSumOfTheSeatPricesWithoutInput() {
		SeatBulkEdit edit = new SeatBulkEdit(List.of(seat(1, "A", 1000), seat(2, "B", 1550), seat(3, "C", 0)));

		assertEquals(2550, edit.totalCents());
	}

	@Test
	void totalCountsTheStandardPriceForBookingsWithoutSinglePrice() {
		presentation.setPrice(2500);
		SeatBulkEdit edit = new SeatBulkEdit(List.of(bookings.booking(presentation, 1, 1), seat(2, "B", 1000)));

		assertEquals(3500, edit.totalCents());
	}

	@Test
	void totalIsSeatCountTimesTheEnteredPrice() {
		SeatBulkEdit edit = new SeatBulkEdit(List.of(seat(1, "A", 1000), seat(2, "B", 1550), seat(3, "C", 0)));

		edit.price().change(1200);

		assertEquals(3600, edit.totalCents());
	}

	@Test
	void totalFollowsTheSeatPricesAgainWhenTheInputIsWithdrawn() {
		SeatBulkEdit edit = new SeatBulkEdit(List.of(seat(1, "A", 1000), seat(2, "B", 1550)));

		edit.price().change(1200);
		edit.price().change(null);

		assertEquals(2550, edit.totalCents());
	}

	@Test
	void totalDoesNotOverflowForManySeats() {
		Booking[] seats = new Booking[3];
		for (int index = 0; index < seats.length; index++) {
			seats[index] = seat(index + 1, "X", Integer.MAX_VALUE);
		}

		assertEquals(3L * Integer.MAX_VALUE, new SeatBulkEdit(List.of(seats)).totalCents());
	}

	@Test
	void seatsAreExposedUnchanged() {
		Booking only = seat(1, "Meier", 1000);

		assertEquals(List.of(only), new SeatBulkEdit(List.of(only)).bookings());
	}
}
