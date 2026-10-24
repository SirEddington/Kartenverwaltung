package de.eltviller_carneval_verein.karten.mirror;

/** Zustand der Spiegelung, wie ihn {@link MirrorService#state()} meldet. */
public enum MirrorState {

	/** Keine Spiegelung eingerichtet. */
	AUS,

	/** Ein Abgleich läuft. */
	ABGLEICH,

	/** Der Spiegelordner entspricht dem lokalen Stand. */
	AKTUELL,

	/** Übertragung offen oder fehlgeschlagen, der Spiegelordner ist aber erreichbar. */
	AUSSTEHEND,

	/** Der Spiegelordner ist nicht erreichbar; lokal wird weitergearbeitet. */
	NICHT_ERREICHBAR,

	/** Der letzte Abgleich ist an einem Fehler gescheitert oder wurde abgebrochen. */
	FEHLER
}
