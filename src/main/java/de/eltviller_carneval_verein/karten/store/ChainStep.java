package de.eltviller_carneval_verein.karten.store;

import java.time.Instant;

/**
 * Ein Glied der gültigen Kette einer Einheit, so weit der Verlauf es braucht: Wann, von welchem Rechner und Bediener
 * und in welchem Speichervorgang es geschrieben wurde, aber ohne Inhalt. Der Start-Index ({@link UnitIndex}) hält die
 * Glieder jeder Einheit, damit die Speicherstände eines Events ohne Dateizugriff entstehen.
 *
 * @param version     die ID der Version
 * @param saveId      die ID des Speichervorgangs
 * @param at          der Zeitpunkt des Speicherns
 * @param machineName der Anzeigename des Rechners, der sie geschrieben hat
 * @param operator    der Bediener (kann {@code null} sein)
 * @param deleted     ob die Einheit mit dieser Version gelöscht wurde
 */
public record ChainStep(VersionId version, String saveId, Instant at, String machineName, String operator,
		boolean deleted) {

	/** Das Glied zu einer Version. */
	public static ChainStep of(Version version) {
		return new ChainStep(version.id(), version.saveId(), version.at(), version.machineName(), version.operator(),
				version.deleted());
	}
}
