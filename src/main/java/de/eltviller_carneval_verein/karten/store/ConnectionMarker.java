package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Die Marke "verbunden" ({@code <AppData>/connection.json}, nur lokal): mit welchem gemeinsamen Ordner dieser Rechner
 * zuletzt verbunden wurde und ob der erste Vollabgleich dieser Verbindung zu Ende kam. Der Beginn des ersten Laufs
 * schreibt sie als unvollständig (nach der Entscheidung über das Beiseitelegen und nach dem Beiseitelegen), sein Ende als
 * vollständig; {@link SyncService#disconnect} löscht sie. Der Start der Anwendung wählt danach seinen Weg (KARV-87).
 * <p>
 * Inhalt: {@code {"dir": "<Ordner>", "at": "<ISO-Zeitpunkt>", "complete": true|false}}. Geschrieben wird über eine
 * temporäre Datei, die die vorhandene atomar ersetzt ({@link AtomicFileReplace}).
 */
final class ConnectionMarker {

	/** Name der Datei im Datenordner. */
	static final String FILE_NAME = "connection.json";

	private static final Logger LOGGER = Logger.getLogger(SyncService.class.getName());
	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();
	private static final String DIR = "dir";
	private static final String AT = "at";
	private static final String COMPLETE = "complete";

	/**
	 * Der Inhalt der Marke.
	 *
	 * @param dir      der gemeinsame Ordner, wie er eingestellt wurde
	 * @param at       wann die Marke geschrieben wurde
	 * @param complete ob der erste Vollabgleich der Verbindung zu Ende kam
	 */
	record State(String dir, Instant at, boolean complete) {
		State {
			Objects.requireNonNull(dir, "dir");
			Objects.requireNonNull(at, "at");
		}
	}

	private ConnectionMarker() {
	}

	/**
	 * Liest die Marke. Fehlt sie, gibt es keine; ist sie unlesbar (Lesefehler, kein JSON-Objekt, ein Feld fehlt oder hat
	 * die falsche Art, leerer Ordner), ebenso, dann mit WARNING.
	 */
	static Optional<State> read(Path appDataDir) {
		Path file = appDataDir.resolve(FILE_NAME);
		try {
			JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
			JsonNode dir = root == null ? null : root.get(DIR);
			JsonNode at = root == null ? null : root.get(AT);
			JsonNode complete = root == null ? null : root.get(COMPLETE);
			if (dir == null || !dir.isTextual() || dir.asText().isBlank() || at == null || !at.isTextual()
					|| complete == null || !complete.isBoolean()) {
				throw new IOException("Die Felder '" + DIR + "', '" + AT + "' und '" + COMPLETE + "' fehlen oder sind ungültig");
			}
			return Optional.of(new State(dir.asText(), Instant.parse(at.asText()), complete.asBoolean()));
		} catch (NoSuchFileException e) {
			return Optional.empty();
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Die Marke 'verbunden' ist unlesbar: " + file, e);
			return Optional.empty();
		}
	}

	/** Schreibt die Marke über eine temporäre Datei, die die vorhandene atomar ersetzt. */
	static void write(Path appDataDir, FileLayer files, State state) throws IOException {
		ObjectNode root = MAPPER.createObjectNode();
		root.put(DIR, state.dir());
		root.put(AT, state.at().toString());
		root.put(COMPLETE, state.complete());
		byte[] bytes = MAPPER.writeValueAsBytes(root);
		Path file = appDataDir.resolve(FILE_NAME);
		Path temp = file.resolveSibling(FileLayer.TEMP_PREFIX + "connection-" + UUID.randomUUID() + FileLayer.TEMP_SUFFIX);
		try {
			files.createNew(temp, bytes);
			AtomicFileReplace.replace(temp, file);
		} catch (IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException | RuntimeException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	/** Löscht die Marke; ohne Marke ohne Wirkung. */
	static void delete(Path appDataDir) throws IOException {
		Files.deleteIfExists(appDataDir.resolve(FILE_NAME));
	}
}
