package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/** KARV-215: "frei: n von m" über dem Auswahlfeld "Vorstellung" der Verkaufsansicht. */
class FreeSeatsTest {

	private final Event event = new Event();
	private final TestBookings bookings = new TestBookings();

	@AfterEach
	void forget() {
		LockedSeats.reset();
	}

	/** Eine Vorstellung des Events mit {@code seats} freien Sitzen an einem Tisch: je Sitz eine leere Buchung. */
	private Presentation presentation(String name, int seats) {
		Presentation presentation = event.addPresentation(name);
		for (int number = 1; number <= seats; number++) {
			bookings.booking(presentation, 1, number);
		}
		return presentation;
	}

	private static Booking seat(Presentation presentation, int index) {
		return presentation.getBookings().get(index);
	}

	private void lock(Presentation presentation, Booking booking) {
		UnitKey unit = UnitKey.booking(event.getId(), presentation.getId(), booking.getSeatId());
		LockedSeats.install(() -> Set.of(unit), () -> {
		});
	}

	@Test
	void reservedAndPaidSeatsCountOnlyInTotal() {
		Presentation saturday = presentation("Samstag", 4);
		seat(saturday, 0).setLastName("Meier");
		seat(saturday, 1).setPaymentStatus(PaymentStatus.CASH);

		assertEquals(new FreeSeats.Count(2, 4, 0), FreeSeats.count(saturday));
	}

	@Test
	void seatWithLeftoversButNoLastNameIsFree() {
		Presentation saturday = presentation("Samstag", 2);
		seat(saturday, 0).setFirstName("Eva");
		seat(saturday, 0).setComment("storniert");

		assertEquals(new FreeSeats.Count(2, 2, 0), FreeSeats.count(saturday));
	}

	@Test
	void bookingWithoutPlaceCountsNowhere() {
		Presentation saturday = presentation("Samstag", 2);
		Booking withoutPlace = saturday.addBooking();
		withoutPlace.setUnassigned(true);
		withoutPlace.setLastName("Meier");
		Booking emptyWithoutPlace = saturday.addBooking();
		emptyWithoutPlace.setUnassigned(true);

		assertEquals(new FreeSeats.Count(2, 2, 0), FreeSeats.count(saturday));
	}

	@Test
	void lockedSeatCountsNeitherAsFreeNorInTotal() {
		Presentation saturday = presentation("Samstag", 3);
		lock(saturday, seat(saturday, 1));

		assertEquals(new FreeSeats.Count(2, 2, 1), FreeSeats.count(saturday));
	}

	@Test
	void presentationWithoutBookingListCountsNothing() {
		Presentation saturday = event.addPresentation("Samstag");
		saturday.setBookings(null);

		assertEquals(new FreeSeats.Count(0, 0, 0), FreeSeats.count(saturday));
	}

	@Test
	void selectedPresentationIsTheOnlyOneCounted() {
		Presentation friday = presentation("Freitag", 2);
		presentation("Samstag", 3);

		assertEquals(List.of(friday), FreeSeats.counted(event, friday));
	}

	@Test
	void withoutSelectionAllPresentationsOfTheEventAreCounted() {
		Presentation friday = presentation("Freitag", 2);
		Presentation saturday = presentation("Samstag", 3);

		assertEquals(List.of(friday, saturday), FreeSeats.counted(event, null));
	}

	@Test
	void eventWithoutPresentationListCountsNoPresentation() {
		event.setPresentations(null);

		assertEquals(List.of(), FreeSeats.counted(event, null));
	}

	@Test
	void sumAddsFreeTotalAndLockedOfAllPresentations() {
		Presentation friday = presentation("Freitag", 2);
		Presentation saturday = presentation("Samstag", 3);
		seat(friday, 0).setLastName("Meier");
		lock(saturday, seat(saturday, 0));

		assertEquals(new FreeSeats.Count(3, 4, 1), FreeSeats.count(List.of(friday, saturday)));
	}

	@Test
	void labelTextNamesFreeOfTotal() {
		assertEquals("frei: 0 von 0", FreeSeats.labelText(new FreeSeats.Count(0, 0, 0)));
		assertEquals("frei: 42 von 180", FreeSeats.labelText(new FreeSeats.Count(42, 180, 3)));
	}

	@Test
	void tooltipHasOneLineForASinglePresentation() {
		Presentation saturday = presentation("Samstag", 3);
		seat(saturday, 0).setLastName("Meier");

		assertEquals("Samstag: 2 von 3", FreeSeats.tooltipText(List.of(saturday)));
	}

	@Test
	void tooltipListsPresentationsInTheGivenOrder() {
		Presentation saturday = presentation("Samstag", 3);
		Presentation friday = presentation("Freitag", 2);
		seat(friday, 0).setLastName("Meier");

		assertEquals("""
				Samstag: 3 von 3
				Freitag: 1 von 2""", FreeSeats.tooltipText(List.of(saturday, friday)));
	}

	@Test
	void tooltipNamesLockedSeatsOnlyWhenThereAreSome() {
		Presentation friday = presentation("Freitag", 2);
		Presentation saturday = presentation("Samstag", 3);
		lock(saturday, seat(saturday, 2));

		assertEquals("""
				Freitag: 2 von 2
				Samstag: 2 von 2

				Gesperrt, nicht mitgezählt: 1""", FreeSeats.tooltipText(List.of(friday, saturday)));
	}

	@Test
	void tooltipUsesThePlaceholderForAPresentationWithoutName() {
		Presentation unnamed = presentation("", 1);

		assertEquals(EntityLabels.nameOrPlaceholder("") + ": 1 von 1", FreeSeats.tooltipText(List.of(unnamed)));
	}

	@Test
	void tooltipWithoutPresentationSaysSo() {
		assertEquals("Keine Vorstellung", FreeSeats.tooltipText(List.of()));
	}
}
