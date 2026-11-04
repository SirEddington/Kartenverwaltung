package de.eltviller_carneval_verein.karten.store;

/**
 * Die Fachregel für eine Art von Einheiten: Sie entscheidet, wie eine verlorene eigene Strecke mit dem gültigen Stand
 * zusammengeführt wird. Gesetzt über {@link SyncService#setConflictPolicy}; ohne Strategie gilt "Deren bleibt" mit dem
 * Vermerk {@link ConflictNote.Kind#UNMERGED}.
 * <p>
 * Läuft auf dem Hintergrund-Thread des {@link SyncService} (beim Start auf dem Thread des Aufrufers) und fasst weder
 * Modell noch Vergleichsstand noch Tracker an. Die Inhalte der Eingabe sind Kopien. Wirft die Strategie, schreibt der
 * Dienst nichts, und die Einheit bleibt gegabelt.
 */
public interface ConflictPolicy {

	/**
	 * Führt eine verlorene Strecke mit dem gültigen Stand zusammen.
	 *
	 * @return die Fassung der Einheit, weitere Änderungen und der Vermerk; nie {@code null}
	 */
	Resolution resolve(ConflictInput input);
}
