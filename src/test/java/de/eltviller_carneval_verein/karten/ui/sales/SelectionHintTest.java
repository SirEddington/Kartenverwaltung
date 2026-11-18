package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.sales.SelectionHint.Conditions;
import javafx.geometry.Point2D;

/** Der Hinweis zur Auswahl am Mauszeiger (KARV-194): Text, wann er zu sehen ist und wo er steht. */
class SelectionHintTest {

	private final Event event = new Event();
	private final Presentation saturday = event.addPresentation("Samstag");
	private final Presentation sunday = event.addPresentation("Sonntag");
	private final TestBookings bookings = new TestBookings();

	private Booking seat(Presentation in, int seatNumber, int priceCents) {
		Booking booking = bookings.booking(in, 1, seatNumber);
		booking.setPrice(priceCents);
		return booking;
	}

	@Test
	void oneCardNamesTheSingularAndItsPrice() {
		assertEquals("1 Karte gewählt · 12,00 €", SelectionHint.text(List.of(seat(saturday, 1, 1200))));
	}

	@Test
	void severalCardsAddUpTheirPrices() {
		List<Booking> selected = List.of(seat(saturday, 1, 1200), seat(saturday, 2, 1500), seat(saturday, 3, 2500));

		assertEquals("3 Karten gewählt · 52,00 €", SelectionHint.text(selected));
	}

	@Test
	void cardsOfOtherPresentationsCountLikeInTheContextMenu() {
		// Tabelle mit "Alle Vorstellungen": Das Kontextmenü zählt alle gewählten Zeilen, der Hinweis ebenso
		List<Booking> selected = List.of(seat(saturday, 1, 1000), seat(sunday, 1, 2000));

		assertEquals("2 Karten gewählt · 30,00 €", SelectionHint.text(selected));
	}

	@Test
	void theStandardPriceAppliesWithoutASinglePrice() {
		sunday.setPrice(2200);

		assertEquals("1 Karte gewählt · 22,00 €", SelectionHint.text(List.of(bookings.booking(sunday, 2, 1))));
	}

	@Test
	void aFreeCardShowsZero() {
		assertEquals("1 Karte gewählt · 0,00 €", SelectionHint.text(List.of(seat(saturday, 1, 0))));
	}

	@Test
	void largeSumsDoNotOverflow() {
		List<Booking> selected = List.of(seat(saturday, 1, Integer.MAX_VALUE), seat(saturday, 2, Integer.MAX_VALUE));

		assertEquals("2 Karten gewählt · 42.949.672,94 €", SelectionHint.text(selected));
	}

	private static final Conditions ALL = new Conditions(true, true, true, true, false);

	@Test
	void visibleOnlyWhenEveryConditionHolds() {
		assertTrue(ALL.visible());
		assertFalse(new Conditions(false, true, true, true, false).visible(), "Schalter aus");
		assertFalse(new Conditions(true, false, true, true, false).visible(), "keine Auswahl");
		assertFalse(new Conditions(true, true, false, true, false).visible(), "Zeiger nicht über dem Inhaltsbereich");
		assertFalse(new Conditions(true, true, true, false, false).visible(), "Hauptfenster ohne Fokus");
		assertFalse(new Conditions(true, true, true, true, true).visible(), "Dialog oder Menü offen");
	}

	@Test
	void standsRightAboveThePointer() {
		Point2D corner = SelectionHint.place(200, 300, 120, 24, 1000, 800);

		assertEquals(new Point2D(216, 268), corner, "linke Kante 16 px rechts, untere Kante 8 px über dem Zeiger");
	}

	@Test
	void movesLeftOfThePointerAtTheRightEdge() {
		Point2D corner = SelectionHint.place(950, 300, 120, 24, 1000, 800);

		assertEquals(new Point2D(822, 268), corner, "rechte Kante 8 px links vom Zeiger");
	}

	@Test
	void movesLeftBelowThePointerAtTheTopEdge() {
		// Rechts unter dem Zeiger erschiene der Tooltip eines Sitzes und verdeckte den Hinweis
		Point2D corner = SelectionHint.place(200, 10, 120, 24, 1000, 800);

		assertEquals(new Point2D(72, 30), corner, "rechte Kante 8 px links vom Zeiger, obere Kante 20 px darunter");
	}

	@Test
	void topRightCornerUsesLeftBelow() {
		Point2D corner = SelectionHint.place(990, 5, 120, 24, 1000, 800);

		assertEquals(new Point2D(862, 25), corner);
	}

	@Test
	void neverLeavesTheAreaAtTheBottom() {
		// Sehr niedriger Inhaltsbereich, Zeiger oben: links unter dem Zeiger, aber nicht unter den Rand
		Point2D corner = SelectionHint.place(200, 10, 120, 24, 1000, 40);

		assertEquals(new Point2D(72, 16), corner);
	}

	@Test
	void neverLeavesTheAreaToTheLeftOrTop() {
		// Bereich schmaler als der Hinweis: Er beginnt am linken Rand statt außerhalb
		Point2D corner = SelectionHint.place(60, 10, 120, 24, 100, 800);

		assertEquals(new Point2D(0, 30), corner);
	}
}
