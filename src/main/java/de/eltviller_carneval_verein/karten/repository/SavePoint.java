package de.eltviller_carneval_verein.karten.repository;

import java.time.Instant;

/**
 * Ein Speicherstand: alle Versionen, die ein Speichervorgang ({@code saveId}) geschrieben hat. Wiederherstellen
 * setzt ein Event bzw. eine Halle auf den Stand zum Zeitpunkt {@code at}.
 *
 * @param saveId      die ID des Speichervorgangs
 * @param at          der späteste Zeitpunkt unter den Versionen dieses Speichervorgangs
 * @param machineName der Rechner, der diese späteste Version geschrieben hat
 * @param operator    der Bediener, der sie geschrieben hat (kann {@code null} sein)
 * @param unitCount   die Zahl der Einheiten, die der Speichervorgang geändert hat
 */
public record SavePoint(String saveId, Instant at, String machineName, String operator, int unitCount) {
}
