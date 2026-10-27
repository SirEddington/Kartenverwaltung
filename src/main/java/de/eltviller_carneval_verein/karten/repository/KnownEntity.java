package de.eltviller_carneval_verein.karten.repository;

/**
 * Ein Event oder eine Halle, die das Repository kennt, auch wenn sie gelöscht ist (zum Wiederherstellen).
 *
 * @param id      die ID
 * @param name    der Name; bei einer gelöschten Einheit der aus ihrer letzten nicht gelöschten Version, sonst die ID
 * @param deleted ob die Einheit gelöscht ist
 */
public record KnownEntity(String id, String name, boolean deleted) {
}
