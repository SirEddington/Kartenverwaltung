package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Die zuletzt gelesene Nummer der Änderungsliste je fremdem Rechner, für einen gemeinsamen Ordner. Sie steht in
 * {@code sync-state.json} im Datenordner dieses Rechners, nie im gemeinsamen Ordner:
 * {@code {"<Schlüssel des gemeinsamen Ordners>": {"<Rechner-ID>": <Nummer>, …}, …}}. Mehrere gemeinsame Ordner teilen
 * sich eine Datei; beim Speichern bleiben die Einträge der anderen Ordner erhalten.
 * <p>
 * Die Datei gehört diesem Rechner allein und darf deshalb überschrieben werden. Eine fehlende oder beschädigte Datei
 * wird beim nächsten {@link #save()} einfach ersetzt.
 */
public final class SyncPositions {

	private static final ObjectMapper MAPPER = JsonMapperFactory.create();

	private final Path file;
	private final String sharedKey;
	private final ObjectNode root;
	private final Map<String, Long> positions = new TreeMap<>();
	private final boolean broken;

	/**
	 * Lädt die Positionen für einen gemeinsamen Ordner.
	 *
	 * @param file      die Datei {@code sync-state.json}; sie muss nicht vorhanden sein
	 * @param sharedKey der Schlüssel des gemeinsamen Ordners (sein Pfad)
	 */
	public SyncPositions(Path file, String sharedKey) {
		this.file = file;
		this.sharedKey = sharedKey;
		ObjectNode loaded = null;
		boolean ownEntryFound = false;
		JsonNode node = parse(file);
		if (node != null && node.isObject()) {
			loaded = (ObjectNode) node;
			ownEntryFound = loadOwn(loaded.get(sharedKey));
		}
		this.root = loaded != null ? loaded : MAPPER.createObjectNode();
		this.broken = !ownEntryFound;
	}

	/** Der Inhalt der Datei oder {@code null}, wenn sie fehlt, nicht lesbar oder kein JSON ist. */
	private static JsonNode parse(Path file) {
		try {
			return MAPPER.readTree(Files.readAllBytes(file));
		} catch (IOException e) {
			return null;
		}
	}

	/** Übernimmt die Nummern aus dem Eintrag; bei jeder Unstimmigkeit bleibt die Liste leer. */
	private boolean loadOwn(JsonNode entry) {
		if (entry == null || !entry.isObject()) {
			return false;
		}
		Map<String, Long> read = new TreeMap<>();
		for (Map.Entry<String, JsonNode> field : entry.properties()) {
			JsonNode value = field.getValue();
			if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
				return false;
			}
			read.put(field.getKey(), value.asLong());
		}
		positions.putAll(read);
		return true;
	}

	/**
	 * Ob die Datei beim Laden fehlte, unlesbar war oder keinen Eintrag für diesen gemeinsamen Ordner hatte. Dann sind die
	 * Positionen unbekannt, und es folgt ein Vollabgleich. Der Wert beschreibt den Zustand beim Laden und ändert sich
	 * durch {@link #save()} nicht.
	 */
	public boolean wasBroken() {
		return broken;
	}

	/** Die zuletzt gelesene Nummer des Rechners; 0, wenn er unbekannt ist. */
	public synchronized long position(String machineId) {
		return positions.getOrDefault(machineId, 0L);
	}

	/**
	 * Setzt die zuletzt gelesene Nummer des Rechners (nur im Speicher bis {@link #save()}).
	 *
	 * @throws IllegalArgumentException wenn die Nummer negativ ist
	 */
	public synchronized void set(String machineId, long seq) {
		if (seq < 0) {
			throw new IllegalArgumentException("Die Nummer darf nicht negativ sein: " + seq);
		}
		positions.put(machineId, seq);
	}

	/** Die Rechner, für die eine Position bekannt ist (auch mit Nummer 0), sortiert. */
	public synchronized Set<String> machines() {
		return new TreeSet<>(positions.keySet());
	}

	/**
	 * Schreibt die ganze Datei neu: über eine temporäre Datei im selben Ordner, die die vorhandene atomar ersetzt. Die
	 * Einträge anderer gemeinsamer Ordner bleiben erhalten.
	 */
	public synchronized void save() throws IOException {
		ObjectNode own = MAPPER.createObjectNode();
		positions.forEach(own::put);
		root.set(sharedKey, own);
		byte[] bytes = MAPPER.writeValueAsBytes(root);
		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path temp = dir.resolve(FileLayer.TEMP_PREFIX + UUID.randomUUID() + FileLayer.TEMP_SUFFIX);
		try {
			Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException | RuntimeException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}
}
