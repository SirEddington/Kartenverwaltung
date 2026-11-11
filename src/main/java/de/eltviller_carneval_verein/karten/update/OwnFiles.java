package de.eltviller_carneval_verein.karten.update;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Die Dateien, die dieser Programmlauf im gemeinsamen Ordner angelegt hat und noch braucht (KARV-132): laufende
 * Ladedatei, tmp-Datei des Downloads, Zwischendatei beim Ersetzen. Das Aufräumen löscht sie nie; unter Windows ließe
 * sich eine offene Datei sonst löschen. Threadsicher.
 */
public final class OwnFiles {

	private final Set<Path> files = ConcurrentHashMap.newKeySet();

	/** Merkt eine Datei. */
	public void add(Path file) {
		files.add(file.toAbsolutePath().normalize());
	}

	/** Vergisst eine Datei. */
	public void remove(Path file) {
		files.remove(file.toAbsolutePath().normalize());
	}

	/** Ob die Datei von diesem Lauf stammt und noch gebraucht wird. */
	public boolean contains(Path file) {
		return files.contains(file.toAbsolutePath().normalize());
	}
}
