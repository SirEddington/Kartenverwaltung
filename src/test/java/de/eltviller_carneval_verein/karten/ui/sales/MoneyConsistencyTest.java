package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.event.BookingTreeAccess;
import de.eltviller_carneval_verein.karten.ui.sales.SelectionSummary.Line;
import de.eltviller_carneval_verein.karten.util.BookingTotals;

/**
 * Alle Rechner der App, die ohne Oberfläche erreichbar sind, zeigen für dieselben Buchungen dieselben Beträge:
 * {@link BookingTotals} (Event-Übersicht, Statistik, Kassenabgleich), die Knoten der Event-Übersicht
 * ({@code BookingTree}), {@link SelectionSummary} (Kundenfenster) und {@link SeatBulkEdit#totalCents()} (Sitz-Dialog).
 *
 * <p>
 * Ausgangslage, eine Vorstellung mit Standardpreis 25,00 €, der nach zwei Buchungen auf 30,00 € geändert wird:
 * <ol>
 * <li>A1 Sitz 1 "Adler": nur reserviert vor der Preisänderung, behält 25,00 €
 * <li>A1 Sitz 2 "Becker": bar bezahlt vor der Preisänderung, behält 25,00 €
 * <li>A1 Sitz 3 ohne Namen: Karte, Standardpreis 30,00 €
 * <li>A1 Sitz 4 "Dorn": Karte, Einzelpreis 45,00 €
 * <li>A2 Sitz 1 "Ehrlich": Freikarte, Einzelpreis 0, bar
 * <li>"Fuchs" ohne Platz (bisher A2 Sitz 1, wie Ehrlich): Überweisung, Standardpreis 30,00 €
 * <li>A2 Sitz 2 "Graf": nur reserviert, Standardpreis 30,00 €
 * <li>A2 Sitz 3 und 4: leer, Standardpreis 30,00 €
 * </ol>
 */
class MoneyConsistencyTest {

	private static final int OLD_PRICE = 2500;
	private static final int NEW_PRICE = 3000;
	private static final int ABOVE_PRICE = 4500;

	// Von Hand nachgerechnet (Cent)
	private static final int TOTAL_COUNT = 9;
	private static final long POTENTIAL = OLD_PRICE + OLD_PRICE + NEW_PRICE + ABOVE_PRICE + 0 + NEW_PRICE + NEW_PRICE + NEW_PRICE + NEW_PRICE; // 24.500
	private static final int RESERVED_COUNT = 6; // Adler, Becker, Dorn, Ehrlich, Fuchs, Graf
	private static final long EXPECTED = OLD_PRICE + OLD_PRICE + ABOVE_PRICE + 0 + NEW_PRICE + NEW_PRICE; // 15.500
	private static final int PAID_COUNT = 5; // Becker, Karte ohne Namen, Dorn, Ehrlich, Fuchs
	private static final long REVENUE = OLD_PRICE + NEW_PRICE + ABOVE_PRICE + 0 + NEW_PRICE; // 13.000
	private static final int DATA_COUNT = 7; // alle außer den zwei leeren
	private static final long DATA_SUM = OLD_PRICE + OLD_PRICE + NEW_PRICE + ABOVE_PRICE + 0 + NEW_PRICE + NEW_PRICE; // 18.500
	private static final int CASH_COUNT = 2; // Becker, Ehrlich (frei)
	private static final long CASH = OLD_PRICE + 0;
	private static final int CARD_COUNT = 2; // ohne Namen, Dorn
	private static final long CARD = NEW_PRICE + ABOVE_PRICE;
	private static final int TRANSFER_COUNT = 1; // Fuchs
	private static final long TRANSFER = NEW_PRICE;

	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("Prunksitzung");
	private final TestBookings places = new TestBookings();

	private Booking adler;
	private Booking becker;
	private Booking cardWithoutName;
	private Booking dorn;
	private Booking ehrlich;
	private Booking fuchs;
	private Booking graf;

	@BeforeEach
	void setUp() {
		presentation.setPrice(OLD_PRICE);
		adler = places.booking(presentation, "A", 1, 1);
		adler.setLastName("Adler");
		becker = places.booking(presentation, "A", 1, 2);
		becker.setLastName("Becker");
		becker.setPaymentStatus(PaymentStatus.CASH);

		presentation.changePrice(NEW_PRICE);

		cardWithoutName = places.booking(presentation, "A", 1, 3);
		cardWithoutName.setPaymentStatus(PaymentStatus.CARD);
		dorn = places.booking(presentation, "A", 1, 4);
		dorn.setLastName("Dorn");
		dorn.setPaymentStatus(PaymentStatus.CARD);
		dorn.setEffectivePrice(ABOVE_PRICE);
		ehrlich = places.booking(presentation, "A", 2, 1);
		ehrlich.setLastName("Ehrlich");
		ehrlich.setEffectivePrice(0);
		ehrlich.setPaymentStatus(PaymentStatus.CASH);
		fuchs = places.booking(presentation, "A", 2, 1);
		fuchs.setLastName("Fuchs");
		fuchs.setPaymentStatus(PaymentStatus.TRANSFER);
		fuchs.setUnassigned(true);
		graf = places.booking(presentation, "A", 2, 2);
		graf.setLastName("Graf");
		places.booking(presentation, "A", 2, 3);
		places.booking(presentation, "A", 2, 4);
	}

	private List<Booking> all() {
		return presentation.getBookings();
	}

	private List<Booking> where(Predicate<Booking> filter) {
		return all().stream().filter(filter).toList();
	}

	/** Gesamtpreis der Auswahl im Kundenfenster. */
	private long selectionTotal(List<Booking> selected) {
		return SelectionSummary.of(selected, presentation).totalCents();
	}

	/** Gesamtpreis der Auswahl im Sitz-Dialog (ohne neue Eingabe). */
	private static long bulkEditTotal(List<Booking> selected) {
		return new SeatBulkEdit(selected).totalCents();
	}

	@Test
	void die_Ausgangslage_stimmt() {
		assertEquals(TOTAL_COUNT, all().size(), "9 Buchungen");
		assertEquals(OLD_PRICE, adler.getEffectivePrice(), "Adler war vor der Preisänderung reserviert und behält 25 €");
		assertEquals(OLD_PRICE, becker.getEffectivePrice(), "Becker war vor der Preisänderung bezahlt und behält 25 €");
		assertNull(graf.getPrice(), "Graf folgt dem Standardpreis");
		assertEquals(NEW_PRICE, graf.getEffectivePrice(), "Graf zum neuen Standardpreis");
		assertEquals(NEW_PRICE, cardWithoutName.getEffectivePrice(), "Karte ohne Namen zum neuen Standardpreis");
		assertEquals(0, ehrlich.getPrice(), "Freikarte mit Einzelpreis 0, nicht dem Standardpreis");
		assertEquals(DATA_COUNT, where(Booking::hasData).size(), "7 Buchungen mit Daten");
	}

	@Test
	void BookingTotals_liefert_die_von_Hand_gerechneten_Zahlen() {
		BookingTotals totals = BookingTotals.of(all());

		assertEquals(TOTAL_COUNT, totals.total(), "Anzahl gesamt");
		assertEquals(RESERVED_COUNT, totals.reserved(), "Anzahl reserviert");
		assertEquals(PAID_COUNT, totals.paid(), "Anzahl bezahlt");
		assertEquals(POTENTIAL, totals.potentialCents(), "potenziell");
		assertEquals(EXPECTED, totals.expectedCents(), "erwartet");
		assertEquals(REVENUE, totals.revenueCents(), "Einnahmen");
		assertEquals("5 / 6 / 9", totals.countText(), "Anzeige der Anzahlen in der Event-Übersicht");
		assertEquals("130,00 € / 155,00 € / 245,00 €", totals.revenueText(), "Anzeige der Beträge in der Event-Übersicht");
	}

	@Test
	void Kassenabgleich_je_Zahlungsart_ergibt_zusammen_die_Einnahmen() {
		// Der Kassenabgleich (CashReconciliationController.refresh) rechnet je Zahlungsart mit genau diesen Aufrufen
		assertEquals(CASH_COUNT, BookingTotals.paidCount(all(), PaymentStatus.CASH), "Anzahl bar");
		assertEquals(CASH, BookingTotals.paidCents(all(), PaymentStatus.CASH), "bar");
		assertEquals(CARD_COUNT, BookingTotals.paidCount(all(), PaymentStatus.CARD), "Anzahl Karte");
		assertEquals(CARD, BookingTotals.paidCents(all(), PaymentStatus.CARD), "Karte");
		assertEquals(TRANSFER_COUNT, BookingTotals.paidCount(all(), PaymentStatus.TRANSFER), "Anzahl Überweisung");
		assertEquals(TRANSFER, BookingTotals.paidCents(all(), PaymentStatus.TRANSFER), "Überweisung");
		assertEquals(0, BookingTotals.paidCount(all(), PaymentStatus.NONE), "offen zählt nicht als bezahlt");

		BookingTotals totals = BookingTotals.of(all());
		long sumOfMethods = 0;
		int countOfMethods = 0;
		for (PaymentStatus status : PaymentStatus.values()) {
			sumOfMethods += BookingTotals.paidCents(all(), status);
			countOfMethods += BookingTotals.paidCount(all(), status);
		}
		assertEquals(totals.revenueCents(), sumOfMethods, "Summe aller Zahlungsarten (Kassenabgleich) = Einnahmen (Übersicht, Statistik)");
		assertEquals(totals.paid(), countOfMethods, "Anzahl aller Zahlungsarten = Anzahl bezahlt");
	}

	@Test
	void Kundenfenster_und_Sitz_Dialog_rechnen_wie_BookingTotals() {
		assertEquals(POTENTIAL, selectionTotal(all()), "Kundenfenster, alle Buchungen");
		assertEquals(POTENTIAL, bulkEditTotal(all()), "Sitz-Dialog, alle Buchungen");

		List<Booking> withData = where(Booking::hasData);
		assertEquals(DATA_SUM, selectionTotal(withData), "Kundenfenster, alle Buchungen mit Daten");
		assertEquals(DATA_SUM, bulkEditTotal(withData), "Sitz-Dialog, alle Buchungen mit Daten");
		assertEquals(DATA_SUM, BookingTotals.of(withData).potentialCents(), "BookingTotals, alle Buchungen mit Daten");
		assertEquals(DATA_COUNT, SelectionSummary.of(withData, presentation).count(), "Kundenfenster zählt jede Buchung mit Daten einmal");

		List<Booking> paid = where(Booking::isPaid);
		assertEquals(REVENUE, selectionTotal(paid), "Kundenfenster, alle bezahlten = Einnahmen");
		assertEquals(REVENUE, bulkEditTotal(paid), "Sitz-Dialog, alle bezahlten = Einnahmen");

		List<Booking> reserved = where(Booking::isReserved);
		assertEquals(EXPECTED, selectionTotal(reserved), "Kundenfenster, alle reservierten = erwartet");
		assertEquals(EXPECTED, bulkEditTotal(reserved), "Sitz-Dialog, alle reservierten = erwartet");
	}

	@Test
	void Knoten_der_Event_Uebersicht_ergeben_zusammen_die_Vorstellung() {
		// Die Übersicht zeigt je Knoten BookingTotals.of(Buchungen des Knotens); die Knoten liefert BookingTree
		Map<String, List<Booking>> tables = BookingTreeAccess.tables(presentation);
		List<Booking> unassigned = BookingTreeAccess.unassigned(presentation);
		List<List<Booking>> nodes = new ArrayList<>(tables.values());
		nodes.add(unassigned);

		int total = 0;
		int reserved = 0;
		int paid = 0;
		long potential = 0;
		long expected = 0;
		long revenue = 0;
		for (List<Booking> node : nodes) {
			BookingTotals totals = BookingTotals.of(node);
			total += totals.total();
			reserved += totals.reserved();
			paid += totals.paid();
			potential += totals.potentialCents();
			expected += totals.expectedCents();
			revenue += totals.revenueCents();
		}
		assertEquals(TOTAL_COUNT, total, "Knoten zusammen: Anzahl gesamt");
		assertEquals(RESERVED_COUNT, reserved, "Knoten zusammen: Anzahl reserviert");
		assertEquals(PAID_COUNT, paid, "Knoten zusammen: Anzahl bezahlt");
		assertEquals(POTENTIAL, potential, "Knoten zusammen: potenziell");
		assertEquals(EXPECTED, expected, "Knoten zusammen: erwartet");
		assertEquals(REVENUE, revenue, "Knoten zusammen: Einnahmen");

		// Event-Knoten: alle Buchungen aller Vorstellungen
		assertEquals(BookingTotals.of(all()), BookingTotals.of(event.getBookings()), "Event-Knoten = Vorstellungs-Knoten bei einer Vorstellung");
	}

	@Test
	void die_Buchung_ohne_Platz_zaehlt_ueberall_genau_einmal() {
		assertEquals(1, all().stream().filter(b -> b == fuchs).count(), "einmal in der Vorstellung");

		Map<String, List<Booking>> tables = BookingTreeAccess.tables(presentation);
		assertEquals(List.of(fuchs), BookingTreeAccess.unassigned(presentation), "einmal unter Ohne Platz");
		assertFalse(tables.values().stream().anyMatch(bookings -> bookings.contains(fuchs)), "an keinem Tisch, auch nicht am bisherigen A2");
		assertEquals(List.of(ehrlich, graf, all().get(7), all().get(8)), tables.get("Tisch A2"), "Tisch A2 ohne Fuchs");

		List<Line> lines = SelectionSummary.of(all(), presentation).lines();
		assertEquals(TOTAL_COUNT, lines.size(), "Kundenfenster: eine Zeile je Buchung");
		assertEquals(1, lines.stream().filter(line -> line.text().equals("Ohne Platz (bisher Tisch A2, Sitz 1)")).count(), "Kundenfenster: Fuchs genau einmal");
		assertEquals(1, lines.stream().filter(line -> line.text().equals("Tisch A2, Sitz 1")).count(), "Kundenfenster: Ehrlich auf dem echten Sitz genau einmal");

		assertEquals(TOTAL_COUNT, new SeatBulkEdit(all()).bookings().size(), "Sitz-Dialog: jede Buchung einmal");
		assertEquals(TRANSFER_COUNT, BookingTotals.paidCount(all(), PaymentStatus.TRANSFER), "Kassenabgleich: Fuchs einmal bei Überweisung");
		assertEquals(NEW_PRICE, BookingTotals.of(List.of(fuchs)).revenueCents(), "Fuchs zählt mit 30 € zu den Einnahmen");
	}

	@Test
	void die_Freikarte_zaehlt_als_bezahlt_mit_0_Cent() {
		BookingTotals alone = BookingTotals.of(List.of(ehrlich));
		assertTrue(ehrlich.isPaid(), "die Freikarte ist bezahlt");
		assertEquals(1, alone.paid(), "die Freikarte zählt als bezahlt");
		assertEquals(0, alone.revenueCents(), "die Freikarte bringt 0 Cent");
		assertEquals(0, alone.potentialCents(), "die Freikarte zählt nicht zum Standardpreis");

		assertEquals(CASH_COUNT, BookingTotals.paidCount(all(), PaymentStatus.CASH), "Kassenabgleich: die Freikarte zählt bei bar mit");
		assertEquals(OLD_PRICE, BookingTotals.paidCents(all(), PaymentStatus.CASH), "Kassenabgleich: bar ist nur Beckers Karte");

		List<Booking> withoutFree = where(b -> b != ehrlich);
		assertEquals(REVENUE, BookingTotals.of(withoutFree).revenueCents(), "ohne die Freikarte dieselben Einnahmen");
		assertEquals(PAID_COUNT - 1, BookingTotals.of(withoutFree).paid(), "ohne die Freikarte eine bezahlte weniger");
		assertEquals(0, selectionTotal(List.of(ehrlich)), "Kundenfenster: die Freikarte kostet 0");
		assertEquals(0, bulkEditTotal(List.of(ehrlich)), "Sitz-Dialog: die Freikarte kostet 0");
	}
}
