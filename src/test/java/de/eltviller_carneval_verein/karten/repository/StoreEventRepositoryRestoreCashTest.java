package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.CashRegisters.CashState;
import de.eltviller_carneval_verein.karten.model.CashRegisters.VisibleCashRegister;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.NioFileLayer;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestClock;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Wiederherstellen lässt Kassen stehen (KARV-144): Kassen-Einheiten behalten ihren heutigen Stand, alte Felder ihren
 * heutigen Wert, eine entfallende Vorstellung lässt ihre Kasse verwaist zurück; dazu Speicherstände und die Liste
 * „seit diesem Stand gezählt", am echten Versionsspeicher.
 */
class StoreEventRepositoryRestoreCashTest {

	@TempDir
	Path appData;

	private final TestClock clock = new TestClock();
	private SyncService sync;
	private StoreEventRepository repository;

	// Bauteile -->

	private void start() {
		sync = TestSyncServices.singleMode(appData, clock);
		sync.startup(null, p -> {
		});
		repository = TestRepositories.events(sync);
	}

	/** Nach einem Neustart: das Event, wie es gespeichert ist. */
	private Event reloaded() {
		SyncService restarted = TestSyncServices.singleMode(appData, clock);
		restarted.startup(null, p -> {
		});
		return TestRepositories.events(restarted).loadEvents().get(0);
	}

	private void later() {
		clock.advance(Duration.ofMinutes(10));
	}

	private static Event named(String name, String... presentations) {
		Event event = new Event();
		event.setName(name);
		for (String presentation : presentations) {
			event.addPresentation(presentation);
		}
		return event;
	}

	private static Presentation presentation(Event event, String name) {
		return event.getPresentations().stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
	}

	/** Alte Felder, wie ein Rechner mit v1.1 sie schreibt (ohne Kassen-Objekt). */
	private static void oldFields(Presentation presentation, int cents, String comment) {
		presentation.setActualCashAmount(cents);
		presentation.setCashDifferenceComment(comment);
	}

	private SavePoint newest(Event event) throws IOException {
		return repository.savePoints(event.getId()).get(0);
	}

	private Event restore(Event event, SavePoint point) throws IOException {
		later();
		return repository.restoreEvent(event.getId(), point, e -> List.of()).restored();
	}

	private static UnitKey cash(Event event, String id) {
		return UnitKey.cashRegister(event.getId(), id);
	}

	private int versionsOf(UnitKey unit) throws IOException {
		return sync.history(unit).size();
	}

	/** Beschädigt die nächste Version der Kasse: Die Kette endet vor ihr, der Stand davor bleibt lesbar (SYS-008). */
	private void damage(UnitKey unit) throws IOException {
		Version damaged = new ForeignVersions(sync, appData).write(unit, "Fremd", payload -> payload.put("actualCashAmount", 99));
		Files.writeString(appData.resolve("data/" + unit.relativePath() + "/" + damaged.id() + ".json"), "kaputt");
		assertEquals(Status.LOCKED_DAMAGED, sync.unitState(unit).status());
	}

	/** Prüft, dass die Versionen in dieser Reihenfolge in einem Speichervorgang geschrieben wurden. */
	private static void assertWrittenInOrder(Version... versions) {
		for (int i = 1; i < versions.length; i++) {
			assertEquals(versions[0].saveId(), versions[i].saveId(), "ein Speichervorgang");
			assertTrue(versions[i - 1].id().compareTo(versions[i].id()) < 0,
					"Version " + (i - 1) + " vor Version " + i + ": " + versions[i - 1].id() + ", " + versions[i].id());
		}
	}

	// <-- Bauteile

	// Kassen-Einheiten -->

	@Test
	void restoreBeforeCashRegisterKeepsIt() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		event.setDescription("alt");
		repository.saveEvent(event);
		SavePoint before = newest(event);
		later();
		CashRegisters.setActualCash(event, presentation(event, "Freitag"), 12_345);
		CashRegister free = CashRegisters.addFree(event);
		free.setActualCashAmount(500);
		event.setDescription("neu");
		repository.saveEvent(event);

		restore(event, before);

		Event restored = reloaded();
		assertEquals("alt", restored.getDescription());
		assertEquals(new CashState(12_345, null), CashRegisters.effective(restored, presentation(restored, "Freitag")));
		assertEquals(500, restored.findCashRegister(free.getId()).getActualCashAmount());
		assertTrue(HeadRequires.carries(sync.unitState(UnitKey.event(event.getId())).current().payload()));
	}

	@Test
	void restoreDeletedEventBringsCashBackWithoutNewVersion() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		CashRegisters.setActualCash(event, presentation(event, "Freitag"), 12_345);
		repository.saveEvent(event);
		SavePoint counted = newest(event);
		later();
		repository.deleteEvent(event);
		UnitKey register = cash(event, presentation(event, "Freitag").getId());
		int versions = versionsOf(register);

		restore(event, counted);

		assertEquals(versions, versionsOf(register), "keine neue Kassen-Version");
		Event restored = reloaded();
		assertEquals(new CashState(12_345, null), CashRegisters.effective(restored, presentation(restored, "Freitag")));
	}

	/**
	 * Beim Wiederbeleben eines gelöschten Events stehen die geschriebenen Kassen vor den vorangestellten Löschungen und
	 * diese vor dem Kopf (Reihenfolge aus {@link HeadRequires#adjust}).
	 */
	@Test
	void restoreDeletedEventWritesCashBeforeCleanupAndHead() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		repository.saveEvent(event);
		SavePoint before = newest(event);
		later();
		Presentation saturday = event.addPresentation("Samstag");
		oldFields(saturday, 5_000, null);
		repository.saveEvent(event);
		later();
		repository.deleteEvent(event);

		restore(event, before);

		Version register = sync.unitState(cash(event, saturday.getId())).current();
		Version deletion = sync.unitState(UnitKey.presentation(event.getId(), saturday.getId())).current();
		Version head = sync.unitState(UnitKey.event(event.getId())).current();
		assertEquals(5_000, register.payload().get("actualCashAmount").asInt());
		assertTrue(deletion.deleted());
		assertTrue(HeadRequires.carries(head.payload()));
		assertWrittenInOrder(register, deletion, head);
	}

	// <-- Kassen-Einheiten

	// Alte Felder -->

	@Test
	void restoreKeepsTodaysOldFields() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		Presentation friday = presentation(event, "Freitag");
		oldFields(friday, 1_000, "alt");
		repository.saveEvent(event);
		SavePoint before = newest(event);
		later();
		oldFields(friday, 2_000, "neu");
		friday.setName("Fr");
		repository.saveEvent(event);

		restore(event, before);

		Event restored = reloaded();
		Presentation back = presentation(restored, "Freitag");
		assertEquals(2_000, back.getActualCashAmount());
		assertEquals("neu", back.getCashDifferenceComment());
	}

	@Test
	void restoredPresentationGetsLastLiveOldFields() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		oldFields(presentation(event, "Freitag"), 5_000, null);
		repository.saveEvent(event);
		SavePoint before = newest(event);
		later();
		// in 1.2 auf 0 gesetzt: Die Kasse hat keine eigenen Daten und wird nie angelegt, die alten Felder sind geleert
		CashRegisters.setActualCash(event, presentation(event, "Freitag"), 0);
		repository.saveEvent(event);
		later();
		event.deletePresentation(presentation(event, "Freitag"));
		repository.saveEvent(event);

		restore(event, before);

		Event restored = reloaded();
		assertEquals(new CashState(0, null), CashRegisters.effective(restored, presentation(restored, "Freitag")));
	}

	@Test
	void restoredPresentationClearsOldFieldsWhenCashLives() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		Presentation friday = presentation(event, "Freitag");
		oldFields(friday, 5_000, null);
		repository.saveEvent(event);
		SavePoint before = newest(event);
		later();
		CashRegisters.setActualCash(event, friday, 7_000);
		repository.saveEvent(event);
		later();
		event.deletePresentation(friday);
		repository.saveEvent(event);

		restore(event, before);

		Event restored = reloaded();
		Presentation back = presentation(restored, "Freitag");
		assertEquals(0, back.getActualCashAmount());
		assertEquals(new CashState(7_000, null), CashRegisters.effective(restored, back));
	}

	// <-- Alte Felder

	// Vorstellungen, die das Wiederherstellen entfernt -->

	@Test
	void restoreRemovingPresentationKeepsOldFieldAmountAsOrphan() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		repository.saveEvent(event);
		SavePoint before = newest(event);
		later();
		Presentation saturday = event.addPresentation("Samstag");
		oldFields(saturday, 5_000, null);
		repository.saveEvent(event);

		restore(event, before);

		assertOrphan(reloaded(), saturday.getId(), 5_000);
	}

	@Test
	void restoreRemovingPresentationKeepsStoredCashAsOrphan() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		repository.saveEvent(event);
		SavePoint before = newest(event);
		later();
		Presentation saturday = event.addPresentation("Samstag");
		CashRegisters.setActualCash(event, saturday, 5_000);
		repository.saveEvent(event);

		restore(event, before);

		assertOrphan(reloaded(), saturday.getId(), 5_000);
	}

	/** Die Kasse steht verwaist mit Betrag und dem Namen „Kasse Samstag" im Event, ohne Vorstellung. */
	private static void assertOrphan(Event restored, String presentationId, int cents) {
		assertNull(restored.findPresentation(presentationId));
		CashRegister register = restored.findCashRegister(presentationId);
		assertNotNull(register);
		assertEquals(cents, register.getActualCashAmount());
		assertEquals("Kasse Samstag", register.getName());
		VisibleCashRegister line = CashRegisters.visible(restored).stream().filter(VisibleCashRegister::orphan)
				.findFirst().orElseThrow();
		assertEquals("Kasse Samstag", line.displayName());
		assertEquals(cents, line.cash().actualCashAmount());
	}

	@Test
	void restoreRemovingPresentationDeletesEmptyCashRegister() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		repository.saveEvent(event);
		SavePoint before = newest(event);
		later();
		Presentation saturday = event.addPresentation("Samstag");
		CashRegisters.setActualCash(event, saturday, 5_000);
		repository.saveEvent(event);
		later();
		CashRegisters.setActualCash(event, saturday, 0);
		repository.saveEvent(event);

		restore(event, before);

		assertTrue(sync.unitState(cash(event, saturday.getId())).current().deleted());
		assertNull(reloaded().findCashRegister(saturday.getId()));
	}

	// <-- Vorstellungen, die das Wiederherstellen entfernt

	// Gesperrte Kassen -->

	@Test
	void restoreWithLockedCashRegisterIsNotRejected() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		event.setDescription("alt");
		repository.saveEvent(event);
		SavePoint before = newest(event);
		later();
		CashRegisters.setActualCash(event, presentation(event, "Freitag"), 12_345);
		event.setDescription("neu");
		repository.saveEvent(event);
		UnitKey register = cash(event, presentation(event, "Freitag").getId());
		damage(register);
		repository = TestRepositories.events(sync);
		repository.loadEvents();
		int versions = versionsOf(register);

		restore(event, before);

		assertEquals(versions, versionsOf(register), "die gesperrte Kasse bleibt unberührt");
		Event restored = reloaded();
		assertEquals("alt", restored.getDescription());
		assertEquals(12_345, restored.findCashRegister(register.cashRegisterId()).getActualCashAmount());
	}

	@Test
	void restoreRemovingPresentationLeavesLockedCashAlone() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		repository.saveEvent(event);
		SavePoint before = newest(event);
		later();
		Presentation saturday = event.addPresentation("Samstag");
		CashRegisters.setActualCash(event, saturday, 5_000);
		repository.saveEvent(event);
		UnitKey register = cash(event, saturday.getId());
		damage(register);
		repository = TestRepositories.events(sync);
		repository.loadEvents();
		int versions = versionsOf(register);

		restore(event, before);

		assertEquals(versions, versionsOf(register), "weder umbenannt noch gelöscht");
		assertTrue(sync.unitState(UnitKey.presentation(event.getId(), saturday.getId())).current().deleted());
	}

	// <-- Gesperrte Kassen

	// Speicherstände -->

	@Test
	void secondCountIsNoSavePointFirstIs() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		repository.saveEvent(event);
		later();
		CashRegisters.setActualCash(event, presentation(event, "Freitag"), 1_000);
		repository.saveEvent(event);
		List<SavePoint> afterFirst = repository.savePoints(event.getId());
		later();
		CashRegisters.setActualCash(event, presentation(event, "Freitag"), 2_000);
		repository.saveEvent(event);

		assertEquals(2, afterFirst.size(), "die erste Zählung schreibt den Kopf mit und erscheint");
		assertEquals(1, afterFirst.get(0).unitCount(), "die Kasse zählt nicht mit");
		assertEquals(afterFirst, repository.savePoints(event.getId()), "die zweite Zählung erscheint nicht");
	}

	// <-- Speicherstände

	// Kassen noch nicht abgeglichen -->

	@Test
	void restoreRejectedWhileCashPending() throws IOException {
		Path folder = Files.createDirectories(appData.resolve("shared"));
		sync = TestSyncServices.unstarted(appData.resolve("a"), "a", new NioFileLayer(), path -> false, clock);
		sync.startup(folder.toString());
		repository = TestRepositories.events(sync);
		Event event = named("Prunksitzung", "Freitag");
		repository.saveEvent(event);
		// ohne erreichbaren Ordner ist nichts beansprucht, die Ketten sind leer; abgelehnt wird vor dem Zielstand
		SavePoint point = new SavePoint("s1", clock.instant(), "a", null, 2);
		// der Kopf trägt das Merkmal, die Kassen sind noch nicht hier
		UnitKey head = UnitKey.event(event.getId());
		Version current = sync.unitState(head).current();
		ObjectNode marked = current.payload().deepCopy();
		marked.putArray("requires").add("cashRegisters");
		Version withMark = sync.append("merkmal", List.of(new Change(head, List.of(current.id()), marked, false))).get(0);
		assertTrue(repository.cashRegistersPending(event.getId()));

		LockedUnitException refused = assertThrows(LockedUnitException.class,
				() -> repository.prepareRestoreEvent(event.getId(), point));
		assertEquals(StoreEventRepository.CASH_PENDING, refused.getMessage());

		// auch für ein gelöschtes Event, dessen letzter lebender Kopf das Merkmal trug
		sync.append("loeschen", List.of(new Change(head, List.of(withMark.id()), null, true)));
		LockedUnitException deleted = assertThrows(LockedUnitException.class,
				() -> repository.prepareRestoreEvent(event.getId(), point));
		assertEquals(StoreEventRepository.CASH_PENDING, deleted.getMessage());
	}

	// <-- Kassen noch nicht abgeglichen

	// Seit diesem Stand gezählt -->

	@Test
	void countedSinceListsChangedRegisters() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag", "Montag", "Samstag", "Sonntag");
		CashRegisters.setActualCash(event, presentation(event, "Freitag"), 500);
		CashRegisters.setComment(event, presentation(event, "Freitag"), "alt");
		oldFields(presentation(event, "Montag"), 700, null);
		CashRegisters.setActualCash(event, presentation(event, "Samstag"), 300);
		repository.saveEvent(event);
		SavePoint point = newest(event);
		later();
		// nur der Kommentar: kein Eintrag
		CashRegisters.setComment(event, presentation(event, "Freitag"), "neu");
		// die alten Felder kommen beim ersten Ändern in die Kasse, der Betrag bleibt: kein Eintrag
		CashRegisters.setComment(event, presentation(event, "Montag"), "geprüft");
		// auf 0 gesetzt: Eintrag mit 0
		CashRegisters.setActualCash(event, presentation(event, "Samstag"), 0);
		// neu gezählt: Eintrag
		CashRegisters.setActualCash(event, presentation(event, "Sonntag"), 125_000);
		repository.saveEvent(event);

		List<CountedCash> counted = repository.countedSince(event.getId(), point);

		assertEquals(List.of(new CountedCash("Kasse Samstag", 0), new CountedCash("Kasse Sonntag", 125_000)), counted);
		assertEquals(List.of(), repository.countedSince(event.getId(), newest(event)), "seit dem neuesten Stand nichts");
	}

	@Test
	void countedSinceWorksForDeletedEvent() throws IOException {
		start();
		Event event = named("Prunksitzung", "Freitag");
		repository.saveEvent(event);
		SavePoint point = newest(event);
		later();
		CashRegisters.setActualCash(event, presentation(event, "Freitag"), 1_000);
		repository.saveEvent(event);
		later();
		repository.deleteEvent(event);

		assertEquals(List.of(new CountedCash("Kasse Freitag", 1_000)), repository.countedSince(event.getId(), point));
	}

	// <-- Seit diesem Stand gezählt
}
