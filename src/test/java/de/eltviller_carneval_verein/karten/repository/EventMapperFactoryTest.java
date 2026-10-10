package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

class EventMapperFactoryTest {

	@Test
	void savedFileIsByteIdenticalToTheFactorysSerialization(@TempDir File storageDir) throws IOException {
		Event event = new Event();
		event.changeName("Kampagne");
		Presentation presentation = event.addPresentation("Prunksitzung");
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.addTable(1).addSeat(1).setPrice(1000);

		new JsonEventRepository(storageDir).saveEvent(event);

		byte[] onDisk = Files.readAllBytes(new File(storageDir, event.getId() + ".json").toPath());
		// Darauf beruht der ChangeTracker: "geändert" muss dem entsprechen, was gespeichert würde
		assertArrayEquals(EventMapperFactory.create().writeValueAsBytes(event), onDisk);
	}
}
