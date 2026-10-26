package de.eltviller_carneval_verein.karten.store;

import java.util.List;

/**
 * Eigene Versionen einer Einheit, die den Platz in der gültigen Kette nicht bekommen haben.
 *
 * @param unit   die Einheit
 * @param mine   die eigenen verlorenen Versionen, älteste zuerst; mehr als eine, wenn die späteren auf der verlorenen
 *               aufbauen
 * @param winner die Version, die den Platz belegt
 */
public record LostVersion(UnitKey unit, List<VersionId> mine, VersionId winner) {
}
