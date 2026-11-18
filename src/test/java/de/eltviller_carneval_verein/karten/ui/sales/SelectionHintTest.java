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

	// Lage (KARV-197): Hinweis 120 x 24, Inhaltsbereich 1000 x 800, wenn nicht anders angegeben

	private static Point2D above(double pointerX, double pointerY, double areaWidth, double areaHeight) {
		return SelectionHint.place(SelectionHintPlacement.ABOVE, true, pointerX, pointerY, 120, 24, areaWidth, areaHeight);
	}

	private static Point2D below(double pointerX, double pointerY, double areaWidth, double areaHeight) {
		return SelectionHint.place(SelectionHintPlacement.BELOW, true, pointerX, pointerY, 120, 24, areaWidth, areaHeight);
	}

	/** Mit ausgeschaltetem "Am Rand ausweichen": Die Seite bleibt, der Hinweis wird nur bis an den Rand geschoben. */
	private static Point2D stay(SelectionHintPlacement placement, double pointerX, double pointerY) {
		return SelectionHint.place(placement, false, pointerX, pointerY, 120, 24, 1000, 800);
	}

	@Test
	void withoutAvoidingEdgesTheNormalCaseIsUnchanged() {
		assertEquals(new Point2D(216, 268), stay(SelectionHintPlacement.ABOVE, 200, 300));
		assertEquals(new Point2D(216, 320), stay(SelectionHintPlacement.BELOW, 200, 300));
	}

	@Test
	void withoutAvoidingEdgesItStopsAtTheRightEdgeInsteadOfMovingLeft() {
		assertEquals(new Point2D(880, 268), stay(SelectionHintPlacement.ABOVE, 950, 300), "rechte Kante am Rand, weiter über dem Zeiger");
	}

	@Test
	void withoutAvoidingEdgesAboveStaysAboveAtTheTopEdge() {
		assertEquals(new Point2D(216, 0), stay(SelectionHintPlacement.ABOVE, 200, 10), "obere Kante am Rand statt unter den Zeiger");
	}

	@Test
	void withoutAvoidingEdgesBelowStaysBelowAtTheBottomEdge() {
		assertEquals(new Point2D(216, 776), stay(SelectionHintPlacement.BELOW, 200, 790), "untere Kante am Rand statt über den Zeiger");
	}

	@Test
	void withoutAvoidingEdgesTheCornerCoversThePointer() {
		// Vom Entwickler hingenommen: In der Ecke auf seiner Seite liegt der Hinweis auf der Zeigerspitze
		assertEquals(new Point2D(880, 776), stay(SelectionHintPlacement.BELOW, 990, 790));
	}

	@Test
	void aboveStandsRightAboveThePointer() {
		assertEquals(new Point2D(216, 268), above(200, 300, 1000, 800), "linke Kante 16 px rechts, untere Kante 8 px über dem Zeiger");
	}

	@Test
	void aboveMovesLeftOfThePointerAtTheRightEdge() {
		assertEquals(new Point2D(822, 268), above(950, 300, 1000, 800), "rechte Kante 8 px links vom Zeiger");
	}

	@Test
	void aboveMovesRightBelowThePointerAtTheTopEdge() {
		assertEquals(new Point2D(216, 30), above(200, 10, 1000, 800), "obere Kante 20 px unter dem Zeiger, weiter rechts davon");
	}

	@Test
	void aboveUsesLeftBelowOnlyInTheTopRightCorner() {
		assertEquals(new Point2D(862, 25), above(990, 5, 1000, 800));
	}

	@Test
	void belowStandsRightBelowThePointer() {
		assertEquals(new Point2D(216, 320), below(200, 300, 1000, 800), "linke Kante 16 px rechts, obere Kante 20 px unter dem Zeiger");
	}

	@Test
	void belowMovesLeftOfThePointerAtTheRightEdge() {
		assertEquals(new Point2D(822, 320), below(950, 300, 1000, 800));
	}

	@Test
	void belowMovesAboveThePointerAtTheBottomEdge() {
		assertEquals(new Point2D(216, 758), below(200, 790, 1000, 800), "untere Kante 8 px über dem Zeiger");
	}

	@Test
	void belowUsesLeftAboveInTheBottomRightCorner() {
		assertEquals(new Point2D(862, 758), below(990, 790, 1000, 800));
	}

	@Test
	void belowStaysBelowAtTheTopEdge() {
		assertEquals(new Point2D(216, 25), below(200, 5, 1000, 800));
	}

	@Test
	void neverLeavesTheAreaAtTheBottom() {
		// Sehr niedriger Inhaltsbereich: Auf keiner Seite passt er ganz, er bleibt trotzdem im Bereich
		assertEquals(new Point2D(216, 16), above(200, 10, 1000, 40), "oben nicht, also darunter, am unteren Rand begrenzt");
		assertEquals(new Point2D(216, 0), below(200, 10, 1000, 40), "unten nicht, also darüber, am oberen Rand begrenzt");
	}

	@Test
	void neverLeavesTheAreaToTheLeftOrTop() {
		// Bereich schmaler bzw. niedriger als der Hinweis: Er beginnt am linken bzw. oberen Rand statt außerhalb
		assertEquals(new Point2D(0, 30), above(60, 10, 100, 800));
		assertEquals(new Point2D(0, 0), below(60, 10, 100, 20));
	}
}
