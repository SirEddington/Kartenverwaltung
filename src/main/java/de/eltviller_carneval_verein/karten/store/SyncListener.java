package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Set;

/**
 * Rückmeldungen des {@link SyncService}. Alle kommen über den {@code ui}-Executor des Dienstes an (in der Anwendung auf
 * dem JavaFX-Thread). Jede Methode ist leer vorbelegt; ein Empfänger überschreibt nur, was er braucht.
 */
public interface SyncListener {

	/**
	 * Der gültige Stand dieser Einheiten hat sich geändert (fremde Änderungen, eigene Uploads, Verluste, vom Dienst
	 * geschriebene Zusammenführungen).
	 */
	default void unitsChanged(Set<UnitKey> units) {
	}

	/**
	 * Die Menge der offenen Konflikte kann sich geändert haben, etwa nachdem der Dienst eine Zusammenführung mit
	 * Konfliktvermerk geschrieben hat.
	 */
	default void conflictsChanged() {
	}

	/**
	 * Ein anderer Rechner hat eine Version gelistet, deren Anspruch noch fehlt: Sie wird gerade verkauft, gilt aber noch
	 * nicht.
	 *
	 * @param machineName der Anzeigename des Rechners; {@code null}, wenn er unbekannt ist
	 */
	default void pending(UnitKey unit, VersionId version, String machineName) {
	}

	/**
	 * Eigene Versionen haben verloren, ein anderer Rechner hat zuerst beansprucht. Jede Version wird in einem
	 * Programmlauf höchstens einmal gemeldet.
	 *
	 * @param mine   die eigenen verlorenen Versionen, die älteste zuerst
	 * @param winner die Version, die den Platz belegt
	 */
	default void lost(UnitKey unit, List<VersionId> mine, VersionId winner) {
	}

	/** Der gemeinsame Ordner ist nicht erreichbar; einmal je Ausfall. */
	default void unreachable(String path) {
	}

	/** Der gemeinsame Ordner ist nach einem Ausfall wieder erreichbar, und der Abgleich ist gelungen. */
	default void reconnected() {
	}

	/** Ein Fehler mit fertigem Text samt Code für die Fußzeile ({@code SYS-006}, {@code SYS-008}, {@code SYS-009}, {@code SYS-012}). */
	default void error(String message) {
	}

	/** Dieser Rechner hat eine neue Kennung bekommen, weil ein anderer dieselbe verwendet ({@code SYS-011}). */
	default void duplicateMachine(String message) {
	}

	/**
	 * Beim Verbinden haben beide Seiten Einheiten, und der Abgleich pausiert (Zustand {@link SyncState#ENTSCHEIDUNG}), bis
	 * {@link SyncService#decideConnect} entscheidet: im Modus {@link ConnectMode#ASK}, oder wenn die Schutzregel ein
	 * Beiseitelegen verbietet. Kommt einmal je Eintritt in die Pause (also auch nach einem Neustart oder nach der Rückkehr
	 * des Ordners erneut) und noch einmal, wenn {@link SyncService#decideConnect} das Beiseitelegen ablehnt.
	 *
	 * @param dir             der gemeinsame Ordner
	 * @param setAsideAllowed ob Beiseitelegen angeboten werden darf: {@code false}, sobald dieser Programmlauf nach dem
	 *                        Start eigene Versionen geschrieben hat (Schutzregel); Mitnehmen geht immer
	 */
	default void connectDecisionNeeded(String dir, boolean setAsideAllowed) {
	}
}
