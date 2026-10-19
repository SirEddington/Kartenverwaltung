package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

class BookingSearchTest {

	private final Presentation presentation = new Event().addPresentation("Prunksitzung");
	private final TestBookings bookings = new TestBookings();

	private Booking booking(String firstName, String lastName, String comment) {
		Booking booking = bookings.booking(presentation, 1, 1);
		booking.setFirstName(firstName);
		booking.setLastName(lastName);
		booking.setComment(comment);
		return booking;
	}

	@Test
	void anEmptyQueryFindsEverything() {
		assertTrue(BookingSearch.matches(booking(null, null, null), ""));
		assertTrue(BookingSearch.matches(booking("Hans", "Wurst", null), "   "));
		assertTrue(BookingSearch.matches(booking("Hans", "Wurst", null), null));
	}

	@Test
	void eachFieldIsFoundOnItsOwn() {
		Booking hans = booking("Hans", "Wurst", "Idiot");

		assertTrue(BookingSearch.matches(hans, "hans"));
		assertTrue(BookingSearch.matches(hans, "wurst"));
		assertTrue(BookingSearch.matches(hans, "idiot"));
		assertTrue(BookingSearch.matches(hans, "ur"), "Ein Teil eines Feldes genügt");
	}

	@Test
	void theQueryMayRunAcrossFields() {
		Booking hans = booking("Hans", "Wurst", "Idiot");

		assertTrue(BookingSearch.matches(hans, "Hans Wurst Idiot"));
		assertTrue(BookingSearch.matches(hans, "Hans Wurst"));
		assertTrue(BookingSearch.matches(hans, "ns Wurs"), "Auch Teile von Wörtern über die Feldgrenze");
		assertTrue(BookingSearch.matches(hans, "st Idi"));
	}

	@Test
	void theOrderOfTheWordsDoesNotMatter() {
		assertTrue(BookingSearch.matches(booking("Hans", "Wurst", null), "Wurst Hans"));
	}

	@Test
	void caseAndSurroundingSpacesDoNotMatter() {
		assertTrue(BookingSearch.matches(booking("Hans", "Wurst", null), "  HANS   wurst "));
		assertTrue(BookingSearch.matches(booking("Jörg", "Müller", null), "JÖRG müLLER"));
	}

	@Test
	void everyWordMustBeFound() {
		Booking hans = booking("Hans", "Wurst", "Idiot");

		assertFalse(BookingSearch.matches(hans, "Hans Meier"));
		assertFalse(BookingSearch.matches(hans, "Peter"));
	}

	@Test
	void missingFieldsAreSkipped() {
		assertTrue(BookingSearch.matches(booking(null, "Wurst", null), "wurst"));
		assertFalse(BookingSearch.matches(booking(null, null, null), "hans"), "Eine Buchung ohne Daten findet keine Suche");
	}
}
