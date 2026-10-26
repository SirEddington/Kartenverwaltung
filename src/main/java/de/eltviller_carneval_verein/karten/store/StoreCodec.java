package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

	private static final ObjectMapper MAPPER = JsonMapperFactory.create().copy()
			.disable(SerializationFeature.INDENT_OUTPUT);

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
		return MAPPER.writeValueAsBytes(node);
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
					payload == null || payload.isNull() ? MAPPER.createObjectNode() : (ObjectNode) payload);
		} catch (IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
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

	private static JsonNode readTree(byte[] bytes) throws IOException {
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

	private static String text(JsonNode node, String field) throws IOException {
		return asText(node.get(field), field);
	}

	/** Ein Textfeld, das fehlen oder {@code null} sein darf (Anzeigefelder wie Bediener); sonst muss es Text sein. */
	private static String optionalText(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : asText(value, field);
	}

	private static String asText(JsonNode value, String field) throws IOException {
		if (value == null || !value.isTextual()) {
			throw new IOException("Das Feld " + field + " fehlt oder ist kein Text.");
		}
		return value.asText();
	}

	private static boolean bool(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		if (value == null || !value.isBoolean()) {
			throw new IOException("Das Feld " + field + " fehlt oder ist kein Wahrheitswert.");
		}
		return value.asBoolean();
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
