package de.eltviller_carneval_verein.karten.tracking;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.repository.EventMapperFactory;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker.Snapshot;

/**
 * Stellt einen gemerkten Zustand eines Events wieder her: Aus den Bytes des {@link Snapshot} entsteht ein neues
 * Event-Objekt, das im Repository-Cache und im {@link ChangeTracker} an die Stelle des bisherigen tritt. Es gibt
 * genau diesen einen Weg für alle Zustände, auch für den gespeicherten Stand ("Nicht speichern" beim Verlassen
 * eines Screens); eine zweite Variante "aus der Datei neu laden" gibt es bewusst nicht. Undo/Redo (KARV-25)
 * nutzt denselben Baustein.
 *
 * <p>
 * Das alte Objekt ist danach veraltet. Wer es noch hält (ein Screen, der stehen bleibt), erfährt vom Austausch
 * über {@link #addListener(Listener)} und bindet neu; beim Verlassen eines Screens ist das nicht nötig.
 */
public final class EventRestorer {

	/** Wird nach dem Austausch aufgerufen. */
	@FunctionalInterface
	public interface Listener {
		void onRestored(Event replaced, Event restored);
	}

	private static EventRestorer instance;

	private final ObjectMapper mapper = EventMapperFactory.create();
	private final ChangeTracker tracker;
	private final Consumer<Event> replaceInCache;
	private final List<Listener> listeners = new ArrayList<>();

	public static synchronized EventRestorer getInstance() {
		if (instance == null) {
			instance = new EventRestorer(ChangeTracker.getInstance(), JsonEventRepository.getInstance()::replaceCachedEvent);
		}
		return instance;
	}

	/**
	 * @param replaceInCache ersetzt das Event im Cache des Repositorys (als Funktion übergeben, damit Tests ohne
	 *                       Repository auskommen)
	 */
	public EventRestorer(ChangeTracker tracker, Consumer<Event> replaceInCache) {
		this.tracker = tracker;
		this.replaceInCache = replaceInCache;
	}

	/** Stellt den gespeicherten Stand (Baseline) des Events wieder her. */
	public Event restoreBaseline(Event current) {
		Snapshot baseline = tracker.baseline(current);
		if (baseline == null) {
			throw new IllegalArgumentException("Event wird nicht verfolgt: " + current);
		}
		return restore(current, baseline);
	}

	/**
	 * Stellt einen beliebigen gemerkten Zustand des Events wieder her.
	 *
	 * @return das neue Event-Objekt, das jetzt im Cache und im Tracker steht
	 */
	public Event restore(Event current, Snapshot state) {
		Event restored;
		try {
			restored = mapper.readValue(state.bytes(), Event.class);
		} catch (IOException e) {
			throw new IllegalStateException("Zustand konnte nicht wiederhergestellt werden: " + current, e);
		}
		replaceInCache.accept(restored);
		tracker.replaceEvent(restored, state);
		for (Listener listener : new ArrayList<>(listeners)) {
			listener.onRestored(current, restored);
		}
		return restored;
	}

	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}
}
