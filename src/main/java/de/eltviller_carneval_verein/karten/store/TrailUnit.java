package de.eltviller_carneval_verein.karten.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Eine Einheit auf der Umzugsspur einer Buchung, wie die Auflösung sie der Strategie gibt (KARV-93): ihre gültige Kette und
 * ihre Versionen daneben, jeweils mit Inhalt und Angaben. Kopien; die Strategie darf sie verändern.
 *
 * @param unit    die Einheit
 * @param chain   ihre gültige Kette, ältestes Glied zuerst, auch Glieder einer Auflösung
 * @param outside die lesbaren Versionen außerhalb der Kette ohne die einer Auflösung, mit ihrem Stand
 *                ({@link ConflictInput.Standing#ABSORBED} oder {@link ConflictInput.Standing#BESIDE})
 * @param current der aktuelle Stand für diesen Rechner: die letzte eigene ausstehende Version, sonst das Ende der Kette;
 *                {@code null}, wenn die Einheit keinen hat
 */
public record TrailUnit(UnitKey unit, List<Version> chain, List<Outside> outside, VersionId current) {

	/** Eine Version außerhalb der Kette mit ihrem Stand. */
	public record Outside(Version version, ConflictInput.Standing standing) {

		/** Prüft die Angaben. */
		public Outside {
			Objects.requireNonNull(version, "version");
			Objects.requireNonNull(standing, "standing");
		}
	}

	/** Prüft die Einheit und macht die Listen unveränderlich. */
	public TrailUnit {
		Objects.requireNonNull(unit, "unit");
		chain = List.copyOf(chain);
		outside = List.copyOf(outside);
	}

	/**
	 * Die Versionen, die hier gelten, ältestes zuerst: die Kette, danach der Weg vom aktuellen Stand über erste Vorgänger
	 * zurück zur Kette (eigene, noch ausstehende Versionen). Eine Zusammenführung einer Auflösung beendet den Weg (sie
	 * steht nicht unter {@link #outside()}): Was sie als zweiten Vorgänger aufnahm, hat verloren.
	 */
	public List<Version> standing() {
		Map<VersionId, Version> beside = new HashMap<>();
		for (Outside other : outside) {
			beside.put(other.version().id(), other.version());
		}
		List<Version> pending = new ArrayList<>();
		VersionId at = current;
		while (at != null && beside.containsKey(at)) {
			pending.add(0, beside.get(at));
			at = beside.get(at).firstParent();
		}
		List<Version> all = new ArrayList<>(chain);
		all.addAll(pending);
		return all;
	}

	/** Ob die Version hier gilt ({@link #standing()}). */
	public boolean stands(VersionId version) {
		return standing().stream().anyMatch(link -> link.id().equals(version));
	}
}
