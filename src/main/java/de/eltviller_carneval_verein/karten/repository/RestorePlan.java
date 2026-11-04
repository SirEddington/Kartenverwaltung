package de.eltviller_carneval_verein.karten.repository;

import java.util.List;

import de.eltviller_carneval_verein.karten.store.UnitState;

/**
 * Das vorbereitete Wiederherstellen eines Events bzw. einer Halle: alles, was {@code commitRestore} braucht, gelesen
 * und zusammengesetzt, aber noch nichts geändert. Die Vorbereitung liest den Versionsspeicher und darf deshalb im
 * Hintergrund laufen; erst {@code commitRestore} berührt Cache, Vergleichsstand, Listener und den Versionsspeicher.
 *
 * @param ownerId die ID des Events bzw. der Halle
 * @param states  die frisch gelesenen Einheiten, aus denen der Zielstand entstand
 * @param target  der Zielstand als neues Objekt
 * @param changes der Änderungszähler des Vergleichsstands zum Zeitpunkt der Vorbereitung; hat er sich bis zur Übernahme
 *                geändert, wird abgelehnt
 */
public record RestorePlan<T>(String ownerId, List<UnitState> states, T target, long changes) {

	/** Macht die Liste unveränderlich. */
	public RestorePlan {
		states = List.copyOf(states);
	}
}
