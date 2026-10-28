package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestClock;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

/**
 * Fremde Stände im Hallen-Repository. "Der andere Rechner" ist meist ein zweites Repository auf demselben Dienst: Es
 * speichert, und das erste bekommt die Einheiten mit {@link StoreHallRepository#applyForeign} gemeldet.
 */
class StoreHallRepositoryForeignTest {

	/** Merkt sich die Benachrichtigungen über fremde Stände. */
	private static final class Recorder implements RepositoryListener<Hall> {
		final List<String> calls = new ArrayList<>();
		final List<ForeignBatch<Hall>> batches = new ArrayList<>();
		final List<Hall> entities = new ArrayList<>();

		@Override
		public void changingByOthers(Hall hall) {
			calls.add("changing " + hall.getName());
			entities.add(hall);
		}

		@Override
		public void changedByOthers(Hall hall, ForeignBatch<Hall> batch) {
			calls.add("changed " + hall.getName());
			batches.add(batch);
			entities.add(hall);
		}

		@Override
		public void addedByOthers(Hall hall) {
			calls.add("added " + hall.getName());
			entities.add(hall);
		}

		@Override
		public void removedByOthers(Hall hall) {
			calls.add("removed " + hall.getName());
			entities.add(hall);
		}
	}

	@TempDir
	Path appData;

	private SyncService sync;
	private StoreHallRepository mine;
	private Hall hall;
	private final Recorder recorder = new Recorder();
	private ForeignVersions foreign;

	/** Eine Halle "Stadthalle", gespeichert und im Cache von {@code mine}. */
	@BeforeEach
	void setUp() {
		sync = TestSyncServices.singleMode(appData);
		foreign = new ForeignVersions(sync, appData);
		sync.startup(null, p -> {
		});
		hall = new Hall();
		hall.setName("Stadthalle");
		StoreHallRepository writer = TestRepositories.halls(sync);
		writer.saveHall(hall);
		mine = TestRepositories.halls(sync);
		mine.addListener(recorder);
		hall = mine.findById(hall.getId());
	}

	// Bauteile -->

	private UnitKey unit() {
		return UnitKey.hall(hall.getId());
	}

	/** Ein zweites Repository auf demselben Dienst lädt die Halle. */
	private StoreHallRepository other() {
		return TestRepositories.halls(sync);
	}

	/** Der andere Rechner benennt die Halle um und speichert. */
	private void otherRenames(String name) {
		StoreHallRepository other = other();
		other.findById(hall.getId()).setName(name);
		other.saveHall(other.findById(hall.getId()));
	}

	/** Die Anzahl aller Dateien (Versionen und Ansprüche) im lokalen Bestand. */
	private long files() throws IOException {
		return foreign.files();
	}

	/**
	 * Schreibt eine Version des Rechners "Fremd" auf das Ende der Kette und bringt sie in den Index, wie es das Abholen
	 * aus dem gemeinsamen Ordner tut. {@code changes} ändert die Daten der Vorgängerversion.
	 */
	private Version foreignVersion(UnitKey unit, Consumer<ObjectNode> changes) throws IOException {
		return foreign.write(unit, "Fremd", changes);
	}

	private Version foreignName(String name) throws IOException {
		return foreignVersion(unit(), payload -> payload.put("name", name));
	}

	// <-- Bauteile

	@Test
	void foreignHallChangeReplacesTheCachedObject() throws IOException {
		otherRenames("Bürgerhaus");

		ForeignResult result = mine.applyForeign(List.of(unit()));

		Hall replaced = mine.findById(hall.getId());
		assertNotSame(hall, replaced);
		assertEquals("Bürgerhaus", replaced.getName());
		assertEquals("Stadthalle", hall.getName(), "das alte Objekt bleibt unverändert");
		assertEquals(1, mine.loadHalls().size());
		assertTrue(result.deferred().isEmpty());
		assertTrue(result.warnings().isEmpty());
		assertEquals(List.of("changing Stadthalle", "changed Bürgerhaus"), recorder.calls);
		assertSame(hall, recorder.entities.get(0));
		assertSame(replaced, recorder.entities.get(1));
		assertEquals(Set.of(unit()), recorder.batches.get(0).units());
		assertEquals(sync.snapshots(List.of(unit())).get(unit()).version(), mine.knownUnit(unit()).version());
	}

	@Test
	void replacedHallKeepsTheNameOrder() throws IOException {
		Hall second = new Hall();
		second.setName("Ahlhalle");
		other().saveHall(second);
		mine.applyForeign(List.of(UnitKey.hall(second.getId())));
		assertEquals(List.of("Ahlhalle", "Stadthalle"), mine.loadHalls().stream().map(Hall::getName).toList());

		otherRenames("Zeughaus");
		mine.applyForeign(List.of(unit()));

		assertEquals(List.of("Ahlhalle", "Zeughaus"), mine.loadHalls().stream().map(Hall::getName).toList());
	}

	@Test
	void foreignHallIsAddedAndRemoved() throws IOException {
		StoreHallRepository other = other();
		Hall created = new Hall();
		created.setName("Bürgerhaus");
		other.saveHall(created);
		UnitKey createdUnit = UnitKey.hall(created.getId());

		ForeignResult added = mine.applyForeign(List.of(createdUnit));

		Hall seen = mine.findById(created.getId());
		assertNotNull(seen);
		assertEquals("Bürgerhaus", seen.getName());
		assertEquals(List.of("Bürgerhaus", "Stadthalle"), mine.loadHalls().stream().map(Hall::getName).toList());
		assertEquals(List.of("added Bürgerhaus"), recorder.calls);
		assertSame(seen, recorder.entities.get(0));
		assertTrue(added.deferred().isEmpty());
		assertTrue(added.warnings().isEmpty());

		other.deleteHall(created);
		ForeignResult removed = mine.applyForeign(List.of(createdUnit));

		assertNull(mine.findById(created.getId()));
		assertEquals(List.of("Stadthalle"), mine.loadHalls().stream().map(Hall::getName).toList());
		assertEquals(List.of("added Bürgerhaus", "removed Bürgerhaus"), recorder.calls);
		assertSame(seen, recorder.entities.get(1));
		assertTrue(removed.deferred().isEmpty());
		assertTrue(removed.warnings().isEmpty());
	}

	@Test
	void locallyChangedHallIsDeferred() throws IOException {
		hall.setName("Lokal");
		VersionId before = mine.knownUnit(unit()).version();
		Version foreign = foreignName("Fremd-Halle");

		ForeignResult result = mine.applyForeign(List.of(unit()));

		assertEquals(List.of(new Deferral(unit(), foreign.id(), "Fremd", hall)), result.deferred());
		assertSame(hall, mine.findById(hall.getId()));
		assertEquals("Lokal", hall.getName());
		assertEquals(before, mine.knownUnit(unit()).version(), "der Vergleichsstand bleibt");
		assertTrue(recorder.calls.isEmpty());
		assertEquals(result.deferred(), mine.retryDeferred().deferred(), "bleibt zurückgestellt");

		// "Nicht speichern": Die lokale Änderung ist weg, der fremde Stand kommt
		hall.setName("Stadthalle");
		ForeignResult retried = mine.retryDeferred();

		assertTrue(retried.deferred().isEmpty());
		assertEquals("Fremd-Halle", mine.findById(hall.getId()).getName());
		assertEquals(List.of("changing Stadthalle", "changed Fremd-Halle"), recorder.calls);
	}

	@Test
	void blockedHallIsDeferredAndAppliedOnRetry() throws IOException {
		AtomicBoolean blocked = new AtomicBoolean(true);
		mine.setBlockedCheck(h -> blocked.get());
		Version foreign = foreignName("Fremd-Halle");

		ForeignResult result = mine.applyForeign(List.of(unit()));

		assertEquals(List.of(new Deferral(unit(), foreign.id(), "Fremd", hall)), result.deferred());
		assertSame(hall, mine.findById(hall.getId()));
		assertEquals("Stadthalle", hall.getName());
		assertTrue(recorder.calls.isEmpty());

		blocked.set(false);
		ForeignResult retried = mine.retryDeferred();

		assertTrue(retried.deferred().isEmpty());
		assertEquals("Fremd-Halle", mine.findById(hall.getId()).getName());
		assertEquals(foreign.id(), mine.knownUnit(unit()).version());
		assertEquals(List.of("changing Stadthalle", "changed Fremd-Halle"), recorder.calls);
	}

	@Test
	void lostOwnHallVersionIsNotApplied(@TempDir Path dir) throws IOException {
		TestClock clock = new TestClock();
		Path shared = Files.createDirectories(dir.resolve("shared"));
		SyncService a = TestSyncServices.sharedMode(dir.resolve("a"), shared, "a", clock);
		SyncService b = TestSyncServices.sharedMode(dir.resolve("b"), shared, "b", clock);
		StoreHallRepository repositoryA = TestRepositories.halls(a);
		Hall hallA = new Hall();
		hallA.setName("Stadthalle");
		repositoryA.saveHall(hallA);
		TestSyncServices.tick(a);
		clock.advance(Duration.ofSeconds(30));
		TestSyncServices.tick(b);
		StoreHallRepository repositoryB = TestRepositories.halls(b);
		Hall hallB = repositoryB.loadHalls().get(0);
		hallB.setName("Bert");
		repositoryB.saveHall(hallB);
		TestSyncServices.tick(b);
		hallA.setName("Anna");
		repositoryA.saveHall(hallA);
		clock.advance(Duration.ofSeconds(30));
		// A lädt hoch, unterliegt und holt die Version von B ab
		TestSyncServices.tick(a);
		UnitKey unit = UnitKey.hall(hallA.getId());
		assertEquals("Bert", a.snapshots(List.of(unit)).get(unit).payload().get("name").asText());
		UnitSnapshot before = repositoryA.knownUnit(unit);

		ForeignResult result = repositoryA.applyForeign(List.of(unit));

		assertSame(hallA, repositoryA.findById(hallA.getId()));
		assertEquals("Anna", hallA.getName());
		assertSame(before, repositoryA.knownUnit(unit));
		assertTrue(result.deferred().isEmpty());
		assertTrue(result.warnings().isEmpty());
	}

	@Test
	void saveAfterForeignHallMergeWritesNothing() throws IOException {
		otherRenames("Bürgerhaus");
		mine.applyForeign(List.of(unit()));
		long before = files();

		mine.saveHall(mine.findById(hall.getId()));
		mine.saveHalls(mine.loadHalls());

		assertEquals(before, files(), "der fremde Stand wird weder gelöscht noch neu geschrieben");
	}

	@Test
	void staleHallObjectIsRefusedAfterForeignReplacement() throws IOException {
		otherRenames("Bürgerhaus");
		mine.applyForeign(List.of(unit()));
		AtomicInteger saved = new AtomicInteger();
		mine.addListener(new RepositoryListener<>() {
			@Override
			public void saved(Hall entity) {
				saved.incrementAndGet();
			}
		});
		long before = files();
		VersionId known = mine.knownUnit(unit()).version();
		hall.setName("Alt geändert");

		LockedUnitException refused = assertThrows(LockedUnitException.class, () -> mine.saveHall(hall));

		assertEquals("„Alt geändert“ wurde inzwischen auf einem anderen Rechner geändert. Bitte die Ansicht neu öffnen.",
				refused.getMessage());
		assertThrows(LockedUnitException.class, () -> mine.saveHalls(List.of(hall)));
		assertEquals(before, files(), "nichts geschrieben");
		assertEquals(0, saved.get(), "nichts gemeldet");
		assertEquals(known, mine.knownUnit(unit()).version());
		assertEquals("Bürgerhaus", mine.findById(hall.getId()).getName());

		Hall current = mine.findById(hall.getId());
		current.setName("Neu");
		mine.saveHall(current);
		assertEquals(1, saved.get());
		assertEquals("Neu", other().findById(hall.getId()).getName());
	}

	@Test
	void lockedReadableForeignHallIsAppliedWithWarning() throws IOException {
		foreignName("Fremd-Halle");
		Version damaged = foreignName("Kaputt");
		Files.writeString(appData.resolve("data/" + unit().relativePath() + "/" + damaged.id() + ".json"), "kaputt");
		sync.unitState(unit());

		ForeignResult result = mine.applyForeign(List.of(unit()));

		assertEquals("Fremd-Halle", mine.findById(hall.getId()).getName());
		assertEquals(List.of(StoreMessages.damaged(unit().relativePath())), result.warnings());
		Hall replaced = mine.findById(hall.getId());
		replaced.setName("Neu");
		assertThrows(LockedUnitException.class, () -> mine.saveHall(replaced));
	}

	@Test
	void knownHallThatBecomesLockedWarnsAndKeepsTheModel() throws IOException {
		foreignName("Fremd-Halle");
		mine.applyForeign(List.of(unit()));
		Hall applied = mine.findById(hall.getId());
		Version damaged = foreignName("Kaputt");
		Files.writeString(appData.resolve("data/" + unit().relativePath() + "/" + damaged.id() + ".json"), "kaputt");
		sync.unitState(unit());
		recorder.calls.clear();

		ForeignResult result = mine.applyForeign(List.of(unit()));

		assertSame(applied, mine.findById(hall.getId()));
		assertEquals("Fremd-Halle", applied.getName());
		assertEquals(List.of(StoreMessages.damaged(unit().relativePath())), result.warnings());
		assertTrue(recorder.calls.isEmpty());
		applied.setName("Neu");
		assertThrows(LockedUnitException.class, () -> mine.saveHall(applied), "der Zustand ist im Vergleichsstand");
		assertTrue(mine.applyForeign(List.of(unit())).warnings().isEmpty(), "die Warnung kommt nur einmal");
	}

	@Test
	void secondReportOfAppliedHallIsKnown() throws IOException {
		otherRenames("Bürgerhaus");
		mine.applyForeign(List.of(unit()));

		mine.applyForeign(List.of(unit()));

		assertEquals(List.of("changing Stadthalle", "changed Bürgerhaus"), recorder.calls);
	}

	@Test
	void unreadableForeignHallLeavesTheCacheWithWarning() throws IOException {
		foreignVersion(unit(), payload -> payload.put("id", "anderes"));

		ForeignResult result = mine.applyForeign(List.of(unit()));

		assertNull(mine.findById(hall.getId()));
		assertEquals(1, result.warnings().size());
		assertTrue(result.warnings().get(0).contains(hall.getId()), result.warnings().get(0));
		assertEquals(List.of("removed Stadthalle"), recorder.calls);
		assertSame(hall, recorder.entities.get(0));
	}

	@Test
	void hallDeletedElsewhereIsRefusedFromAStaleView() throws IOException {
		StoreHallRepository other = other();
		other.deleteHall(other.findById(hall.getId()));
		mine.applyForeign(List.of(unit()));
		assertNull(mine.findById(hall.getId()));
		long before = files();
		// der Hallen-Editor zeigt die Halle noch (Rückfrage beim Verlassen abgebrochen)
		hall.setName("Alt geändert");

		LockedUnitException refused = assertThrows(LockedUnitException.class, () -> mine.saveHall(hall));

		assertEquals("„Alt geändert“ wurde inzwischen auf einem anderen Rechner geändert. Bitte die Ansicht neu öffnen.",
				refused.getMessage());
		assertEquals(before, files(), "die fremde Löschung wird nicht überschrieben");
		assertNull(mine.findById(hall.getId()));
	}

	@Test
	void hallUnreadableElsewhereIsRefusedFromAStaleView() throws IOException {
		foreignVersion(unit(), payload -> payload.put("id", "anderes"));
		mine.applyForeign(List.of(unit()));
		long before = files();

		assertThrows(LockedUnitException.class, () -> mine.saveHall(hall));

		assertEquals(before, files());
	}

	@Test
	void deletingStaleHallObjectIsRejected() throws IOException {
		StoreHallRepository other = other();
		Hall theirs = other.findById(hall.getId());
		other.deleteHall(theirs);
		mine.applyForeign(List.of(unit()));
		long before = files();
		AtomicInteger deleted = new AtomicInteger();
		mine.addListener(new RepositoryListener<>() {
			@Override
			public void deleted(Hall entity) {
				deleted.incrementAndGet();
			}
		});

		LockedUnitException removed = assertThrows(LockedUnitException.class, () -> mine.deleteHall(hall));

		assertEquals("„Stadthalle“ wurde inzwischen auf einem anderen Rechner geändert. Bitte die Ansicht neu öffnen.",
				removed.getMessage());
		assertEquals(before, files(), "nichts geschrieben");
		// der andere Rechner holt die Halle zurück: Die Ansicht hält das Objekt von vorher
		other.saveHall(theirs);
		mine.applyForeign(List.of(unit()));
		Hall revived = mine.findById(hall.getId());
		assertNotSame(hall, revived);
		long afterRevive = files();

		assertThrows(LockedUnitException.class, () -> mine.deleteHall(hall));
		assertThrows(LockedUnitException.class, () -> mine.deleteHalls(List.of(hall)));

		assertEquals(afterRevive, files(), "nichts geschrieben");
		assertEquals(0, deleted.get(), "nichts gemeldet");
		assertSame(revived, mine.findById(hall.getId()));
		assertFalse(sync.unitState(unit()).head().deleted());

		mine.deleteHall(revived);
		assertNull(mine.findById(hall.getId()));
		assertEquals(1, deleted.get());
	}

	@Test
	void hallDeletedLocallyCanBeSavedAgain() throws IOException {
		mine.deleteHall(hall);

		mine.saveHall(hall);

		assertFalse(sync.unitState(unit()).head().deleted());
		assertSame(hall, mine.findById(hall.getId()));
	}

	@Test
	void hallRevivedElsewhereIsSavableAgain() throws IOException {
		StoreHallRepository other = other();
		Hall theirs = other.findById(hall.getId());
		other.deleteHall(theirs);
		mine.applyForeign(List.of(unit()));
		other.saveHall(theirs);
		mine.applyForeign(List.of(unit()));
		Hall revived = mine.findById(hall.getId());

		revived.setName("Neu");
		mine.saveHall(revived);

		assertEquals("Neu", sync.unitState(unit()).head().payload().get("name").asText());
	}

	@Test
	void failureOnOneHallDoesNotStopTheOthersAndIsRetried() throws IOException {
		Hall second = new Hall();
		second.setName("Bürgerhaus");
		StoreHallRepository other = other();
		other.saveHall(second);
		mine.applyForeign(List.of(UnitKey.hall(second.getId())));
		recorder.calls.clear();
		Hall seconds = mine.findById(second.getId());
		AtomicBoolean fail = new AtomicBoolean(true);
		mine.setBlockedCheck(h -> {
			if (fail.get() && h == hall) {
				throw new IllegalStateException("Prüfung kaputt");
			}
			return false;
		});
		otherRenames("Zeughaus");
		other.findById(second.getId()).setName("Ahlhalle");
		other.saveHall(other.findById(second.getId()));

		ForeignResult result = mine.applyForeign(List.of(unit(), UnitKey.hall(second.getId())));

		assertEquals(1, result.warnings().size());
		assertTrue(result.deferred().isEmpty());
		assertEquals("Stadthalle", hall.getName());
		assertNotSame(seconds, mine.findById(second.getId()));
		assertEquals("Ahlhalle", mine.findById(second.getId()).getName());
		assertEquals(List.of("changing Bürgerhaus", "changed Ahlhalle"), recorder.calls);

		fail.set(false);
		ForeignResult retried = mine.retryDeferred();

		assertTrue(retried.warnings().isEmpty());
		assertEquals("Zeughaus", mine.findById(hall.getId()).getName());
	}

	@Test
	void eventUnitsAndUnknownLoadAreIgnored() {
		StoreHallRepository fresh = TestRepositories.halls(sync);
		assertTrue(fresh.applyForeign(List.of(unit())).deferred().isEmpty(), "noch nicht geladen: nichts zu tun");

		ForeignResult result = mine.applyForeign(List.of(UnitKey.event("e1")));

		assertTrue(result.deferred().isEmpty());
		assertTrue(recorder.calls.isEmpty());
	}

	@Test
	void removedListenerHearsNothing() throws IOException {
		mine.removeListener(recorder);
		otherRenames("Bürgerhaus");

		mine.applyForeign(List.of(unit()));

		assertTrue(recorder.calls.isEmpty());
	}

	@Test
	void replayOnAHallIsNotSupported() throws IOException {
		otherRenames("Bürgerhaus");
		mine.applyForeign(List.of(unit()));

		ForeignBatch<Hall> batch = recorder.batches.get(0);

		assertThrows(UnsupportedOperationException.class, () -> batch.replayOn(new Hall()));
	}
}
