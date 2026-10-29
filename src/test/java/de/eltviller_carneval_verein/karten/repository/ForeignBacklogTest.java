package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.FaultyFileLayer;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.NioFileLayer;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

class ForeignBacklogTest {

	private static final Logger LOG = Logger.getLogger(ForeignBacklog.class.getName());

	private static final UnitKey E1 = UnitKey.event("e1");
	private static final UnitKey E1_P1 = UnitKey.presentation("e1", "p1");
	private static final UnitKey E2 = UnitKey.event("e2");
	private static final VersionId V1 = VersionId.create(1_000L, "aaaaaaaa", 0);

	@TempDir
	Path appData;

	private final ForeignBacklog backlog = new ForeignBacklog();
	private final List<String> logged = new ArrayList<>();
	private Handler handler;

	@BeforeEach
	void catchLog() {
		handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				logged.add(record.getMessage());
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		LOG.addHandler(handler);
		// die Meldungen nur auffangen, nicht in die Testausgabe schreiben
		LOG.setUseParentHandlers(false);
	}

	@AfterEach
	void releaseLog() {
		LOG.removeHandler(handler);
		LOG.setUseParentHandlers(true);
	}

	private static Deferral deferral(UnitKey unit) {
		return new Deferral(unit, V1, "Kasse", null);
	}

	@Test
	void deferredUnitIsRecheckedAndReported() {
		Deferral deferral = deferral(E1);

		backlog.defer(deferral);

		assertTrue(backlog.isDeferred(E1));
		assertEquals(Set.of(E1), backlog.recheck());
		assertEquals(List.of(deferral), backlog.deferrals());
	}

	@Test
	void holdIsSilentAndLoggedOnce() {
		backlog.hold(E1_P1, "x");
		backlog.hold(E1_P1, "x");

		assertTrue(backlog.isHeld(E1_P1));
		assertTrue(backlog.deferrals().isEmpty());
		assertEquals(Set.of(E1_P1), backlog.recheck());
		assertEquals(List.of("Fremder Stand von " + E1_P1.relativePath() + " wartet: x."), logged);
	}

	@Test
	void holdTakesAUnitOutOfTheDeferredOnes() {
		backlog.defer(deferral(E1_P1));

		backlog.hold(E1_P1, "x");

		assertFalse(backlog.isDeferred(E1_P1));
		assertTrue(backlog.isHeld(E1_P1));
		assertTrue(backlog.deferrals().isEmpty(), "eine wartende Einheit gibt keinen Hinweis");
	}

	@Test
	void recheckListsDeferredBeforeHeld() {
		backlog.hold(E1_P1, "x");
		backlog.defer(deferral(E2));
		backlog.defer(deferral(E1));

		assertEquals(List.of(E2, E1, E1_P1), new ArrayList<>(backlog.recheck()));
	}

	@Test
	void settleClearsBothStates() {
		backlog.defer(deferral(E1));
		backlog.hold(E1, "x");

		backlog.settle(E1);

		assertFalse(backlog.isDeferred(E1));
		assertFalse(backlog.isHeld(E1));
		assertTrue(backlog.recheck().isEmpty());
	}

	@Test
	void forgetOwnerDropsOnlyThatOwner() {
		backlog.defer(deferral(E1));
		backlog.hold(E1_P1, "x");
		backlog.hold(E2, "y");

		backlog.forgetOwner("e1");

		assertEquals(Set.of(E2), backlog.recheck());
		assertFalse(backlog.anyFor("e1"));
		assertTrue(backlog.anyFor("e2"));
	}

	@Test
	void forgetOwnerAlsoKnowsHalls() {
		UnitKey hall = UnitKey.hall("h1");
		backlog.hold(hall, "x");

		assertTrue(backlog.anyFor("h1"));
		backlog.forgetOwner("h1");

		assertFalse(backlog.anyFor("h1"));
	}

	@Test
	void retainKnownDropsVanishedUnits() {
		backlog.defer(deferral(E1));
		backlog.hold(E1_P1, "x");
		backlog.hold(E2, "y");

		backlog.retainKnown(Set.of(E1, E2));

		assertEquals(Set.of(E1, E2), backlog.recheck());
		assertFalse(backlog.isHeld(E1_P1));
	}

	/** Der Stand einer Halle: eine eigene Zusammenführung, die eine fremde Version mit Vermerk für diesen Rechner aufnimmt. */
	private record MergedHall(SyncService sync, FaultyFileLayer layer, UnitKey unit, Version merge) {
		UnitSnapshot current() {
			return sync.snapshots(List.of(unit)).get(unit);
		}
	}

	private MergedHall mergedHall() throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService sync = TestSyncServices.singleMode(appData, layer);
		sync.startup(null, p -> {
		});
		Hall hall = new Hall();
		hall.setName("A");
		TestRepositories.halls(sync).saveHall(hall);
		UnitKey unit = UnitKey.hall(hall.getId());
		ForeignVersions foreign = new ForeignVersions(sync, appData);
		ConflictNote forMe = new ConflictNote(ConflictNote.Kind.FIELDS, sync.machineId(), null, List.of(), null, null, null);
		Version absorbed = foreign.writeUnclaimed(unit, forMe, payload -> payload.put("name", "B"));
		Version merge = foreign.writeMerge(unit, sync.machineId(), "test", List.of(absorbed.id()), null,
				payload -> payload.put("name", "M"));
		assertEquals(Set.of(absorbed.id()), sync.absorbed(List.of(unit)).get(unit));
		assertEquals(merge.id(), sync.snapshots(List.of(unit)).get(unit).version());
		return new MergedHall(sync, layer, unit, merge);
	}

	@Test
	void ownMergeAbsorbingANoteForThisMachineIsDeferredSilently() throws IOException {
		MergedHall hall = mergedHall();

		backlog.defer(hall.current(), List.of(), "Halle", hall.sync());

		assertTrue(backlog.deferrals().get(0).silent());
	}

	@Test
	void ownMergeThatCannotBeReadKeepsTheHintAndWarns() throws IOException {
		MergedHall hall = mergedHall();
		hall.layer().failWhen(p -> p.endsWith(hall.merge().id() + ".json"), "read", new IOException("gesperrt"));

		backlog.defer(hall.current(), List.of(), "Halle", hall.sync());

		assertFalse(backlog.deferrals().get(0).silent(), "ohne Auskunft bleibt der Hinweis");
		assertTrue(logged.stream().anyMatch(message -> message.contains(hall.unit().relativePath())
				&& message.contains("eigene Zusammenführung")), logged.toString());
	}

	@Test
	void runGroupsByOwnerAndIsolatesFailures() {
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		StoreHallRepository halls = TestRepositories.halls(sync);
		Hall a = new Hall();
		a.setName("A");
		Hall b = new Hall();
		b.setName("B");
		halls.saveHall(a);
		halls.saveHall(b);
		UnitKey unitA = UnitKey.hall(a.getId());
		UnitKey unitB = UnitKey.hall(b.getId());
		List<String> calls = new ArrayList<>();

		ForeignResult result = backlog.run(List.of(unitA, unitB, E1), unit -> unit.type() == UnitType.HALL, sync,
				new ForeignBacklog.Step() {
					@Override
					public String ownerOf(UnitKey unit) {
						return unit.hallId();
					}

					@Override
					public void apply(List<UnitSnapshot> ofOwner, Map<UnitKey, List<ChainStep>> chains,
							Map<UnitKey, Set<VersionId>> absorbed, List<String> warnings) {
						calls.add("apply " + ofOwner.get(0).unit().hallId());
						if (ofOwner.get(0).unit().equals(unitA)) {
							throw new IllegalStateException("kaputt");
						}
					}

					@Override
					public void failed(String ownerId, List<UnitSnapshot> ofOwner, RuntimeException e,
							List<String> warnings) {
						calls.add("failed " + ownerId);
						warnings.add("Fehler bei " + ownerId);
						backlog.hold(ofOwner.get(0).unit(), "das Einarbeiten ist gescheitert");
					}
				});

		assertEquals(List.of("apply " + a.getId(), "failed " + a.getId(), "apply " + b.getId()), calls);
		assertEquals(List.of("Fehler bei " + a.getId()), result.warnings());
		assertTrue(result.deferred().isEmpty());
		assertTrue(backlog.isHeld(unitA));
		assertFalse(backlog.isHeld(unitB));
	}
}
