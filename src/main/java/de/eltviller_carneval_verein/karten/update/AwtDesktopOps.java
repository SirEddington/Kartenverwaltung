package de.eltviller_carneval_verein.karten.update;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * {@link DesktopOps} über {@link java.awt.Desktop} (KARV-131). Jede Ausnahme wird zu einer {@link IOException}, damit der
 * Aufrufer nur einen Fehlerweg hat. {@code open} geht unter Windows über {@code ShellExecute}, {@code browse} startet den
 * Standardbrowser als Prozess.
 */
public final class AwtDesktopOps implements DesktopOps {

	@Override
	public void open(Path file) throws IOException {
		try {
			desktop(Desktop.Action.OPEN).open(file.toFile());
		} catch (UnsupportedOperationException | SecurityException | IllegalArgumentException e) {
			throw new IOException("Datei ließ sich nicht öffnen: " + file + " (" + e.getMessage() + ")", e);
		}
	}

	@Override
	public void browse(URI uri) throws IOException {
		try {
			desktop(Desktop.Action.BROWSE).browse(uri);
		} catch (UnsupportedOperationException | SecurityException | IllegalArgumentException e) {
			throw new IOException("Browser ließ sich nicht öffnen: " + uri + " (" + e.getMessage() + ")", e);
		}
	}

	/** Der Desktop, wenn er {@code action} kann; sonst {@link UnsupportedOperationException} (auch {@code HeadlessException}). */
	private static Desktop desktop(Desktop.Action action) {
		if (!Desktop.isDesktopSupported()) {
			throw new UnsupportedOperationException("kein Desktop");
		}
		Desktop desktop = Desktop.getDesktop();
		if (!desktop.isSupported(action)) {
			throw new UnsupportedOperationException(action + " nicht unterstützt");
		}
		return desktop;
	}
}
