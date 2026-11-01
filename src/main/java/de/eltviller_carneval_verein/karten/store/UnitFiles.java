package de.eltviller_carneval_verein.karten.store;

import java.util.Map;

/**
 * Alle Versions- und Anspruchsdateien einer Einheit in einem Bestand, nach ID bzw. Name sortiert.
 *
 * @param unit          die Einheit
 * @param versions      die Versionsdateien nach ID
 * @param claims        die Anspruchsdateien nach Name ({@code root} oder ID des Vorgängers)
 * @param versionStamps Größe und Änderungszeit der Versionsdateien ({@link FileLayer.DirEntry#stamp()}) nach ID, aus dem
 *                      Auflisten vor dem Lesen; leer, wenn unbekannt
 * @param claimStamps   ebenso für die Anspruchsdateien nach Name
 */
public record UnitFiles(UnitKey unit, Map<VersionId, VersionFile> versions, Map<String, ClaimFile> claims,
		Map<VersionId, String> versionStamps, Map<String, String> claimStamps) {

	/** Ohne Größe und Änderungszeit der Dateien. */
	public UnitFiles(UnitKey unit, Map<VersionId, VersionFile> versions, Map<String, ClaimFile> claims) {
		this(unit, versions, claims, Map.of(), Map.of());
	}
}
