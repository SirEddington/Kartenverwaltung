package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Liest und schreibt die drei Dateiarten des Versionsspeichers (Version, Anspruch, Liste) als JSON.
 * <p>
 * Die Dateien entstehen kompakt in fester Feldreihenfolge, denn die SHA-256 der Versionsdatei steht später in der
 * Anspruchsdatei und muss aus genau diesen Bytes berechnet werden. Zeitpunkte stehen als ISO-8601-Text, Versions-IDs
 * als einfacher Text. Beim Lesen werden unbekannte Felder übergangen, damit neuere Programmversionen innerhalb von
 * Format 1 Felder ergänzen können. Das Format wird vor allem anderen geprüft: Ein höheres Format meldet
 * {@link UnknownFormatException}, jede andere Beschädigung eine gewöhnliche {@link IOException}.
 */
public final class StoreCodec {

	/** Das Dateiformat, das diese Programmversion schreibt und liest. */
	public static final int FORMAT = 1;

	private static final Logger LOGGER = Logger.getLogger(StoreCodec.class.getName());

	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();

	private StoreCodec() {
	}

	/** Schreibt eine Versionsdatei; bei einer gelöschten Version ist die Nutzlast leer. */
	public static byte[] write(Version version) throws IOException {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("format", version.format());
		node.put("id", version.id().value());
		ArrayNode parents = node.putArray("parents");
		for (VersionId parent : version.parents()) {
			parents.add(parent.value());
		}
		node.put("machine", version.machine());
		node.put("machineName", version.machineName());
		node.put("operator", version.operator());
		node.put("at", version.at().toString());
		node.put("saveId", version.saveId());
		node.put("deleted", version.deleted());
		node.set("payload", version.deleted() || version.payload() == null ? MAPPER.createObjectNode() : version.payload());
		if (version.conflict() != null) {
			writeConflict(node.putObject("conflict"), version.conflict());
		}
		if (!version.seen().isEmpty()) {
			ObjectNode seen = node.putObject("seen");
			new TreeMap<>(version.seen()).forEach(seen::put);
		}
		return MAPPER.writeValueAsBytes(node);
	}

	private static void writeConflict(ObjectNode node, ConflictNote note) {
		node.put("kind", note.kind().name());
		node.put("affected", note.affected());
		ConflictNote.Party other = note.other();
		if (other != null) {
			ObjectNode party = node.putObject("other");
			party.put("machine", other.machine());
			if (other.machineName() != null) {
				party.put("machineName", other.machineName());
			}
			if (other.operator() != null) {
				party.put("operator", other.operator());
			}
			party.put("version", other.version().value());
		}
		if (!note.fields().isEmpty()) {
			ArrayNode fields = node.putArray("fields");
			for (ConflictNote.FieldConflict field : note.fields()) {
				ObjectNode item = fields.addObject();
				item.put("group", field.group());
				item.set("mine", field.mine());
				item.set("theirs", field.theirs());
			}
		}
		if (note.moved() != null) {
			node.put("moved", note.moved().relativePath());
		}
		if (note.place() != null) {
			node.set("place", note.place());
		}
		if (note.dropped() != null) {
			node.put("dropped", note.dropped().value());
		}
	}

	/** Schreibt eine Anspruchsdatei. */
	public static byte[] write(Claim claim) throws IOException {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("format", FORMAT);
		node.put("version", claim.version().value());
		node.put("sha256", claim.sha256());
		node.put("machine", claim.machine());
		node.put("at", claim.at().toString());
		return MAPPER.writeValueAsBytes(node);
	}

	/** Schreibt eine Listendatei. */
	public static byte[] write(Feed feed) throws IOException {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("format", feed.format());
		node.put("machine", feed.machine());
		node.put("seq", feed.seq());
		node.put("at", feed.at().toString());
		ArrayNode entries = node.putArray("entries");
		for (FeedEntry entry : feed.entries()) {
			ObjectNode item = entries.addObject();
			item.put("unit", entry.unit());
			item.put("version", entry.version().value());
			item.put("claim", entry.claim());
		}
		return MAPPER.writeValueAsBytes(node);
	}

	/**
	 * Liest eine Versionsdatei. Fehlt die Nutzlast oder ist sie {@code null}, ist sie ein leeres Objekt. Rechnername
	 * und Bediener dürfen fehlen oder {@code null} sein (der Bediener ist nicht immer gesetzt) und sind dann
	 * {@code null}.
	 *
	 * @throws UnknownFormatException wenn die Datei ein höheres Format hat
	 * @throws IOException            wenn die Datei kein gültiges JSON ist oder Felder fehlen oder ungültig sind
	 */
	public static Version readVersion(byte[] bytes) throws IOException {
		JsonNode node = readTree(bytes);
		try {
			int format = checkFormat(node);
			List<VersionId> parents = new ArrayList<>();
			for (JsonNode parent : array(node, "parents")) {
				parents.add(VersionId.of(asText(parent, "parents")));
			}
			JsonNode payload = node.get("payload");
			if (payload != null && !payload.isNull() && !payload.isObject()) {
				throw new IOException("Das Feld payload ist kein Objekt.");
			}
			return new Version(format, VersionId.of(text(node, "id")), parents, text(node, "machine"),
					optionalText(node, "machineName"), optionalText(node, "operator"), instant(node, "at"), text(node, "saveId"),
					bool(node, "deleted"),
					payload == null || payload.isNull() ? MAPPER.createObjectNode() : (ObjectNode) payload,
					readConflict(node.get("conflict")), readSeen(node.get("seen")));
		} catch (IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Der optionale Konfliktvermerk; fehlt das Feld oder ist es {@code null}, gibt es keinen. Ein Vermerk mit
	 * unbekannter Art (aus einer neueren Programmversion) wird mit einer Warnung übergangen, sonstige Beschädigungen
	 * darin sind eine {@link IOException}.
	 */
	private static ConflictNote readConflict(JsonNode node) throws IOException {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isObject()) {
			throw new IOException("Das Feld conflict ist kein Objekt.");
		}
		String kindText = text(node, "kind");
		ConflictNote.Kind kind;
		try {
			kind = ConflictNote.Kind.valueOf(kindText);
		} catch (IllegalArgumentException e) {
			// eine Art aus einer neueren Programmversion: Die Version bleibt lesbar, nur der Vermerk entfällt
			LOGGER.log(Level.WARNING, "Der Konfliktvermerk mit der unbekannten Art " + kindText + " wird übergangen.");
			return null;
		}
		List<ConflictNote.FieldConflict> fields = new ArrayList<>();
		JsonNode fieldNodes = node.get("fields");
		if (fieldNodes != null && !fieldNodes.isNull()) {
			for (JsonNode item : array(node, "fields")) {
				if (!item.isObject()) {
					throw new IOException("Ein Eintrag in fields ist kein Objekt.");
				}
				fields.add(new ConflictNote.FieldConflict(text(item, "group"), requiredObject(item, "mine"),
						requiredObject(item, "theirs")));
			}
		}
		return new ConflictNote(kind, text(node, "affected"), readParty(node.get("other")), fields,
				readMoved(node.get("moved")), optionalObject(node, "place"), optionalVersionId(node, "dropped"));
	}

	private static ConflictNote.Party readParty(JsonNode node) throws IOException {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isObject()) {
			throw new IOException("Das Feld other ist kein Objekt.");
		}
		return new ConflictNote.Party(text(node, "machine"), optionalText(node, "machineName"),
				optionalText(node, "operator"), VersionId.of(text(node, "version")));
	}

	private static UnitKey readMoved(JsonNode node) throws IOException {
		if (node == null || node.isNull()) {
			return null;
		}
		String path = asText(node, "moved");
		return UnitKey.parse(path)
				.orElseThrow(() -> new IOException("Das Feld moved enthält keinen Einheitenpfad: " + path));
	}

	/** Die optionalen gelesenen Listen: Rechner-ID auf Nummer; fehlt das Feld, ist die Zuordnung leer. */
	private static Map<String, Long> readSeen(JsonNode node) throws IOException {
		Map<String, Long> seen = new LinkedHashMap<>();
		if (node == null || node.isNull()) {
			return seen;
		}
		if (!node.isObject()) {
			throw new IOException("Das Feld seen ist kein Objekt.");
		}
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			JsonNode number = entry.getValue();
			if (!number.isIntegralNumber() || !number.canConvertToLong()) {
				throw new IOException("Das Feld seen enthält für " + entry.getKey() + " keine ganze Zahl.");
			}
			seen.put(entry.getKey(), number.asLong());
		}
		return seen;
	}

	/**
	 * Liest eine Anspruchsdatei.
	 *
	 * @throws UnknownFormatException wenn die Datei ein höheres Format hat
	 * @throws IOException            wenn die Datei kein gültiges JSON ist oder Felder fehlen oder ungültig sind
	 */
	public static Claim readClaim(byte[] bytes) throws IOException {
		JsonNode node = readTree(bytes);
		try {
			checkFormat(node);
			return new Claim(VersionId.of(text(node, "version")), text(node, "sha256"), text(node, "machine"),
					instant(node, "at"));
		} catch (IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Liest eine Listendatei.
	 *
	 * @throws UnknownFormatException wenn die Datei ein höheres Format hat
	 * @throws IOException            wenn die Datei kein gültiges JSON ist oder Felder fehlen oder ungültig sind
	 */
	public static Feed readFeed(byte[] bytes) throws IOException {
		JsonNode node = readTree(bytes);
		try {
			int format = checkFormat(node);
			JsonNode seq = node.get("seq");
			if (seq == null || !seq.isIntegralNumber() || !seq.canConvertToLong()) {
				throw new IOException("Das Feld seq fehlt oder ist keine ganze Zahl.");
			}
			List<FeedEntry> entries = new ArrayList<>();
			for (JsonNode item : array(node, "entries")) {
				if (!item.isObject()) {
					throw new IOException("Ein Eintrag in entries ist kein Objekt.");
				}
				entries.add(new FeedEntry(text(item, "unit"), VersionId.of(text(item, "version")), text(item, "claim")));
			}
			return new Feed(format, text(node, "machine"), seq.asLong(), instant(node, "at"), entries);
		} catch (IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/** SHA-256 der Bytes in Hex-Schreibweise (Kleinbuchstaben). */
	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 ist in jeder Java-Laufzeit vorhanden.", e);
		}
	}

	/** Liest die Bytes als JSON-Objekt; alles andere ist eine {@link IOException}. */
	static JsonNode readTree(byte[] bytes) throws IOException {
		JsonNode node = MAPPER.readTree(bytes);
		if (node == null || !node.isObject()) {
			throw new IOException("Die Datei enthält kein JSON-Objekt.");
		}
		return node;
	}

	/** Prüft das Format vor allem anderen und liefert es zurück. */
	private static int checkFormat(JsonNode node) throws IOException {
		JsonNode format = node.get("format");
		if (format == null || !format.isIntegralNumber() || !format.canConvertToInt() || format.asInt() < 1) {
			throw new IOException("Das Feld format fehlt oder ist ungültig.");
		}
		if (format.asInt() > FORMAT) {
			throw new UnknownFormatException(format.asInt());
		}
		return format.asInt();
	}

	/** Ein Pflichtfeld, das Text sein muss. Die Feldleser sind paketsichtbar, weil auch {@link UnitIndex} sie benutzt. */
	static String text(JsonNode node, String field) throws IOException {
		return asText(node.get(field), field);
	}

	/** Ein Textfeld, das fehlen oder {@code null} sein darf (Anzeigefelder wie Bediener); sonst muss es Text sein. */
	static String optionalText(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : asText(value, field);
	}

	private static String asText(JsonNode value, String field) throws IOException {
		if (value == null || !value.isTextual()) {
			throw new IOException("Das Feld " + field + " fehlt oder ist kein Text.");
		}
		return value.asText();
	}

	/** Ein Pflichtfeld, das ein Wahrheitswert sein muss. */
	static boolean bool(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		if (value == null || !value.isBoolean()) {
			throw new IOException("Das Feld " + field + " fehlt oder ist kein Wahrheitswert.");
		}
		return value.asBoolean();
	}

	/** Ein Pflichtfeld, das ein Objekt sein muss. */
	private static ObjectNode requiredObject(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		if (value == null || !value.isObject()) {
			throw new IOException("Das Feld " + field + " fehlt oder ist kein Objekt.");
		}
		return (ObjectNode) value;
	}

	/** Ein Feld, das fehlen oder {@code null} sein darf; sonst muss es ein Objekt sein. */
	private static ObjectNode optionalObject(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : requiredObject(node, field);
	}

	/** Ein Feld mit einer Versions-ID, das fehlen oder {@code null} sein darf. */
	private static VersionId optionalVersionId(JsonNode node, String field) throws IOException {
		String value = optionalText(node, field);
		return value == null ? null : VersionId.of(value);
	}

	private static JsonNode array(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		if (value == null || !value.isArray()) {
			throw new IOException("Das Feld " + field + " fehlt oder ist keine Liste.");
		}
		return value;
	}

	private static Instant instant(JsonNode node, String field) throws IOException {
		String text = text(node, field);
		try {
			return Instant.parse(text);
		} catch (DateTimeParseException e) {
			throw new IOException("Das Feld " + field + " enthält keinen Zeitpunkt: " + text, e);
		}
	}
}
