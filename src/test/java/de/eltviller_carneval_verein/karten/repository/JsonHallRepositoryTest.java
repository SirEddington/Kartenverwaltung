package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

class JsonHallRepositoryTest {

	private static Hall newHall(String name) {
		Hall hall = new Hall();
		hall.setName(name);
		return hall;
	}

	@Test
	void saveAndLoad_roundTripWithHallObjects(@TempDir File storageDir) {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Hall hall = newHall("Stadthalle");
		hall.setDescription("Große Halle");
		hall.setHallWidth(3000);
		hall.setHallHeight(2000);
		HallObject stage = hall.addHallObject("Bühne");
		stage.setPosX(100);
		stage.setPosY(200);
		stage.setWidth(800);
		stage.setHeight(400);
		stage.setShape(Shape.CIRCLE);
		stage.setColor("#FF0000");
		repository.saveHall(hall);

		List<Hall> loaded = new JsonHallRepository(storageDir).loadHalls();

		assertEquals(1, loaded.size());
		Hall reloaded = loaded.get(0);
		assertEquals(hall.getId(), reloaded.getId());
		assertEquals("Stadthalle", reloaded.getName());
		assertEquals("Große Halle", reloaded.getDescription());
		assertEquals(3000, reloaded.getHallWidth());
		assertEquals(2000, reloaded.getHallHeight());
		assertEquals(1, reloaded.getHallObjects().size());
		HallObject reloadedStage = reloaded.getHallObjects().get(0);
		assertEquals("Bühne", reloadedStage.getName());
		assertEquals(100, reloadedStage.getPosX());
		assertEquals(200, reloadedStage.getPosY());
		assertEquals(800, reloadedStage.getWidth());
		assertEquals(400, reloadedStage.getHeight());
		assertEquals(Shape.CIRCLE, reloadedStage.getShape());
		assertEquals("#FF0000", reloadedStage.getColor());
		assertSame(reloaded, reloadedStage.getParent(), "Die Rückreferenz des Hallenobjekts auf die Halle muss nach dem Laden gesetzt sein.");
	}

	@Test
	void speichert_und_laedt_Bestuhlung(@TempDir File storageDir) {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Hall hall = newHall("Stadthalle");
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setDescription("Mit Mittelgang");
		plan.setDefaultSeatWidth(40);
		plan.setDefaultSeatHeight(30);
		TableRow rowA = plan.addRow();
		rowA.setLabel("A");
		rowA.setAngle(12.5);
		rowA.setTableRotation(90);
		rowA.setTableGap(15);
		rowA.setAisleWidth(60);
		TableRow rowB = plan.addRow();
		rowB.setLabel("B");
		rowB.setAisleAfter(3);
		Table tableA = plan.addTable(rowA);
		tableA.setRotation(45);
		tableA.addSeat();
		tableA.addSeat();
		Table tableB = plan.addTable(rowB);
		tableB.addSeat();
		tableB.addSeat();
		repository.saveHall(hall);

		Hall reloaded = new JsonHallRepository(storageDir).loadHalls().get(0);

		assertEquals(1, reloaded.getSeatingPlans().size());
		SeatingPlan reloadedPlan = reloaded.getSeatingPlans().get(0);
		assertEquals(plan.getId(), reloadedPlan.getId());
		assertEquals("Bestuhlung 1", reloadedPlan.getName());
		assertEquals("Mit Mittelgang", reloadedPlan.getDescription());
		assertEquals(40, reloadedPlan.getDefaultSeatWidth());
		assertEquals(30, reloadedPlan.getDefaultSeatHeight());
		assertSame(reloaded, reloadedPlan.getParent());
		assertEquals(2, reloadedPlan.getRows().size());
		TableRow reloadedA = reloadedPlan.getRows().get(0);
		TableRow reloadedB = reloadedPlan.getRows().get(1);
		assertEquals(rowA.getId(), reloadedA.getId());
		assertEquals("A", reloadedA.getLabel());
		assertEquals(12.5, reloadedA.getAngle());
		assertEquals(90, reloadedA.getTableRotation());
		assertEquals(15, reloadedA.getTableGap());
		assertEquals(60, reloadedA.getAisleWidth());
		assertNull(reloadedA.getAisleAfter());
		assertEquals(3, reloadedB.getAisleAfter());
		assertSame(reloadedPlan, reloadedA.getParent());
		assertSame(reloadedPlan, reloadedB.getParent());
		assertEquals(2, reloadedPlan.getTables().size());
		Table reloadedTableA = reloadedPlan.getTables().get(0);
		Table reloadedTableB = reloadedPlan.getTables().get(1);
		assertEquals(tableA.getId(), reloadedTableA.getId());
		assertEquals(reloadedA.getId(), reloadedTableA.getRowId());
		assertEquals(reloadedB.getId(), reloadedTableB.getRowId());
		assertEquals(45, reloadedTableA.getRotation());
		assertSame(reloadedPlan, reloadedTableA.getPlan());
		assertSame(reloadedPlan, reloadedTableB.getPlan());
		assertEquals(2, reloadedTableA.getSeats().size());
		for (Seat seat : reloadedTableA.getSeats()) {
			assertSame(reloadedTableA, seat.getParent());
			assertEquals(40, seat.getWidth());
		}
		for (Seat seat : reloadedTableB.getSeats()) {
			assertSame(reloadedTableB, seat.getParent());
		}
		assertEquals("Tisch A1", reloadedTableA.getDisplayName());
		assertTrue(new JsonHallRepository(storageDir).getAndClearLoadWarnings().isEmpty());
	}

	@Test
	void hallObject_withoutStoredShape_defaultsToRectangle(@TempDir File storageDir) throws IOException {
		Hall hall = newHall("Halle");
		String id = hall.getId();
		// Datei ohne "shape" (z.B. älterer Stand): darf beim Zeichnen nicht zu null führen
		Files.writeString(new File(storageDir, id + ".json").toPath(),
				"{\"id\":\"" + id + "\",\"name\":\"Halle\",\"hallObjects\":[{\"id\":\"o1\",\"name\":\"Objekt\"}]}", StandardCharsets.UTF_8);

		Hall loaded = new JsonHallRepository(storageDir).loadHalls().get(0);

		assertEquals(Shape.RECTANGLE, loaded.getHallObjects().get(0).getShape());
	}

	@Test
	void hallFile_withUnknownProperties_stillLoads(@TempDir File storageDir) throws IOException {
		Hall hall = newHall("Alte Halle");
		String id = hall.getId();
		// Entfernte Felder (z.B. die früheren Standard-Objektmaße) dürfen alte Dateien nicht unlesbar machen
		Files.writeString(new File(storageDir, id + ".json").toPath(),
				"{\"id\":\"" + id + "\",\"name\":\"Alte Halle\",\"defaultObjectWidth\":100.0,\"defaultObjectHeight\":50.0,\"hallObjects\":[]}", StandardCharsets.UTF_8);

		JsonHallRepository repository = new JsonHallRepository(storageDir);

		assertEquals(1, repository.loadHalls().size());
		assertEquals("Alte Halle", repository.loadHalls().get(0).getName());
		assertTrue(repository.getAndClearLoadWarnings().isEmpty());
	}

	@Test
	void newHall_isFoundByIdAfterSave_andReturnsSameInstance(@TempDir File storageDir) {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Hall hall = newHall("Neu");

		assertNull(repository.findById(hall.getId()), "Eine noch nicht gespeicherte Halle ist dem Repository unbekannt.");
		repository.saveHall(hall);

		assertSame(hall, repository.findById(hall.getId()));
		assertNull(repository.findById(null));
		assertNull(repository.findById("gibt-es-nicht"));
	}

	@Test
	void savingTwice_keepsSingleFileAndCreatesBackupInBackupFolder(@TempDir File storageDir) {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Hall hall = newHall("Halle");
		repository.saveHall(hall);
		hall.setName("Halle (umbenannt)");
		repository.saveHall(hall);

		File[] jsonFiles = storageDir.listFiles((dir, name) -> name.endsWith(".json"));
		assertNotNull(jsonFiles);
		assertEquals(1, jsonFiles.length);
		assertEquals(1, repository.backups().list(hall.getId()).size(), "Beim Überschreiben wird die letzte Fassung im Backup-Ordner gesichert.");
		assertFalse(new File(storageDir, hall.getId() + ".json.bak").exists(), "Die einzelne .bak-Datei gibt es nicht mehr.");
		assertEquals(1, repository.loadHalls().size(), "Mehrfaches Speichern darf die Halle nicht im Cache verdoppeln.");
		assertEquals("Halle (umbenannt)", new JsonHallRepository(storageDir).loadHalls().get(0).getName());
	}

	@Test
	void savingWithoutChange_createsNoSecondBackup(@TempDir File storageDir) {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Hall hall = newHall("Halle");
		repository.saveHall(hall);
		repository.saveHall(hall); // sichert die Datei von eben (noch keine Kopie vorhanden)

		repository.saveHall(hall); // Datei ist unverändert, also gleich der neuesten Kopie

		assertEquals(1, repository.backups().list(hall.getId()).size());
	}

	@Test
	void backupFailure_doesNotBlockSaving(@TempDir File storageDir) throws IOException {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Hall hall = newHall("Halle");
		repository.saveHall(hall);
		Files.writeString(new File(storageDir, "backups").toPath(), "ich bin eine Datei", StandardCharsets.UTF_8);
		hall.setName("Halle (neu)");

		repository.saveHall(hall);

		assertEquals("Halle (neu)", new JsonHallRepository(storageDir).loadHalls().get(0).getName());
	}

	@Test
	void deleteHall_removesFileAndCacheEntry_butKeepsBackupsIncludingTheLastState(@TempDir File storageDir) throws IOException {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Hall keep = newHall("Bleibt");
		Hall remove = newHall("Weg");
		repository.saveHall(keep);
		repository.saveHall(remove);
		remove.setName("Weg (umbenannt)");
		repository.saveHall(remove); // erzeugt eine Kopie im Backup-Ordner

		repository.deleteHall(remove);

		assertFalse(new File(storageDir, remove.getId() + ".json").exists());
		List<BackupStore.Backup> backups = repository.backups().list(remove.getId());
		assertEquals(2, backups.size(), "Die Backups bleiben liegen, dazu der Stand zum Zeitpunkt des Löschens.");
		assertTrue(new String(repository.backups().read(backups.get(0)), StandardCharsets.UTF_8).contains("Weg (umbenannt)"));
		assertNull(repository.findById(remove.getId()));
		assertEquals(1, repository.loadHalls().size());
		assertEquals(1, new JsonHallRepository(storageDir).loadHalls().size());
		repository.deleteHall(null); // darf nicht werfen
	}

	@Test
	void unreadableFile_isSkippedWithWarning(@TempDir File storageDir) throws IOException {
		Hall good = newHall("Gut");
		new JsonHallRepository(storageDir).saveHall(good);
		Files.writeString(new File(storageDir, "kaputt.json").toPath(), "{ das ist kein json", StandardCharsets.UTF_8);

		JsonHallRepository repository = new JsonHallRepository(storageDir);

		assertEquals(1, repository.loadHalls().size());
		List<String> warnings = repository.getAndClearLoadWarnings();
		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).contains("kaputt.json"));
		assertTrue(repository.getAndClearLoadWarnings().isEmpty(), "Warnungen werden beim Abholen geleert.");
	}

	@Test
	void duplicateHallId_keepsNewerFileAndSetsOtherAside(@TempDir File storageDir) throws IOException {
		Hall hall = newHall("Doppelt");
		String json = "{\"id\":\"" + hall.getId() + "\",\"name\":\"Doppelt\",\"hallObjects\":[]}";
		File older = new File(storageDir, "kopie-alt.json");
		File newer = new File(storageDir, "kopie-neu.json");
		Files.writeString(older.toPath(), json, StandardCharsets.UTF_8);
		Files.writeString(newer.toPath(), json, StandardCharsets.UTF_8);
		assertTrue(older.setLastModified(System.currentTimeMillis() - 60_000));

		JsonHallRepository repository = new JsonHallRepository(storageDir);

		assertEquals(1, repository.loadHalls().size());
		assertFalse(older.exists(), "Die veraltete Kopie wird beiseitegelegt (umbenannt), nicht gelöscht.");
		assertTrue(newer.exists());
		File[] setAside = storageDir.listFiles((dir, name) -> name.contains(".duplicate-"));
		assertNotNull(setAside);
		assertEquals(1, setAside.length);
		assertEquals(1, repository.getAndClearLoadWarnings().size());
	}

	@Test
	void saveNull_throws(@TempDir File storageDir) {
		JsonHallRepository repository = new JsonHallRepository(storageDir);

		assertThrows(IllegalArgumentException.class, () -> repository.saveHall(null));
	}
}
