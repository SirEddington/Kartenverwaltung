package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Die Kennung dieses Rechners: eine beständige, zufällige ID und ein Anzeigename, dazu der Bediener dieser Sitzung.
 * <p>
 * Die ID kommt in jede Versionsdatei (daran erkennt der Speicher "eigene" Versionen und den eigenen Listenordner); die
 * ersten 8 Zeichen bilden das Rechner-Kürzel der {@link VersionId}. ID und Name stehen in {@code machine.json} direkt im
 * Datenordner dieses Rechners, nie im gemeinsamen Ordner. Die Datei gehört diesem Rechner allein und darf deshalb
 * überschrieben werden (anders als Datendateien).
 * <p>
 * Der Bediener wird nur im Speicher gehalten. ID, Name und Bediener sind {@code volatile}, weil ein Hintergrund-Thread
 * sie später liest.
 */
public final class MachineIdentity {

	/** Name der Datei im Datenordner. */
	static final String FILE_NAME = "machine.json";

	/** Name, wenn der Computername nicht zu ermitteln ist. */
	static final String DEFAULT_NAME = "Rechner";

	private static final int ID_LENGTH = 26;
	private static final int RADIX = 36;
	private static final Pattern ID_PATTERN = Pattern.compile("[0-9a-z]{" + ID_LENGTH + "}");
	private static final DateTimeFormatter CORRUPT_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final ObjectMapper MAPPER = JsonMapperFactory.create();

	private final Path file;
	private final String id;
	private volatile String name;
	private volatile String operator;

	private MachineIdentity(Path file, String id, String name, String operator) {
		this.file = file;
		this.id = id;
		this.name = name;
		this.operator = operator;
	}

	/**
	 * Lädt die Kennung aus {@code machine.json} im Datenordner oder legt sie an. Mit der Systemuhr für den Namen einer
	 * beschädigten Kopie.
	 *
	 * @see #loadOrCreate(Path, Supplier, Clock)
	 */
	public static MachineIdentity loadOrCreate(Path appDataDir, Supplier<String> computerName) throws IOException {
		return loadOrCreate(appDataDir, computerName, Clock.systemDefaultZone());
	}

	/**
	 * Lädt die Kennung aus {@code machine.json} im Datenordner oder legt sie an. Fehlt die Datei, entsteht eine neue ID,
	 * der Name ist der Computername (oder "Rechner", wenn der Lieferant nichts oder nur Leerraum liefert). Hat die Datei
	 * eine gültige ID, aber keinen brauchbaren Namen (fehlt, kein Text, leer), bleibt die ID; der Name wird wie bei einer
	 * neuen Kennung vorbelegt und die Datei neu geschrieben, ohne Kopie. Ist die Datei sonst beschädigt (kein JSON-Objekt,
	 * ID fehlt oder nicht aus 26 Zeichen {@code [0-9a-z]}), wird sie nach
	 * {@code machine.json.corrupt-<yyyy-MM-dd_HH-mm-ss>} umbenannt (nie überschrieben, bei Namensgleichheit mit Zusatz
	 * {@code -2}, {@code -3} …) und eine neue Kennung angelegt; gelöscht wird nichts.
	 *
	 * @param appDataDir   Datenordner dieses Rechners (wird bei Bedarf angelegt)
	 * @param computerName liefert den Computernamen als Vorbelegung des Anzeigenamens
	 * @param clock        Uhr für den Zeitstempel im Namen einer beschädigten Kopie
	 * @throws IOException wenn die Datei nicht gelesen oder die neue nicht geschrieben werden kann
	 */
	public static MachineIdentity loadOrCreate(Path appDataDir, Supplier<String> computerName, Clock clock)
			throws IOException {
		Path file = appDataDir.resolve(FILE_NAME);
		if (Files.exists(file)) {
			byte[] bytes = Files.readAllBytes(file);
			JsonNode node = parseObject(bytes);
			String id = validId(node);
			if (id != null) {
				String name = validName(node);
				if (name != null) {
					return new MachineIdentity(file, id, name, null);
				}
				// Gültige ID, aber Name fehlt oder ist unbrauchbar: die ID bleibt (sonst wären die eigenen Versionen und der
				// eigene Listenordner plötzlich fremd), nur der Name wird neu vorbelegt und die Datei neu geschrieben.
				MachineIdentity repaired = new MachineIdentity(file, id, nameFrom(computerName), null);
				repaired.write(id, repaired.name);
				return repaired;
			}
			moveAsideCorrupt(file, clock);
		}
		String name = nameFrom(computerName);
		MachineIdentity created = new MachineIdentity(file, newId(), name, null);
		created.write(created.id, name);
		return created;
	}

	/** Der Computername als Anzeigename; "Rechner", wenn der Lieferant nichts oder nur Leerraum liefert. */
	private static String nameFrom(Supplier<String> computerName) {
		String supplied = computerName.get();
		return supplied == null || supplied.isBlank() ? DEFAULT_NAME : supplied.trim();
	}

	/** Der Inhalt als JSON-Objekt oder {@code null}, wenn er kein JSON-Objekt ist. */
	private static JsonNode parseObject(byte[] bytes) {
		try {
			JsonNode node = MAPPER.readTree(bytes);
			return node != null && node.isObject() ? node : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** Die ID aus dem Objekt oder {@code null}, wenn sie fehlt oder nicht aus 26 Zeichen {@code [0-9a-z]} besteht. */
	private static String validId(JsonNode node) {
		JsonNode id = node == null ? null : node.get("id");
		return id != null && id.isTextual() && ID_PATTERN.matcher(id.asText()).matches() ? id.asText() : null;
	}

	/** Der Name aus dem Objekt oder {@code null}, wenn er fehlt, kein Text oder leer ist. */
	private static String validName(JsonNode node) {
		JsonNode name = node.get("name");
		return name != null && name.isTextual() && !name.asText().isBlank() ? name.asText() : null;
	}

	/** Benennt die beschädigte Datei um, ohne eine vorhandene Kopie zu überschreiben. */
	private static void moveAsideCorrupt(Path file, Clock clock) throws IOException {
		String base = FILE_NAME + ".corrupt-" + LocalDateTime.now(clock).format(CORRUPT_STAMP);
		for (int attempt = 1;; attempt++) {
			try {
				Files.move(file, file.resolveSibling(attempt == 1 ? base : base + "-" + attempt));
				return;
			} catch (FileAlreadyExistsException e) {
				// Kopie mit diesem Namen gibt es schon: nächsten Namen versuchen
			}
		}
	}

	private static String newId() {
		StringBuilder id = new StringBuilder(ID_LENGTH);
		for (int i = 0; i < ID_LENGTH; i++) {
			id.append(Character.forDigit(RANDOM.nextInt(RADIX), RADIX));
		}
		return id.toString();
	}

	/** Die beständige, zufällige ID dieses Rechners: 26 Zeichen aus {@code [0-9a-z]}. */
	public String id() {
		return id;
	}

	/** Der Anzeigename dieses Rechners. */
	public String name() {
		return name;
	}

	/** Der Bediener dieser Sitzung oder {@code null}, solange keiner gesetzt ist. */
	public String operator() {
		return operator;
	}

	/** Setzt den Bediener (nur im Speicher). Leerraum wird abgeschnitten, Leeres und {@code null} heißen "kein Bediener". */
	public void setOperator(String operator) {
		String trimmed = operator == null ? null : operator.trim();
		this.operator = trimmed == null || trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * Ändert den Anzeigenamen und schreibt {@code machine.json} neu. Schlägt das Schreiben fehl, bleibt der alte Name.
	 *
	 * @param name der neue Name; Leerraum wird abgeschnitten
	 * @throws IllegalArgumentException wenn der Name leer ist
	 * @throws IOException              wenn die Datei nicht geschrieben werden kann
	 */
	public synchronized void rename(String name) throws IOException {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Der Name des Rechners darf nicht leer sein.");
		}
		String trimmed = name.trim();
		write(id, trimmed);
		this.name = trimmed;
	}

	/**
	 * Vergibt eine neue ID (gleicher Name, gleicher Bediener) und schreibt {@code machine.json} neu. Nötig, wenn ein
	 * Datenordner auf einen zweiten Rechner kopiert wurde und beide dieselbe ID tragen. Diese Instanz behält ihre ID; wer
	 * die neue braucht, nimmt die zurückgegebene.
	 *
	 * @throws IOException wenn die Datei nicht geschrieben werden kann
	 */
	public synchronized MachineIdentity regenerate() throws IOException {
		String newId = newId();
		write(newId, name);
		return new MachineIdentity(file, newId, name, operator);
	}

	/** Schreibt die Datei über eine temporäre Datei im selben Ordner und ersetzt die vorhandene atomar. */
	private void write(String id, String name) throws IOException {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("id", id);
		node.put("name", name);
		byte[] bytes = MAPPER.writeValueAsBytes(node);
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
