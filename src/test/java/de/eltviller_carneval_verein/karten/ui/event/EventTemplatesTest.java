package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;

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
		de.eltviller_carneval_verein.karten.model.BookingSync.sync(presentation, plan);
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
