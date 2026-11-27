package de.eltviller_carneval_verein.karten.ui.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.LockedCashRegister;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.ui.accounting.CashFigures.Figures;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;

class CashFiguresTest {

	private static final String SHARED_HINT = "Für dieses Event gilt eine gemeinsame Kasse. Der Kassensturz je Kasse folgt.";
	private static final String TOTAL_HINT = "Summe der Ist-Beträge aller Kassen des Events, hier nicht direkt bearbeitbar.";
	private static final String SECOND_PREFIX = "Zweiter Ist-Betrag aus einer älteren Programmversion: ";

	private final Event event = new Event();
	private final Presentation first = event.addPresentation("1. Sitzung");
	private final Presentation second = event.addPresentation("2. Sitzung");

	private Figures of(Presentation presentation) {
		return of(presentation, Map.of(), false);
	}

	private Figures of(Presentation presentation, Map<String, LockedCashRegister> locked, boolean pending) {
		return CashFigures.of(event, presentation, locked, pending, true);
	}

	private static Map<String, LockedCashRegister> locked(String id, Status status, boolean readable) {
		return Map.of(id, new LockedCashRegister(id, status, readable));
	}

	@Test
	void presentationShowsEffectiveState() {
		// Bestand aus v1.1: nur die alten Felder
		first.setActualCashAmount(1250);
		first.setCashDifferenceComment("Wechselgeld");

		Figures old = of(first);
		assertEquals(1250L, old.actualCents());
		assertEquals("Wechselgeld", old.comment());
		assertTrue(old.editable());
		assertNull(old.hint());
		assertNull(old.secondAmountHint());
		assertFalse(old.discardable());

		// Nach der ersten Eingabe gilt die Kasse
		CashRegisters.setActualCash(event, first, 3000);
		Figures counted = of(first);
		assertEquals(3000L, counted.actualCents());
		assertEquals("Wechselgeld", counted.comment());
		assertTrue(counted.editable());

		assertFalse(CashFigures.of(event, first, Map.of(), false, false).editable(), "Anzeigemodus");
	}

	@Test
	void sharedSelectedShowsDashAndLocks() {
		CashRegisters.selectShared(event, true);

		Figures figures = of(first);
		assertNull(figures.actualCents());
		assertNull(figures.comment());
		assertFalse(figures.editable());
		assertEquals(SHARED_HINT, figures.hint());

		// Eine gesperrte gemeinsame Kasse, die im Modell fehlt, gilt ebenso als gewählt
		event.removeCashRegister(CashRegister.SHARED_ID);
		Figures locked = of(first, locked(CashRegister.SHARED_ID, Status.LOCKED_FORMAT, false), false);
		assertNull(locked.actualCents());
		assertFalse(locked.editable());
		assertEquals(SHARED_HINT, locked.hint());
	}

	@Test
	void sharedSelectedShowsCountedPresentationRegisterLocked() {
		CashRegisters.setActualCash(event, first, 1250);
		CashRegisters.setComment(event, first, "Wechselgeld");
		CashRegisters.selectShared(event, true);

		Figures figures = of(first);
		assertEquals(1250L, figures.actualCents());
		assertEquals("Wechselgeld", figures.comment());
		assertFalse(figures.editable());
		assertEquals(SHARED_HINT, figures.hint());

		// Auch nur in den alten Feldern gezählt
		second.setActualCashAmount(700);
		assertEquals(700L, of(second).actualCents());
		assertFalse(of(second).editable());
	}

	@Test
	void lockedUnreadableShowsDash() {
		// Die alten Felder dürfen nicht durchscheinen, wenn die Kasse gesperrt und unlesbar ist
		first.setActualCashAmount(1250);
		first.setCashDifferenceComment("alt");
		Map<String, LockedCashRegister> unreadable = locked(first.getId(), Status.LOCKED_DAMAGED, false);

		Figures figures = of(first, unreadable, false);
		assertNull(figures.actualCents());
		assertNull(figures.comment());
		assertFalse(figures.editable());
		assertEquals(StoreMessages.locked("Kasse \"Kasse 1. Sitzung\"", Status.LOCKED_DAMAGED), figures.hint());

		// Sie zählt auch nicht in die Gesamtbilanz
		second.setActualCashAmount(700);
		assertEquals(700L, of(null, unreadable, false).actualCents());

		// Gesperrt mit lesbarem Stand (wartend): der Stand, gesperrt, mit der Meldung
		CashRegisters.setActualCash(event, first, 500);
		Figures waiting = of(first, locked(first.getId(), Status.WAITING, true), false);
		assertEquals(500L, waiting.actualCents());
		assertFalse(waiting.editable());
		assertTrue(waiting.hint().startsWith("Kasse \"Kasse 1. Sitzung\" wartet noch auf eine Datei"), waiting.hint());
	}

	@Test
	void pendingShowsDashInTotal() {
		first.setActualCashAmount(1250);

		Figures total = of(null, Map.of(), true);
		assertNull(total.actualCents());
		assertFalse(total.editable());
		assertEquals(StoreEventRepository.CASH_PENDING, total.hint());

		Figures presentation = of(first, Map.of(), true);
		assertNull(presentation.actualCents());
		assertNull(presentation.comment());
		assertFalse(presentation.editable());
		assertEquals(StoreEventRepository.CASH_PENDING, presentation.hint());
	}

	@Test
	void totalSumsVisibleRegistersAndHint() {
		first.setActualCashAmount(1250); // alte Felder
		CashRegisters.setActualCash(event, second, 300);
		CashRegister free = CashRegisters.addFree(event);
		free.setActualCashAmount(2000);
		Presentation gone = event.addPresentation("Generalprobe");
		CashRegisters.setActualCash(event, gone, 50);
		event.deletePresentation(gone); // bleibt als verwaiste Kasse

		Figures total = of(null);
		assertEquals(3600L, total.actualCents());
		assertNull(total.comment());
		assertFalse(total.editable());
		assertEquals(TOTAL_HINT, total.hint());
		assertFalse(total.discardable());

		// Mit gemeinsamer Kasse: sie und die vorher gezählten Kassen
		CashRegisters.selectShared(event, true);
		event.findCashRegister(CashRegister.SHARED_ID).setActualCashAmount(10_000);
		assertEquals(13_600L, of(null).actualCents());
	}

	@Test
	void totalWithOnlyOrphanHasZeroSoll() {
		Event empty = new Event();
		assertFalse(CashFigures.shown(empty), "ohne Vorstellung und ohne Kasse bleibt die Ansicht leer");

		CashRegister orphan = new CashRegister(UUID.randomUUID().toString());
		orphan.setName("Kasse Generalprobe");
		orphan.setActualCashAmount(5000);
		empty.putCashRegister(orphan);

		assertTrue(CashFigures.shown(empty));
		assertEquals(List.of(), CashFigures.scope(empty, null), "kein Soll");
		Figures total = CashFigures.of(empty, null, Map.of(), false, true);
		assertEquals(5000L, total.actualCents());
		assertEquals(TOTAL_HINT, total.hint());
	}

	@Test
	void secondAmountHintAndDiscardEvenWhenShared() {
		CashRegisters.setActualCash(event, first, 1000);
		// ein Rechner mit v1.1 hat danach noch in die alten Felder geschrieben
		first.setActualCashAmount(250);

		Figures figures = of(first);
		assertEquals(1000L, figures.actualCents());
		assertEquals(SECOND_PREFIX + MoneyFormat.formatCents(250), figures.secondAmountHint());
		assertTrue(figures.discardable());

		first.setCashDifferenceComment("aus 1.1");
		assertEquals(SECOND_PREFIX + MoneyFormat.formatCents(250) + " („aus 1.1“)", of(first).secondAmountHint());

		CashRegisters.selectShared(event, true);
		Figures shared = of(first);
		assertFalse(shared.editable());
		assertEquals(SHARED_HINT, shared.hint());
		assertEquals(SECOND_PREFIX + MoneyFormat.formatCents(250) + " („aus 1.1“)", shared.secondAmountHint());
		assertTrue(shared.discardable(), "KAS-005 lässt sich immer beheben");

		Figures display = CashFigures.of(event, first, Map.of(), false, false);
		assertNotNull(display.secondAmountHint());
		assertFalse(display.discardable(), "„Verwerfen“ nur im Bearbeiten-Modus");

		CashRegisters.discardSecondAmount(first);
		assertNull(of(first).secondAmountHint());
		assertFalse(of(first).discardable());
	}
}
