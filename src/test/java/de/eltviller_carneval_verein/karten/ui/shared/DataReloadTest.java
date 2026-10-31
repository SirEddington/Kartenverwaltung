package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;

/**
 * Das Neuladen mit echten Repositories auf einem Versionsspeicher im temporären Ordner: Die Repositories der App haben
 * ihre Daten im Cache, "der Bestand hat sich geändert" ist ein zweites Repository-Paar auf demselben Dienst.
 */
class DataReloadTest {

	@TempDir
	Path appData;

	private SyncService sync;
	private StoreHallRepository halls;
	private StoreEventRepository events;
	private final List<String> steps = new ArrayList<>();

	@BeforeEach
	void setUp() {
		sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		halls = TestRepositories.halls(sync);
		events = TestRepositories.events(sync);
		halls.loadHalls();
		events.loadEvents();
	}

	private DataReload reload() {
		return new DataReload(halls, events, () -> steps.add("clear"), () -> steps.add("conflicts"));
	}

	/** Eine Halle mit einer Reihe "A" aus 2 Tischen zu je 3 Sitzen; die Repositories der App kennen sie (Cache). */
	private Hall hallKnownToTheApp() {
		Hall created = new Hall();
		created.setName("Stadthalle");
		SeatingPlan plan = created.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 2, 3);
		TestRepositories.halls(sync).saveHall(created);
		halls.reloadHalls();
		return created;
	}

	/**
	 * "Ein anderer Rechner" hängt der Bestuhlung eine Reihe "B" an und speichert ein Event mit einer Buchung "Neu" auf
	 * einem Sitz dieser Reihe. Die Buchung passt nur zur neuen Bestuhlung.
	 */
	private Event otherAddsRowAndSellsThere(Hall known) {
		StoreHallRepository otherHalls = TestRepositories.halls(sync);
		Hall hall = otherHalls.findById(known.getId());
		SeatingPlan plan = hall.getSeatingPlans().get(0);
		List<String> oldSeats = plan.getSeats().stream().map(seat -> seat.getId()).toList();
		TableRow row = plan.addRow();
		row.setLabel("B");
		plan.fillRow(row, 1, 3);
		otherHalls.saveHall(hall);

		Event created = new Event();
		created.setName("Prunksitzung");
		Presentation presentation = created.addPresentation("Freitag");
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId(plan.getId());
		BookingSync.sync(presentation, plan);
		String newSeat = plan.getSeats().stream().map(seat -> seat.getId()).filter(id -> !oldSeats.contains(id)).findFirst()
				.orElseThrow();
		presentation.getBookings().stream().filter(b -> newSeat.equals(b.getSeatId())).findFirst().orElseThrow()
				.setLastName("Neu");
		TestRepositories.events(sync).saveEvent(created);
		return created;
	}

	@Test
	void hallsAreReloadedBeforeEvents() {
		// Die Buchung passt nur zur neuen Bestuhlung: Kennen die Events beim Laden noch die alte Halle, bleibt sie draußen
		Hall known = hallKnownToTheApp();
		Event event = otherAddsRowAndSellsThere(known);

		reload().reload();

		Event loaded = events.loadEvents().stream().filter(e -> e.getId().equals(event.getId())).findFirst().orElseThrow();
		assertTrue(loaded.getPresentations().get(0).getBookings().stream().anyMatch(b -> "Neu".equals(b.getLastName())),
				"die Buchung steht im neu geladenen Event");
		assertEquals(2, halls.findById(known.getId()).getSeatingPlans().get(0).getRows().size());
		assertTrue(events.getAndClearLoadWarnings().isEmpty());
	}

	@Test
	void loadWarningsOfBothRepositoriesAreReturned() throws IOException {
		ObjectNode hallPayload = JsonMapperFactory.create().createObjectNode();
		hallPayload.put("id", "andere-halle");
		sync.append("test", List.of(new Change(UnitKey.hall("halle-ordner"), List.of(), hallPayload, false)));
		ObjectNode eventPayload = JsonMapperFactory.create().createObjectNode();
		eventPayload.put("id", "andere-id");
		eventPayload.put("name", "Fremd");
		sync.append("test", List.of(new Change(UnitKey.event("event-ordner"), List.of(), eventPayload, false)));

		List<String> warnings = reload().reload();

		assertEquals(2, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).contains("halle-ordner"), "erst die Hallen: " + warnings);
		assertTrue(warnings.get(1).contains("event-ordner"), "dann die Events: " + warnings);
		assertTrue(halls.getAndClearLoadWarnings().isEmpty(), "die Warnungen sind abgeholt");
		assertTrue(events.getAndClearLoadWarnings().isEmpty(), "die Warnungen sind abgeholt");
	}

	@Test
	void reloadWithoutProblemsReturnsNoWarnings() {
		assertTrue(reload().reload().isEmpty());
	}

	@Test
	void heldStatesAreClearedAndConflictsRefreshed() {
		List<String> warnings = reload().reload();

		assertEquals(List.of("clear", "conflicts"), steps);
		assertTrue(warnings.isEmpty());
	}

	@Test
	void eachReloadClearsAndRefreshesExactlyOnce() {
		reload().reload();
		reload().reload();

		assertEquals(List.of("clear", "conflicts", "clear", "conflicts"), steps);
	}
}
