package de.eltviller_carneval_verein.karten.store;

import java.util.Map;

/**
 * Alle Versions- und Anspruchsdateien einer Einheit in einem Bestand, nach ID bzw. Name sortiert.
 *
 * @param unit     die Einheit
 * @param versions die Versionsdateien nach ID
 * @param claims   die Anspruchsdateien nach Name ({@code root} oder ID des Vorgängers)
 */
public record UnitFiles(UnitKey unit, Map<VersionId, VersionFile> versions, Map<String, ClaimFile> claims) {
}
