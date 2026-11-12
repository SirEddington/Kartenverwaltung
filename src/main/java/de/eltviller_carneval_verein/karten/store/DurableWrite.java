package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Schreibt eine Datei und wartet, bis ihr Inhalt auf der Platte ist ({@link FileChannel#force(boolean) force(true)}),
 * bevor der Aufruf zurückkehrt (KARV-90).
 * <p>
 * Ohne das kann eine temporäre Datei schon umbenannt sein, während ihr Inhalt noch im Schreibcache des Betriebssystems
 * liegt: Fällt dann der Strom aus, steht die Datei unter ihrem richtigen Namen mit fehlendem oder genulltem Inhalt da,
 * und der Start-Index (Abdruck über Name, Größe, Änderungszeit) erkennt das nicht. Wer eine temporäre Datei schreibt und
 * danach umbenennt (oder verlinkt), schreibt sie deshalb hiermit. {@code force(true)}, damit auch die Größe auf der Platte
 * stimmt; unter Windows ist es ohnehin dasselbe ({@code FlushFileBuffers}).
 * <p>
 * Scheitert das Schreiben oder das Erzwingen, geht die Ausnahme an den Aufrufer; eine angefangene Datei räumt er weg.
 */
public final class DurableWrite {

	/** Das Erzwingen auf die Platte; im Test ersetzbar. */
	@FunctionalInterface
	interface Forcer {
		void force(FileChannel channel) throws IOException;
	}

	/** Schreibt eine neue temporäre Datei; die Naht, über die Tests das Erzwingen beobachten. */
	@FunctionalInterface
	interface TempWriter {
		void write(Path temp, byte[] content) throws IOException;
	}

	private static final Forcer FORCE = channel -> channel.force(true);

	private DurableWrite() {
	}

	/**
	 * Legt {@code file} neu an, schreibt den ganzen Inhalt und wartet, bis er auf der Platte ist.
	 *
	 * @throws java.nio.file.FileAlreadyExistsException wenn es die Datei schon gibt (sie bleibt unverändert)
	 */
	public static void writeNew(Path file, byte[] content) throws IOException {
		writeNew(file, content, FORCE);
	}

	static void writeNew(Path file, byte[] content, Forcer forcer) throws IOException {
		write(file, content, forcer, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
	}

	/**
	 * Schreibt {@code file} mit dem ganzen Inhalt neu (legt sie an, falls sie fehlt; ein älterer, längerer Inhalt entfällt
	 * ganz) und wartet, bis er auf der Platte ist. Für Dateien, die ohne temporäre Datei geschrieben werden.
	 */
	public static void overwrite(Path file, byte[] content) throws IOException {
		overwrite(file, content, FORCE);
	}

	static void overwrite(Path file, byte[] content, Forcer forcer) throws IOException {
		write(file, content, forcer, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);
	}

	private static void write(Path file, byte[] content, Forcer forcer, OpenOption... options) throws IOException {
		try (FileChannel channel = FileChannel.open(file, options)) {
			ByteBuffer buffer = ByteBuffer.wrap(content);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			forcer.force(channel);
		}
	}
}
