package de.eltviller_carneval_verein.karten.store;

/**
 * Eine Einheit, die der Vollabgleich übersprungen hat, weil sie sich wiederholt nicht mit dem gemeinsamen Ordner
 * abgleichen ließ (SYS-013). Der nächste Lauf versucht es erneut.
 *
 * @param unit    die Einheit
 * @param subtree ob auch alles darunter übersprungen ist: Ihre Vorstellungen bzw. Buchungen ließen sich nicht auflisten
 * @param cause   die Ursache für Anzeige und Log ({@link StoreMessages#describe})
 */
public record SkippedUnit(UnitKey unit, boolean subtree, String cause) {
}
