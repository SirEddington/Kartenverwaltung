package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.NioFileLayer;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncListener;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestClock;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Gesperrte Kassen und der Zustand „Kassen noch nicht abgeglichen" (KARV-144): was das Repository je Event meldet, am
 * Stand des Versionsspeichers.
 */
class CashRegisterStateTest {

	/** Merkt sich die fremden Änderungen, die das Repository meldet. */
	private static final class Recorder implements RepositoryListener<Event> {
		final List<String> calls = new ArrayList<>();

		@Override
		public void changingByOthers(Event event) {
			calls.add("changing " + event.getName());
		}

		@Override
		public void changedByOthers(Event event, ForeignBatch<Event> batch) {
			calls.add("changed " + event.getName() + " " + batch.units());
		}
	}

	@TempDir
	Path dir;

	private SyncService sync;
	private Path appData;
	private StoreEventRepository repository;
	private ForeignVersions foreign;

	// Bauteile -->

	/** Ob der gemeinsame Ordner erreichbar ist. */
	private final AtomicBoolean reachable = new AtomicBoolean();

	/**
	 * Ein gestarteter Dienst im gemeinsamen Betrieb, dessen erster Vollabgleich noch aussteht: Der Ordner ist verbunden,
	 * aber nicht erreichbar, bis der Test {@link #reachable} setzt.
	 */
	private void shared() throws IOException {
		appData = dir.resolve("a");
		Path folder = Files.createDirectories(dir.resolve("shared"));
		sync = TestSyncServices.unstarted(appData, "a", new NioFileLayer(), path -> reachable.get(), new TestClock());
		sync.startup(folder.toString());
		repository = TestRepositories.events(sync);
		foreign = new ForeignVersions(sync, appData);
	}

	/** Ein gestarteter Dienst im Einzelbetrieb. */
	private void single() {
		appData = dir.resolve("a");
		sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		repository = TestRepositories.events(sync);
		foreign = new ForeignVersions(sync, appData);
	}

	private Event saved(String name, String... presentations) {
		Event event = new Event();
		event.setName(name);
		for (String presentation : presentations) {
			event.addPresentation(presentation);
		}
		repository.saveEvent(event);
		return event;
	}

	/**
	 * Der Kopf bekommt das Merkmal, wie ihn ein Rechner mit 1.2 geschrieben hat, dessen Kassen noch nicht hier sind (über
	 * den eigenen Weg: Im gemeinsamen Betrieb hat die noch nicht hochgeladene Kette kein beanspruchtes Glied).
	 */
	private Version markHead(Event event) throws IOException {
		UnitKey head = UnitKey.event(event.getId());
		Version current = sync.unitState(head).current();
		ObjectNode payload = current.payload().deepCopy();
		payload.putArray("requires").add("cashRegisters");
		return sync.append("merkmal", List.of(new Change(head, List.of(current.id()), payload, false))).get(0);
	}

	/** Ein neueres Programm hat die Einheit geschrieben (SYS-009). */
	private void lock(UnitKey unit) throws IOException {
		foreign.write(unit, "Fremd", payload -> payload.putArray("requires").add("x-test-neu"));
		assertTrue(sync.unitState(unit).contentLocked());
	}

	/** Die Kassen-Einheit kommt in den Versionsspeicher, wie ein Abholen sie bringt. */
	private List<Version> arrive(UnitKey cash) throws IOException {
		return sync.append("kasse-" + cash.cashRegisterId(), List.of(new Change(cash, List.of(),
				JsonNodeFactory.instance.objectNode().put("id", cash.cashRegisterId()), false)));
	}

	/** Takte, bis ein Vollabgleich seit dem Start zu Ende lief. */
	private void untilFullSync() {
		for (int i = 0; !sync.fullSyncCompletedSinceStart(); i++) {
			assertTrue(i < 100, "der Vollabgleich endet nicht");
			TestSyncServices.tick(sync);
		}
	}

	// <-- Bauteile

	@Test
	void pendingAfterUpdateUntilFullSync() throws IOException {
		shared();
		Event event = saved("Prunksitzung", "Freitag");
		markHead(event);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);
		sync.setListener(new SyncListener() {
			@Override
			public void fullSyncCompleted() {
				repository.cashRegistersSettled();
			}
		});

		assertFalse(sync.fullSyncCompletedSinceStart());
		assertTrue(repository.cashRegistersPending(event.getId()), "verbunden, auch wenn nicht erreichbar");
		TestSyncServices.tick(sync);
		assertTrue(repository.cashRegistersPending(event.getId()), "ein Takt ohne Ordner ändert nichts");

		reachable.set(true);
		untilFullSync();

		assertFalse(repository.cashRegistersPending(event.getId()), "endet mit dem ersten Vollabgleich");
		String head = Set.of(UnitKey.event(event.getId())).toString();
		assertEquals(List.of("changing Prunksitzung", "changed Prunksitzung " + head), recorder.calls,
				"wie eine fremde Änderung am Kopf");

		repository.cashRegistersSettled();
		assertEquals(2, recorder.calls.size(), "das Ende wird einmal gemeldet");
	}

	@Test
	void settledNotifiesEveryEventDespiteThrowingListener() throws IOException {
		shared();
		Event first = saved("Prunksitzung", "Freitag");
		Event second = saved("Herrensitzung", "Samstag");
		markHead(first);
		markHead(second);
		repository.addListener(new RepositoryListener<Event>() {
			@Override
			public void changingByOthers(Event event) {
				throw new IllegalStateException("Test");
			}
		});
		Recorder recorder = new Recorder();
		repository.addListener(recorder);
		sync.setListener(new SyncListener() {
			@Override
			public void fullSyncCompleted() {
				repository.cashRegistersSettled();
			}
		});

		reachable.set(true);
		untilFullSync();

		assertEquals(2, recorder.calls.stream().filter(call -> call.startsWith("changed ")).count());
	}

	@Test
	void neverPendingInSingleMode() throws IOException {
		single();
		Event event = saved("Prunksitzung", "Freitag");
		markHead(event);

		assertTrue(sync.fullSyncCompletedSinceStart(), "im Einzelbetrieb immer");
		assertFalse(repository.cashRegistersPending(event.getId()));
	}

	@Test
	void pendingEndsWhenCashArrives() throws IOException {
		shared();
		Event event = saved("Prunksitzung", "Freitag");
		markHead(event);
		Event other = saved("Kappensitzung", "Samstag");
		assertTrue(repository.cashRegistersPending(event.getId()));
		assertFalse(repository.cashRegistersPending(other.getId()), "ohne Merkmal am Kopf");

		arrive(UnitKey.cashRegister(event.getId(), CashRegister.SHARED_ID));

		assertFalse(repository.cashRegistersPending(event.getId()));
	}

	@Test
	void deletedCashUnitCountsAsArrived() throws IOException {
		shared();
		Event event = saved("Prunksitzung", "Freitag");
		markHead(event);
		UnitKey cash = UnitKey.cashRegister(event.getId(), CashRegister.SHARED_ID);
		Version created = arrive(cash).get(0);
		sync.append("kasse-weg", List.of(new Change(cash, List.of(created.id()), null, true)));
		assertTrue(sync.cashRegisterSnapshots(event.getId()).get(cash).deleted());

		assertFalse(repository.cashRegistersPending(event.getId()));
	}

	@Test
	void pendingUsesLastLiveHeadOfDeletedEvent() throws IOException {
		shared();
		Event marked = saved("Prunksitzung", "Freitag");
		Version head = markHead(marked);
		sync.append("event-weg", List.of(new Change(UnitKey.event(marked.getId()), List.of(head.id()), null, true)));
		Event plain = saved("Kappensitzung", "Samstag");
		repository.deleteEvent(plain);

		assertTrue(repository.cashRegistersPending(marked.getId()), "der letzte lebende Kopf trug das Merkmal");
		assertFalse(repository.cashRegistersPending(plain.getId()));
		assertFalse(repository.cashRegistersPending(UUID.randomUUID().toString()), "unbekanntes Event");
	}

	@Test
	void lockedCashRegistersIncludesWaiting() throws IOException {
		single();
		Event event = new Event();
		event.setName("Prunksitzung");
		event.addPresentation("Freitag");
		CashRegisters.selectShared(event, true);
		CashRegister waiting = CashRegisters.addFree(event);
		CashRegister fine = CashRegisters.addFree(event);
		repository.saveEvent(event);
		lock(UnitKey.cashRegister(event.getId(), CashRegister.SHARED_ID));
		UnitKey waitingUnit = UnitKey.cashRegister(event.getId(), waiting.getId());
		Version missing = foreign.write(waitingUnit, "Fremd", payload -> payload.put("name", "Abendkasse"));
		Files.delete(appData.resolve("data").resolve(waitingUnit.relativePath()).resolve(missing.id() + ".json"));
		assertEquals(Status.WAITING, sync.unitState(waitingUnit).status());

		Map<String, LockedCashRegister> locked = repository.lockedCashRegisters(event.getId());

		assertEquals(Map.of(CashRegister.SHARED_ID, new LockedCashRegister(CashRegister.SHARED_ID, Status.LOCKED_FORMAT, false),
				waiting.getId(), new LockedCashRegister(waiting.getId(), Status.WAITING, true)), locked);
		assertFalse(locked.containsKey(fine.getId()));
	}

	@Test
	void presentationUnavailableWhenLocked() throws IOException {
		single();
		Event event = saved("Prunksitzung", "Freitag", "Samstag", "Sonntag");
		Presentation friday = event.getPresentations().get(0);
		Presentation saturday = event.getPresentations().get(1);
		Presentation sunday = event.getPresentations().get(2);
		event.deletePresentation(sunday);
		repository.saveEvent(event);
		lock(UnitKey.presentation(event.getId(), friday.getId()));
		StoreEventRepository fresh = TestRepositories.events(sync);
		assertEquals(List.of(saturday.getId()),
				fresh.findEvent(event.getId()).getPresentations().stream().map(Presentation::getId).toList());

		assertTrue(fresh.presentationUnavailable(event.getId(), friday.getId()), "gesperrt, fehlt im Modell");
		assertFalse(fresh.presentationUnavailable(event.getId(), saturday.getId()), "im Modell");
		assertFalse(fresh.presentationUnavailable(event.getId(), sunday.getId()), "gelöscht");
		assertFalse(fresh.presentationUnavailable(event.getId(), UUID.randomUUID().toString()), "gab es nie");

		// nur im Modell gelöscht, noch nicht gespeichert: gelöscht, nicht „nicht verfügbar"
		Event shown = fresh.findEvent(event.getId());
		shown.deletePresentation(shown.findPresentation(saturday.getId()));
		assertFalse(fresh.presentationUnavailable(event.getId(), saturday.getId()), "ungespeichert gelöscht");
	}

	@Test
	void deletingEventBlockedByLockedCashRegister() throws IOException {
		single();
		Event event = new Event();
		event.setName("Prunksitzung");
		event.addPresentation("Freitag");
		CashRegisters.selectShared(event, true);
		repository.saveEvent(event);
		lock(UnitKey.cashRegister(event.getId(), CashRegister.SHARED_ID));

		LockedUnitException e = assertThrows(LockedUnitException.class, () -> repository.deleteEvent(event));

		assertEquals(StoreMessages.lockedBelow("Prunksitzung", Status.LOCKED_FORMAT), e.getMessage());
		assertEquals(List.of(event), repository.loadEvents(), "es wurde nichts gelöscht");
		assertTrue(sync.snapshots(List.of(UnitKey.event(event.getId()))).get(UnitKey.event(event.getId())).isLive());
	}
}
