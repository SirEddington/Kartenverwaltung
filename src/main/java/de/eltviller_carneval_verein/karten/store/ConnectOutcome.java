package de.eltviller_carneval_verein.karten.store;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Wie ein {@link SyncService#connect} (oder ein {@link SyncService#decideConnect}) ausging. Es kommt genau einmal je
 * Aufruf, beim ersten Ausgang: am Ende des ersten Vollabgleichs der Verbindung oder vorher, wenn der Ordner nicht
 * erreichbar ist, ein Abschnitt scheitert oder die Verbindung getrennt bzw. gewechselt wird. Läuft der erste
 * Vollabgleich danach weiter, meldet sein Ende
 * {@link SyncService#connectionComplete()}; ein zweites Ergebnis gibt es nicht.
 *
 * @param kind            der Ausgang
 * @param setAsideAllowed nur bei {@link Kind#ENTSCHEIDUNG}: ob Beiseitelegen angeboten werden darf; sonst {@code false}
 * @param setAsidePath    der Ordner, in den der lokale Bestand beiseitegelegt wurde; {@code null}, wenn (noch) nicht
 *                        beiseitegelegt wurde
 * @param message         der Text zum Ausgang: SYS-005 bei {@link Kind#OFFLINE}, SYS-006 bei {@link Kind#FEHLER}; sonst
 *                        {@code null}
 */
public record ConnectOutcome(Kind kind, boolean setAsideAllowed, Path setAsidePath, String message) {

	/** Die Ausgänge des Verbindens. */
	public enum Kind {
		/** Der erste Vollabgleich ist zu Ende, nichts Eigenes wartet noch auf das Hochladen. */
		AKTUELL,
		/** Der erste Vollabgleich ist zu Ende, eigene Versionen warten noch auf das Hochladen. */
		AUSSTEHEND,
		/** Der Ordner war vor oder während des ersten Vollabgleichs nicht erreichbar; die Verbindung bleibt, der Lauf ruht. */
		OFFLINE,
		/** Ein Abschnitt des ersten Vollabgleichs ist gescheitert; der Lauf setzt in einem späteren Takt fort. */
		FEHLER,
		/**
		 * Beide Seiten haben Einheiten, und der Aufrufer muss entscheiden ({@link SyncService#decideConnect}); der Abgleich
		 * pausiert.
		 */
		ENTSCHEIDUNG,
		/** Getrennt oder neu verbunden, bevor ein anderer Ausgang kam. */
		ABGEBROCHEN
	}

	public ConnectOutcome {
		Objects.requireNonNull(kind, "kind");
	}
}
