package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;

class BookingAutoSaveTest {

	private ChangeTracker<Event> tracker;
	private final List<Event> saved = new ArrayList<>();
	/** Die eingeplanten Läufe, die in der App {@code Platform.runLater} ausführt. */
	private final List<Runnable> later = new ArrayList<>();
	private boolean editMode;
	private boolean settingOn;
	private boolean saveSucceeds;
	private BookingAutoSave autoSave;
	private Event event;

	@BeforeEach
	void setUp() {
		tracker = new ChangeTracker<>(Event::getId);
		editMode = true;
		settingOn = true;
		saveSucceeds = true;
		autoSave = new BookingAutoSave(tracker, this::save, later::add, () -> editMode && settingOn);
		autoSave.setActive(true);
		event = new Event();
		event.setName("Sitzung");
		tracker.track(event);
	}

	/** Wie {@code EventSaver.save}: Bei Erfolg gilt der Stand als gespeichert (das Repository meldet es dem Tracker). */
	private boolean save(Event toSave) {
		saved.add(toSave);
		if (saveSucceeds) {
			tracker.markSaved(toSave);
		}
		return saveSucceeds;
	}

	private void runLater() {
		List<Runnable> due = List.copyOf(later);
		later.clear();
		due.forEach(Runnable::run);
	}

	@Test
	void changeIsSavedWhenEnabled() {
		event.setName("Prunksitzung");
		tracker.check(event);

		assertTrue(saved.isEmpty(), "Gespeichert wird später, nicht mitten im Vergleich");
		runLater();

		assertEquals(List.of(event), saved);
		assertFalse(tracker.isDirty(event));
	}

	@Test
	void nothingIsSavedWhenDisabledByViewModeOrSetting() {
		// Ansicht nicht offen
		autoSave.setActive(false);
		event.setName("Erste Änderung");
		tracker.check(event);
		runLater();
		assertTrue(saved.isEmpty(), "Ansicht nicht offen");
		autoSave.setActive(true);

		// Anzeigemodus
		editMode = false;
		event.setName("Zweite Änderung");
		tracker.check(event);
		runLater();
		assertTrue(saved.isEmpty(), "Anzeigemodus");
		editMode = true;

		// Einstellung aus
		settingOn = false;
		event.setName("Dritte Änderung");
		tracker.check(event);
		runLater();
		assertTrue(saved.isEmpty(), "Einstellung aus");
		assertTrue(tracker.isDirty(event), "Die Änderung bleibt für Speichern und LeaveGuard stehen");
	}

	@Test
	void failedSaveIsNotRetriedWithoutANewChange() {
		saveSucceeds = false;
		event.setName("Ungültig");
		tracker.check(event);
		runLater();
		assertEquals(1, saved.size());

		tracker.check(event);
		tracker.checkAll();
		runLater();
		assertEquals(1, saved.size(), "Ohne neue Änderung kein neuer Versuch");
		assertTrue(tracker.isDirty(event), "Bleibt ungespeichert, 'Speichern *' bleibt sichtbar");

		saveSucceeds = true;
		event.setName("Gültig");
		tracker.check(event);
		runLater();
		assertEquals(2, saved.size(), "Die nächste Änderung versucht es erneut");
		assertFalse(tracker.isDirty(event));
	}

	@Test
	void flushSavesPendingChangeBeforeLeaving() {
		// Review Focus 4: Der ChangeTrigger hat noch nicht verglichen (weniger als 300 ms seit der Eingabe)
		event.setName("Schnell geändert");
		assertFalse(tracker.isDirty(event), "Ohne Vergleich weiß der Tracker noch nichts");

		autoSave.flush();

		assertEquals(List.of(event), saved);
		assertFalse(tracker.isDirty(event), "Danach ist der Tracker sauber, der LeaveGuard fragt nicht");
		assertFalse(tracker.anyDirty());
		assertTrue(later.isEmpty(), "flush speichert sofort und plant nichts ein");
	}

	@Test
	void flushWithoutChangesSavesNothing() {
		autoSave.flush();

		assertTrue(saved.isEmpty());
		assertTrue(later.isEmpty());
	}

	@Test
	void flushDoesNothingWhenDisabled() {
		settingOn = false;
		event.setName("Schnell geändert");

		autoSave.flush();

		assertTrue(saved.isEmpty(), "Mit der Einstellung auf Nein gilt alles wie bisher");
	}

	@Test
	void queuedSaveIsSkippedWhenFlushHasSavedAlready() {
		event.setName("Prunksitzung");
		tracker.check(event);
		assertEquals(1, later.size());

		autoSave.flush();
		runLater();

		assertEquals(1, saved.size(), "Derselbe Stand wird nicht zweimal gespeichert");
	}

	@Test
	void changeBackToSavedStateIsNotSaved() {
		event.setName("Prunksitzung");
		tracker.check(event);
		event.setName("Sitzung");
		tracker.check(event);
		runLater();

		assertTrue(saved.isEmpty(), "Der Stand gleicht dem gespeicherten");
	}

	@Test
	void flushRetriesAFailedSaveSoTheLeaveGuardSeesTheTruth() {
		saveSucceeds = false;
		event.setName("Ungültig");
		tracker.check(event);
		runLater();
		assertEquals(1, saved.size());

		autoSave.flush();

		assertEquals(2, saved.size());
		assertTrue(tracker.isDirty(event));
	}

	@Test
	void flushActiveReachesTheActiveInstanceOnly() {
		event.setName("Schnell geändert");

		BookingAutoSave.flushActive();
		assertEquals(List.of(event), saved);

		autoSave.setActive(false);
		event.setName("Noch schneller");
		BookingAutoSave.flushActive();
		assertEquals(1, saved.size(), "Eine Ansicht, die nicht offen ist, speichert nichts");
	}
}
