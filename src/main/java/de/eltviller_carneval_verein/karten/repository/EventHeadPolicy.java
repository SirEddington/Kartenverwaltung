package de.eltviller_carneval_verein.karten.repository;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Requires;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Die Fachregel für den Event-Kopf (das Event ohne seine Vorstellungen): "Gleich" und "Löschen gewinnt", sonst der
 * Dreiwegevergleich, jedes Feld eine Gruppe ({@link FieldGroups#eventHead()}). {@code requires} steht außerhalb davon
 * (KARV-144): Das Ergebnis trägt das Merkmal der Kassen ({@link HeadRequires}), wenn "Meine" oder "Deren" es trägt, ohne
 * Vermerk dafür. Zustandslos; läuft auf dem Hintergrund-Thread des Versionsspeichers und kennt weder Modell noch
 * Repository.
 */
public final class EventHeadPolicy implements ConflictPolicy {

	@Override
	public Resolution resolve(ConflictInput input) {
		boolean required = carries(input.mine()) || carries(input.theirs());
		// Die Inhalte sind Kopien, die Strategie darf sie verändern (ConflictInput). Ein unbekanntes Merkmal bleibt stehen:
		// Es macht das Ergebnis inhaltlich zu neu, und die Auflösung wartet (ConflictResolver), statt es zu verlieren.
		for (Version version : new Version[] { input.base(), input.mine(), input.theirs() }) {
			if (version != null && version.payload() != null && Requires.satisfied(version.payload().get(Requires.KEY))) {
				version.payload().remove(Requires.KEY);
			}
		}
		Resolution resolution = PolicyRules.mergeFields(input, FieldGroups.eventHead());
		if (!required || resolution.deleted() || resolution.payload() == null
				|| !Requires.satisfied(resolution.payload().get(Requires.KEY))) {
			return resolution;
		}
		ObjectNode payload = HeadRequires.with(resolution.payload(), true);
		return new Resolution(payload, false, resolution.extra(), resolution.note());
	}

	private static boolean carries(Version version) {
		return !version.deleted() && HeadRequires.carries(version.payload());
	}
}
