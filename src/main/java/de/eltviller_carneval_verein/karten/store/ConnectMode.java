package de.eltviller_carneval_verein.karten.store;

/**
 * Wie der lokale Bestand mit einem gemeinsamen Ordner verbunden wird, wenn beide Seiten schon Einheiten haben. Hat eine
 * Seite keine, wird in jedem Modus vereinigt.
 */
public enum ConnectMode {

	/**
	 * Mitnehmen: Vollabgleich, zusätzlich werden lokale Anspruchsdateien, die im gemeinsamen Ordner fehlen, dort
	 * angelegt. Liegt dort schon ein anderer Anspruch, ist die lokale Version verloren.
	 */
	MERGE,

	/**
	 * Beiseitelegen: Das lokale {@code data} wird in {@code data-beiseite-<Zeitstempel>} umbenannt (nie gelöscht), dann
	 * folgt der Vollabgleich in einen leeren Bestand. Hat dieser Programmlauf nach dem Start eigene Versionen geschrieben,
	 * verbietet es die Schutzregel: Der Abgleich pausiert dann wie bei {@link #ASK} mit der Frage, Beiseitelegen ist nicht
	 * mehr angeboten.
	 */
	SET_ASIDE,

	/**
	 * Beim ersten Lauf entscheidet der Versionsspeicher: eine Seite leer → vereinigen; beide mit Einheiten → Pause und Frage.
	 * Die Frage kommt über {@link SyncListener#connectDecisionNeeded}, die Antwort über {@link SyncService#decideConnect}.
	 */
	ASK
}
