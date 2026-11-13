package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Gemeinsame Hilfen für die Dateien in {@code updates/} (KARV-132): begrenztes Lesen, JSON, SHA-256 und Löschen. Die
 * Dateien können untergeschoben sein; deshalb ist jedes Lesen begrenzt.
 */
final class UpdateFiles {

	/** Größte JSON-Datei (Kasseneintrag, Beschreibung, Ladedatei), die gelesen wird. */
	static final int MAX_JSON_BYTES = 65536;
	/** Der eine kompakte JSON-Mapper des Pakets; gelesen und geschrieben wird über den Baum. */
	static final ObjectMapper JSON = JsonMapperFactory.createCompact();

	private static final Logger LOG = Logger.getLogger(UpdateFiles.class.getName());

	private UpdateFiles() {
	}

	/** Liest höchstens {@code max} Bytes; leer, wenn es mehr sind. */
	static Optional<byte[]> readAtMost(InputStream in, int max) throws IOException {
		byte[] data = in.readNBytes(max + 1);
		return data.length > max ? Optional.empty() : Optional.of(data);
	}

	/** Wie {@link #readAtMost(InputStream, int)} für eine Datei. */
	static Optional<byte[]> readAtMost(Path file, int max) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			return readAtMost(in, max);
		}
	}

	/** Ein JSON-Baum als kompakte Bytes (UTF-8 ohne BOM, Feldreihenfolge wie eingefügt). */
	static byte[] json(ObjectNode node) {
		try {
			return JSON.writeValueAsBytes(node);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 fehlt", e);
		}
	}

	/** Die SHA-256 einer Datei in Kleinbuchstaben-Hex. */
	static String sha256Hex(Path file) throws IOException {
		MessageDigest md = sha256();
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buf = new byte[1 << 16];
			for (int n; (n = in.read(buf)) > 0;) {
				md.update(buf, 0, n);
			}
		}
		return HexFormat.of().formatHex(md.digest());
	}

	/** Löscht eine Datei, wenn sie da ist; ein Fehler wird nur protokolliert. {@code null} wird übergangen. */
	static void deleteQuietly(Path p) {
		if (p == null) {
			return;
		}
		try {
			if (Files.deleteIfExists(p)) {
				LOG.info("Im gemeinsamen Ordner aufgeräumt: " + p.getFileName());
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Ließ sich nicht löschen: " + p, e);
		}
	}
}
