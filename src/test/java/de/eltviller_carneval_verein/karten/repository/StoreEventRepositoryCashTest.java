package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.CashRegisters.CashState;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.FaultyFileLayer;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.NioFileLayer;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestClock;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Die Sperre für v1.1 über {@code requires} am Event-Kopf und die Reihenfolge eines Speichervorgangs mit Kassen
 * (KARV-144), am echten Versionsspeicher.
 */
class StoreEventRepositoryCashTest {

	// Bauteile -->

	private static SyncService started(SyncService sync) {
		sync.startup(null, p -> {
		});
		return sync;
	}

	private static SyncService started(Path appData) {
		return started(TestSyncServices.singleMode(appData));
	}

	private static Event named(String name) {
		Event event = new Event();
		event.setName(name);
		return event;
	}

	private static Booking booking(Presentation presentation, String seatId, int seat, String lastName) {
		Booking booking = presentation.addBooking();
		booking.setSeatId(seatId);
		booking.setRowLabel("A");
		booking.setTableNumber(1);
		booking.setSeatNumber(seat);
		booking.setLastName(lastName);
		return booking;
	}

	private static UnitKey head(Event event) {
		return UnitKey.event(event.getId());
	}

	private static UnitKey cash(Event event, String id) {
		return UnitKey.cashRegister(event.getId(), id);
	}

	private static Version current(SyncService sync, UnitKey unit) throws IOException {
		return sync.unitState(unit).current();
	}

	private static boolean headCarries(SyncService sync, Event event) throws IOException {
		return HeadRequires.carries(current(sync, head(event)).payload());
	}

	private static long filesIn(Path appData) throws IOException {
		try (Stream<Path> walk = Files.walk(appData.resolve("data"))) {
			return walk.filter(Files::isRegularFile).count();
		}
	}

	/** Prüft, dass die Versionen in dieser Reihenfolge in einem Speichervorgang geschrieben wurden. */
	private static void assertWrittenInOrder(Version... versions) {
		for (int i = 1; i < versions.length; i++) {
			assertEquals(versions[0].saveId(), versions[i].saveId(), "ein Speichervorgang");
			assertTrue(versions[i - 1].id().compareTo(versions[i].id()) < 0,
					"Version " + (i - 1) + " vor Version " + i + ": " + versions[i - 1].id() + ", " + versions[i].id());
		}
	}

	/** Ein gespeichertes Event mit der Vorstellung "Freitag" und einer gezählten Kasse; der Kopf trägt das Merkmal. */
	private static Event savedWithCash(StoreEventRepository repository, SyncService sync) throws IOException {
		Event event = named("Prunksitzung");
		CashRegisters.setActualCash(event, event.addPresentation("Freitag"), 12_345);
		repository.saveEvent(event);
		assertTrue(headCarries(sync, event));
		return event;
	}

	// <-- Bauteile

	// Setzen -->

	@Test
	void firstCashRegisterWritesHeadWithRequires(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		repository.saveEvent(event);
		Version before = current(sync, head(event));
		assertFalse(HeadRequires.carries(before.payload()));

		CashRegisters.setActualCash(event, friday, 12_345);
		repository.saveEvent(event);

		Version headNow = current(sync, head(event));
		Version register = current(sync, cash(event, friday.getId()));
		assertNotEquals(before.id(), headNow.id(), "der Kopf wird mitgeschrieben");
		assertEquals(List.of(before.id()), headNow.parents());
		assertEquals("[\"cashRegisters\"]", headNow.payload().get("requires").toString());
		assertWrittenInOrder(headNow, register);
		assertEquals(Set.of(), repository.locallyChangedUnits());

		// ein neues Event mit Kasse: ebenso der Kopf zuerst
		Event fresh = named("Kappensitzung");
		CashRegisters.addFree(fresh);
		repository.saveEvent(fresh);
		CashRegister free = fresh.getCashRegisters().get(0);
		assertTrue(headCarries(sync, fresh));
		assertWrittenInOrder(current(sync, head(fresh)), current(sync, cash(fresh, free.getId())));
	}

	@Test
	void firstCountOfOldStockWritesHeadThenCashThenPresentation(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		// ein Bestand wie aus v1.1: der Betrag steht in den alten Feldern der Vorstellung
		friday.setActualCashAmount(125_000);
		friday.setCashDifferenceComment("Wechselgeld");
		repository.saveEvent(event);
		assertFalse(headCarries(sync, event));

		CashRegisters.setActualCash(event, friday, 130_000);
		repository.saveEvent(event);

		Version headNow = current(sync, head(event));
		Version register = current(sync, cash(event, friday.getId()));
		Version presentation = current(sync, UnitKey.presentation(event.getId(), friday.getId()));
		assertWrittenInOrder(headNow, register, presentation);
		assertTrue(HeadRequires.carries(headNow.payload()));
		assertEquals(130_000, register.payload().get("actualCashAmount").asInt());
		assertEquals(0, presentation.payload().get("actualCashAmount").asInt(), "die alten Felder sind leer");
		assertTrue(presentation.payload().get("cashDifferenceComment").isNull());
	}

	@Test
	void missingRequiresIsRepaired(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = savedWithCash(TestRepositories.events(sync), sync);
		// etwa ein beschädigter Kopf, roh auf einen Stand ohne Merkmal zurückgesetzt
		new ForeignVersions(sync, appData).write(head(event), "Fremd", payload -> payload.remove("requires"));
		assertFalse(headCarries(sync, event));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().get(0);
		assertEquals(Set.of(), repository.locallyChangedUnits(), "das fehlende Merkmal ist keine Änderung des Nutzers");

		booking(loaded.getPresentations().get(0), "seat-1", 1, "Müller");
		repository.saveEvent(loaded);

		assertTrue(headCarries(sync, event), "das nächste Speichern setzt das Merkmal");
		assertEquals(Set.of(), repository.locallyChangedUnits());
	}

	// <-- Setzen

	// Entfernen und Stehenlassen -->

	@Test
	void deletingLastCashRegisterRemovesRequiresAfterDeletion(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		event.addPresentation("Freitag");
		CashRegister free = CashRegisters.addFree(event);
		repository.saveEvent(event);
		assertTrue(headCarries(sync, event));

		assertTrue(CashRegisters.removeFree(event, free.getId()));
		repository.saveEvent(event);

		Version deletion = current(sync, cash(event, free.getId()));
		Version headNow = current(sync, head(event));
		assertTrue(deletion.deleted());
		assertFalse(headNow.payload().has("requires"), "der Kopf verliert das Merkmal");
		assertWrittenInOrder(deletion, headNow);
		assertEquals(Set.of(), repository.locallyChangedUnits());
	}

	@Test
	void deletingOneOfTwoCashRegistersKeepsRequires(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		CashRegister first = CashRegisters.addFree(event);
		CashRegisters.addFree(event);
		repository.saveEvent(event);
		int versions = sync.history(head(event)).size();

		assertTrue(CashRegisters.removeFree(event, first.getId()));
		repository.saveEvent(event);

		assertTrue(current(sync, cash(event, first.getId())).deleted());
		assertEquals(versions, sync.history(head(event)).size(), "der Kopf bleibt, wie er ist");
		assertTrue(headCarries(sync, event));
	}

	@Test
	void headWithRequiresButNoKnownCashKeepsIt(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = named("Prunksitzung");
		event.addPresentation("Freitag");
		TestRepositories.events(sync).saveEvent(event);
		// ein Kopf mit Merkmal, dessen Kassen dieser Rechner nicht kennt (unterwegs, verpasst oder nie gespeichert)
		new ForeignVersions(sync, appData).write(head(event), "Fremd",
				payload -> payload.putArray("requires").add("cashRegisters"));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().get(0);
		int versions = sync.history(head(event)).size();

		booking(loaded.getPresentations().get(0), "seat-1", 1, "Müller");
		repository.saveEvent(loaded);

		assertEquals(versions, sync.history(head(event)).size(), "der Kopf wird nicht neu geschrieben");
		assertTrue(headCarries(sync, event), "das Merkmal bleibt");
	}

	@Test
	void lockedCashRegisterKeepsRequires(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = named("Prunksitzung");
		CashRegister visible = CashRegisters.addFree(event);
		CashRegister hidden = CashRegisters.addFree(event);
		TestRepositories.events(sync).saveEvent(event);
		// die zweite Kasse steht nur im Versionsspeicher: inhaltlich zu neu, im Modell fehlt sie
		new ForeignVersions(sync, appData).write(cash(event, hidden.getId()), "Fremd",
				payload -> payload.putArray("requires").add("x-unbekannt"));
		assertTrue(sync.unitState(cash(event, hidden.getId())).contentLocked());
		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().get(0);
		assertNull(loaded.findCashRegister(hidden.getId()));
		int versions = sync.history(head(event)).size();

		assertTrue(CashRegisters.removeFree(loaded, visible.getId()));
		repository.saveEvent(loaded);

		assertTrue(current(sync, cash(event, visible.getId())).deleted());
		assertEquals(versions, sync.history(head(event)).size());
		assertTrue(headCarries(sync, event), "die gesperrte Kasse hält das Merkmal");
	}

	@Test
	void archivingWritesOnlyHead(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = savedWithCash(repository, sync);
		CashRegister free = CashRegisters.addFree(event);
		repository.saveEvent(event);
		UnitKey counted = cash(event, event.getPresentations().get(0).getId());
		UnitKey presentation = UnitKey.presentation(event.getId(), event.getPresentations().get(0).getId());
		int headVersions = sync.history(head(event)).size();
		List<Integer> others = List.of(sync.history(counted).size(), sync.history(cash(event, free.getId())).size(),
				sync.history(presentation).size());
		long before = filesIn(appData);

		event.setArchived(true);
		repository.saveEvent(event);

		assertEquals(headVersions + 1, sync.history(head(event)).size());
		assertEquals(others, List.of(sync.history(counted).size(), sync.history(cash(event, free.getId())).size(),
				sync.history(presentation).size()), "Kassen und Vorstellung bleiben unberührt");
		assertEquals(before + 2, filesIn(appData), "eine Version und ihr Anspruch");
		assertTrue(headCarries(sync, event));
		assertTrue(current(sync, head(event)).payload().get("archived").asBoolean());
	}

	// <-- Entfernen und Stehenlassen

	// Vergleichen -->

	@Test
	void requiresAloneIsNotLocalChange(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreEventRepository repository = TestRepositories.events(sync);
		savedWithCash(repository, sync);
		assertEquals(Set.of(), repository.locallyChangedUnits());

		StoreEventRepository fresh = TestRepositories.events(started(appData));
		fresh.loadEvents();

		assertEquals(Set.of(), fresh.locallyChangedUnits());
	}

	@Test
	void emptyRequiresWritesNoVersion(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = named("Prunksitzung");
		event.addPresentation("Freitag");
		TestRepositories.events(sync).saveEvent(event);
		new ForeignVersions(sync, appData).write(head(event), "Fremd", payload -> payload.putArray("requires"));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().get(0);
		assertEquals(Set.of(), repository.locallyChangedUnits());
		long before = filesIn(appData);

		repository.saveEvent(loaded);

		assertEquals(before, filesIn(appData));
	}

	// <-- Vergleichen

	// Gesperrte Einheiten -->

	/** Lässt sich der Kopf nicht schreiben, unterbleibt nur das Mitschreiben; Verkauf und Kasse werden gespeichert. */
	@Test
	void lockedHeadStillAllowsBookingSave(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = named("Prunksitzung");
		event.addPresentation("Freitag");
		TestRepositories.events(sync).saveEvent(event);
		UnitKey head = head(event);
		Version first = current(sync, head);
		ObjectNode changed = first.payload().deepCopy();
		changed.put("description", "geändert");
		Version second = sync.append("test", List.of(new Change(head, List.of(first.id()), changed, false))).get(0);
		// die zweite Version wird beschädigt: Die Kette endet vor ihr, die erste bleibt lesbar
		Files.writeString(appData.resolve("data/events/" + event.getId() + "/" + second.id() + ".json"), "kaputt");
		SyncService restarted = started(appData);
		assertEquals(Status.LOCKED_DAMAGED, restarted.unitState(head).status());
		StoreEventRepository repository = TestRepositories.events(restarted);
		Event loaded = repository.loadEvents().get(0);
		Presentation friday = loaded.getPresentations().get(0);
		int versions = restarted.history(head).size();

		booking(friday, "seat-1", 1, "Müller");
		CashRegisters.setActualCash(loaded, friday, 5_000);
		repository.saveEvent(loaded);

		assertEquals(versions, restarted.history(head).size(), "der gesperrte Kopf bleibt unberührt");
		assertEquals(5_000, current(restarted, cash(event, friday.getId())).payload().get("actualCashAmount").asInt());
		assertTrue(current(restarted, UnitKey.booking(event.getId(), friday.getId(), "seat-1")).payload().has("lastName"));
	}

	@Test
	void bookingSavesWhileCashRegisterLocked(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Event event = savedWithCash(TestRepositories.events(sync), sync);
		UnitKey counted = cash(event, event.getPresentations().get(0).getId());
		Version damaged = new ForeignVersions(sync, appData).write(counted, "Fremd",
				payload -> payload.put("actualCashAmount", 99));
		Files.writeString(appData.resolve("data/" + counted.relativePath() + "/" + damaged.id() + ".json"), "kaputt");
		assertEquals(Status.LOCKED_DAMAGED, sync.unitState(counted).status());
		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().get(0);
		int versions = sync.history(head(event)).size();

		booking(loaded.getPresentations().get(0), "seat-1", 1, "Müller");
		repository.saveEvent(loaded);

		UnitKey seat = UnitKey.booking(event.getId(), loaded.getPresentations().get(0).getId(), "seat-1");
		assertEquals("Müller", current(sync, seat).payload().get("lastName").asText());
		assertEquals(versions, sync.history(head(event)).size());
		assertTrue(headCarries(sync, event));
	}

	// <-- Gesperrte Einheiten

	// Abbruch -->

	/**
	 * Bricht der erste Speichervorgang einer gezählten Vorstellung nach der Kasse ab, gilt nach dem Neuladen die Kasse, und
	 * der Betrag der alten Felder steht als zweiter Ist-Betrag da.
	 */
	@Test
	void abortAfterCashKeepsSecondAmountVisible(@TempDir Path appData) throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService sync = started(TestSyncServices.singleMode(appData, layer));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		friday.setActualCashAmount(125_000);
		repository.saveEvent(event);
		CashRegisters.setActualCash(event, friday, 130_000);
		// der dritte createNew des Vorgangs scheitert: Kopf und Kasse sind geschrieben, die Vorstellung nicht
		int[] count = { 0 };
		layer.onEach("createNew", () -> {
			if (++count[0] == 3) {
				layer.failNext("createNew", new IOException("Platte voll"));
			}
		});

		assertThrows(RuntimeException.class, () -> repository.saveEvent(event));

		assertTrue(headCarries(sync, event));
		Event loaded = TestRepositories.events(sync).loadEvents().get(0);
		Presentation reloaded = loaded.getPresentations().get(0);
		assertEquals(130_000, CashRegisters.effective(loaded, reloaded).actualCashAmount(), "es gilt die Kasse");
		Optional<CashState> second = CashRegisters.secondAmount(loaded, reloaded);
		assertTrue(second.isPresent());
		assertEquals(125_000, second.get().actualCashAmount());
	}

	/** Bricht derselbe Vorgang direkt nach dem Kopf ab, gelten die alten Felder weiter; v1.1 ist schon ausgesperrt. */
	@Test
	void abortAfterHeadKeepsOldFieldsValid(@TempDir Path appData) throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService sync = started(TestSyncServices.singleMode(appData, layer));
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		friday.setActualCashAmount(125_000);
		repository.saveEvent(event);
		CashRegisters.setActualCash(event, friday, 130_000);
		int[] count = { 0 };
		layer.onEach("createNew", () -> {
			if (++count[0] == 2) {
				layer.failNext("createNew", new IOException("Platte voll"));
			}
		});

		assertThrows(RuntimeException.class, () -> repository.saveEvent(event));

		assertTrue(headCarries(sync, event));
		Event loaded = TestRepositories.events(sync).loadEvents().get(0);
		assertEquals(List.of(), loaded.getCashRegisters());
		assertEquals(125_000, CashRegisters.effective(loaded, loaded.getPresentations().get(0)).actualCashAmount());
	}

	// <-- Abbruch

	// Zwei Rechner -->

	/**
	 * Rechner B bekommt den Kopf mit dem Merkmal, die Kasse ist noch unterwegs: Ein Verkauf an B lässt das Merkmal
	 * stehen, obwohl B keine Kasse des Events kennt.
	 */
	@Test
	void requiresStaysWhenHeadArrivesBeforeCash(@TempDir Path dir) throws IOException {
		TestClock clock = new TestClock();
		Path shared = Files.createDirectories(dir.resolve("shared"));
		SyncService a = TestSyncServices.sharedMode(dir.resolve("a"), shared, "a", clock);
		SyncService b = TestSyncServices.sharedMode(dir.resolve("b"), shared, "b", clock);
		Event eventA = named("Prunksitzung");
		eventA.addPresentation("Freitag");
		TestRepositories.events(a).saveEvent(eventA);
		// der Kopf, wie A ihn mit der ersten Kasse schreibt; die Kasse selbst ist an B noch nicht angekommen
		UnitKey head = head(eventA);
		Version stored = current(a, head);
		ObjectNode flagged = HeadRequires.with(stored.payload(), true);
		a.append("kopf-zuerst", List.of(new Change(head, List.of(stored.id()), flagged, false)));
		TestSyncServices.tick(a);
		clock.advance(Duration.ofSeconds(30));
		TestSyncServices.tick(b);
		assertTrue(HeadRequires.carries(current(b, head).payload()));
		assertTrue(b.eventUnits(eventA.getId()).stream().noneMatch(unit -> unit.type() == UnitType.CASH_REGISTER));
		StoreEventRepository repositoryB = TestRepositories.events(b);
		Event eventB = repositoryB.loadEvents().get(0);

		booking(eventB.getPresentations().get(0), "seat-1", 1, "Bert");
		eventB.setDescription("Verkauf an B");
		repositoryB.saveEvent(eventB);

		Version headB = current(b, head);
		assertEquals("Verkauf an B", headB.payload().get("description").asText(), "der Kopf wurde geschrieben");
		assertTrue(HeadRequires.carries(headB.payload()), "und behält das Merkmal");
		TestSyncServices.tick(b);
		clock.advance(Duration.ofSeconds(30));
		TestSyncServices.tick(a);
		assertTrue(HeadRequires.carries(current(a, head).payload()));
	}

	// <-- Zwei Rechner
}
