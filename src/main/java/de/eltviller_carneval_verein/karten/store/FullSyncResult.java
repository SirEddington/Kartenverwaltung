package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Set;

/**
 * Das Ergebnis eines Vollabgleichs.
 *
 * @param changed  die Einheiten, die im lokalen Bestand neue Dateien bekommen oder einen Anspruch ersetzt bekommen haben
 *                 und deren gültiger Stand sich damit geändert haben kann
 * @param lost     die eigenen Versionen, die beim Hochladen lokaler Ansprüche gegen einen schon vorhandenen Anspruch
 *                 verloren haben
 * @param uploaded die lokalen Ansprüche, die dieser Abgleich im gemeinsamen Ordner angelegt hat (nur beim Hochladen
 *                 lokaler Ansprüche)
 */
public record FullSyncResult(Set<UnitKey> changed, List<LostVersion> lost, List<UploadedClaim> uploaded) {

	/**
	 * Ein Anspruch, den der Abgleich im gemeinsamen Ordner angelegt hat.
	 *
	 * @param claimName {@code root} oder die ID des Vorgängers
	 * @param version   die Version, die der Anspruch nennt
	 */
	public record UploadedClaim(UnitKey unit, String claimName, VersionId version) {
	}

	/** Macht die Sammlungen unveränderlich. */
	public FullSyncResult {
		changed = Set.copyOf(changed);
		lost = List.copyOf(lost);
		uploaded = List.copyOf(uploaded);
	}
}
