package de.eltviller_carneval_verein.karten.update;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Was das Paket {@code update} vom gemeinsamen Ordner wissen muss (KARV-132), ohne {@code store} zu kennen. Die App
 * verbindet es mit dem Versionsspeicher. Alle Methoden dürfen von Hintergrund-Threads aufgerufen werden.
 */
public interface SharedFolderAccess {

	/** Der verbundene gemeinsame Ordner; leer im Einzelbetrieb. */
	Optional<Path> folder();

	/** Ob der Ordner lokal als Verzeichnis vorhanden ist (mit Zeitlimit; genügt zum Lesen und Installieren). */
	boolean isPresent(Path folder);

	/** Ob der Ordner erreichbar ist wie für den Abgleich, bei Cloud-Ordnern samt Server (nötig für den Download). */
	boolean isReachable(Path folder);

	/** Die aktuelle Rechner-ID. */
	String machineId();

	/** Der Anzeigename dieses Rechners. */
	String machineName();

	/** Ob die Kennung einer temporären Datei diesem Rechner gehört (aktuelle oder frühere Rechner-ID). */
	boolean isOwnTempTag(String tag);
}
