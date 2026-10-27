package de.eltviller_carneval_verein.karten.ui.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.SavePoint;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Entry;

class HallBackupCatalogTest {

	@TempDir
	Path appData;

	private final SteppedClock clock = new SteppedClock();
	private SyncService sync;
	private StoreHallRepository halls;

	@BeforeEach
	void setUp() {
		start();
	}

	/** Startet den Dienst neu auf demselben Ordner, wie nach einem Neustart der App. */
	private void start() {
		sync = TestSyncServices.singleMode(appData, clock);
		sync.startup(null, p -> {
		});
		halls = TestRepositories.halls(sync);
	}

	private HallBackupCatalog catalog() {
		return new HallBackupCatalog(halls);
	}

	private static Hall hall(String name) {
		Hall hall = new Hall();
		hall.setName(name);
		return hall;
	}

	@Test
	void entriesIncludeDeletedHalls() {
		Hall existing = hall("Stadthalle");
		Hall gone = hall("Alte Halle");
		halls.saveHalls(List.of(existing, gone));
		halls.deleteHall(gone);

		assertEquals(List.of(new Entry(gone.getId(), "Alte Halle", true), new Entry(existing.getId(), "Stadthalle", false)), catalog().entries());
		assertEquals("Halle", catalog().entityLabel());
	}

	@Test
	void versionsAreTheSavePointsNewestFirst() throws IOException {
		Hall hall = hall("Stadthalle");
		halls.saveHall(hall);
		clock.advance(10);
		hall.setName("Stadthalle 2");
		halls.saveHall(hall);

		List<SavePoint> versions = catalog().versions(hall.getId());

		assertEquals(2, versions.size());
		assertEquals(halls.savePoints(hall.getId()), versions);
		assertTrue(versions.get(0).at().isAfter(versions.get(1).at()));
	}

	@Test
	void restoreResetsHallAndReplacesCache() throws IOException {
		Hall hall = hall("Stadthalle");
		halls.saveHall(hall);
		SavePoint saved = halls.savePoints(hall.getId()).get(0);
		clock.advance(10);
		hall.setName("Kaputt");
		halls.saveHall(hall);
		clock.advance(10);

		RestoreResult result = catalog().restore(new Entry(hall.getId(), "Kaputt", false), saved);

		assertTrue(result.success(), result.message());
		assertTrue(result.message().startsWith("Halle \"Stadthalle\" auf den Stand vom "), result.message());
		assertTrue(result.message().endsWith(" zurückgesetzt."), result.message());
		assertTrue(result.notes().isEmpty());
		assertEquals("Stadthalle", halls.findById(hall.getId()).getName());
		assertSame(halls.findById(hall.getId()), halls.loadHalls().get(0));
		assertEquals("Stadthalle", TestRepositories.halls(sync).loadHalls().get(0).getName());
	}

	@Test
	void restoreBringsBackADeletedHall() throws IOException {
		Hall hall = hall("Stadthalle");
		halls.saveHall(hall);
		SavePoint saved = halls.savePoints(hall.getId()).get(0);
		clock.advance(10);
		halls.deleteHall(hall);
		clock.advance(10);

		RestoreResult result = catalog().restore(new Entry(hall.getId(), "Stadthalle", true), saved);

		assertTrue(result.success(), result.message());
		assertEquals(List.of(new Entry(hall.getId(), "Stadthalle", false)), catalog().entries());
	}

	@Test
	void restoreOfLockedHallFailsWithText() throws IOException {
		Hall hall = hall("Stadthalle");
		halls.saveHall(hall);
		SavePoint saved = halls.savePoints(hall.getId()).get(0);
		UnitKey unit = UnitKey.hall(hall.getId());
		Version first = sync.unitState(unit).head();
		ObjectNode changed = first.payload().deepCopy();
		changed.put("description", "geändert");
		Version second = sync.append("test", List.of(new Change(unit, List.of(first.id()), changed, false))).get(0);
		Files.writeString(appData.resolve("data/halls/" + hall.getId() + "/" + second.id() + ".json"), "kaputt");
		start();

		RestoreResult result = catalog().restore(new Entry(hall.getId(), "Stadthalle", false), saved);

		assertFalse(result.success());
		assertTrue(result.message().contains(unit.relativePath()), result.message());
		assertFalse(result.message().startsWith("Der Speicherstand konnte nicht wiederhergestellt werden"));
	}

	@Test
	void restoreWarningNamesOtherMachines() {
		assertEquals("Spätere Änderungen, auch die anderer Rechner, werden mit zurückgesetzt.", catalog().restoreWarning());
	}
}
