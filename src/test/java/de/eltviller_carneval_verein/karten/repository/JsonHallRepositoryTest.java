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
import de.eltviller_carneval_verein.karten.model.Shape;

class JsonHallRepositoryTest {

	private static Hall newHall(String name) {
		Hall hall = new Hall();
		hall.changeName(name);
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
	void savingTwice_keepsSingleFileAndCreatesBackup(@TempDir File storageDir) {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Hall hall = newHall("Halle");
		repository.saveHall(hall);
		hall.changeName("Halle (umbenannt)");
		repository.saveHall(hall);

		File[] jsonFiles = storageDir.listFiles((dir, name) -> name.endsWith(".json"));
		assertNotNull(jsonFiles);
		assertEquals(1, jsonFiles.length);
		assertTrue(new File(storageDir, hall.getId() + ".json.bak").exists(), "Beim Überschreiben wird die letzte Fassung gesichert.");
		assertEquals(1, repository.loadHalls().size(), "Mehrfaches Speichern darf die Halle nicht im Cache verdoppeln.");
		assertEquals("Halle (umbenannt)", new JsonHallRepository(storageDir).loadHalls().get(0).getName());
	}

	@Test
	void deleteHall_removesFilesAndCacheEntry(@TempDir File storageDir) {
		JsonHallRepository repository = new JsonHallRepository(storageDir);
		Hall keep = newHall("Bleibt");
		Hall remove = newHall("Weg");
		repository.saveHall(keep);
		repository.saveHall(remove);
		repository.saveHall(remove); // erzeugt zusätzlich eine .bak-Datei

		repository.deleteHall(remove);

		assertFalse(new File(storageDir, remove.getId() + ".json").exists());
		assertFalse(new File(storageDir, remove.getId() + ".json.bak").exists());
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
