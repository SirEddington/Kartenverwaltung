package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.Version;

class StoreHallRepositoryTest {

	private static final class Recorder implements RepositoryListener<Hall> {
		final List<String> calls = new ArrayList<>();

		@Override
		public void saved(Hall hall) {
			calls.add("saved " + hall.getName());
		}

		@Override
		public void deleted(Hall hall) {
			calls.add("deleted " + hall.getName());
		}
	}

	private static SyncService started(Path appData) {
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		return sync;
	}

	private static Hall named(String name) {
		Hall hall = new Hall();
		hall.setName(name);
		return hall;
	}

	private static long filesIn(Path appData) throws IOException {
		try (Stream<Path> walk = Files.walk(appData.resolve("data"))) {
			return walk.filter(Files::isRegularFile).count();
		}
	}

	@Test
	void savedHallLoadsInNewInstance(@TempDir Path appData) {
		StoreHallRepository repository = TestRepositories.storeHalls(started(appData));
		Hall hall = named("Stadthalle");
		hall.setDescription("Große Halle");
		hall.setHallWidth(24.5);
		hall.addSeatingPlan();
		hall.addHallObject();
		repository.saveHall(hall);

		StoreHallRepository fresh = TestRepositories.storeHalls(started(appData));
		List<Hall> loaded = fresh.loadHalls();

		assertEquals(1, loaded.size());
		Hall copy = loaded.get(0);
		assertNotSame(hall, copy);
		assertEquals(hall.getId(), copy.getId());
		assertEquals("Stadthalle", copy.getName());
		assertEquals("Große Halle", copy.getDescription());
		assertEquals(24.5, copy.getHallWidth());
		assertEquals(1, copy.getSeatingPlans().size());
		assertEquals(1, copy.getHallObjects().size());
		assertTrue(fresh.getAndClearLoadWarnings().isEmpty());
	}

	@Test
	void findByIdUsesCache(@TempDir Path appData) {
		StoreHallRepository repository = TestRepositories.storeHalls(started(appData));
		Hall hall = named("Stadthalle");
		repository.saveHall(hall);

		assertSame(hall, repository.findById(hall.getId()));
		assertSame(hall, repository.loadHalls().get(0));
		assertNull(repository.findById("unbekannt"));
		assertNull(repository.findById(null));
		assertNotSame(repository.loadHalls(), repository.loadHalls());
	}

	@Test
	void hallsAndEventsDoNotSeeEachOther(@TempDir Path appData) {
		SyncService sync = started(appData);
		TestRepositories.storeHalls(sync).saveHall(named("Stadthalle"));
		Event event = new Event();
		event.setName("Prunksitzung");
		TestRepositories.storeEvents(sync).saveEvent(event);

		assertEquals(1, TestRepositories.storeHalls(sync).loadHalls().size());
		assertEquals(1, TestRepositories.storeEvents(sync).loadEvents().size());
	}

	@Test
	void saveWithoutChangesWritesNothing(@TempDir Path appData) throws IOException {
		StoreHallRepository repository = TestRepositories.storeHalls(started(appData));
		Hall hall = named("Stadthalle");
		hall.addSeatingPlan();
		repository.saveHall(hall);
		long before = filesIn(appData);

		repository.saveHall(hall);
		repository.saveHalls(List.of(hall));

		assertEquals(before, filesIn(appData));
	}

	@Test
	void saveAfterRestartWithoutChangesWritesNothing(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Hall hall = named("Stadthalle");
		hall.addSeatingPlan();
		hall.addHallObject();
		TestRepositories.storeHalls(sync).saveHall(hall);
		sync.flushIndex();
		long before = filesIn(appData);

		StoreHallRepository fresh = TestRepositories.storeHalls(started(appData));
		fresh.saveHalls(fresh.loadHalls());

		assertEquals(before, filesIn(appData));
	}

	@Test
	void changedHallWritesOneNewVersion(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreHallRepository repository = TestRepositories.storeHalls(sync);
		Hall hall = named("Stadthalle");
		repository.saveHall(hall);

		hall.setName("Festhalle");
		repository.saveHall(hall);

		assertEquals(2, sync.history(UnitKey.hall(hall.getId())).size());
		assertEquals("Festhalle", TestRepositories.storeHalls(started(appData)).loadHalls().get(0).getName());
	}

	@Test
	void deleteHallWritesOnlyDeletion(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreHallRepository repository = TestRepositories.storeHalls(sync);
		Hall deleted = named("Stadthalle");
		Hall kept = named("Festhalle");
		repository.saveHalls(List.of(deleted, kept));

		repository.deleteHalls(List.of(deleted));

		List<Version> history = sync.history(UnitKey.hall(deleted.getId()));
		assertEquals(2, history.size());
		assertEquals(1, history.stream().filter(Version::deleted).count());
		assertEquals(1, sync.history(UnitKey.hall(kept.getId())).size());
		assertEquals(List.of(kept.getId()), repository.loadHalls().stream().map(Hall::getId).toList());
		StoreHallRepository fresh = TestRepositories.storeHalls(started(appData));
		assertEquals(List.of(kept.getId()), fresh.loadHalls().stream().map(Hall::getId).toList());
		assertNull(fresh.findById(deleted.getId()));
	}

	@Test
	void deleteOfNeverSavedHallOnlyNotifies(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		StoreHallRepository repository = TestRepositories.storeHalls(sync);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);

		repository.deleteHall(named("Nie gespeichert"));
		repository.deleteHall(null);

		assertEquals(List.of("deleted Nie gespeichert"), recorder.calls);
		assertTrue(sync.hallIds().isEmpty());
	}

	@Test
	void listenerGetsSavedAndDeleted(@TempDir Path appData) {
		StoreHallRepository repository = TestRepositories.storeHalls(started(appData));
		Recorder recorder = new Recorder();
		repository.addListener(recorder);
		Hall hall = named("Stadthalle");

		repository.saveHall(hall);
		repository.saveHalls(List.of(hall));
		repository.deleteHall(hall);

		assertEquals(List.of("saved Stadthalle", "saved Stadthalle", "deleted Stadthalle"), recorder.calls);
	}

	@Test
	void loadBeforeStartupIsRefused(@TempDir Path appData) {
		SyncService notStarted = TestSyncServices.singleMode(appData);
		StoreHallRepository repository = TestRepositories.storeHalls(notStarted);

		IllegalStateException load = assertThrows(IllegalStateException.class, repository::loadHalls);
		assertThrows(IllegalStateException.class, () -> repository.findById("x"));
		assertThrows(IllegalStateException.class, () -> repository.saveHall(named("Zu früh")));

		assertEquals("Der Versionsspeicher wurde noch nicht gestartet.", load.getMessage());
		notStarted.startup(null, p -> {
		});
		assertTrue(repository.loadHalls().isEmpty());
	}

	@Test
	void deleteOfLockedHallIsRefused(@TempDir Path appData) throws IOException {
		SyncService sync = started(appData);
		Hall hall = named("Stadthalle");
		TestRepositories.storeHalls(sync).saveHall(hall);
		UnitKey unit = UnitKey.hall(hall.getId());
		Version first = sync.unitState(unit).head();
		ObjectNode changed = first.payload().deepCopy();
		changed.put("description", "geändert");
		Version second = sync.append("test", List.of(new Change(unit, List.of(first.id()), changed, false))).get(0);
		// die zweite Version wird beschädigt: Die Kette endet vor ihr, die erste bleibt lesbar
		Files.writeString(appData.resolve("data/halls/" + hall.getId() + "/" + second.id() + ".json"), "kaputt");

		SyncService restarted = started(appData);
		StoreHallRepository repository = TestRepositories.storeHalls(restarted);
		Hall loaded = repository.loadHalls().get(0);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);

		long versionsBefore = restarted.history(unit).size();

		assertThrows(LockedUnitException.class, () -> repository.deleteHall(loaded));

		assertEquals(versionsBefore, restarted.history(unit).size());
		assertTrue(repository.loadHalls().contains(loaded));
		assertTrue(recorder.calls.isEmpty());
	}

	/** Uhr, die der Test von Hand stellt: Jeder Speicherstand bekommt so seinen eigenen Zeitpunkt. */
	private static final class SteppedClock extends Clock {
		private Instant now = Instant.parse("2026-10-27T10:00:00Z");

		/** Stellt die Uhr {@code minutes} Minuten vor. */
		void advance(int minutes) {
			now = now.plusSeconds(60L * minutes);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@Test
	void restoreHallToEarlierVersion(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		SyncService sync = TestSyncServices.singleMode(appData, clock);
		sync.startup(null, p -> {
		});
		StoreHallRepository repository = TestRepositories.storeHalls(sync);
		Hall hall = named("Stadthalle");
		repository.saveHall(hall);
		SavePoint first = repository.savePoints(hall.getId()).get(0);
		clock.advance(10);
		hall.setName("Festhalle");
		repository.saveHall(hall);
		assertEquals(2, repository.savePoints(hall.getId()).size());
		long before = filesIn(appData);

		assertEquals("Stadthalle", repository.hallAt(hall.getId(), first).getName());
		assertEquals(before, filesIn(appData));
		assertSame(hall, repository.findById(hall.getId()));
		clock.advance(10);
		Restored<Hall> restored = repository.restoreHall(hall.getId(), first, h -> List.of("Hinweis"));

		assertEquals(List.of("Hinweis"), restored.notes());
		assertSame(restored.restored(), repository.findById(hall.getId()));

		StoreHallRepository fresh = TestRepositories.storeHalls(started(appData));
		assertEquals("Stadthalle", fresh.loadHalls().get(0).getName());
		assertEquals(3, repository.savePoints(hall.getId()).size());
		assertEquals(List.of(new KnownEntity(hall.getId(), "Stadthalle", false)), fresh.knownHalls());
	}

	@Test
	void restoreHallAfterOutsideChangesBuildsOnCurrentEnd(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		SyncService sync = TestSyncServices.singleMode(appData, clock);
		sync.startup(null, p -> {
		});
		StoreHallRepository repository = TestRepositories.storeHalls(sync);
		Hall hall = named("Stadthalle");
		repository.saveHall(hall);
		SavePoint first = repository.savePoints(hall.getId()).get(0);
		clock.advance(10);
		// eine Änderung, die nach dem Laden dieses Repositorys von anderer Seite dazukommt
		StoreHallRepository other = TestRepositories.storeHalls(sync);
		Hall outside = other.loadHalls().get(0);
		outside.setName("Fremd");
		other.saveHall(outside);
		clock.advance(10);

		Restored<Hall> restored = repository.restoreHall(hall.getId(), first, h -> List.of());

		List<Version> chain = sync.unitState(UnitKey.hall(hall.getId())).chain();
		assertEquals(3, chain.size());
		assertEquals(chain.get(1).id(), chain.get(2).firstParent());
		assertEquals("Stadthalle", chain.get(2).payload().get("name").asText());
		assertEquals(3, repository.savePoints(hall.getId()).size());
		assertSame(restored.restored(), repository.findById(hall.getId()));
		assertEquals("Stadthalle", TestRepositories.storeHalls(started(appData)).loadHalls().get(0).getName());
	}

	@Test
	void restoreBringsBackDeletedHallButNotToItsDeletionPoint(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		SyncService sync = TestSyncServices.singleMode(appData, clock);
		sync.startup(null, p -> {
		});
		StoreHallRepository repository = TestRepositories.storeHalls(sync);
		Hall hall = named("Stadthalle");
		repository.saveHall(hall);
		SavePoint saved = repository.savePoints(hall.getId()).get(0);
		clock.advance(10);
		repository.deleteHall(hall);
		SavePoint deletion = repository.savePoints(hall.getId()).get(0);

		assertEquals(List.of(new KnownEntity(hall.getId(), "Stadthalle", true)), repository.knownHalls());
		LockedUnitException refused = assertThrows(LockedUnitException.class,
				() -> repository.restoreHall(hall.getId(), deletion, h -> List.of()));
		assertEquals("Zu diesem Speicherstand war die Halle gelöscht.", refused.getMessage());
		clock.advance(10);
		repository.restoreHall(hall.getId(), saved, h -> List.of());

		StoreHallRepository fresh = TestRepositories.storeHalls(started(appData));
		assertEquals("Stadthalle", fresh.loadHalls().get(0).getName());
		assertEquals(List.of(new KnownEntity(hall.getId(), "Stadthalle", false)), fresh.knownHalls());
	}

	@Test
	void refusedHallRestoreLeavesCacheAndBaselineUntouched(@TempDir Path appData) throws IOException {
		SteppedClock clock = new SteppedClock();
		SyncService sync = TestSyncServices.singleMode(appData, clock);
		sync.startup(null, p -> {
		});
		StoreHallRepository repository = TestRepositories.storeHalls(sync);
		Hall hall = named("Stadthalle");
		repository.saveHall(hall);
		clock.advance(10);
		// die Halle wird von anderer Seite gelöscht, nachdem dieses Repository sie geladen hat
		StoreHallRepository other = TestRepositories.storeHalls(sync);
		other.deleteHall(other.loadHalls().get(0));
		SavePoint deletion = other.savePoints(hall.getId()).get(0);
		long before = filesIn(appData);

		LockedUnitException refused = assertThrows(LockedUnitException.class,
				() -> repository.restoreHall(hall.getId(), deletion, h -> List.of()));

		assertEquals("Zu diesem Speicherstand war die Halle gelöscht.", refused.getMessage());
		assertEquals(before, filesIn(appData));
		// Cache und Vergleichsstand sind unberührt: Das gehaltene Objekt ist noch dasselbe, und Speichern schreibt nichts
		assertSame(hall, repository.findById(hall.getId()));
		repository.saveHall(hall);
		assertEquals(before, filesIn(appData));
	}

	@Test
	void replaceCachedHallSwapsTheObjectWithoutWriting(@TempDir Path appData) throws IOException {
		StoreHallRepository repository = TestRepositories.storeHalls(started(appData));
		Hall original = named("Vorher");
		repository.saveHall(original);
		long before = filesIn(appData);
		Recorder recorder = new Recorder();
		repository.addListener(recorder);

		Hall replacement = new Hall(original.getId());
		replacement.setName("Nachher");
		repository.replaceCachedHall(replacement);
		repository.replaceCachedHall(named("Unbekannt"));

		assertEquals(1, repository.loadHalls().size());
		assertSame(replacement, repository.findById(original.getId()));
		assertEquals(before, filesIn(appData));
		assertTrue(recorder.calls.isEmpty());
	}
}
