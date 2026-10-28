package de.eltviller_carneval_verein.karten.repository;

import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.VersionId;

/**
 * Ein fremder Stand, der zurückgestellt wurde, weil der Nutzer die Einheit ungespeichert geändert hat oder gerade
 * bearbeitet. Daraus entsteht der Hinweis in der Fußzeile.
 *
 * @param unit        die Einheit
 * @param version     der zurückgestellte fremde Stand
 * @param machineName der Anzeigename des Rechners, der ihn geschrieben hat (letztes Glied der Kette); {@code null},
 *                    wenn er nicht bekannt ist
 * @param model       das Objekt im Modell, das der Hinweis nennt (Event, Presentation, Booking oder Hall)
 */
public record Deferral(UnitKey unit, VersionId version, String machineName, Object model) {
}
