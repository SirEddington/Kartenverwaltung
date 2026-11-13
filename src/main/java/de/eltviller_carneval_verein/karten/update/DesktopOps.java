package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Eine Datei öffnen bzw. eine Adresse im Browser zeigen; nie auf dem FX-Thread aufrufen (KARV-131). */
public interface DesktopOps {

	/** Öffnet {@code file} mit dem zugeordneten Programm (die {@code .appinstaller}-Datei mit dem App Installer). */
	void open(Path file) throws IOException;

	/** Öffnet {@code uri} im Standardbrowser. */
	void browse(URI uri) throws IOException;
}
