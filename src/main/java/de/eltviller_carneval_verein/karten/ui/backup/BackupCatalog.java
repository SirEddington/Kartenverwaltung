package de.eltviller_carneval_verein.karten.ui.backup;

import java.util.List;

import de.eltviller_carneval_verein.karten.repository.SavePoint;

/**
 * Was der {@link BackupRestoreDialog} über eine Art von Entität (Events oder Hallen) wissen muss: welche es gibt,
 * auch gelöschte, welche Speicherstände sie haben und wie eine davon zurückgeholt wird. Ohne Oberfläche, damit sich
 * das Wiederherstellen ohne JavaFX testen lässt.
 */
public interface BackupCatalog {

	/**
	 * Ein Event bzw. eine Halle, die das Repository kennt.
	 *
	 * @param id      ID der Entität
	 * @param name    Name zur Anzeige (aktueller Name, bei gelöschten der aus der letzten nicht gelöschten Version, sonst die ID)
	 * @param deleted {@code true}, wenn es die Entität nicht mehr gibt
	 */
	record Entry(String id, String name, boolean deleted) {
	}

	/** Bezeichnung der Entität in Texten, z. B. "Event" oder "Halle". */
	String entityLabel();

	/** Alle Entitäten, auch gelöschte, alphabetisch nach Name. */
	List<Entry> entries();

	/**
	 * Die Speicherstände einer Entität, neueste zuerst. Darf dauern (liest den Versionsspeicher) und gehört deshalb
	 * nicht auf den Oberflächen-Thread.
	 *
	 * @throws java.io.UncheckedIOException wenn der Versionsspeicher nicht lesbar ist
	 */
	List<SavePoint> versions(String id);

	/** Holt den Speicherstand zurück; wirft nie, Fehler stehen im Ergebnis. */
	RestoreResult restore(Entry entry, SavePoint point);

	/** Was das Zurücksetzen außer der gewählten Entität noch mitnimmt; Teil der Rückfrage. */
	String restoreWarning();
}
