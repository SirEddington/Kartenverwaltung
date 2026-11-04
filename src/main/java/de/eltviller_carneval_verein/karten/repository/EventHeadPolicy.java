package de.eltviller_carneval_verein.karten.repository;

import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;

/**
 * Die Fachregel für den Event-Kopf (das Event ohne seine Vorstellungen): "Gleich" und "Löschen gewinnt", sonst der
 * Dreiwegevergleich, jedes Feld eine Gruppe ({@link FieldGroups#eventHead()}). Zustandslos; läuft auf dem
 * Hintergrund-Thread des Versionsspeichers und kennt weder Modell noch Repository.
 */
public final class EventHeadPolicy implements ConflictPolicy {

	@Override
	public Resolution resolve(ConflictInput input) {
		return PolicyRules.mergeFields(input, FieldGroups.eventHead());
	}
}
