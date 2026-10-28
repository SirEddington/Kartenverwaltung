package de.eltviller_carneval_verein.karten.repository;

import java.util.List;

import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.VersionId;

/**
 * Ein fremder Stand, der zurückgestellt wurde, weil der Nutzer die Einheit ungespeichert geändert hat oder gerade
 * bearbeitet. Daraus entsteht der Hinweis in der Fußzeile.
 *
 * @param unit        die Einheit
 * @param version     der zurückgestellte fremde Stand
 * @param machineName der Anzeigename des Rechners, der ihn geschrieben hat (letztes Glied der Kette); {@code null},
 *                    wenn er nicht bekannt ist
 * @param model       das Objekt im Modell, das der Hinweis nennt (Event, Presentation, Booking oder Hall)
 */
public record Deferral(UnitKey unit, VersionId version, String machineName, Object model) {

	/** Der Hinweis zu einem Stand; der Rechner ist der des letzten Gliedes der Kette ({@code null}, wenn sie leer ist). */
	static Deferral of(UnitKey unit, VersionId version, List<ChainStep> chain, Object model) {
		String machineName = chain.isEmpty() ? null : chain.get(chain.size() - 1).machineName();
		return new Deferral(unit, version, machineName, model);
	}
}
