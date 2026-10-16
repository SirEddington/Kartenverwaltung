package de.eltviller_carneval_verein.karten.ui;

import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;

/**
 * Bereitet ein geladenes {@link Event} für die Anzeige vor: Je Vorstellung werden die Buchungen mit der Bestuhlung
 * der Halle abgeglichen ({@link BookingSync#sync}). Der Abgleich ist keine Änderung des Nutzers: War das Event
 * vorher sauber oder nicht verfolgt, gilt es danach wieder als sauber; hatte es schon ungespeicherte Änderungen,
 * bleiben sie erhalten.
 */
public final class BookingSyncService {

	private BookingSyncService() {
	}

	/** Wie {@link #prepare(Event, Function, ChangeTracker)} mit den Hallen der App und dem Event-Tracker der App. */
	public static void prepare(Event event) {
		prepare(event, JsonHallRepository.getInstance()::findById, ChangeTracker.events());
	}

	/**
	 * @param hallLookup liefert die Halle zur Id oder {@code null}, wenn es sie nicht (mehr) gibt
	 * @param tracker    der Tracker, in dem das Event verfolgt wird
	 */
	public static void prepare(Event event, Function<String, Hall> hallLookup, ChangeTracker<Event> tracker) {
		// Eine noch nicht verglichene Änderung zählt mit: erst vergleichen, dann den Abgleich laufen lassen.
		boolean wasDirty = false;
		if (tracker.baseline(event) != null) {
			tracker.check(event);
			wasDirty = tracker.isDirty(event);
		}

		for (Presentation presentation : event.getPresentations()) {
			Hall hall = presentation.getHallId() != null ? hallLookup.apply(presentation.getHallId()) : null;
			SeatingPlan plan = hall != null ? hall.findSeatingPlan(presentation.getSeatingPlanId()) : null;
			BookingSync.sync(presentation, plan);
		}

		if (!wasDirty) {
			tracker.markSaved(event);
		}
	}
}
