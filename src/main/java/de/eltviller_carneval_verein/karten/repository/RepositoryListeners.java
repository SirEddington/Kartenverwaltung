package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Die Listener eines Repositorys. Eine Ausnahme in einem Listener wird nur geloggt: Die Datei ist zu diesem
 * Zeitpunkt schon geschrieben bzw. gelöscht, ein Fehler dürfte weder den Aufrufer glauben lassen, das Speichern
 * sei gescheitert, noch die übrigen Listener überspringen.
 */
final class RepositoryListeners<T> {

	private static final Logger LOG = Logger.getLogger(RepositoryListeners.class.getName());

	private final List<RepositoryListener<T>> listeners = new ArrayList<>();

	void add(RepositoryListener<T> listener) {
		listeners.add(listener);
	}

	void notifySaved(T entity) {
		for (RepositoryListener<T> listener : new ArrayList<>(listeners)) {
			try {
				listener.saved(entity);
			} catch (RuntimeException e) {
				LOG.log(Level.SEVERE, "Repository-Listener ist beim Speichern fehlgeschlagen: " + entity, e);
			}
		}
	}

	void notifyDeleted(T entity) {
		for (RepositoryListener<T> listener : new ArrayList<>(listeners)) {
			try {
				listener.deleted(entity);
			} catch (RuntimeException e) {
				LOG.log(Level.SEVERE, "Repository-Listener ist beim Löschen fehlgeschlagen: " + entity, e);
			}
		}
	}
}
