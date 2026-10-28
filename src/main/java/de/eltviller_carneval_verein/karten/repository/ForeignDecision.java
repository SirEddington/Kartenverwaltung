package de.eltviller_carneval_verein.karten.repository;

import java.util.List;
import java.util.Objects;

import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;

/**
 * Was mit einer Einheit geschieht, deren Stand sich auf einem anderen Rechner geändert hat. Die Entscheidung ist rein:
 * Sie liest weder Dateien noch den Index, sondern bekommt den Vergleichsstand, den aktuellen Stand und die gültige
 * Kette. Ob ein Stand ein Nachfolger ist, entscheidet allein die gültige Kette, nicht der Index.
 */
enum ForeignDecision {

	/** Der Stand ist schon bekannt; nichts zu tun. */
	KNOWN,

	/** Die Kette der Einheit wartet auf Dateien; der Stand bleibt unberührt, bis sie vollständig ist. */
	WAITING,

	/** Der Vergleichsstand gehört nicht zur gültigen Kette (eigene Version im Wettlauf unterlegen); nicht einarbeiten. */
	FORK,

	/** Der Stand ist ein Nachfolger, aber der Nutzer hat die Einheit ungespeichert geändert oder bearbeitet sie gerade. */
	DEFER,

	/** Der Stand ist ein Nachfolger und darf in Modell und Vergleichsstand übernommen werden. */
	APPLY;

	/**
	 * Entscheidet für eine Einheit, deren Stand sich geändert hat. Die Regeln gelten in dieser Reihenfolge.
	 *
	 * @param baseline der Vergleichsstand der Einheit; {@code null}, wenn sie bisher unbekannt war
	 * @param current  der aktuelle Stand der Einheit
	 * @param chain    die gültige Kette der Einheit, älteste Version zuerst
	 * @param blocked  ob der Nutzer die Einheit ungespeichert geändert hat oder gerade bearbeitet
	 */
	static ForeignDecision decide(UnitSnapshot baseline, UnitSnapshot current, List<ChainStep> chain, boolean blocked) {
		if (Objects.equals(current.version(), baseline == null ? null : baseline.version())) {
			return KNOWN;
		}
		if (current.status() == UnitState.Status.WAITING) {
			return WAITING;
		}
		if (baseline != null && baseline.version() != null && chain.stream()
				.noneMatch(step -> step.version().equals(baseline.version()))) {
			return FORK;
		}
		return blocked ? DEFER : APPLY;
	}

	/**
	 * Die Entscheidung ohne "blockiert" (das fragt das Repository erst danach, wenn ein Nachfolger da ist). Ist die Einheit
	 * gesperrt, ohne dass ein Glied lesbar ist, gibt es keine Kette, an der sich ein Nachfolger zeigt: Sie wird wie beim
	 * Laden behandelt und verlässt das Modell.
	 */
	static ForeignDecision decideUnblocked(UnitSnapshot baseline, UnitSnapshot current, List<ChainStep> chain) {
		boolean locked = current.status() == UnitState.Status.LOCKED_DAMAGED
				|| current.status() == UnitState.Status.LOCKED_FORMAT;
		if (current.version() == null && locked && baseline != null && baseline.version() != null) {
			return APPLY;
		}
		return decide(baseline, current, chain, false);
	}
}
