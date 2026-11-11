package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sucht und prüft die Installer in {@code updates/} des gemeinsamen Ordners und räumt dort auf (KARV-132).
 * <ul>
 * <li>Die Version kommt nur aus dem Manifest, nie aus Dateiname oder Beschreibung.</li>
 * <li>Mit Beschreibung ({@code <Installer>.json}) müssen Größe, SHA-256, Paketfamilie und Paketversion passen; ohne
 * (von Hand abgelegt) wird die ZIP-Datei vollständig geprüft.</li>
 * <li>Prüfergebnis und „zuerst gesehen“ werden je Datei nach Größe und Änderungszeit gemerkt.</li>
 * </ul>
 * {@link #scan} und {@link #cleanup} laufen auf einem Thread (dem Takt); {@link FolderOffer#stillValid()} darf von einem
 * anderen kommen.
 */
public final class FolderUpdates {

	private static final Duration ORPHAN_SEEN = Duration.ofHours(1);
	private static final Duration ORPHAN_AGE = Duration.ofDays(1);
	private static final Logger LOG = Logger.getLogger(FolderUpdates.class.getName());

	/** Das Ergebnis eines Durchsuchens. */
	public record Scan(Optional<FolderOffer> candidate, Optional<AppVersion> highestValidOwn,
			Optional<AppVersion> otherFamilyNewer) {
		/** Nichts gefunden. */
		public static Scan empty() {
			return new Scan(Optional.empty(), Optional.empty(), Optional.empty());
		}
	}

	private record Seen(FileKey key, Instant firstSeen) {
	}

	private record Check(FileKey msix, FileKey description, Optional<MsixManifest> manifest, boolean valid) {
	}

	private record Evaluated(Path file, Check check, Instant firstSeen) {
		/** Die Version aus dem Manifest; nur bei gelesenem Manifest aufrufen. */
		AppVersion version() {
			return check.manifest().get().version();
		}
	}

	private final Clock clock;
	private final OwnFiles own;
	private final Predicate<String> ownTempTag;
	private final Map<String, Seen> seen = new HashMap<>();
	private final Map<String, Check> checks = new HashMap<>();
	private final Map<String, Instant> orphanSeen = new HashMap<>();
	private final AtomicInteger fullChecks = new AtomicInteger();
	private List<Evaluated> last = List.of();

	/**
	 * @param ownTempTag ob die Kennung im Namen einer tmp-Datei diesem Rechner gehört (aktuelle oder frühere ID)
	 */
	public FolderUpdates(Clock clock, OwnFiles own, Predicate<String> ownTempTag) {
		this.clock = clock;
		this.own = own;
		this.ownTempTag = ownTempTag;
	}

	/** Durchsucht {@code updates/} nach Installern; der Kandidat gehört zu {@code ownFamily} und ist neuer als {@code own}. */
	public Scan scan(Path folder, String ownFamily, AppVersion own) {
		Path dir = folder.resolve(UpdatesDir.UPDATES);
		List<String> names = list(dir);
		if (names == null) {
			seen.clear();
			checks.clear();
			orphanSeen.clear();
			last = List.of();
			return Scan.empty();
		}
		Instant now = clock.instant();
		Set<String> present = new HashSet<>(names);
		List<Evaluated> evaluated = new ArrayList<>();
		for (String name : names) {
			if (!UpdatesDir.isInstaller(name)) {
				continue;
			}
			Path file = dir.resolve(name);
			Optional<FileKey> key = FileKey.of(file);
			if (key.isEmpty() || key.get().size() > MsixManifest.MAX_MSIX_BYTES) {
				continue;
			}
			Seen s = seen.get(name);
			if (s == null || !s.key().equals(key.get())) {
				s = new Seen(key.get(), now);
				seen.put(name, s);
			}
			FileKey descKey = FileKey.of(dir.resolve(UpdatesDir.descriptionOf(name))).orElse(null);
			Check c = checks.get(name);
			if (c == null || !c.msix().equals(key.get()) || !Objects.equals(c.description(), descKey)) {
				c = check(file, key.get(), descKey, ownFamily);
				checks.put(name, c);
			}
			evaluated.add(new Evaluated(file, c, s.firstSeen()));
		}
		seen.keySet().retainAll(present);
		checks.keySet().retainAll(present);
		for (String name : names) {
			if (UpdatesDir.isDescription(name) && !present.contains(UpdatesDir.installerOf(name))) {
				orphanSeen.putIfAbsent(name, now);
			}
		}
		orphanSeen.keySet().removeIf(n -> !present.contains(n) || present.contains(UpdatesDir.installerOf(n)));
		last = List.copyOf(evaluated);

		Optional<Evaluated> candidate = evaluated.stream().filter(e -> isValidOwn(e, ownFamily))
				.filter(e -> e.version().compareTo(own) > 0)
				.max(Comparator.comparing(Evaluated::version)
						.thenComparing(e -> e.file().getFileName().toString(), Comparator.reverseOrder()));
		Optional<AppVersion> highest = highestValidOwn(evaluated, ownFamily);
		Optional<AppVersion> otherNewer = evaluated.stream().filter(e -> e.check().manifest().isPresent())
				.map(e -> e.check().manifest().get())
				.filter(m -> !m.packageFamilyName().equals(ownFamily) && m.version().compareTo(own) > 0)
				.map(MsixManifest::version).max(Comparator.naturalOrder());
		return new Scan(candidate.map(e -> offer(e, ownFamily)), highest, otherNewer);
	}

	/**
	 * Räumt {@code updates/} auf: ältere gültige Installer der eigenen Familie (nach dem letzten {@link #scan}),
	 * Konfliktkopien, eigene tmp-Dateien aus früheren Läufen (nie aus diesem, siehe {@link OwnFiles}) und Beschreibungen
	 * ohne Installer. Ladedateien räumt {@code FolderDownloader} auf.
	 */
	public void cleanup(Path folder, String ownFamily) {
		Path dir = folder.resolve(UpdatesDir.UPDATES);
		List<String> names = list(dir);
		if (names == null) {
			return;
		}
		Instant now = clock.instant();
		Optional<AppVersion> highest = highestValidOwn(last, ownFamily);
		if (highest.isPresent()) {
			for (Evaluated e : last) {
				if (isValidOwn(e, ownFamily) && e.version().compareTo(highest.get()) < 0 && e.file().getParent().equals(dir)) {
					UpdateFiles.deleteQuietly(e.file());
					UpdateFiles.deleteQuietly(dir.resolve(UpdatesDir.descriptionOf(e.file().getFileName().toString())));
				}
			}
		}
		Set<String> present = new HashSet<>(names);
		for (String name : names) {
			Path p = dir.resolve(name);
			if (UpdatesDir.isConflictCopy(name)) {
				UpdateFiles.deleteQuietly(p);
			} else if (UpdatesDir.tempTag(name).filter(ownTempTag).isPresent() && !own.contains(p)) {
				UpdateFiles.deleteQuietly(p);
			} else if (UpdatesDir.isDescription(name) && !present.contains(UpdatesDir.installerOf(name))) {
				Instant firstSeen = orphanSeen.getOrDefault(name, now);
				boolean seenLong = !Duration.between(firstSeen, now).minus(ORPHAN_SEEN).isNegative();
				boolean old = FileKey.of(p).map(k -> k.modified().toInstant().isBefore(now.minus(ORPHAN_AGE))).orElse(false);
				if (seenLong || old) {
					UpdateFiles.deleteQuietly(p);
					orphanSeen.remove(name);
				}
			}
		}
	}

	/** Wie oft eine Datei vollständig geprüft wurde (für Tests). */
	int fullChecks() {
		return fullChecks.get();
	}

	private static Optional<AppVersion> highestValidOwn(List<Evaluated> evaluated, String ownFamily) {
		return evaluated.stream().filter(e -> isValidOwn(e, ownFamily)).map(Evaluated::version)
				.max(Comparator.naturalOrder());
	}

	/**
	 * Die Beschreibung eines Installers, kompakt in fester Feldreihenfolge, UTF-8 ohne BOM, ohne Rechner-ID und Zeit: Zwei
	 * Kassen schreiben für dieselbe Datei bytegleiche Beschreibungen.
	 */
	public static byte[] descriptionJson(String packageVersion, String packageFamilyName, String sha256, long size) {
		ObjectNode n = UpdateFiles.JSON.createObjectNode();
		n.put("format", 1);
		n.put("paketversion", packageVersion);
		n.put("packageFamilyName", packageFamilyName);
		n.put("sha256", sha256);
		n.put("size", size);
		return UpdateFiles.json(n);
	}

	private static boolean isValidOwn(Evaluated e, String ownFamily) {
		return e.check().valid() && e.check().manifest().isPresent()
				&& e.check().manifest().get().packageFamilyName().equals(ownFamily)
				&& "x64".equalsIgnoreCase(e.check().manifest().get().architecture());
	}

	private FolderOffer offer(Evaluated e, String ownFamily) {
		Path file = e.file();
		AppVersion version = e.version();
		Instant firstSeen = e.firstSeen();
		FileKey msixKey = e.check().msix();
		FileKey descKey = e.check().description();
		return new FolderOffer() {
			@Override
			public Path file() {
				return file;
			}

			@Override
			public AppVersion version() {
				return version;
			}

			@Override
			public Instant firstSeen() {
				return firstSeen;
			}

			@Override
			public boolean stillValid() {
				try {
					Optional<FileKey> now = FileKey.of(file);
					if (now.isEmpty()) {
						return false;
					}
					FileKey descNow = FileKey.of(file.resolveSibling(UpdatesDir.descriptionOf(file.getFileName().toString())))
							.orElse(null);
					if (now.get().equals(msixKey) && Objects.equals(descNow, descKey)) {
						return true;
					}
					Check c = check(file, now.get(), descNow, ownFamily);
					return c.valid() && c.manifest().isPresent() && c.manifest().get().version().compareTo(version) == 0;
				} catch (RuntimeException ex) {
					LOG.log(Level.WARNING, "Installer im gemeinsamen Ordner ließ sich nicht erneut prüfen: " + file, ex);
					return false;
				}
			}

			@Override
			public String toString() {
				return file + " (" + version.display() + ")";
			}
		};
	}

	/**
	 * Volle Prüfung einer Datei; wirft nie. Eine andere Familie oder Architektur kommt nie in Frage: dafür genügt das
	 * Manifest (Hinweis „Neuinstallation nötig“), SHA-256 bzw. ZIP-Prüfung entfallen.
	 */
	private Check check(Path file, FileKey key, FileKey descKey, String ownFamily) {
		fullChecks.incrementAndGet();
		MsixManifest m;
		try {
			m = MsixManifest.read(file);
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.INFO, "Installer im gemeinsamen Ordner ungültig oder unvollständig: " + file + " (" + e + ")");
			return new Check(key, descKey, Optional.empty(), false);
		}
		if (!m.packageFamilyName().equals(ownFamily) || !"x64".equalsIgnoreCase(m.architecture())) {
			return new Check(key, descKey, Optional.of(m), false);
		}
		boolean valid;
		try {
			if (descKey != null) {
				valid = descriptionMatches(file.resolveSibling(UpdatesDir.descriptionOf(file.getFileName().toString())),
						file, m);
			} else {
				MsixManifest.verifyComplete(file);
				valid = true;
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.INFO, "Installer im gemeinsamen Ordner nicht vollständig: " + file + " (" + e + ")");
			valid = false;
		}
		if (!valid) {
			LOG.info("Installer im gemeinsamen Ordner passt nicht zu seiner Beschreibung oder ist beschädigt: " + file);
		}
		return new Check(key, descKey, Optional.of(m), valid);
	}

	/** Ob die Beschreibung zu Datei und Manifest passt (Größe, SHA-256, Familie, Paketversion). */
	static boolean descriptionMatches(Path desc, Path file, MsixManifest m) throws IOException {
		Optional<byte[]> data = UpdateFiles.readAtMost(desc, UpdateFiles.MAX_JSON_BYTES);
		if (data.isEmpty()) {
			return false;
		}
		JsonNode n = UpdateFiles.JSON.readTree(data.get());
		Optional<AppVersion> v = AppVersion.fromPackageVersion(n.path("paketversion").asText(null));
		if (v.isEmpty() || v.get().compareTo(m.version()) != 0) {
			return false;
		}
		if (!m.packageFamilyName().equals(n.path("packageFamilyName").asText(null))) {
			return false;
		}
		if (!n.path("size").canConvertToLong() || n.path("size").asLong() != Files.size(file)) {
			return false;
		}
		return UpdateFiles.sha256Hex(file).equalsIgnoreCase(n.path("sha256").asText(""));
	}

	/** Die Namen im Ordner, {@code null}, wenn er fehlt oder nicht lesbar ist. */
	private static List<String> list(Path dir) {
		if (!Files.isDirectory(dir)) {
			return null;
		}
		List<String> names = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path p : stream) {
				names.add(p.getFileName().toString());
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Ordner updates ließ sich nicht lesen: " + dir, e);
			return null;
		}
		names.sort(Comparator.naturalOrder());
		return names;
	}
}
