package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Ersetzt eine Datei im gemeinsamen Ordner, die genau ein Rechner schreibt (KARV-132: eigener Kasseneintrag, eigene
 * Ladedatei): temporäre Datei {@code ~karv-<Rechner-ID>-<UUID>.tmp} daneben, dann atomar ersetzen. Anders als
 * {@code store.AtomicFileReplace} legt es <b>keine</b> Elternordner an, damit ein fehlender gemeinsamer Ordner nicht
 * neu und leer entsteht.
 */
final class SharedFileReplace {

	static final int ATTEMPTS = 10;
	static final long PAUSE_MILLIS = 50;

	@FunctionalInterface
	interface Mover {
		void move(Path source, Path target) throws IOException;
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	private SharedFileReplace() {
	}

	/**
	 * @throws NoSuchFileException wenn der Elternordner fehlt
	 */
	static void write(Path file, byte[] bytes, String machineId, OwnFiles own) throws IOException {
		write(file, bytes, machineId, own,
				(s, t) -> Files.move(s, t, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
				Thread::sleep);
	}

	static void write(Path file, byte[] bytes, String machineId, OwnFiles own, Mover mover, Sleeper sleeper)
			throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		if (!Files.isDirectory(dir)) {
			throw new NoSuchFileException(dir.toString());
		}
		Path temp = dir.resolve(UpdatesDir.tempName(machineId));
		own.add(temp);
		try {
			Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			replace(temp, file, mover, sleeper);
		} finally {
			try {
				Files.deleteIfExists(temp);
			} finally {
				own.remove(temp);
			}
		}
	}

	private static void replace(Path temp, Path file, Mover mover, Sleeper sleeper) throws IOException {
		for (int attempt = 1;; attempt++) {
			try {
				mover.move(temp, file);
				return;
			} catch (AccessDeniedException e) {
				if (attempt >= ATTEMPTS) {
					throw e;
				}
				try {
					sleeper.sleep(PAUSE_MILLIS);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}
}
