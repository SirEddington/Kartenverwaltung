package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Die Versionseinträge der Kassen in {@code updates/kassen/} (KARV-132). Jede Kasse schreibt nur ihre eigene Datei;
 * fremde werden nur gelesen, unlesbare übersprungen und nie verändert.
 */
public final class StationRegistry {

	/** Größte Datei, die gelesen wird. */
	static final int MAX_JSON_BYTES = 65536;
	/** Höchstens so viele Einträge werden gelesen. */
	static final int MAX_ENTRIES = 200;
	/** So lange wird ein unveränderter eigener Eintrag nicht neu geschrieben. */
	static final Duration REWRITE_AFTER = Duration.ofDays(1);

	private static final Logger LOG = Logger.getLogger(StationRegistry.class.getName());
	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();

	private final SharedFolderAccess access;
	private final OwnFiles own;
	private final Clock clock;
	private volatile boolean writtenThisRun;

	public StationRegistry(SharedFolderAccess access, OwnFiles own, Clock clock) {
		this.access = access;
		this.own = own;
		this.clock = clock;
	}

	/**
	 * Schreibt den eigenen Eintrag beim ersten Aufruf eines Programmlaufs, danach nur, wenn sich etwas geändert hat oder
	 * der vorhandene älter als einen Tag ist. Fehlt der gemeinsame Ordner, geschieht nichts.
	 */
	public void writeOwn(Path folder, AppVersion version, String fsname, Plattform plattform) throws IOException {
		Optional<Path> dir = UpdatesDir.ensure(folder, UpdatesDir.STATIONS);
		if (dir.isEmpty()) {
			return;
		}
		String id = access.machineId();
		Path file = dir.get().resolve(id + ".json");
		Instant now = clock.instant();
		StationEntry wanted = new StationEntry(id, access.machineName(), fsname == null ? "" : fsname, version,
				plattform.name(), now);
		Optional<StationEntry> existing = read(file);
		// der erste Aufruf eines Programmlaufs schreibt immer, damit „zuletzt gestartet“ stimmt
		if (writtenThisRun && existing.isPresent() && sameExceptTime(existing.get(), wanted)
				&& Duration.between(existing.get().gestartet(), now).compareTo(REWRITE_AFTER) < 0) {
			return;
		}
		SharedFileReplace.write(file, toJson(wanted), id, own);
		writtenThisRun = true;
	}

	/** Alle lesbaren Einträge, nach Rechner-ID sortiert; leer, wenn der Ordner fehlt. */
	public List<StationEntry> readAll(Path folder) {
		Path dir = folder.resolve(UpdatesDir.UPDATES).resolve(UpdatesDir.STATIONS);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		List<Path> files = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path p : stream) {
				if (UpdatesDir.isStation(p.getFileName().toString())) {
					files.add(p);
				}
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Kasseneinträge ließen sich nicht lesen: " + dir, e);
			return List.of();
		}
		files.sort(Comparator.comparing(p -> p.getFileName().toString()));
		List<StationEntry> result = new ArrayList<>();
		for (Path p : files.subList(0, Math.min(files.size(), MAX_ENTRIES))) {
			read(p).ifPresent(result::add);
		}
		result.sort(Comparator.comparing(StationEntry::machineId));
		return result;
	}

	/** Der fremde Eintrag mit der höchsten Version über {@code own}, nur mit gleichem {@code fsname}. */
	public static Optional<StationEntry> newestForeign(List<StationEntry> all, String ownId, String fsname, AppVersion own) {
		String fs = fsname == null ? "" : fsname;
		return all.stream().filter(e -> !e.machineId().equals(ownId)).filter(e -> e.fsname().equals(fs))
				.filter(e -> e.version().compareTo(own) > 0).max(Comparator.comparing(StationEntry::version));
	}

	private static boolean sameExceptTime(StationEntry a, StationEntry b) {
		return a.machineId().equals(b.machineId()) && a.name().equals(b.name()) && a.fsname().equals(b.fsname())
				&& a.version().compareTo(b.version()) == 0 && a.version().version().equals(b.version().version())
				&& a.plattform().equals(b.plattform());
	}

	private static byte[] toJson(StationEntry e) throws IOException {
		ObjectNode n = MAPPER.createObjectNode();
		n.put("format", 1);
		n.put("machineId", e.machineId());
		n.put("name", e.name());
		n.put("fsname", e.fsname());
		n.put("version", e.version().version());
		n.put("revision", e.version().revision());
		n.put("plattform", e.plattform());
		n.put("gestartet", e.gestartet().toString());
		return MAPPER.writeValueAsBytes(n);
	}

	/** Liest einen Eintrag; leer bei fehlender, zu großer oder unlesbarer Datei (protokolliert). */
	private static Optional<StationEntry> read(Path file) {
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			byte[] data;
			try (InputStream in = Files.newInputStream(file)) {
				data = in.readNBytes(MAX_JSON_BYTES + 1);
			}
			if (data.length > MAX_JSON_BYTES) {
				LOG.warning("Kasseneintrag zu groß, übersprungen: " + file);
				return Optional.empty();
			}
			JsonNode n = MAPPER.readTree(data);
			String id = text(n, "machineId");
			String version = text(n, "version");
			Optional<AppVersion> v = AppVersion.parse(version, n.path("revision").asText(""));
			if (id == null || v.isEmpty()) {
				LOG.warning("Kasseneintrag ohne Rechner-ID oder Version, übersprungen: " + file);
				return Optional.empty();
			}
			String started = text(n, "gestartet");
			Instant gestartet = started == null ? Instant.EPOCH : Instant.parse(started);
			return Optional.of(new StationEntry(id, Objects.requireNonNullElse(text(n, "name"), id),
					Objects.requireNonNullElse(text(n, "fsname"), ""), v.get(),
					Objects.requireNonNullElse(text(n, "plattform"), ""), gestartet));
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Kasseneintrag unlesbar, übersprungen: " + file, e);
			return Optional.empty();
		}
	}

	private static String text(JsonNode n, String field) {
		JsonNode v = n.get(field);
		return v == null || !v.isValueNode() || v.isNull() ? null : v.asText();
	}
}
