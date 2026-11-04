package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;

class JsonMapperFactoryTest {

	private static SyncService started(Path appData) {
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		return sync;
	}

	@Test
	void savedAndReloadedEventSerializesByteIdenticallyToTheOriginal(@TempDir Path appData) throws IOException {
		Event event = new Event();
		event.setName("Kampagne");
		Presentation presentation = event.addPresentation("Prunksitzung");
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.setPrice(1000);
		Booking booking = presentation.addBooking();
		booking.setSeatId("sitz-1");
		booking.setTableNumber(1);
		booking.setSeatNumber(1);
		booking.setLastName("Müller");

		new StoreEventRepository(started(appData)).saveEvent(event);

		Event reloaded = new StoreEventRepository(started(appData)).loadEvents().get(0);
		// Darauf beruht der ChangeTracker: "geändert" muss dem entsprechen, was gespeichert würde
		assertArrayEquals(JsonMapperFactory.create().writeValueAsBytes(event), JsonMapperFactory.create().writeValueAsBytes(reloaded));
	}
}
