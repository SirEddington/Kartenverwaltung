package de.eltviller_carneval_verein.karten.repository;

import de.eltviller_carneval_verein.karten.model.Event;

/**
 * Wird vom {@link JsonEventRepository} nach jedem Speichern oder Löschen eines Events aufgerufen, egal von
 * welcher Stelle aus gespeichert wurde. So kann der Änderungsmelder den gespeicherten Stand nachführen, ohne
 * dass jeder Speicherweg daran denken muss.
 */
public interface EventRepositoryListener {

	/** Das Event wurde erfolgreich in die Datei geschrieben. */
	default void eventSaved(Event event) {
	}

	/** Das Event wurde gelöscht. */
	default void eventDeleted(Event event) {
	}
}
