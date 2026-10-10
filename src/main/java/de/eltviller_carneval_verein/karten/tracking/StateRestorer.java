package de.eltviller_carneval_verein.karten.tracking;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker.Snapshot;

/**
 * Stellt einen gemerkten Zustand einer Entität wieder her: Aus den Bytes des {@link Snapshot} entsteht ein neues
 * Objekt, das im Repository-Cache und im {@link ChangeTracker} an die Stelle des bisherigen tritt. Es gibt genau
 * diesen einen Weg für alle Zustände, auch für den gespeicherten Stand ("Nicht speichern" beim Verlassen eines
 * Screens); eine zweite Variante "aus der Datei neu laden" gibt es bewusst nicht. Undo/Redo (KARV-25) nutzt
 * denselben Baustein.
 *
 * <p>
 * Das alte Objekt ist danach veraltet. Wer es noch hält (ein Screen, der stehen bleibt), erfährt vom Austausch
 * über {@link #addListener(Listener)} und bindet neu; beim Verlassen eines Screens ist das nicht nötig.
 *
 * @param <T> {@link Event} oder {@link Hall}
 */
public final class StateRestorer<T> {

	/** Wird nach dem Austausch aufgerufen. */
	@FunctionalInterface
	public interface Listener<T> {
		void onRestored(T replaced, T restored);
	}

	private static StateRestorer<Event> eventRestorer;
	private static StateRestorer<Hall> hallRestorer;

	private final ObjectMapper mapper = JsonMapperFactory.create();
	private final Class<T> type;
	private final ChangeTracker<T> tracker;
	private final Consumer<T> replaceInCache;
	private final List<Listener<T>> listeners = new ArrayList<>();

	/** Der Restorer der App für Events. */
	public static synchronized StateRestorer<Event> events() {
		if (eventRestorer == null) {
			eventRestorer = new StateRestorer<>(Event.class, ChangeTracker.events(), JsonEventRepository.getInstance()::replaceCachedEvent);
		}
		return eventRestorer;
	}

	/** Der Restorer der App für Hallen. */
	public static synchronized StateRestorer<Hall> halls() {
		if (hallRestorer == null) {
			hallRestorer = new StateRestorer<>(Hall.class, ChangeTracker.halls(), JsonHallRepository.getInstance()::replaceCachedHall);
		}
		return hallRestorer;
	}

	/**
	 * @param type           Klasse, in die zurückgelesen wird
	 * @param replaceInCache ersetzt die Entität im Cache des Repositorys (als Funktion übergeben, damit Tests ohne
	 *                       Repository auskommen)
	 */
	public StateRestorer(Class<T> type, ChangeTracker<T> tracker, Consumer<T> replaceInCache) {
		this.type = type;
		this.tracker = tracker;
		this.replaceInCache = replaceInCache;
	}

	/** Stellt den gespeicherten Stand (Baseline) der Entität wieder her. */
	public T restoreBaseline(T current) {
		Snapshot baseline = tracker.baseline(current);
		if (baseline == null) {
			throw new IllegalArgumentException("Wird nicht verfolgt: " + current);
		}
		return restore(current, baseline);
	}

	/**
	 * Stellt einen beliebigen gemerkten Zustand der Entität wieder her.
	 *
	 * @return das neue Objekt, das jetzt im Cache und im Tracker steht
	 */
	public T restore(T current, Snapshot state) {
		// Vor dem Austausch im Cache prüfen: Sonst bliebe nach der Ausnahme das neue Objekt im Cache, ohne verfolgt zu sein
		if (tracker.baseline(current) == null) {
			throw new IllegalArgumentException("Wird nicht verfolgt: " + current);
		}
		T restored;
		try {
			restored = mapper.readValue(state.bytes(), type);
		} catch (IOException e) {
			throw new IllegalStateException("Zustand konnte nicht wiederhergestellt werden: " + current, e);
		}
		replaceInCache.accept(restored);
		tracker.replaceEntity(restored, state);
		for (Listener<T> listener : new ArrayList<>(listeners)) {
			listener.onRestored(current, restored);
		}
		return restored;
	}

	public void addListener(Listener<T> listener) {
		listeners.add(listener);
	}

	public void removeListener(Listener<T> listener) {
		listeners.remove(listener);
	}
}
