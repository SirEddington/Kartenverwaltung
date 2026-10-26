package de.eltviller_carneval_verein.karten.store;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Der Inhalt einer Versionsdatei: ein unveränderlicher Stand einer Einheit samt Herkunft.
 *
 * @param format      Dateiformat, derzeit {@link StoreCodec#FORMAT}
 * @param id          ID dieser Version (zugleich der Dateiname ohne Endung)
 * @param parents     IDs der Vorgänger; leer bei der ersten Version, mehrere nach einem Zusammenführen
 * @param machine     ID des Rechners, der die Version geschrieben hat
 * @param machineName Anzeigename dieses Rechners
 * @param operator    Name des Bedieners
 * @param at          Zeitpunkt des Speicherns
 * @param saveId      ID des Speichervorgangs, um Wiederholungen desselben Speicherns zu erkennen
 * @param deleted     ob die Einheit mit dieser Version gelöscht wurde
 * @param payload     die Daten der Einheit; bei einer gelöschten Version leer
 */
public record Version(int format, VersionId id, List<VersionId> parents, String machine, String machineName,
		String operator, Instant at, String saveId, boolean deleted, ObjectNode payload) {

	/** Der erste Vorgänger, oder {@code null} bei der ersten Version. */
	public VersionId firstParent() {
		return parents.isEmpty() ? null : parents.get(0);
	}

	/** Name der Anspruchsdatei dieser Version ohne Endung: {@code "root"} oder die ID des ersten Vorgängers. */
	public String claimName() {
		VersionId first = firstParent();
		return first == null ? "root" : first.value();
	}
}
