package de.eltviller_carneval_verein.karten.ui.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.SavePoint;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Entry;

class EventBackupCatalogTest {

	@TempDir
	Path appData;

	private final SteppedClock clock = new SteppedClock();
	private SyncService sync;
	private StoreEventRepository events;
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
		events = TestRepositories.storeEvents(sync);
		halls = TestRepositories.storeHalls(sync);
	}

	private EventBackupCatalog catalog() {
		return new EventBackupCatalog(events, halls);
	}

	private static Event event(String name) {
		Event event = new Event();
		event.setName(name);
		return event;
	}

	private SavePoint newestSavePoint(Event event) throws IOException {
		return events.savePoints(event.getId()).get(0);
	}

	@Test
	void entriesIncludeDeletedEvents() {
		Event beta = event("beta");
		Event alpha = event("Alpha");
		Event gone = event("Gamma");
		events.saveEvents(List.of(beta, alpha, gone));
		events.deleteEvent(gone);

		assertEquals(List.of(new Entry(alpha.getId(), "Alpha", false), new Entry(beta.getId(), "beta", false), new Entry(gone.getId(), "Gamma", true)),
				catalog().entries());
		assertEquals("Event", catalog().entityLabel());
	}

	@Test
	void versionsAreTheSavePointsNewestFirst() throws IOException {
		Event event = event("Alpha");
		events.saveEvent(event);
		clock.advance(10);
		event.setName("Alpha 2");
		events.saveEvent(event);

		List<SavePoint> versions = catalog().versions(event.getId());

		assertEquals(2, versions.size());
		assertEquals(events.savePoints(event.getId()), versions);
		assertTrue(versions.get(0).at().isAfter(versions.get(1).at()));
	}

	@Test
	void restoreResetsEventAndReportsNotes() throws IOException {
		Hall hall = new Hall();
		hall.setName("Stadthalle");
		halls.saveHall(hall);
		Event event = event("Alpha");
		Presentation prunk = event.addPresentation("Prunksitzung");
		Presentation damen = event.addPresentation("Damensitzung");
		prunk.setHallId(hall.getId());
		damen.setHallId(hall.getId());
		events.saveEvent(event);
		SavePoint withHall = newestSavePoint(event);
		clock.advance(10);
		halls.deleteHall(hall);
		event.setName("Kaputt");
		events.saveEvent(event);
		clock.advance(10);

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Kaputt", false), withHall);

		assertTrue(result.success(), result.message());
		assertTrue(result.message().startsWith("Event \"Alpha\" auf den Stand vom "), result.message());
		assertTrue(result.message().endsWith(" zurückgesetzt."), result.message());
		assertEquals(2, result.notes().size());
		String notes = String.join("\n", result.notes());
		assertTrue(notes.contains("Prunksitzung"), notes);
		assertTrue(notes.contains("Damensitzung"), notes);
		Event restored = events.loadEvents().get(0);
		assertNull(restored.getPresentations().get(0).getHallId());
		assertNull(restored.getPresentations().get(1).getHallId());
		assertEquals("Alpha", restored.getName());
	}

	@Test
	void restoreKeepsAHallThatStillExists() throws IOException {
		Hall hall = new Hall();
		hall.setName("Stadthalle");
		halls.saveHall(hall);
		Event event = event("Alpha");
		event.addPresentation("Prunksitzung").setHallId(hall.getId());
		events.saveEvent(event);
		SavePoint saved = newestSavePoint(event);
		clock.advance(10);
		event.setName("Neu");
		events.saveEvent(event);

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Neu", false), saved);

		assertTrue(result.success(), result.message());
		assertTrue(result.notes().isEmpty());
		assertEquals(hall.getId(), events.loadEvents().get(0).getPresentations().get(0).getHallId());
	}

	@Test
	void restoreBringsBackADeletedEvent() throws IOException {
		Event event = event("Beta");
		events.saveEvent(event);
		SavePoint saved = newestSavePoint(event);
		clock.advance(10);
		events.deleteEvent(event);
		clock.advance(10);

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Beta", true), saved);

		assertTrue(result.success(), result.message());
		assertEquals(1, events.loadEvents().size());
		assertEquals("Beta", events.loadEvents().get(0).getName());
		assertEquals(List.of(new Entry(event.getId(), "Beta", false)), catalog().entries());
	}

	@Test
	void restoreToTheDeletionPointFailsWithText() throws IOException {
		Event event = event("Beta");
		events.saveEvent(event);
		clock.advance(10);
		events.deleteEvent(event);
		SavePoint deletion = events.savePoints(event.getId()).get(0);

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Beta", true), deletion);

		assertFalse(result.success());
		assertEquals("Zu diesem Speicherstand war das Event gelöscht.", result.message());
		assertTrue(result.notes().isEmpty());
	}

	@Test
	void restoreOfLockedEventFailsWithText() throws IOException {
		Event event = event("Alpha");
		events.saveEvent(event);
		SavePoint saved = newestSavePoint(event);
		UnitKey unit = UnitKey.event(event.getId());
		Version first = sync.unitState(unit).head();
		ObjectNode changed = first.payload().deepCopy();
		changed.put("description", "geändert");
		Version second = sync.append("test", List.of(new Change(unit, List.of(first.id()), changed, false))).get(0);
		Files.writeString(appData.resolve("data/events/" + event.getId() + "/" + second.id() + ".json"), "kaputt");
		start();

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Alpha", false), saved);

		assertFalse(result.success());
		assertTrue(result.message().contains(unit.relativePath()), result.message());
		assertFalse(result.message().startsWith("Der Speicherstand konnte nicht wiederhergestellt werden"));
	}

	@Test
	void restoreWarningNamesSalesAndOtherMachines() {
		assertEquals("Spätere Änderungen und Verkäufe, auch die anderer Rechner, werden mit zurückgesetzt.", catalog().restoreWarning());
	}

	@Test
	void timeInMessageIsShownInTheSystemZone() throws IOException {
		Event event = event("Alpha");
		events.saveEvent(event);
		SavePoint saved = newestSavePoint(event);
		clock.advance(10);
		event.setName("Neu");
		events.saveEvent(event);

		RestoreResult result = catalog().restore(new Entry(event.getId(), "Neu", false), saved);

		assertTrue(result.message().contains(AbstractBackupCatalog.timeText(saved.at(), ZoneId.systemDefault())), result.message());
	}
}
