package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

class SearchHitsTest {

	private final Event event = new Event();
	private final Presentation friday = event.addPresentation("Freitag");
	private final Presentation saturday = event.addPresentation("Samstag");
	private final TestBookings bookings = new TestBookings();
	private int seat = 0;

	private Booking booking(Presentation presentation, String lastName, String firstName) {
		Booking booking = bookings.booking(presentation, 1, ++seat);
		booking.setLastName(lastName);
		booking.setFirstName(firstName);
		return booking;
	}

	@Test
	void countTextIsTrefferForEveryNumber() {
		assertEquals("0 Treffer", SearchHits.countText(0));
		assertEquals("1 Treffer", SearchHits.countText(1));
		assertEquals("12 Treffer", SearchHits.countText(12));
	}

	@Test
	void withoutHitsTheTooltipSaysSo() {
		assertEquals("Keine Treffer", SearchHits.tooltipText(List.of(), List.of(friday), true));
	}

	@Test
	void namesAreCountedAlphabeticallyWithoutNameLast() {
		List<Booking> hits = List.of(booking(friday, "Müller", "Hans"), booking(friday, "Müller", "Hans"), booking(friday, "Bauer", null),
				booking(friday, null, null), booking(friday, null, "Eva"), booking(friday, "Mueller", "Hans"));

		assertEquals("""
				Nach Name:
				Bauer: 1
				Eva: 1
				Mueller, Hans: 1
				Müller, Hans: 2
				(ohne Namen): 1""", SearchHits.tooltipText(hits, List.of(friday), false));
	}

	@Test
	void namesDifferingOnlyInCaseAndSpacesAreOneLineInTheFirstSpelling() {
		List<Booking> hits = List.of(booking(friday, " müller ", "hans"), booking(friday, "Müller", "Hans"));

		assertEquals("Nach Name:\nmüller, hans: 2", SearchHits.tooltipText(hits, List.of(friday), false));
	}

	@Test
	void umlautsSortLikeTheirBaseLetter() {
		List<Booking> hits = List.of(booking(friday, "Zander", null), booking(friday, "Ärger", null), booking(friday, "Bauer", null));

		assertEquals("Nach Name:\nÄrger: 1\nBauer: 1\nZander: 1", SearchHits.tooltipText(hits, List.of(friday), false));
	}

	@Test
	void atMostFifteenNamesThenTheRestIsSummarized() {
		List<Booking> hits = new ArrayList<>();
		for (int index = 0; index < SearchHits.MAX_NAMES + 3; index++) {
			hits.add(booking(friday, String.format("Name%02d", index), null));
		}
		hits.add(booking(friday, null, null)); // "(ohne Namen)" steht zuletzt und fällt mit weg

		String[] lines = SearchHits.tooltipText(hits, List.of(friday), false).split("\n");

		assertEquals(1 + SearchHits.MAX_NAMES + 1, lines.length);
		assertEquals("Name14: 1", lines[SearchHits.MAX_NAMES]);
		assertEquals("… und 4 weitere", lines[lines.length - 1]);
	}

	@Test
	void exactlyFifteenNamesHaveNoSummaryLine() {
		List<Booking> hits = new ArrayList<>();
		for (int index = 0; index < SearchHits.MAX_NAMES; index++) {
			hits.add(booking(friday, String.format("Name%02d", index), null));
		}

		assertFalse(SearchHits.tooltipText(hits, List.of(friday), false).contains("weitere"));
	}

	@Test
	void byPresentationListsPresentationsWithHitsInTheirOrder() {
		List<Booking> hits = List.of(booking(saturday, "Müller", null), booking(friday, "Müller", null), booking(saturday, "Bauer", null));
		Presentation empty = event.addPresentation("Sonntag");

		assertEquals("""
				Nach Name:
				Bauer: 1
				Müller: 2

				Nach Vorstellung:
				Freitag: 1
				Samstag: 2""", SearchHits.tooltipText(hits, List.of(friday, saturday, empty), true));
	}

	@Test
	void withoutByPresentationOrWithoutPresentationsThereIsNoSection() {
		List<Booking> hits = List.of(booking(friday, "Müller", null));

		assertFalse(SearchHits.tooltipText(hits, List.of(friday), false).contains("Vorstellung"));
		assertFalse(SearchHits.tooltipText(hits, List.of(), true).contains("Vorstellung"));
		assertTrue(SearchHits.tooltipText(hits, List.of(friday), true).contains("Nach Vorstellung:\nFreitag: 1"));
	}
}
