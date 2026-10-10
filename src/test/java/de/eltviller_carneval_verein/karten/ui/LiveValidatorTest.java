package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

class LiveValidatorTest {

	/** Mitschrift dessen, was die Live-Prüfung der Fußzeile mitteilt: Codes der Meldungen bzw. "clear". */
	private final List<String> shown = new ArrayList<>();
	private ChangeTracker<Event> tracker;

	@BeforeEach
	void setUp() {
		tracker = new ChangeTracker<>(Event::getId);
		tracker.addListener(new LiveValidator(new LiveValidator.Sink() {
			@Override
			public void show(ValidationResult result) {
				shown.add(String.join(",", result.getIssues().stream().map(issue -> issue.code()).toList()));
			}

			@Override
			public void clear() {
				shown.add("clear");
			}
		}));
	}

	/** Event ohne jede Meldung. */
	private static Event validEvent() {
		Event event = new Event();
		event.changeName("Kampagne");
		Presentation presentation = event.addPresentation("Prunksitzung");
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.setHallId("halle-1");
		presentation.addTable(1).addSeat(1).setPrice(1000);
		return event;
	}

	private static Seat firstSeat(Event event) {
		return event.getPresentations().get(0).getTables().get(0).getSeats().get(0);
	}

	private void change(Event event, Runnable edit) {
		edit.run();
		tracker.check(event);
	}

	@Test
	void harmlessChangeOfAValidEventSaysNothing() {
		Event event = validEvent();
		tracker.track(event);

		change(event, () -> event.setDescription("neu"));

		assertTrue(shown.isEmpty());
	}

	@Test
	void newErrorIsShown() {
		Event event = validEvent();
		tracker.track(event);

		change(event, () -> firstSeat(event).setPrice(-100));

		assertEquals(List.of("SEA-001"), shown);
	}

	@Test
	void fixedErrorIsRemoved() {
		Event event = validEvent();
		tracker.track(event);
		change(event, () -> firstSeat(event).setPrice(-100));

		change(event, () -> firstSeat(event).setPrice(1000));

		assertEquals(List.of("SEA-001", "clear"), shown);
	}

	@Test
	void sameSetOfIssuesIsNotRepeated() {
		Event event = validEvent();
		tracker.track(event);
		change(event, () -> firstSeat(event).setPrice(-100));

		change(event, () -> event.setDescription("anderes Feld"));
		change(event, () -> event.setDescription("noch ein anderes"));

		assertEquals(List.of("SEA-001"), shown, "Die bestehende Meldung wird nicht wiederholt");
	}

	@Test
	void changedMessageIsShownAgain() {
		Event event = validEvent();
		tracker.track(event);
		change(event, () -> firstSeat(event).setPrice(-100));

		// derselbe Code, aber anderer Text (anderer Preis): die Menge hat sich geändert
		change(event, () -> firstSeat(event).setPrice(2_000_000));

		assertEquals(2, shown.size());
	}

	@Test
	void issuesAlreadyThereWhenLoadedAreNotAnnouncedOnUnrelatedChanges() {
		Event event = validEvent();
		event.getPresentations().get(0).setHallId(null); // PRS-003, schon beim Laden vorhanden
		tracker.track(event);

		change(event, () -> event.setDescription("neu"));

		assertTrue(shown.isEmpty());
	}

	@Test
	void newIssueIsShownTogetherWithTheOldOnes() {
		Event event = validEvent();
		event.getPresentations().get(0).setHallId(null);
		tracker.track(event);

		change(event, () -> firstSeat(event).setPrice(-100));

		assertEquals(1, shown.size());
		assertEquals("SEA-001,PRS-003", shown.get(0), "Fehler zuerst, dann die Warnung");
	}

	@Test
	void fixingTheOnlyNewIssueLeavesTheOldOnesAndClearsNothing() {
		Event event = validEvent();
		event.getPresentations().get(0).setHallId(null);
		tracker.track(event);
		change(event, () -> firstSeat(event).setPrice(-100));

		change(event, () -> firstSeat(event).setPrice(1000));

		// Es bleibt die Warnung, die Menge hat sich also wieder geändert: aktualisierte Meldung statt "clear"
		assertEquals(List.of("SEA-001,PRS-003", "PRS-003"), shown);
	}

	@Test
	void eventsAreEvaluatedIndependently() {
		Event first = validEvent();
		Event second = validEvent();
		tracker.track(first);
		tracker.track(second);

		change(first, () -> firstSeat(first).setPrice(-1));
		change(second, () -> second.setDescription("harmlos"));

		assertEquals(List.of("SEA-001"), shown);
	}
}
