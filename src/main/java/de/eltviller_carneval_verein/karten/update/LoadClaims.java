package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Die Ladedateien {@code laden-<Paketversion>-<Rechner-ID>.json} in {@code updates/} und ihre Altersregeln (KARV-132).
 * Maßgeblich ist, seit wann <b>diese</b> Kasse eine Datei (mit dieser Größe und Änderungszeit) sieht, und die Zeit in der
 * Datei nach eigener Uhr; eine fremde Uhr allein entscheidet nie. Threadsicher.
 */
final class LoadClaims {

	static final Duration FRESH = Duration.ofHours(1);
	static final Duration DONE_FRESH = Duration.ofHours(3);
	static final Duration EXPIRE = Duration.ofDays(1);
	private static final Logger LOG = Logger.getLogger(LoadClaims.class.getName());

	/** Eine Ladedatei. */
	record Claim(Path file, String machineId, Optional<Instant> angelegt, Optional<Instant> fertig) {
		/** Ob der Download dieser Kasse fertig ist. */
		boolean done() {
			return fertig.isPresent();
		}
	}

	private record Observed(FileKey key, Instant since) {
	}

	private final Clock clock;
	private final Map<Path, Observed> observed = new ConcurrentHashMap<>();

	LoadClaims(Clock clock) {
		this.clock = clock;
	}

	/** Der Inhalt einer Ladedatei; {@code fertig} {@code null} solange geladen wird. */
	static byte[] json(String machineId, String name, Instant angelegt, Instant fertig) {
		ObjectNode n = UpdateFiles.JSON.createObjectNode();
		n.put("format", 1);
		n.put("machineId", machineId);
		n.put("name", name);
		n.put("angelegt", angelegt.toString());
		if (fertig != null) {
			n.put("fertig", fertig.toString());
		}
		return UpdateFiles.json(n);
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
				Optional<FileKey> key = FileKey.of(p);
				if (key.isEmpty()) {
					continue;
				}
				present.add(p);
				observed.compute(p, (k, old) -> old != null && old.key().equals(key.get()) ? old : new Observed(key.get(), now));
				if (paketversion == null || paketversion.equals(v.get())) {
					result.add(parse(p, UpdatesDir.claimMachine(name).orElseThrow()));
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

	private static Claim parse(Path p, String machine) {
		Optional<Instant> angelegt = Optional.empty();
		Optional<Instant> fertig = Optional.empty();
		try {
			Optional<byte[]> data = UpdateFiles.readAtMost(p, UpdateFiles.MAX_JSON_BYTES);
			if (data.isPresent()) {
				JsonNode n = UpdateFiles.JSON.readTree(data.get());
				angelegt = instant(n, "angelegt");
				fertig = instant(n, "fertig");
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.INFO, "Ladedatei unlesbar: " + p + " (" + e + ")");
		}
		return new Claim(p, machine, angelegt, fertig);
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
}
