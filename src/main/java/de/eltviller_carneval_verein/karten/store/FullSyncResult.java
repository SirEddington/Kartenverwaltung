package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Set;

/**
 * Das Ergebnis eines Vollabgleichs.
 *
 * @param changed  die Einheiten, die im lokalen Bestand neue Dateien bekommen oder einen Anspruch ersetzt bekommen haben
 *                 und deren gültiger Stand sich damit geändert haben kann, dazu die lokalen Einheiten, die der Aufrufer
 *                 noch nicht kannte ({@link FullSync#syncUnits})
 * @param lost     die eigenen Versionen, die beim Hochladen lokaler Ansprüche gegen einen schon vorhandenen Anspruch
 *                 verloren haben
 * @param uploaded die lokalen Ansprüche, die dieser Abgleich im gemeinsamen Ordner angelegt hat (nur beim Hochladen
 *                 lokaler Ansprüche)
 * @param contested die Plätze, an denen der lokale Anspruch der eigene ist und der gemeinsame eine andere Version nennt
 *                  (späte Niederlage, die keine Änderungsliste aufgedeckt hat; nur ohne Hochladen lokaler Ansprüche,
 *                  denn dabei entscheidet der Abgleich selbst). Ersetzt wird hier nichts; das tut der Aufrufer.
 * @param sharedTemps die temporären Dateien ({@code ~karv-*.tmp}, eigene wie fremde), die der Abgleich beim Auflisten der
 *                    Ordner der Einheiten im gemeinsamen Ordner mitgesehen hat, als Pfade relativ zu dessen Datenordner.
 *                    Der Aufrufer räumt davon auf, was ihm gehört; dafür ist kein weiterer Listenzugriff nötig.
 */
public record FullSyncResult(Set<UnitKey> changed, List<LostVersion> lost, List<UploadedClaim> uploaded,
		List<FeedPoll.Contested> contested, List<String> sharedTemps) {

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
		contested = List.copyOf(contested);
		sharedTemps = List.copyOf(sharedTemps);
	}
}
