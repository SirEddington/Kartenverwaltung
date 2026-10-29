package de.eltviller_carneval_verein.karten.repository;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.VersionId;

/**
 * Was mit einer Einheit geschieht, deren Stand sich auf einem anderen Rechner geändert hat. Die Entscheidung ist rein:
 * Sie liest weder Dateien noch den Index, sondern bekommt den Vergleichsstand, den aktuellen Stand, die gültige Kette und
 * die aufgegangenen Versionen. Ob ein Stand ein Nachfolger ist, entscheiden allein Kette und aufgegangene Versionen (beide
 * vom {@code ChainResolver} bestimmt), nicht der Index.
 */
enum ForeignDecision {

	/** Der Stand ist schon bekannt; nichts zu tun. */
	KNOWN,

	/** Die Kette der Einheit wartet auf Dateien; der Stand bleibt unberührt, bis sie vollständig ist. */
	WAITING,

	/**
	 * Der Vergleichsstand gehört weder zur gültigen Kette noch ist er im aktuellen Stand aufgegangen (eigene Version im
	 * Wettlauf unterlegen, noch nicht zusammengeführt); nicht einarbeiten.
	 */
	FORK,

	/**
	 * Der Stand ist ein Nachfolger mit demselben Inhalt und derselben Löschmarke wie der Vergleichsstand (etwa die
	 * Zusammenführung des Verlierers beim Gewinner eines Doppelverkaufs): nur die Version in den Vergleichsstand übernehmen.
	 * Das Modell bleibt unberührt; es gibt keinen Hinweis und kein Zurückstellen, auch wenn der Nutzer die Einheit gerade
	 * ändert. Sonst baute sein nächstes Speichern auf der alten Version auf und gabelte erneut.
	 */
	SAME,

	/** Der Stand ist ein Nachfolger, aber der Nutzer hat die Einheit ungespeichert geändert oder bearbeitet sie gerade. */
	DEFER,

	/** Der Stand ist ein Nachfolger und darf in Modell und Vergleichsstand übernommen werden. */
	APPLY;

	/** Wie {@link #decide(UnitSnapshot, UnitSnapshot, List, Set, boolean)} ohne aufgegangene Versionen. */
	static ForeignDecision decide(UnitSnapshot baseline, UnitSnapshot current, List<ChainStep> chain, boolean blocked) {
		return decide(baseline, current, chain, Set.of(), blocked);
	}

	/**
	 * Entscheidet für eine Einheit, deren Stand sich geändert hat. Die Regeln gelten in dieser Reihenfolge. Ein Nachfolger
	 * ist der aktuelle Stand, wenn der Vergleichsstand in der Kette steht oder in ihm aufgegangen ist (eine
	 * Zusammenführung hat ihn aufgenommen).
	 * <p>
	 * "Gleich" gilt nur für einen Stand ohne Sperre: Eine gesperrte Einheit geht über {@link #APPLY}, damit der Nutzer
	 * die Sperrmeldung bekommt.
	 *
	 * @param baseline der Vergleichsstand der Einheit; {@code null}, wenn sie bisher unbekannt war
	 * @param current  der aktuelle Stand der Einheit
	 * @param chain    die gültige Kette der Einheit, älteste Version zuerst
	 * @param absorbed die Versionen, die im aktuellen Stand aufgegangen sind, ohne die Kette (siehe
	 *                 {@code SyncService.absorbed})
	 * @param blocked  ob der Nutzer die Einheit ungespeichert geändert hat oder gerade bearbeitet
	 */
	static ForeignDecision decide(UnitSnapshot baseline, UnitSnapshot current, List<ChainStep> chain,
			Set<VersionId> absorbed, boolean blocked) {
		if (Objects.equals(current.version(), baseline == null ? null : baseline.version())) {
			return KNOWN;
		}
		if (current.status() == UnitState.Status.WAITING) {
			return WAITING;
		}
		boolean known = baseline != null && baseline.version() != null;
		if (known && !absorbed.contains(baseline.version())
				&& chain.stream().noneMatch(step -> step.version().equals(baseline.version()))) {
			return FORK;
		}
		if (known && current.version() != null && current.status() == UnitState.Status.OK
				&& current.deleted() == baseline.deleted() && Objects.equals(current.payload(), baseline.payload())) {
			return SAME;
		}
		return blocked ? DEFER : APPLY;
	}

	/** Wie {@link #decideUnblocked(UnitSnapshot, UnitSnapshot, List, Set)} ohne aufgegangene Versionen. */
	static ForeignDecision decideUnblocked(UnitSnapshot baseline, UnitSnapshot current, List<ChainStep> chain) {
		return decideUnblocked(baseline, current, chain, Set.of());
	}

	/**
	 * Die Entscheidung ohne "blockiert" (das fragt das Repository erst danach, wenn ein Nachfolger da ist). Ist die Einheit
	 * gesperrt, ohne dass ein Glied lesbar ist, gibt es keine Kette, an der sich ein Nachfolger zeigt: Sie wird wie beim
	 * Laden behandelt und verlässt das Modell.
	 */
	static ForeignDecision decideUnblocked(UnitSnapshot baseline, UnitSnapshot current, List<ChainStep> chain,
			Set<VersionId> absorbed) {
		boolean locked = current.status() == UnitState.Status.LOCKED_DAMAGED
				|| current.status() == UnitState.Status.LOCKED_FORMAT;
		if (current.version() == null && locked && baseline != null && baseline.version() != null) {
			return APPLY;
		}
		return decide(baseline, current, chain, absorbed, false);
	}
}
