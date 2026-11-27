package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.Snapshots;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestClock;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;

/**
 * Fremde Stände von Kassen (KARV-144): Einarbeiten in das offene Event, Zurückstellen während einer Bearbeitung,
 * Zurücknehmen bei einem Fehler und die Wege der Konfliktaktionen.
 */
class StoreEventRepositoryForeignCashTest {

	private static final ObjectMapper MAPPER = JsonMapperFactory.create();

	/** Merkt sich die Stapel fremder Stände. */
	private static final class Recorder implements RepositoryListener<Event> {
		final List<ForeignBatch<Event>> batches = new ArrayList<>();

		@Override
		public void changedByOthers(Event event, ForeignBatch<Event> batch) {
			batches.add(batch);
		}
	}

	@TempDir
	Path appData;

	// Bauteile -->

	private static SyncService started(SyncService sync) {
		sync.startup(null, p -> {
		});
		return sync;
	}

	private static Event named(String name) {
		Event event = new Event();
		event.setName(name);
		return event;
	}

	private static UnitKey cash(Event event, String id) {
		return UnitKey.cashRegister(event.getId(), id);
	}

	/** Ein zweites Repository auf demselben Dienst lädt das Event: "der andere Rechner". */
	private static Event loadedBy(StoreEventRepository repository, Event event) {
		return repository.loadEvents().stream().filter(e -> e.getId().equals(event.getId())).findFirst().orElseThrow();
	}

	private int versions;

	/** Der Stand einer Kasse, wie ihn ein anderer Rechner geschrieben hat. */
	private UnitSnapshot foreign(Event state, UnitKey unit) {
		versions++;
		return Snapshots.snapshot(unit, VersionId.create(1_000L * versions, "abcdefgh", versions), Status.OK, false,
				UnitMapper.payloads(state, unit::equals).get(unit));
	}

	private static EventMerge.Sink failingAdopt() {
		return new EventMerge.Sink() {
			@Override
			public void adopted(UnitSnapshot snapshot, boolean shown) {
				throw new IllegalStateException("Melden scheitert");
			}
		};
	}

	// <-- Bauteile

	@Test
	void twoMachinesChangeDifferentRegistersWithoutConflict(@TempDir Path dir) throws IOException {
		TestClock clock = new TestClock();
		Path shared = Files.createDirectories(dir.resolve("shared"));
		SyncService a = TestSyncServices.sharedMode(dir.resolve("a"), shared, "a", clock);
		SyncService b = TestSyncServices.sharedMode(dir.resolve("b"), shared, "b", clock);
		ConflictPolicies.install(a);
		ConflictPolicies.install(b);
		StoreEventRepository repositoryA = TestRepositories.events(a);
		Event eventA = named("Prunksitzung");
		eventA.addPresentation("Freitag");
		CashRegisters.selectShared(eventA, true);
		CashRegister freeA = CashRegisters.addFree(eventA);
		repositoryA.saveEvent(eventA);
		TestSyncServices.tick(a);
		clock.advance(Duration.ofSeconds(30));
		TestSyncServices.tick(b);
		StoreEventRepository repositoryB = TestRepositories.events(b);
		Event eventB = repositoryB.loadEvents().get(0);
		UnitKey sharedUnit = cash(eventA, CashRegister.SHARED_ID);
		UnitKey freeUnit = cash(eventA, freeA.getId());

		eventA.findCashRegister(CashRegister.SHARED_ID).setActualCashAmount(10_000);
		repositoryA.saveEvent(eventA);
		eventB.findCashRegister(freeA.getId()).setActualCashAmount(20_000);
		repositoryB.saveEvent(eventB);
		TestSyncServices.tick(a);
		clock.advance(Duration.ofSeconds(30));
		TestSyncServices.tick(b);
		clock.advance(Duration.ofSeconds(30));
		TestSyncServices.tick(a);
		repositoryA.applyForeign(List.of(sharedUnit, freeUnit));
		repositoryB.applyForeign(List.of(sharedUnit, freeUnit));

		assertTrue(a.openConflicts().isEmpty(), a.openConflicts().toString());
		assertTrue(b.openConflicts().isEmpty(), b.openConflicts().toString());
		for (Event event : List.of(eventA, eventB)) {
			assertEquals(10_000, event.findCashRegister(CashRegister.SHARED_ID).getActualCashAmount());
			assertEquals(20_000, event.findCashRegister(freeA.getId()).getActualCashAmount());
		}
		assertSame(freeA, eventA.findCashRegister(freeA.getId()), "das vorhandene Objekt übernimmt den fremden Stand");
		assertTrue(repositoryA.locallyChangedUnits().isEmpty());
		assertTrue(repositoryB.locallyChangedUnits().isEmpty());
	}

	@Test
	void foreignCashRegisterAppearsInOpenEvent() throws IOException {
		SyncService sync = started(TestSyncServices.singleMode(appData));
		StoreEventRepository mine = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		event.addPresentation("Freitag");
		mine.saveEvent(event);
		ChangeTracker<Event> tracker = new ChangeTracker<>(Event::getId);
		tracker.track(event);
		Recorder recorder = new Recorder();
		mine.addListener(recorder);
		StoreEventRepository otherRepository = TestRepositories.events(sync);
		Event other = loadedBy(otherRepository, event);
		CashRegister added = CashRegisters.addFree(other);
		added.setActualCashAmount(4_200);
		otherRepository.saveEvent(other);
		UnitKey unit = cash(event, added.getId());

		ForeignResult result = mine.applyForeign(List.of(UnitKey.event(event.getId()), unit));

		assertTrue(result.deferred().isEmpty());
		CashRegister arrived = event.findCashRegister(added.getId());
		assertNotNull(arrived);
		assertEquals("Neue Kasse", arrived.getName());
		assertEquals(4_200, arrived.getActualCashAmount());
		assertTrue(mine.locallyChangedUnits().isEmpty());
		// der gespeicherte Stand des Trackers wird mit demselben Stapel fortgeschrieben wie in der App
		assertEquals(1, recorder.batches.size());
		Event copy = MAPPER.readValue(tracker.baseline(event).bytes(), Event.class);
		recorder.batches.get(0).replayOn(copy);
		tracker.rebase(event, tracker.snapshotOf(copy));
		assertFalse(tracker.isDirty(event));
		assertNotNull(copy.findCashRegister(added.getId()));

		// gelöscht verlässt sie die Liste
		Event again = loadedBy(otherRepository, event);
		again.findCashRegister(added.getId()).setActualCashAmount(0);
		assertTrue(CashRegisters.removeFree(again, added.getId()));
		otherRepository.saveEvent(again);
		mine.applyForeign(List.of(unit));
		assertNull(event.findCashRegister(added.getId()));
	}

	@Test
	void foreignStateDeferredWhilePresentationEdited() {
		SyncService sync = started(TestSyncServices.singleMode(appData));
		StoreEventRepository mine = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		mine.saveEvent(event);
		mine.setEditingCheck(model -> model == friday);
		StoreEventRepository otherRepository = TestRepositories.events(sync);
		Event other = loadedBy(otherRepository, event);
		CashRegisters.setActualCash(other, other.getPresentations().get(0), 5_000);
		otherRepository.saveEvent(other);
		UnitKey unit = cash(event, friday.getId());

		ForeignResult result = mine.applyForeign(List.of(unit));

		assertEquals(List.of(unit), result.deferred().stream().map(Deferral::unit).toList(),
				"die Kasse gibt es noch nicht als Objekt; ihre Vorstellung wird bearbeitet");
		assertNull(event.findCashRegister(friday.getId()));

		mine.setEditingCheck(model -> false);
		mine.retryDeferred();

		assertEquals(5_000, event.findCashRegister(friday.getId()).getActualCashAmount());
	}

	@Test
	void foreignStateDeferredWhilePresentationEditedAlsoWithObject() {
		SyncService sync = started(TestSyncServices.singleMode(appData));
		StoreEventRepository mine = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		CashRegisters.setActualCash(event, friday, 1_000);
		mine.saveEvent(event);
		mine.setEditingCheck(model -> model == friday);
		StoreEventRepository otherRepository = TestRepositories.events(sync);
		Event other = loadedBy(otherRepository, event);
		CashRegisters.setActualCash(other, other.getPresentations().get(0), 5_000);
		otherRepository.saveEvent(other);
		UnitKey unit = cash(event, friday.getId());

		ForeignResult result = mine.applyForeign(List.of(unit));

		assertEquals(List.of(unit), result.deferred().stream().map(Deferral::unit).toList());
		assertEquals(1_000, event.findCashRegister(friday.getId()).getActualCashAmount());
	}

	@Test
	void headDeletionDeferredWhileCashRegisterInEditing() {
		SyncService sync = started(TestSyncServices.singleMode(appData));
		StoreEventRepository mine = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		CashRegister free = CashRegisters.addFree(event);
		mine.saveEvent(event);
		mine.setEditingCheck(model -> model == free);
		StoreEventRepository otherRepository = TestRepositories.events(sync);
		otherRepository.deleteEvent(loadedBy(otherRepository, event));
		UnitKey head = UnitKey.event(event.getId());

		ForeignResult result = mine.applyForeign(List.of(head));

		assertEquals(List.of(head), result.deferred().stream().map(Deferral::unit).toList());
		assertTrue(mine.loadEvents().contains(event));
	}

	/** Review-Schwerpunkt 3: Scheitert das Einarbeiten nach dem Einfügen, ist die Liste wie vorher. */
	@Test
	void failedCashMergeRestoresList() {
		Event event = named("Prunksitzung");
		CashRegister shared = new CashRegister(CashRegister.SHARED_ID);
		shared.setActualCashAmount(1_000);
		event.putCashRegister(shared);
		List<CashRegister> list = event.getCashRegisters();
		Event state = new Event(event.getId());
		state.setName("Prunksitzung");
		CashRegister added = CashRegisters.addFree(state);
		CashRegister foreignShared = new CashRegister(CashRegister.SHARED_ID);
		foreignShared.setActualCashAmount(9_000);
		foreignShared.setCashDifferenceComment("fremd");
		state.putCashRegister(foreignShared);

		// eine neue Kasse
		UnitSnapshot newOne = foreign(state, cash(event, added.getId()));
		assertThrows(IllegalStateException.class,
				() -> EventMerge.apply(event, new EventMerge.Plan(List.of(newOne), List.of(), id -> null, Map.of()),
						failingAdopt()));

		assertSame(list, event.getCashRegisters());
		assertEquals(List.of(shared), event.getCashRegisters());

		// eine vorhandene Kasse: ihre Felder wie vorher
		UnitSnapshot changed = foreign(state, cash(event, CashRegister.SHARED_ID));
		assertThrows(IllegalStateException.class,
				() -> EventMerge.apply(event, new EventMerge.Plan(List.of(changed), List.of(), id -> null, Map.of()),
						failingAdopt()));

		assertEquals(List.of(shared), event.getCashRegisters());
		assertSame(shared, event.findCashRegister(CashRegister.SHARED_ID));
		assertEquals(1_000, shared.getActualCashAmount());
		assertNull(shared.getCashDifferenceComment());

		// eine gelöschte Kasse kommt zurück in die Liste
		UnitSnapshot gone = Snapshots.snapshot(cash(event, CashRegister.SHARED_ID), VersionId.create(99_000L, "abcdefgh", 99),
				Status.OK, true, JsonNodeFactory.instance.objectNode());
		assertThrows(IllegalStateException.class,
				() -> EventMerge.apply(event, new EventMerge.Plan(List.of(gone), List.of(), id -> null, Map.of()),
						failingAdopt()));

		assertEquals(List.of(shared), event.getCashRegisters());
	}

	@Test
	void applyGroupCashRegisterScope() {
		SyncService sync = started(TestSyncServices.singleMode(appData));
		StoreEventRepository mine = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		CashRegister free = CashRegisters.addFree(event);
		free.setActualCashAmount(700);
		mine.saveEvent(event);
		UnitKey unit = cash(event, free.getId());
		ObjectNode before = mine.payloadOf(unit);
		assertSame(free, mine.modelObject(unit));

		ObjectNode mineValue = JsonNodeFactory.instance.objectNode().put("allPresentations", false);
		mineValue.putArray("presentationIds").add(friday.getId());
		mine.applyGroup(unit, "scope", mineValue);

		assertSame(free, event.findCashRegister(free.getId()));
		assertFalse(free.isAllPresentations());
		assertEquals(List.of(friday.getId()), free.getPresentationIds());
		assertEquals("Neue Kasse", free.getName(), "andere Gruppen bleiben");
		assertEquals(700, free.getActualCashAmount());

		// fehlt ein Feld der Gruppe, wird es geleert
		mine.applyGroup(unit, "scope", JsonNodeFactory.instance.objectNode().put("allPresentations", true));
		assertTrue(free.isAllPresentations());
		assertEquals(List.of(), free.getPresentationIds());

		mine.applyGroup(unit, "cash", JsonNodeFactory.instance.objectNode().put("actualCashAmount", 900));
		assertEquals(900, free.getActualCashAmount());
		assertNull(free.getCashDifferenceComment());

		mine.restoreFields(unit, before);
		assertEquals(before, mine.payloadOf(unit));

		// der Kopf behält seine Kassen
		mine.applyFields(UnitKey.event(event.getId()), JsonNodeFactory.instance.objectNode().put("description", "neu"));
		mine.restoreFields(UnitKey.event(event.getId()), mine.payloadOf(UnitKey.event(event.getId())));
		assertEquals("neu", event.getDescription());
		assertEquals(List.of(free), event.getCashRegisters());
	}

	@Test
	void rebuiltEventReplaysCashRegisters() throws IOException {
		SyncService sync = started(TestSyncServices.singleMode(appData));
		StoreEventRepository mine = TestRepositories.events(sync);
		Event event = named("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		CashRegister free = CashRegisters.addFree(event);
		mine.saveEvent(event);
		ChangeTracker<Event> tracker = new ChangeTracker<>(Event::getId);
		tracker.track(event);
		Recorder recorder = new Recorder();
		mine.addListener(recorder);
		// das erste Nachschlagen einer Halle scheitert: das Einarbeiten der Vorstellung bricht ab, das Event wird neu
		// zusammengesetzt
		AtomicInteger lookups = new AtomicInteger();
		mine.setHallLookup(id -> {
			if (lookups.getAndIncrement() == 0) {
				throw new IllegalStateException("Halle nicht lesbar");
			}
			return null;
		});
		StoreEventRepository otherRepository = TestRepositories.events(sync);
		Event other = loadedBy(otherRepository, event);
		other.findCashRegister(free.getId()).setActualCashAmount(3_300);
		other.getPresentations().get(0).setHallId("andere-halle");
		otherRepository.saveEvent(other);

		mine.applyForeign(List.of(cash(event, free.getId()), UnitKey.presentation(event.getId(), friday.getId())));

		Event fresh = mine.findEvent(event.getId());
		assertEquals(3_300, fresh.findCashRegister(free.getId()).getActualCashAmount());
		ForeignBatch<Event> batch = recorder.batches.get(recorder.batches.size() - 1);
		Event copy = MAPPER.readValue(tracker.baseline(event).bytes(), Event.class);
		batch.replayOn(copy);
		assertEquals(3_300, copy.findCashRegister(free.getId()).getActualCashAmount());
		assertEquals(MAPPER.valueToTree(fresh), MAPPER.valueToTree(copy));
	}
}
