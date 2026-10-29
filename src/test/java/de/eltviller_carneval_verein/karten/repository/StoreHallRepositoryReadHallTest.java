package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;

/** {@link StoreHallRepository#readHall}: eine frühere Fassung der Halle als neues Objekt, ohne Cache und Speicher anzufassen. */
class StoreHallRepositoryReadHallTest {

	@TempDir
	Path appData;

	@Test
	void readsAStoredHallAsANewObject() throws IOException {
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		StoreHallRepository repository = TestRepositories.halls(sync);
		Hall hall = new Hall();
		hall.setName("Stadthalle");
		repository.saveHall(hall);

		Hall read = repository.readHall(sync.unitState(UnitKey.hall(hall.getId())).current().payload());

		assertEquals(hall.getId(), read.getId());
		assertEquals("Stadthalle", read.getName());
		assertNotSame(hall, read);
		assertSame(hall, repository.findById(hall.getId()), "der Cache bleibt unberührt");
	}

	@Test
	void unreadableContentGivesNull() {
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});

		assertNull(TestRepositories.halls(sync).readHall(JsonNodeFactory.instance.objectNode().put("seatingPlans", 1)));
	}
}
