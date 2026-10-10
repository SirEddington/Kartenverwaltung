package de.eltviller_carneval_verein.karten.repository;

/**
 * Wird von einem Repository ({@link JsonEventRepository}, {@link JsonHallRepository}) nach jedem Speichern oder
 * Löschen aufgerufen, egal von welcher Stelle aus gespeichert wurde. So kann der Änderungsmelder den gespeicherten
 * Stand nachführen, ohne dass jeder Speicherweg daran denken muss.
 *
 * @param <T> Event oder Hall
 */
public interface RepositoryListener<T> {

	/** Die Entität wurde erfolgreich in die Datei geschrieben. */
	default void saved(T entity) {
	}

	/** Die Entität wurde gelöscht. */
	default void deleted(T entity) {
	}
}
