package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Die Ladedateien {@code laden-<Paketversion>-<Rechner-ID>.json} in {@code updates/} und ihre Altersregeln (KARV-132).
 * Maßgeblich ist, seit wann <b>diese</b> Kasse eine Datei (mit dieser Größe und Änderungszeit) sieht, und die Zeit in der
 * Datei nach eigener Uhr; eine fremde Uhr allein entscheidet nie. Threadsicher.
 */
final class LoadClaims {

	static final Duration FRESH = Duration.ofHours(1);
	static final Duration DONE_FRESH = Duration.ofHours(3);
	static final Duration EXPIRE = Duration.ofDays(1);
	private static final int MAX_JSON_BYTES = 65536;
	private static final Logger LOG = Logger.getLogger(LoadClaims.class.getName());
	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();

	/** Eine Ladedatei. */
	record Claim(Path file, String paketversion, String machineId, Optional<Instant> angelegt, Optional<Instant> fertig) {
		/** Ob der Download dieser Kasse fertig ist. */
		boolean done() {
			return fertig.isPresent();
		}
	}

	private record Key(long size, FileTime modified) {
	}

	private record Observed(Key key, Instant since) {
	}

	private final Clock clock;
	private final Map<Path, Observed> observed = new ConcurrentHashMap<>();

	LoadClaims(Clock clock) {
		this.clock = clock;
	}

	/** Der Inhalt einer Ladedatei; {@code fertig} {@code null} solange geladen wird. */
	static byte[] json(String machineId, String name, Instant angelegt, Instant fertig) {
		ObjectNode n = MAPPER.createObjectNode();
		n.put("format", 1);
		n.put("machineId", machineId);
		n.put("name", name);
		n.put("angelegt", angelegt.toString());
		if (fertig != null) {
			n.put("fertig", fertig.toString());
		}
		try {
			return MAPPER.writeValueAsBytes(n);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Alle Ladedateien in {@code updatesDir}, für {@code paketversion} oder ({@code null}) für alle Versionen. Merkt sich
	 * dabei, seit wann jede Datei unverändert zu sehen ist; verschwundene werden vergessen.
	 */
	List<Claim> read(Path updatesDir, String paketversion) {
		List<Claim> result = new ArrayList<>();
		if (!Files.isDirectory(updatesDir)) {
			return result;
		}
		Instant now = clock.instant();
		Set<Path> present = new HashSet<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(updatesDir)) {
			for (Path p : stream) {
				String name = p.getFileName().toString();
				Optional<String> v = UpdatesDir.claimVersion(name);
				if (v.isEmpty()) {
					continue;
				}
				Optional<Key> key = key(p);
				if (key.isEmpty()) {
					continue;
				}
				present.add(p);
				observed.compute(p, (k, old) -> old != null && old.key().equals(key.get()) ? old : new Observed(key.get(), now));
				if (paketversion == null || paketversion.equals(v.get())) {
					result.add(parse(p, v.get(), UpdatesDir.claimMachine(name).orElseThrow()));
				}
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Ladedateien ließen sich nicht lesen: " + updatesDir, e);
		}
		if (paketversion == null) {
			observed.keySet().removeIf(p -> p.getParent().equals(updatesDir) && !present.contains(p));
		}
		return result;
	}

	/** Ob die Ladedatei noch gilt: nicht fertig 1 h, fertig 3 h (nach Beobachtung und eigener Uhr). */
	boolean isFresh(Claim c) {
		Instant now = clock.instant();
		Duration seen = seenFor(c, now);
		if (c.done()) {
			Instant t = c.fertig().get();
			if (t.isAfter(now)) {
				return seen.compareTo(FRESH) <= 0;
			}
			return seen.compareTo(DONE_FRESH) <= 0 && !t.isBefore(now.minus(DONE_FRESH));
		}
		if (c.angelegt().isEmpty()) {
			return false;
		}
		return seen.compareTo(FRESH) <= 0 && !c.angelegt().get().isBefore(now.minus(FRESH));
	}

	/** Ob die Ladedatei gelöscht werden darf: Zeit älter als 1 Tag oder seit 1 Tag zu sehen. */
	boolean isExpired(Claim c) {
		Instant now = clock.instant();
		Optional<Instant> t = c.fertig().or(c::angelegt);
		if (t.isPresent() && t.get().isBefore(now.minus(EXPIRE))) {
			return true;
		}
		return seenFor(c, now).compareTo(EXPIRE) >= 0;
	}

	private Duration seenFor(Claim c, Instant now) {
		Observed o = observed.get(c.file());
		return o == null ? Duration.ZERO : Duration.between(o.since(), now);
	}

	private static Claim parse(Path p, String version, String machine) {
		Optional<Instant> angelegt = Optional.empty();
		Optional<Instant> fertig = Optional.empty();
		try {
			byte[] data;
			try (InputStream in = Files.newInputStream(p)) {
				data = in.readNBytes(MAX_JSON_BYTES + 1);
			}
			if (data.length <= MAX_JSON_BYTES) {
				JsonNode n = MAPPER.readTree(data);
				angelegt = instant(n, "angelegt");
				fertig = instant(n, "fertig");
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.INFO, "Ladedatei unlesbar: " + p + " (" + e + ")");
		}
		return new Claim(p, version, machine, angelegt, fertig);
	}

	private static Optional<Instant> instant(JsonNode n, String field) {
		JsonNode v = n.get(field);
		if (v == null || !v.isTextual()) {
			return Optional.empty();
		}
		try {
			return Optional.of(Instant.parse(v.asText()));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private static Optional<Key> key(Path file) {
		try {
			return Files.isRegularFile(file) ? Optional.of(new Key(Files.size(file), Files.getLastModifiedTime(file)))
					: Optional.empty();
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}
}
