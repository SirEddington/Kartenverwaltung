package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * Nach einer Neuvergabe der ID merkt sich {@code machine.json} die früheren IDs mit dem Zeitpunkt der Neuvergabe
 * ({@code "previous"}, fehlt in älteren Dateien); {@link #ownership()} entscheidet damit, welche Versionen als eigen gelten.
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
	private final List<Ownership.Previous> previous;
	private final Clock clock;
	private volatile String name;
	private volatile String operator;

	/**
	 * Ob die Datei beim Laden schadhafte Einträge in {@code previous} enthielt, die das nächste Schreiben verlieren würde:
	 * Dann wird vor diesem Schreiben eine Kopie der Datei angelegt. Zugriff nur in {@code synchronized}-Methoden oder vor
	 * der Veröffentlichung der Instanz.
	 */
	private boolean keepOriginal;

	private MachineIdentity(Path file, String id, List<Ownership.Previous> previous, String name, String operator,
			Clock clock, boolean keepOriginal) {
		this.file = file;
		this.id = id;
		this.previous = List.copyOf(previous);
		this.name = name;
		this.operator = operator;
		this.clock = clock;
		this.keepOriginal = keepOriginal;
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
	 * {@code -2}, {@code -3} …) und eine neue Kennung angelegt; gelöscht wird nichts. Enthält {@code previous} schadhafte
	 * Einträge (oder ist kein Array), bleibt die ID, die brauchbaren Einträge gelten weiter, und vor dem ersten Neuschreiben
	 * der Datei entsteht eine Kopie unter demselben Namensschema.
	 *
	 * @param appDataDir   Datenordner dieses Rechners (wird bei Bedarf angelegt)
	 * @param computerName liefert den Computernamen als Vorbelegung des Anzeigenamens
	 * @param clock        Uhr für den Zeitstempel im Namen einer beschädigten Kopie
	 * @throws IOException wenn die Datei nicht gelesen oder die neue nicht geschrieben werden kann. Jeder Lesefehler außer
	 *                     "Datei fehlt" wird weitergeworfen: Eine vorhandene Kennung wird nie wegen einer vorübergehenden
	 *                     Störung ersetzt.
	 */
	public static MachineIdentity loadOrCreate(Path appDataDir, Supplier<String> computerName, Clock clock)
			throws IOException {
		return loadOrCreate(appDataDir, computerName, clock, Files::readAllBytes);
	}

	/** Liest eine Datei; {@link java.nio.file.NoSuchFileException} heißt "fehlt". Für Tests austauschbar. */
	interface Reader {
		byte[] read(Path file) throws IOException;
	}

	/**
	 * Wie {@link #loadOrCreate(Path, Supplier, Clock)} mit austauschbarem Lesezugriff. Eine neue Datei wird ohne
	 * Ersetzen angelegt; ist inzwischen eine da (ein anderer Start war schneller), wird sie gelesen.
	 */
	static MachineIdentity loadOrCreate(Path appDataDir, Supplier<String> computerName, Clock clock, Reader reader)
			throws IOException {
		Path file = appDataDir.resolve(FILE_NAME);
		for (int attempt = 1;; attempt++) {
			byte[] bytes;
			try {
				bytes = reader.read(file);
			} catch (NoSuchFileException e) {
				bytes = null;
			}
			if (bytes != null) {
				JsonNode node = parseObject(bytes);
				String id = validId(node);
				if (id != null) {
					String name = validName(node);
					PreviousRead read = readPrevious(node);
					if (name != null) {
						return new MachineIdentity(file, id, read.entries(), name, null, clock, read.lossy());
					}
					// Gültige ID, aber Name fehlt oder ist unbrauchbar: die ID bleibt (sonst wären die eigenen Versionen und
					// der eigene Listenordner plötzlich fremd), nur der Name wird neu vorbelegt und die Datei neu geschrieben.
					MachineIdentity repaired = new MachineIdentity(file, id, read.entries(), nameFrom(computerName), null,
							clock, read.lossy());
					repaired.write(id, repaired.name, read.entries());
					return repaired;
				}
				asideCorrupt(file, clock, false);
			}
			String name = nameFrom(computerName);
			MachineIdentity created = new MachineIdentity(file, newId(), List.of(), name, null, clock, false);
			if (created.writeNew(name)) {
				return created;
			}
			if (attempt >= 3) {
				throw new IOException("Die Datei " + file + " lässt sich weder lesen noch anlegen.");
			}
			// inzwischen angelegt: noch einmal lesen
		}
	}

	/**
	 * Legt {@code machine.json} an, ohne eine vorhandene zu ersetzen.
	 *
	 * @return {@code false}, wenn es die Datei schon gibt
	 */
	private boolean writeNew(String name) throws IOException {
		byte[] bytes = toBytes(id, name, previous);
		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path temp = dir.resolve(FileLayer.TEMP_PREFIX + UUID.randomUUID() + FileLayer.TEMP_SUFFIX);
		try {
			Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			// ohne Optionen: ein vorhandenes Ziel wird nicht ersetzt
			Files.move(temp, file);
			return true;
		} catch (FileAlreadyExistsException e) {
			Files.deleteIfExists(temp);
			return false;
		} catch (IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException | RuntimeException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
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

	/**
	 * Das Ergebnis des Lesens von {@code previous}.
	 *
	 * @param entries die brauchbaren Einträge
	 * @param lossy   ob etwas fehlt, was ein Neuschreiben verlöre: übersprungene Einträge, oder {@code previous} ist kein
	 *                Array
	 */
	private record PreviousRead(List<Ownership.Previous> entries, boolean lossy) {
	}

	/**
	 * Die früheren IDs aus dem Objekt. Fehlt das Feld, ist die Liste leer. Ein Eintrag ohne gültige ID (26 Zeichen
	 * {@code [0-9a-z]}) oder ohne lesbaren Zeitpunkt (nicht lesbar, oder nicht in Millisekunden darstellbar) wird
	 * übersprungen: Die aktuelle ID ist wichtiger als ein schadhafter Eintrag, deshalb gilt die Datei dadurch nicht als
	 * beschädigt. Damit nichts still verloren geht, meldet das Ergebnis, ob Einträge übersprungen wurden oder
	 * {@code previous} kein Array ist; dann sichert das nächste Schreiben zuvor eine Kopie der Datei.
	 */
	private static PreviousRead readPrevious(JsonNode node) {
		JsonNode array = node.get("previous");
		if (array == null) {
			return new PreviousRead(List.of(), false);
		}
		if (!array.isArray()) {
			return new PreviousRead(List.of(), true);
		}
		List<Ownership.Previous> result = new ArrayList<>();
		boolean lossy = false;
		for (JsonNode entry : array) {
			JsonNode entryId = entry.get("id");
			JsonNode until = entry.get("until");
			if (entryId == null || !entryId.isTextual() || !ID_PATTERN.matcher(entryId.asText()).matches()
					|| until == null || !until.isTextual()) {
				lossy = true;
				continue;
			}
			try {
				Instant parsed = Instant.parse(until.asText());
				// Ownership vergleicht in Millisekunden: Ein Zeitpunkt, der dort überläuft, wäre später ein Fehler im Betrieb
				parsed.toEpochMilli();
				result.add(new Ownership.Previous(entryId.asText(), parsed));
			} catch (DateTimeParseException | ArithmeticException e) {
				lossy = true;
			}
		}
		return new PreviousRead(result, lossy);
	}

	/**
	 * Legt die Datei unter dem Namen {@code machine.json.corrupt-<yyyy-MM-dd_HH-mm-ss>} (bei Namensgleichheit mit Zusatz
	 * {@code -2}, {@code -3} …) beiseite, ohne eine vorhandene Datei zu überschreiben.
	 *
	 * @param copy {@code true}: kopieren, die Datei bleibt; {@code false}: umbenennen
	 */
	private static void asideCorrupt(Path file, Clock clock, boolean copy) throws IOException {
		String base = FILE_NAME + ".corrupt-" + LocalDateTime.now(clock).format(CORRUPT_STAMP);
		for (int attempt = 1;; attempt++) {
			Path target = file.resolveSibling(attempt == 1 ? base : base + "-" + attempt);
			try {
				if (copy) {
					Files.copy(file, target);
				} else {
					Files.move(file, target);
				}
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
		write(id, trimmed, previous);
		this.name = trimmed;
	}

	/**
	 * Was dieser Rechner als "eigen" ansieht: die aktuelle ID und die früheren (siehe {@link Ownership}).
	 */
	public Ownership ownership() {
		return new Ownership(id, previous);
	}

	/**
	 * Vergibt eine neue ID (gleicher Name, gleicher Bediener) und schreibt {@code machine.json} neu. Nötig, wenn ein
	 * Datenordner auf einen zweiten Rechner kopiert wurde und beide dieselbe ID tragen. Die alte ID bleibt mit der Grenze
	 * {@code until} (auf die Millisekunde abgeschnitten) als frühere ID erhalten: Versionen der alten ID bis einschließlich
	 * dahin gelten weiter als eigen (siehe {@link Ownership}). Der Aufrufer wählt die Grenze so, dass sie jede Version deckt,
	 * die er mit der alten ID erzeugt hat oder noch erzeugt (siehe {@link VersionIdGenerator#freeze()}). Diese Instanz
	 * behält ihre ID; wer die neue braucht, nimmt die zurückgegebene.
	 *
	 * @param until die Grenze der alten ID
	 * @throws IOException wenn die Datei nicht geschrieben werden kann
	 */
	public synchronized MachineIdentity regenerate(Instant until) throws IOException {
		String newId = newId();
		List<Ownership.Previous> all = new ArrayList<>(previous);
		all.add(new Ownership.Previous(id, until.truncatedTo(ChronoUnit.MILLIS)));
		write(newId, name, all);
		return new MachineIdentity(file, newId, all, name, operator, clock, false);
	}

	private static byte[] toBytes(String id, String name, List<Ownership.Previous> previous) throws IOException {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("id", id);
		node.put("name", name);
		if (!previous.isEmpty()) {
			ArrayNode array = node.putArray("previous");
			for (Ownership.Previous old : previous) {
				ObjectNode entry = array.addObject();
				entry.put("id", old.id());
				entry.put("until", old.until().toString());
			}
		}
		return MAPPER.writeValueAsBytes(node);
	}

	/**
	 * Schreibt die Datei über eine temporäre Datei im selben Ordner und ersetzt die vorhandene atomar
	 * ({@link AtomicFileReplace#write}, mit Wiederholung, solange ein anderer Zugriff sie offen hält). Enthielt die Datei
	 * beim Laden schadhafte Einträge in {@code previous}, wird vor dem ersten Schreiben eine Kopie angelegt (siehe
	 * {@link #asideCorrupt}, mit der Uhr dieser Instanz); schlägt das fehl, bleibt die Datei unverändert.
	 */
	private void write(String id, String name, List<Ownership.Previous> previous) throws IOException {
		byte[] bytes = toBytes(id, name, previous);
		Files.createDirectories(file.toAbsolutePath().getParent());
		if (keepOriginal) {
			asideCorrupt(file, clock, true);
		}
		AtomicFileReplace.write(file, bytes, null);
		keepOriginal = false;
	}
}
