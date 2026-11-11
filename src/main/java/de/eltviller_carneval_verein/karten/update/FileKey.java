package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

/**
 * Größe und Änderungszeit einer Datei (KARV-132): Ändert sich eins davon, gilt die Datei als neu (Prüfung, „zuerst
 * gesehen“, Beobachtung von Ladedateien).
 */
record FileKey(long size, FileTime modified) {

	/** Der Schlüssel einer vorhandenen regulären Datei; leer, wenn sie fehlt oder nicht lesbar ist. */
	static Optional<FileKey> of(Path file) {
		try {
			return Files.isRegularFile(file) ? Optional.of(new FileKey(Files.size(file), Files.getLastModifiedTime(file)))
					: Optional.empty();
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}
}
