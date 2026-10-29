package de.eltviller_carneval_verein.karten.repository;

import java.util.List;

import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.VersionId;

/**
 * Ein fremder Stand, der zurückgestellt wurde, weil der Nutzer die Einheit ungespeichert geändert hat oder gerade
 * bearbeitet. Daraus entsteht der Hinweis in der Fußzeile, außer er ist still.
 *
 * @param unit        die Einheit
 * @param version     der zurückgestellte fremde Stand
 * @param machineName der Anzeigename des Rechners, der ihn geschrieben hat (letztes Glied der Kette); {@code null},
 *                    wenn er nicht bekannt ist
 * @param model       das Objekt im Modell, das der Hinweis nennt (Event, Presentation, Booking oder Hall)
 * @param silent      ob der Hinweis entfällt: Der Stand ist eine eigene Zusammenführung mit einem Vermerk für diesen
 *                    Rechner, der Hinweis nennte den eigenen Rechner als "anderen Rechner"; den Konflikt zeigt der Dialog
 */
public record Deferral(UnitKey unit, VersionId version, String machineName, Object model, boolean silent) {

	/** Ein zurückgestellter Stand mit Hinweis. */
	public Deferral(UnitKey unit, VersionId version, String machineName, Object model) {
		this(unit, version, machineName, model, false);
	}

	/** Der Hinweis zu einem Stand; der Rechner ist der des letzten Gliedes der Kette ({@code null}, wenn sie leer ist). */
	static Deferral of(UnitKey unit, VersionId version, List<ChainStep> chain, Object model) {
		return new Deferral(unit, version, machineOf(chain), model, false);
	}

	/** Wie {@link #of}, aber ohne Hinweis: Der Stand ist eine eigene Zusammenführung mit Vermerk für diesen Rechner. */
	static Deferral silent(UnitKey unit, VersionId version, List<ChainStep> chain, Object model) {
		return new Deferral(unit, version, machineOf(chain), model, true);
	}

	private static String machineOf(List<ChainStep> chain) {
		return chain.isEmpty() ? null : chain.get(chain.size() - 1).machineName();
	}
}
