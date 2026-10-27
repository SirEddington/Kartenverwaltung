package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Ersetzt eine Datei atomar durch eine fertig geschriebene temporäre Datei ({@code ATOMIC_MOVE},
 * {@code REPLACE_EXISTING}) und wiederholt das kurz, wenn die Zieldatei gerade von jemand anderem geöffnet ist.
 * <p>
 * Windows verweigert das Ersetzen, solange ein anderer Zugriff auf die Zieldatei offen ist: die Spiegelung, die
 * sie gerade liest, ein Virenscanner, die Indizierung oder ein Synchronisationsprogramm. Solche Zugriffe dauern
 * meist nur Millisekunden; deshalb wird bis zu {@link #ATTEMPTS}-mal im Abstand von {@link #PAUSE_MILLIS} ms
 * versucht und erst danach die letzte Ausnahme unverändert weitergeworfen.
 * <p>
 * Wiederholt wird nur, was nach einem solchen Zugriff aussieht: eine {@link AccessDeniedException} und eine
 * {@link FileSystemException} genau dieser Klasse (als eine von beiden meldet Java unter Windows eine
 * Freigabeverletzung). Ihre speziellen Unterklassen wie {@code NoSuchFileException},
 * {@code FileAlreadyExistsException}, {@code DirectoryNotEmptyException}, {@code NotDirectoryException} oder
 * {@code AtomicMoveNotSupportedException} beschreiben dagegen einen festen Zustand, den Warten nicht ändert; sie
 * und alle anderen Ausnahmen gehen sofort an den Aufrufer.
 */
public final class AtomicFileReplace {

	/** So oft wird das Ersetzen höchstens versucht. */
	static final int ATTEMPTS = 10;

	/** Pause zwischen zwei Versuchen in Millisekunden. */
	static final long PAUSE_MILLIS = 50;

	/** Das eigentliche Verschieben; im Test ersetzbar. */
	@FunctionalInterface
	interface Mover {
		void move(Path source, Path target) throws IOException;
	}

	/** Die Pause zwischen zwei Versuchen; im Test ersetzbar, damit kein Test wirklich wartet. */
	@FunctionalInterface
	interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	private AtomicFileReplace() {
	}

	/** Ersetzt {@code target} atomar durch {@code temp}; siehe Klassenbeschreibung. */
	public static void replace(Path temp, Path target) throws IOException {
		replace(temp, target,
				(source, destination) -> Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING),
				Thread::sleep);
	}

	static void replace(Path temp, Path target, Mover mover, Sleeper sleeper) throws IOException {
		for (int attempt = 1;; attempt++) {
			try {
				mover.move(temp, target);
				return;
			} catch (FileSystemException e) {
				if (!isTransient(e) || attempt >= ATTEMPTS) {
					throw e;
				}
				try {
					sleeper.sleep(PAUSE_MILLIS);
				} catch (InterruptedException interrupted) {
					// Wer unterbrochen wird, wartet nicht weiter: der letzte Fehler gilt
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}

	// Nur ein offener fremder Zugriff vergeht von selbst; siehe Klassenbeschreibung
	private static boolean isTransient(FileSystemException e) {
		return e instanceof AccessDeniedException || e.getClass() == FileSystemException.class;
	}
}
