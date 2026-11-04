package de.eltviller_carneval_verein.karten.repository;

import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;

/**
 * Die Fachregel für die Vorstellung (ohne ihre Buchungen): "Gleich" und "Löschen gewinnt", sonst der Dreiwegevergleich
 * je Feldgruppe ({@link FieldGroups#presentation()}: Halle und Bestuhlung, Termin, Kasse, übrige Felder einzeln).
 * Zustandslos; läuft auf dem Hintergrund-Thread des Versionsspeichers und kennt weder Modell noch Repository.
 */
public final class PresentationPolicy implements ConflictPolicy {

	@Override
	public Resolution resolve(ConflictInput input) {
		return PolicyRules.mergeFields(input, FieldGroups.presentation());
	}
}
