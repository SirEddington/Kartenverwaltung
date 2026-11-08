package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Syncs;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;

class EventTemplatesTest {

	private final Hall hall = new Hall();
	private final SeatingPlan plan = hall.addSeatingPlan();
	private final Function<String, Hall> halls = id -> hall.getId().equals(id) ? hall : null;

	private Presentation presentation(Event event) {
		plan.addTable(plan.addRow()).addSeat(1);
		Presentation presentation = event.addPresentation("Prunksitzung");
		presentation.setDescription("Mit Büttenreden");
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.setHallId(hall.getId());
		presentation.setSeatingPlanId(plan.getId());
		presentation.setPrice(1700);
		de.eltviller_carneval_verein.karten.model.Syncs.sync(presentation, plan);
		return presentation;
	}

	@Test
	void presentationCopyInTheSameEventGetsAFreeName() {
		Event event = new Event();
		Presentation original = presentation(event);

		Presentation copy = EventTemplates.copyPresentation(original, event, halls);

		assertEquals("Vorstellung 1", copy.getName(), "Zwei Vorstellungen eines Events heißen nie gleich");
		assertEquals(2, event.getPresentations().size());
	}

	@Test
	void presentationCopyKeepsTheSettingsAndLandsInTheTargetEvent() {
		Event event = new Event();
		event.setName("Kampagne");
		Presentation original = presentation(event);
		Event target = new Event();

		Presentation copy = EventTemplates.copyPresentation(original, target, halls);

		assertNotSame(original, copy);
		assertSame(target, copy.getParent());
		assertTrue(target.getPresentations().contains(copy));
		assertEquals("Prunksitzung", copy.getName());
		assertEquals("Mit Büttenreden", copy.getDescription());
		assertEquals(LocalDate.of(2027, 2, 12), copy.getDate());
		assertEquals(LocalTime.of(19, 11), copy.getTime());
		assertEquals(hall.getId(), copy.getHallId());
		assertEquals(plan.getId(), copy.getSeatingPlanId());
		assertEquals(1700, copy.getPrice());
	}

	@Test
	void presentationCopyStartsWithEmptyBookingsForEverySeat() {
		Event event = new Event();
		Presentation original = presentation(event);
		Booking sold = original.getBookings().get(0);
		sold.setLastName("Meier");

		Presentation copy = EventTemplates.copyPresentation(original, event, halls);

		assertEquals(1, copy.getBookings().size(), "Je Sitz der Bestuhlung eine Buchung");
		assertEquals("", copy.getBookings().get(0).getLastName() == null ? "" : copy.getBookings().get(0).getLastName(), "Die Daten der Buchungen werden nicht kopiert");
	}

	@Test
	void eventCopyGetsItsOwnNameAndCopiesAllPresentationsButIsNotAddedAnywhere() {
		Event event = new Event();
		event.setName("Kampagne");
		event.setDescription("Alles");
		presentation(event);
		event.addPresentation("Zweite");

		Event copy = EventTemplates.copyEvent(event, List.of("Kampagne"), halls);

		assertNotSame(event, copy);
		assertNotNull(copy.getId());
		assertEquals("Kampagne (Kopie)", copy.getName());
		assertEquals("Alles", copy.getDescription());
		assertEquals(2, copy.getPresentations().size());
		assertEquals(2, event.getPresentations().size(), "Das Original bleibt unverändert");
		assertSame(copy, copy.getPresentations().get(0).getParent());
	}

	@Test
	void eventCopyNameAvoidsTakenNames() {
		Event event = new Event();
		event.setName("Kampagne");

		Event copy = EventTemplates.copyEvent(event, List.of("Kampagne", "Kampagne (Kopie)"), halls);

		assertEquals("Kampagne (Kopie 2)", copy.getName());
	}

	@Test
	void copyEventAndPresentationCarryUnknownFields() {
		Event event = new Event();
		event.setName("Kampagne");
		event.getUnknown().put("x-test-event", TextNode.valueOf("e"));
		Presentation original = presentation(event);
		ObjectNode extra = JsonNodeFactory.instance.objectNode().put("inner", 1);
		original.getUnknown().put("x-test-presentation", extra);

		Presentation presentationCopy = EventTemplates.copyPresentation(original, new Event(), halls);
		Event eventCopy = EventTemplates.copyEvent(event, List.of("Kampagne"), halls);

		assertEquals(extra, presentationCopy.getUnknown().asMap().get("x-test-presentation"));
		assertNotSame(extra, presentationCopy.getUnknown().asMap().get("x-test-presentation"), "Tiefe Kopie");
		assertEquals(TextNode.valueOf("e"), eventCopy.getUnknown().asMap().get("x-test-event"));
		assertEquals(extra, eventCopy.getPresentations().get(0).getUnknown().asMap().get("x-test-presentation"));
	}

	/**
	 * KARV-135: Was gesperrt ist, steht nicht (oder nur im älteren Stand) im Modell und kommt nicht in die Kopie; der
	 * Hinweis nennt die Zahl. Buchungen zählen nicht mit, sie werden nie kopiert.
	 */
	@Test
	void copiesCountTheLockedEntriesTheyLeaveOut(@TempDir Path appData) throws IOException {
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		StoreEventRepository repository = TestRepositories.events(sync);
		Event event = new Event();
		event.setName("Kampagne");
		Presentation friday = event.addPresentation("Freitag");
		Presentation saturday = event.addPresentation("Samstag");
		Booking sold = friday.addBooking();
		sold.setUnassigned(true);
		sold.setLastName("Müller");
		repository.saveEvent(event);
		ForeignVersions foreign = new ForeignVersions(sync, appData);
		UnitKey booking = UnitKey.unassignedBooking(event.getId(), friday.getId(), sold.getId());
		foreign.write(booking, "Fremd", payload -> payload.put("paymentStatus", "x-test-voucher"));
		assertTrue(sync.unitState(booking).contentLocked());

		assertEquals(0, EventTemplates.lockedLeftOut(event, repository), "eine gesperrte Buchung zählt nicht");
		assertEquals(0, EventTemplates.lockedLeftOut(friday, repository));

		UnitKey presentation = UnitKey.presentation(event.getId(), saturday.getId());
		foreign.write(presentation, "Fremd",
				payload -> payload.set("requires", JsonNodeFactory.instance.arrayNode().add("x-test-merkmal")));
		assertTrue(sync.unitState(presentation).contentLocked());

		assertEquals(1, EventTemplates.lockedLeftOut(event, repository));
		assertEquals(1, EventTemplates.lockedLeftOut(saturday, repository));
		assertEquals(0, EventTemplates.lockedLeftOut(friday, repository));
		assertEquals(0, EventTemplates.lockedLeftOut(new Event(), repository), "nie gespeichert");
		assertEquals("Gesperrte Einträge werden nicht mitkopiert (1).", EventTemplates.lockedNote(1));
		assertNull(EventTemplates.lockedNote(0));
	}

	@Test
	void newEventGetsAFreeName() {
		assertEquals("Neues Event", EventTemplates.newEvent(List.of("Prunksitzung")).getName());
		assertEquals("Neues Event 2", EventTemplates.newEvent(List.of("Neues Event")).getName());
		assertEquals("Neues Event 3", EventTemplates.newEvent(List.of("neues event", "Neues Event 2")).getName());
	}

	@Test
	void newEventHasNoPresentations() {
		assertTrue(EventTemplates.newEvent(List.of()).getPresentations().isEmpty());
	}
}
