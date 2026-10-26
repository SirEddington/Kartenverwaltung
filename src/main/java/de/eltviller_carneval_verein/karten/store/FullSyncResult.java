package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Set;

/**
 * Das Ergebnis eines Vollabgleichs.
 *
 * @param changed die Einheiten, die im lokalen Bestand neue Dateien bekommen oder einen Anspruch ersetzt bekommen haben
 *                und deren gültiger Stand sich damit geändert haben kann
 * @param lost    die eigenen Versionen, die beim Hochladen lokaler Ansprüche gegen einen schon vorhandenen Anspruch
 *                verloren haben
 */
public record FullSyncResult(Set<UnitKey> changed, List<LostVersion> lost) {

	/** Macht die Sammlungen unveränderlich. */
	public FullSyncResult {
		changed = Set.copyOf(changed);
		lost = List.copyOf(lost);
	}
}
