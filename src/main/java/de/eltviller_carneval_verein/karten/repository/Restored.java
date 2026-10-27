package de.eltviller_carneval_verein.karten.repository;

import java.util.List;

/**
 * Das Ergebnis eines Wiederherstellens.
 *
 * @param restored das wiederhergestellte Event bzw. die wiederhergestellte Halle; es steht jetzt im Cache des Repositorys
 * @param notes    die Hinweise der Anpassung, die vor dem Speichern am Zielstand vorgenommen wurde (kann leer sein)
 */
public record Restored<T>(T restored, List<String> notes) {
}
