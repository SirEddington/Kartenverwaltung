package de.eltviller_carneval_verein.karten.repository;

import de.eltviller_carneval_verein.karten.store.UnitState;

/**
 * Eine Kassen-Einheit, die sich nicht ändern lässt (KARV-144): gesperrt (SYS-008, SYS-009) oder wartend auf eine Datei
 * aus dem gemeinsamen Ordner, am Stand des Versionsspeichers ({@link StoreEventRepository#lockedCashRegisters}). Die
 * Event-Verwaltung behandelt eine wartende Kasse wie eine gesperrte.
 *
 * @param id       die Kennung der Kasse ({@code UnitKey#cashRegisterId})
 * @param status   die Sperre ({@code LOCKED_DAMAGED}, {@code LOCKED_FORMAT}) oder {@code WAITING}
 * @param readable ob das Zusammensetzen einen Stand der Kasse liest (sie dann im Modell steht); ohne lesbaren Stand
 *                 ersetzt eine Zeile „(gesperrt)" die Kasse
 */
public record LockedCashRegister(String id, UnitState.Status status, boolean readable) {
}
