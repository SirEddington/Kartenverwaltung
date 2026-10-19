package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.JsonProcessingException;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.event.BookingTreeAccess;
import de.eltviller_carneval_verein.karten.util.BookingTotals;

/**
 * Änderungen an der Bestuhlung von Anfang bis Ende, mit echter Speicherung in einem temporären Ordner: Tisch löschen
 * und Reihe neu nummerieren wie im Hallen-Editor, Halle speichern und laden, Abgleich nach dem Laden, Event speichern
 * und laden, schließlich die Bestuhlung löschen. Keine Buchung mit Daten geht verloren, kein Cent ändert sich.
 *
 * <p>
 * Ausgangslage: Reihe A mit den Tischen A1 bis A3 zu je 2 Sitzen, Standardpreis 25,00 €. Gebucht sind A1 Sitz 1 (Karte,
 * Standardpreis, "Schmidt"), A2 Sitz 1 (bar, Einzelpreis 30,00 €, "Müller") und A3 Sitz 2 (nur reserviert, "Weber"),
 * die übrigen drei Sitze sind leer.
 */
class PlanEditsEndToEndTest {

	private static final int DEFAULT_PRICE = 2500;
	private static final int INDIVIDUAL_PRICE = 3000;

	@TempDir
	File storageDir;

	/** Die Ids, über die der Test die gespeicherten Objekte wiederfindet. */
	private record Fixture(String hallId, String planId, String eventId, String seatA1s1, String seatA2s1, String seatA3s2) {
	}

	/** Was eine Buchung an Daten trägt; Momentaufnahme, Sitz-Id und "Ohne Platz" gehören nicht dazu. */
	private record Data(String lastName, String firstName, String eMail, String comment, PaymentStatus paymentStatus, Integer price,
			int effectivePrice, boolean collected) {
		static Data of(Booking booking) {
			return new Data(booking.getLastName(), booking.getFirstName(), booking.getEMail(), booking.getComment(), booking.getPaymentStatus(),
					booking.getPrice(), booking.getEffectivePrice(), booking.isCollected());
		}
	}

	// --- Speicherung ---

	private JsonEventRepository events() {
		return TestRepositories.events(new File(storageDir, "events"));
	}

	private JsonHallRepository halls() {
		return TestRepositories.halls(new File(storageDir, "halls"));
	}

	/** Liest das Event frisch von der Festplatte (neues Repository, kein Cache). */
	private Event loadEvent(String eventId) {
		return events().loadEvents().stream().filter(e -> e.getId().equals(eventId)).findFirst().orElseThrow();
	}

	/** Liest die Halle frisch von der Festplatte (neues Repository, kein Cache). */
	private Hall loadHall(String hallId) {
		Hall hall = halls().findById(hallId);
		assertNotNull(hall, "Die Halle muss gespeichert sein");
		return hall;
	}

	// --- Ausgangslage ---

	/** Legt Halle und Event der Ausgangslage an und speichert beide. */
	private Fixture saveFixture() {
		Hall hall = new Hall();
		hall.setName("Festhalle");
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Prunksitzung");
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 3, 2);
		halls().saveHall(hall);

		Event event = new Event();
		event.setName("Kampagne 2027");
		Presentation presentation = event.addPresentation("Prunksitzung");
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId(plan.getId());
		presentation.setPrice(DEFAULT_PRICE);
		BookingSync.sync(presentation, plan);

		Booking card = booking(presentation, 1, 1);
		card.setLastName("Schmidt");
		card.setPaymentStatus(PaymentStatus.CARD);

		Booking cash = booking(presentation, 2, 1);
		cash.setLastName("Müller");
		cash.setFirstName("Hans");
		cash.setEMail("hans.mueller@example.de");
		cash.setPrice(INDIVIDUAL_PRICE);
		cash.setPaymentStatus(PaymentStatus.CASH);
		cash.setCollected(true);

		Booking reserved = booking(presentation, 3, 2);
		reserved.setLastName("Weber");

		events().saveEvent(event);
		return new Fixture(hall.getId(), plan.getId(), event.getId(), card.getSeatId(), cash.getSeatId(), reserved.getSeatId());
	}

	// --- Hilfen ---

	private static Presentation presentation(Event event) {
		return event.getPresentations().get(0);
	}

	/** Die Buchung mit Platz an "Tisch A{tableNumber}, Sitz {seatNumber}" (genau eine). */
	private static Booking booking(Presentation p, int tableNumber, int seatNumber) {
		List<Booking> found = p.getBookings().stream().filter(b -> !b.isUnassigned() && "A".equals(b.getRowLabel())
				&& b.getTableNumber() == tableNumber && b.getSeatNumber() == seatNumber).toList();
		assertEquals(1, found.size(), "genau eine Buchung an Tisch A" + tableNumber + ", Sitz " + seatNumber);
		return found.get(0);
	}

	private static Booking byLastName(Presentation p, String lastName) {
		List<Booking> found = p.getBookings().stream().filter(b -> lastName.equals(b.getLastName())).toList();
		assertEquals(1, found.size(), "genau eine Buchung von " + lastName);
		return found.get(0);
	}

	private static Table table(SeatingPlan plan, int tableNumber) {
		return plan.getTables().stream().filter(t -> t.getTableNumber() == tableNumber).findFirst().orElseThrow();
	}

	private static Set<Data> dataOf(Presentation p) {
		return p.getBookings().stream().filter(Booking::hasData).map(Data::of).collect(Collectors.toSet());
	}

	/** Abgleich nach dem Laden wie in der App, mit frischem Tracker gegen genau diese Halle. */
	private static ChangeTracker<Event> prepare(Event event, Hall hall) {
		ChangeTracker<Event> tracker = new ChangeTracker<>(Event::getId);
		BookingSyncService.prepare(event, id -> hall.getId().equals(id) ? hall : null, tracker);
		return tracker;
	}

	private static String json(Event event) throws JsonProcessingException {
		return JsonMapperFactory.create().writeValueAsString(event);
	}

	/** Jeder Sitz der Bestuhlung trägt genau eine Buchung mit Platz, und es gibt keine Buchung mit Platz ohne Sitz. */
	private static void assertEverySeatBookedOnce(Presentation p, SeatingPlan plan) {
		for (Seat seat : plan.getSeats()) {
			long count = p.getBookings().stream().filter(b -> !b.isUnassigned() && seat.getId().equals(b.getSeatId())).count();
			assertEquals(1, count, "Sitz " + seat.getSeatNumber() + " an Tisch A" + seat.getParent().getTableNumber() + " braucht genau eine Buchung");
		}
		long assigned = p.getBookings().stream().filter(b -> !b.isUnassigned()).count();
		assertEquals(plan.getSeats().size(), assigned, "Buchungen mit Platz = Sitze der Bestuhlung");
	}

	@Test
	void Bestuhlung_aendern_speichern_laden_und_loeschen_verliert_keine_Buchung_und_keinen_Cent() throws JsonProcessingException {
		Fixture f = saveFixture();

		// a. Stand vorher (aus der Datei gelesen)
		Presentation before = presentation(loadEvent(f.eventId()));
		BookingTotals totalsBefore = BookingTotals.of(before.getBookings());
		Set<Data> dataBefore = dataOf(before);
		assertEquals(6, totalsBefore.total(), "6 Sitze, 6 Buchungen");
		assertEquals(2, totalsBefore.paid(), "bezahlt: Schmidt (Karte), Müller (bar)");
		assertEquals(3, totalsBefore.reserved(), "reserviert: Schmidt, Müller, Weber");
		assertEquals(DEFAULT_PRICE + INDIVIDUAL_PRICE, totalsBefore.revenueCents(), "Einnahmen vorher");
		assertEquals(DEFAULT_PRICE + INDIVIDUAL_PRICE + DEFAULT_PRICE, totalsBefore.expectedCents(), "erwartet vorher");
		assertEquals(5 * DEFAULT_PRICE + INDIVIDUAL_PRICE, totalsBefore.potentialCents(), "potenziell vorher");
		assertEquals(3, dataBefore.size(), "drei Buchungen mit Daten");

		// b. Hallen-Editor: Tisch A2 löschen, Reihe A neu nummerieren (A3 wird A2), speichern, neu laden
		Hall editedHall = loadHall(f.hallId());
		SeatingPlan editedPlan = editedHall.findSeatingPlan(f.planId());
		assertTrue(editedPlan.deleteTable(table(editedPlan, 2)), "Tisch A2 gelöscht");
		editedPlan.renumber(editedPlan.getRows().get(0));
		halls().saveHall(editedHall);

		Hall changedHall = loadHall(f.hallId());
		SeatingPlan changedPlan = changedHall.findSeatingPlan(f.planId());
		assertEquals(List.of(1, 2), changedPlan.getTables().stream().map(Table::getTableNumber).sorted().toList(), "nach dem Neu-Nummerieren: A1, A2");
		assertNull(changedPlan.findSeat(f.seatA2s1()), "der Sitz von Müller ist mit Tisch A2 weg");
		assertEquals(2, changedPlan.findSeat(f.seatA3s2()).getParent().getTableNumber(), "der frühere Tisch A3 heißt jetzt A2");

		// c. Abgleich nach dem Laden gegen die geänderte Halle
		Event event = loadEvent(f.eventId());
		Presentation p = presentation(event);
		ChangeTracker<Event> tracker = prepare(event, changedHall);

		assertFalse(p.isPlanMissing(), "die Bestuhlung gibt es noch");
		assertFalse(tracker.isDirty(event), "der Abgleich ist keine Änderung des Nutzers");
		assertEquals(5, p.getBookings().size(), "die leere Buchung von A2 Sitz 2 ist entfernt, sonst bleibt alles");

		Booking mueller = byLastName(p, "Müller");
		assertTrue(mueller.isUnassigned(), "Müller ist ohne Platz");
		assertEquals(INDIVIDUAL_PRICE, mueller.getPrice(), "Müllers Einzelpreis bleibt");
		assertEquals(PaymentStatus.CASH, mueller.getPaymentStatus(), "Müller bleibt bar bezahlt");
		assertEquals(f.seatA2s1(), mueller.getSeatId(), "die alte Sitz-Id bleibt als Teil der Momentaufnahme");
		assertEquals(2, mueller.getTableNumber(), "Momentaufnahme bisher Tisch A2");
		assertEquals(1, mueller.getSeatNumber(), "Momentaufnahme bisher Sitz 1");

		Booking weber = byLastName(p, "Weber");
		assertFalse(weber.isUnassigned(), "Weber behält den Platz");
		assertEquals(f.seatA3s2(), weber.getSeatId(), "Weber sitzt auf demselben Sitz");
		assertEquals(2, weber.getTableNumber(), "Momentaufnahme aufgefrischt: Tisch A3 heißt jetzt A2");
		assertEquals(2, weber.getSeatNumber(), "Weber bleibt Sitz 2");
		assertEquals("A", weber.getRowLabel(), "Weber bleibt in Reihe A");

		Booking schmidt = byLastName(p, "Schmidt");
		assertFalse(schmidt.isUnassigned(), "Schmidt behält den Platz");
		assertEquals(f.seatA1s1(), schmidt.getSeatId(), "Schmidt sitzt auf demselben Sitz");
		assertEquals(1, schmidt.getTableNumber(), "Schmidt bleibt an Tisch A1");

		assertEverySeatBookedOnce(p, changedPlan);
		assertEquals(dataBefore, dataOf(p), "keine Buchung mit Daten verloren oder verändert");

		BookingTotals totalsAfter = BookingTotals.of(p.getBookings());
		assertEquals(totalsBefore.revenueCents(), totalsAfter.revenueCents(), "Einnahmen unverändert");
		assertEquals(totalsBefore.expectedCents(), totalsAfter.expectedCents(), "erwartet unverändert");
		assertEquals(totalsBefore.potentialCents() - DEFAULT_PRICE, totalsAfter.potentialCents(), "potenziell: nur die entfernte leere Buchung fehlt");
		assertEquals(4 * DEFAULT_PRICE + INDIVIDUAL_PRICE, totalsAfter.potentialCents(), "potenziell nachgerechnet: vier Buchungen zu 25 € und Müller zu 30 €");
		assertEquals(totalsBefore.paid(), totalsAfter.paid(), "Anzahl bezahlt unverändert");
		assertEquals(totalsBefore.reserved(), totalsAfter.reserved(), "Anzahl reserviert unverändert");
		assertEquals(5, totalsAfter.total(), "Anzahl gesamt: 4 Sitze und Müller");

		// d. Event speichern, laden, nochmals abgleichen: nichts ändert sich mehr
		String jsonAfterSync = json(event);
		events().saveEvent(event);
		Event reloaded = loadEvent(f.eventId());
		Presentation reloadedPresentation = presentation(reloaded);
		assertTrue(byLastName(reloadedPresentation, "Müller").isUnassigned(), "Ohne Platz steht in der Datei, schon vor dem Abgleich");

		ChangeTracker<Event> reloadedTracker = prepare(reloaded, changedHall);
		assertFalse(reloadedTracker.isDirty(reloaded), "der zweite Abgleich ist keine Änderung");
		assertEquals(jsonAfterSync, json(reloaded), "der zweite Abgleich ändert nichts");
		assertEquals(totalsAfter, BookingTotals.of(reloadedPresentation.getBookings()), "dieselben Zahlen nach Speichern und Laden");

		// e. Hallen-Editor löscht die Bestuhlung: erst einfrieren, dann entfernen und speichern
		Hall editorHall = loadHall(f.hallId());
		SeatingPlan doomed = editorHall.findSeatingPlan(f.planId());
		Event stored = loadEvent(f.eventId());
		JsonEventRepository eventRepository = events();
		List<Event> saved = PlanFreezer.freeze(List.of(stored), presentation -> doomed.getId().equals(presentation.getSeatingPlanId()),
				id -> editorHall.getId().equals(id) ? editorHall : null, new ChangeTracker<>(Event::getId), eventRepository::saveEvent);
		assertEquals(List.of(stored), saved, "das betroffene Event wird vor dem Löschen gespeichert");
		assertTrue(editorHall.getSeatingPlans().remove(doomed), "Bestuhlung entfernt");
		halls().saveHall(editorHall);

		Hall hallWithoutPlan = loadHall(f.hallId());
		assertNull(hallWithoutPlan.findSeatingPlan(f.planId()), "die Bestuhlung ist auch in der Datei weg");
		Event frozen = loadEvent(f.eventId());
		Presentation frozenPresentation = presentation(frozen);
		prepare(frozen, hallWithoutPlan);

		assertTrue(frozenPresentation.isPlanMissing(), "die Vorstellung ist eingefroren");
		assertEquals(f.planId(), frozenPresentation.getSeatingPlanId(), "die Vorstellung zeigt weiter auf die gelöschte Bestuhlung");
		assertEquals(jsonAfterSync, json(frozen), "Einfrieren, Löschen und Laden ändern keine Buchung");
		assertEquals(5, frozenPresentation.getBookings().size(), "alle Buchungen bleiben");
		Booking frozenMueller = byLastName(frozenPresentation, "Müller");
		assertTrue(frozenMueller.isUnassigned(), "Müller bleibt ohne Platz");
		assertEquals(dataBefore, dataOf(frozenPresentation), "keine Buchung mit Daten verloren oder verändert");
		assertEquals(totalsAfter, BookingTotals.of(frozenPresentation.getBookings()), "dieselben Zahlen nach dem Löschen der Bestuhlung");

		Map<String, List<Booking>> tables = BookingTreeAccess.tables(frozenPresentation);
		assertEquals(List.of("Tisch A1", "Tisch A2"), new ArrayList<>(tables.keySet()), "die Tische mit Platz aus der Momentaufnahme");
		assertEquals(2, tables.get("Tisch A1").size(), "Tisch A1 mit 2 Sitzen");
		assertEquals(2, tables.get("Tisch A2").size(), "Tisch A2 (früher A3) mit 2 Sitzen");
		assertTrue(tables.get("Tisch A2").contains(byLastName(frozenPresentation, "Weber")), "Weber an Tisch A2");
		assertFalse(tables.values().stream().anyMatch(bookings -> bookings.contains(frozenMueller)), "Müller an keinem Tisch");
		assertEquals(List.of(frozenMueller), BookingTreeAccess.unassigned(frozenPresentation), "Müller unter Ohne Platz");
	}
}
