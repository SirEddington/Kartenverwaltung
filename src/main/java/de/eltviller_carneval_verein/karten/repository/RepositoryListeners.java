package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Die Listener eines Repositorys. Eine Ausnahme in einem Listener wird nur geloggt: Die Datei ist zu diesem
 * Zeitpunkt schon geschrieben bzw. gelöscht, ein Fehler dürfte weder den Aufrufer glauben lassen, das Speichern
 * sei gescheitert, noch die übrigen Listener überspringen. Dasselbe gilt für fremde Stände: Sie sind schon
 * eingearbeitet, wenn die Listener davon erfahren.
 */
final class RepositoryListeners<T> {

	private static final Logger LOG = Logger.getLogger(RepositoryListeners.class.getName());

	private final List<RepositoryListener<T>> listeners = new ArrayList<>();

	void add(RepositoryListener<T> listener) {
		listeners.add(listener);
	}

	void remove(RepositoryListener<T> listener) {
		listeners.remove(listener);
	}

	void notifySaved(T entity) {
		notifyEach(listener -> listener.saved(entity), "beim Speichern", entity);
	}

	void notifyDeleted(T entity) {
		notifyEach(listener -> listener.deleted(entity), "beim Löschen", entity);
	}

	void notifyChangingByOthers(T entity) {
		notifyEach(listener -> listener.changingByOthers(entity), "vor dem Einarbeiten fremder Stände", entity);
	}

	void notifyChangedByOthers(T entity, ForeignBatch<T> batch) {
		notifyEach(listener -> listener.changedByOthers(entity, batch), "nach dem Einarbeiten fremder Stände", entity);
	}

	void notifyAddedByOthers(T entity) {
		notifyEach(listener -> listener.addedByOthers(entity), "bei einem fremd angelegten Eintrag", entity);
	}

	void notifyRemovedByOthers(T entity) {
		notifyEach(listener -> listener.removedByOthers(entity), "bei einem fremd gelöschten Eintrag", entity);
	}

	private void notifyEach(Consumer<RepositoryListener<T>> call, String when, T entity) {
		for (RepositoryListener<T> listener : new ArrayList<>(listeners)) {
			try {
				call.accept(listener);
			} catch (RuntimeException e) {
				LOG.log(Level.SEVERE, "Repository-Listener ist " + when + " fehlgeschlagen: " + entity, e);
			}
		}
	}
}
