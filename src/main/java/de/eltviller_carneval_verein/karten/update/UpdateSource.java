package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Woher die App erfährt, ob es ein Update gibt, und woher sie die {@code .appinstaller}-Datei holt (KARV-131). */
public interface UpdateSource {

	/** Liest {@code metadata.properties} der Quelle. */
	UpdateInfo fetchInfo() throws IOException;

	/** Lädt die {@code .appinstaller}-Datei frisch in eine eigene Datei und liefert deren Pfad. */
	Path downloadAppInstaller() throws IOException;

	/** Die Adresse der {@code .appinstaller}-Datei (für den Browser-Weg). */
	URI appInstallerUri();

	/** Räumt alte Update-Dateien weg; Fehler werden nur protokolliert. */
	void cleanup();
}
