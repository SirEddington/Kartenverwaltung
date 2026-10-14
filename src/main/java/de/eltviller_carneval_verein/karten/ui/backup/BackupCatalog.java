package de.eltviller_carneval_verein.karten.ui.backup;

import java.util.List;

import de.eltviller_carneval_verein.karten.repository.BackupStore.Backup;

/**
 * Was der {@link BackupRestoreDialog} über eine Art von Entität (Events oder Hallen) wissen muss: welche es mit
 * Sicherungen gibt, welche Sicherungen das sind und wie eine davon zurückgeholt wird. Ohne Oberfläche, damit sich
 * das Wiederherstellen ohne JavaFX testen lässt.
 */
public interface BackupCatalog {

	/**
	 * Ein Event bzw. eine Halle mit Sicherungen.
	 *
	 * @param id      ID der Entität
	 * @param name    Name zur Anzeige (aktueller Name, bei gelöschten der aus der neuesten Sicherung, sonst die ID)
	 * @param deleted {@code true}, wenn es die Entität nicht mehr gibt
	 */
	record Entry(String id, String name, boolean deleted) {
	}

	/** Bezeichnung der Entität in Texten, z. B. "Event" oder "Halle". */
	String entityLabel();

	/** Alle Entitäten mit Sicherungen, alphabetisch nach Name. */
	List<Entry> entries();

	/** Die Sicherungen einer Entität, neueste zuerst. */
	List<Backup> versions(String id);

	/** Holt die Sicherung zurück; wirft nie, Fehler stehen im Ergebnis. */
	RestoreResult restore(Entry entry, Backup backup);
}
