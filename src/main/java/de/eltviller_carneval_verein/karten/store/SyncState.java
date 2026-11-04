package de.eltviller_carneval_verein.karten.store;

/** Der Zustand des Austauschs mit dem gemeinsamen Ordner, wie ihn {@link SyncService#syncState()} zeigt. */
public enum SyncState {
	/** Kein gemeinsamer Ordner: Einzelbetrieb, der lokale Bestand ist Schiedsrichter. */
	AUS,
	/** Der Ordner ist erreichbar, und alle eigenen Versionen sind hochgeladen. */
	AKTUELL,
	/** Der Ordner ist erreichbar, aber eigene Versionen warten noch auf das Hochladen (sie sind vorläufig). */
	AUSSTEHEND,
	/** Der Ordner ist nicht erreichbar; es wird lokal weitergearbeitet, neue Versionen sind vorläufig. */
	OFFLINE,
	/** Der Ordner ist erreichbar, aber der Abgleich scheitert an einem anderen Fehler (z. B. fehlende Schreibrechte). */
	FEHLER,
	/**
	 * Modus {@link ConnectMode#ASK}: Beide Seiten haben Einheiten, und der Abgleich pausiert, bis
	 * {@link SyncService#decideConnect} entscheidet (nichts wird hochgeladen oder abgeholt); neue Versionen sind vorläufig.
	 */
	ENTSCHEIDUNG
}
