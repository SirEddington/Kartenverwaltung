package de.eltviller_carneval_verein.karten.repository;

/**
 * Eine sichtbare Kasse, deren wirksamer Ist-Betrag heute von dem zu einem Speicherstand abweicht (KARV-144): Das
 * Wiederherstellen setzt Kassen nicht zurück, die Rückfrage nennt sie deshalb ({@link StoreEventRepository#countedSince}).
 *
 * @param name  der Anzeigename der Kasse heute
 * @param cents der heutige Ist-Betrag in Cent, auch 0
 */
public record CountedCash(String name, int cents) {
}
