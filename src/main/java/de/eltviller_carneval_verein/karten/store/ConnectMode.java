package de.eltviller_carneval_verein.karten.store;

/**
 * Wie der lokale Bestand mit einem gemeinsamen Ordner verbunden wird, wenn beide Seiten schon Einheiten haben.
 */
public enum ConnectMode {

	/**
	 * Mitnehmen: Vollabgleich, zusätzlich werden lokale Anspruchsdateien, die im gemeinsamen Ordner fehlen, dort
	 * angelegt. Liegt dort schon ein anderer Anspruch, ist die lokale Version verloren.
	 */
	MERGE,

	/**
	 * Beiseitelegen: Das lokale {@code data} wird in {@code data-beiseite-<Zeitstempel>} umbenannt (nie gelöscht), dann
	 * folgt der Vollabgleich in einen leeren Bestand.
	 */
	SET_ASIDE
}
