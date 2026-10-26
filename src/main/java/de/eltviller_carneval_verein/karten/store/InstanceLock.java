package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Sperre gegen einen zweiten Start der Anwendung auf demselben Rechner. Zwei Prozesse würden kollidierende
 * Versions-IDs erzeugen und dieselbe Liste des Rechners schreiben.
 * <p>
 * Die Sperre ist eine Dateisperre auf {@code instance.lock} im Datenordner dieses Rechners (nie im gemeinsamen Ordner).
 * Sie hält, bis {@link #close()} sie freigibt oder der Prozess endet; die Datei selbst bleibt liegen.
 */
public final class InstanceLock implements AutoCloseable {

	/** Name der Sperrdatei im Datenordner. */
	static final String FILE_NAME = "instance.lock";

	private final FileChannel channel;
	private final FileLock lock;

	private InstanceLock(FileChannel channel, FileLock lock) {
		this.channel = channel;
		this.lock = lock;
	}

	/**
	 * Versucht, die Sperre zu erhalten.
	 *
	 * @param appDataDir Datenordner dieses Rechners (wird bei Bedarf angelegt)
	 * @return die Sperre, oder leer, wenn schon ein anderer Start sie hält (auch im selben Prozess)
	 * @throws IOException wenn die Sperrdatei nicht angelegt oder geöffnet werden kann
	 */
	public static Optional<InstanceLock> tryAcquire(Path appDataDir) throws IOException {
		Files.createDirectories(appDataDir);
		FileChannel channel = FileChannel.open(appDataDir.resolve(FILE_NAME), StandardOpenOption.READ,
				StandardOpenOption.WRITE, StandardOpenOption.CREATE);
		try {
			FileLock lock = channel.tryLock();
			if (lock == null) {
				channel.close();
				return Optional.empty();
			}
			return Optional.of(new InstanceLock(channel, lock));
		} catch (OverlappingFileLockException e) {
			channel.close();
			return Optional.empty();
		} catch (IOException | RuntimeException e) {
			try {
				channel.close();
			} catch (IOException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	/** Gibt die Sperre frei und schließt die Datei; mehrfaches Aufrufen schadet nicht. Die Datei bleibt liegen. */
	@Override
	public void close() throws IOException {
		try {
			if (channel.isOpen() && lock.isValid()) {
				lock.release();
			}
		} finally {
			channel.close();
		}
	}
}
