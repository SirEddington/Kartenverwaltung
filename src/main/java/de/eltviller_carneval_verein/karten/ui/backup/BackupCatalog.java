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

	/** Ein vorbereitetes Wiederherstellen (Ergebnis von {@link #prepare}), das {@link #commit} übernimmt. */
	interface Prepared {
	}

	/**
	 * Erster Schritt des Wiederherstellens: liest den Versionsspeicher und setzt den Zielstand zusammen, ändert aber nichts.
	 * Darf dauern und gehört deshalb auf einen Hintergrund-Thread. Wirft nie; scheitert die Vorbereitung, steht der Fehler im
	 * Ergebnis von {@link #commit}.
	 */
	Prepared prepare(Entry entry, SavePoint point);

	/**
	 * Zweiter Schritt: übernimmt den vorbereiteten Zielstand in Cache und Versionsspeicher. Gehört auf den
	 * Oberflächen-Thread. Wirft nie, Fehler (auch die der Vorbereitung und ein inzwischen geänderter Stand) stehen im Ergebnis.
	 */
	RestoreResult commit(Prepared prepared);

	/** Beide Schritte nacheinander auf dem aufrufenden Thread; wirft nie, Fehler stehen im Ergebnis. */
	default RestoreResult restore(Entry entry, SavePoint point) {
		return commit(prepare(entry, point));
	}

	/** Was das Zurücksetzen außer der gewählten Entität noch mitnimmt; Teil der Rückfrage. */
	String restoreWarning();
}
