package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Ein Bestand von Versions- und Anspruchsdateien: der lokale unter {@code <AppData>/data} oder der gemeinsame unter
 * {@code <Ordner>/data}. Alle späteren Bausteine lesen und schreiben Einheiten nur hierüber.
 * <p>
 * Der Bestand schreibt ausschließlich über {@link FileLayer#createNew} und löscht nichts. Er übernimmt nur Dateien, die
 * wie Versions- und Anspruchsdateien heißen: {@code <VersionId>.json} im Ordner der Einheit, {@code root.json} und
 * {@code <VersionId>.json} unter {@code claims/}. Alles andere (fremde Namen, Unterordner, {@code Thumbs.db},
 * Konfliktkopien der Synchronisation, Großschreibung) wird stillschweigend übergangen.
 */
public final class VersionStore {

	private static final String JSON = ".json";
	private static final String CLAIMS = "claims";
	private static final String ROOT = "root";
	private static final String EVENTS = "events";
	private static final String HALLS = "halls";
	private static final String PRESENTATIONS = "presentations";
	private static final String BOOKINGS = "bookings";

	private final Path dataRoot;
	private final FileLayer files;

	/**
	 * @param dataRoot der Ordner {@code data} dieses Bestands
	 * @param files    die Dateischicht, über die gelesen und geschrieben wird
	 */
	public VersionStore(Path dataRoot, FileLayer files) {
		this.dataRoot = dataRoot;
		this.files = files;
	}

	/** Der Ordner {@code data} dieses Bestands. */
	public Path dataRoot() {
		return dataRoot;
	}

	/**
	 * Legt die Versionsdatei an, wenn es sie noch nicht gibt.
	 *
	 * @return {@code true}, wenn sie angelegt wurde; {@code false}, wenn die Version schon da war (unverändert)
	 */
	public boolean writeVersion(UnitKey unit, VersionId id, byte[] bytes) throws IOException {
		return files.createNew(versionFile(unit, id), bytes);
	}

	/** Die Bytes der Versionsdatei; leer, wenn es sie nicht gibt. */
	public Optional<byte[]> readVersionBytes(UnitKey unit, VersionId id) throws IOException {
		return files.read(versionFile(unit, id));
	}

	/**
	 * Legt die Anspruchsdatei an, wenn es sie noch nicht gibt: Wer zuerst kommt, gewinnt.
	 *
	 * @param claimName {@code root} oder die ID des Vorgängers
	 * @return {@code true}, wenn dieser Aufruf den Anspruch angelegt hat; {@code false}, wenn er schon vergeben war
	 * @throws IllegalArgumentException wenn der Name weder {@code root} noch eine Versions-ID ist
	 */
	public boolean createClaim(UnitKey unit, String claimName, byte[] bytes) throws IOException {
		return files.createNew(claimFile(unit, claimName), bytes);
	}

	/**
	 * Die Bytes der Anspruchsdatei; leer, wenn es sie nicht gibt.
	 *
	 * @throws IllegalArgumentException wenn der Name weder {@code root} noch eine Versions-ID ist
	 */
	public Optional<byte[]> readClaimBytes(UnitKey unit, String claimName) throws IOException {
		return files.read(claimFile(unit, claimName));
	}

	/**
	 * Liest alle Versions- und Anspruchsdateien der Einheit. Unlesbare Dateien und solche mit höherem Format sind
	 * gekennzeichnet (siehe {@link VersionFile}, {@link ClaimFile}), ihre Bytes bleiben erhalten. Fehler beim Lesen
	 * einer Datei selbst werden als {@link IOException} gemeldet.
	 */
	public UnitFiles readUnit(UnitKey unit) throws IOException {
		Path unitDir = unitDir(unit);
		Map<VersionId, VersionFile> versions = new TreeMap<>();
		for (VersionId id : versionIds(unitDir)) {
			Optional<byte[]> bytes = files.read(unitDir.resolve(id + JSON));
			if (bytes.isPresent()) {
				versions.put(id, parseVersion(id, bytes.get()));
			}
		}
		Map<String, ClaimFile> claims = new TreeMap<>();
		for (String name : claimNames(unitDir)) {
			Optional<byte[]> bytes = files.read(unitDir.resolve(CLAIMS).resolve(name + JSON));
			if (bytes.isPresent()) {
				claims.put(name, parseClaim(name, bytes.get()));
			}
		}
		return new UnitFiles(unit, Collections.unmodifiableMap(versions), Collections.unmodifiableMap(claims));
	}

	private static VersionFile parseVersion(VersionId id, byte[] bytes) {
		try {
			Version version = StoreCodec.readVersion(bytes);
			if (!version.id().equals(id)) {
				return new VersionFile(id, bytes, null, null);
			}
			return new VersionFile(id, bytes, version, null);
		} catch (UnknownFormatException e) {
			return new VersionFile(id, bytes, null, e.format());
		} catch (IOException e) {
			return new VersionFile(id, bytes, null, null);
		}
	}

	private static ClaimFile parseClaim(String name, byte[] bytes) {
		try {
			return new ClaimFile(name, bytes, StoreCodec.readClaim(bytes));
		} catch (IOException e) {
			// unlesbar oder unbekanntes Format: ohne gelesenen Inhalt, die Bytes bleiben
			return new ClaimFile(name, bytes, null);
		}
	}

	/** Die IDs aller Events (gültige Ordnernamen unter {@code events/}), sortiert. */
	public List<String> eventIds() throws IOException {
		return unitIds(EVENTS, true);
	}

	/** Die IDs aller Hallen (gültige Ordnernamen unter {@code halls/}), sortiert. */
	public List<String> hallIds() throws IOException {
		return unitIds(HALLS, false);
	}

	private List<String> unitIds(String folder, boolean events) throws IOException {
		Path parent = dataRoot.resolve(folder);
		List<String> ids = new ArrayList<>();
		for (String name : files.listNames(parent)) {
			if (files.isDirectory(parent.resolve(name)) && isValidUnit(name, events)) {
				ids.add(name);
			}
		}
		return ids;
	}

	private static boolean isValidUnit(String id, boolean event) {
		try {
			if (event) {
				UnitKey.event(id);
			} else {
				UnitKey.hall(id);
			}
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Alle Einheiten eines Events: zuerst das Event, dann jeder Auftritt gefolgt von seinen Buchungen. Auftritte und
	 * Buchungen stehen nach Ordnernamen sortiert; es zählen nur Ordner mit gültigem Namen.
	 */
	public List<UnitKey> eventUnits(String eventId) throws IOException {
		UnitKey event = UnitKey.event(eventId);
		List<UnitKey> units = new ArrayList<>();
		units.add(event);
		for (UnitKey presentation : childUnits(event, PRESENTATIONS)) {
			units.add(presentation);
			units.addAll(childUnits(presentation, BOOKINGS));
		}
		return units;
	}

	private List<UnitKey> childUnits(UnitKey parent, String folder) throws IOException {
		Path childDir = unitDir(parent).resolve(folder);
		List<UnitKey> children = new ArrayList<>();
		for (String name : files.listNames(childDir)) {
			if (files.isDirectory(childDir.resolve(name))) {
				UnitKey.parse(parent.relativePath() + "/" + folder + "/" + name).ifPresent(children::add);
			}
		}
		return children;
	}

	/** Alle Einheiten des Bestands: die aller Events, dann alle Hallen. */
	public List<UnitKey> allUnits() throws IOException {
		List<UnitKey> units = new ArrayList<>();
		for (String eventId : eventIds()) {
			units.addAll(eventUnits(eventId));
		}
		for (String hallId : hallIds()) {
			units.add(UnitKey.hall(hallId));
		}
		return units;
	}

	/**
	 * Die Pfade aller Versions- und Anspruchsdateien aller Einheiten, relativ zu {@link #dataRoot()} und mit {@code /}
	 * getrennt, sortiert. Es sind genau die Dateien, die {@link #readUnit} übernimmt; Listendateien unter
	 * {@code feeds/} gehören nicht dazu.
	 */
	public List<String> allDataFiles() throws IOException {
		List<String> paths = new ArrayList<>();
		for (UnitKey unit : allUnits()) {
			Path unitDir = unitDir(unit);
			String prefix = unit.relativePath() + "/";
			for (VersionId id : versionIds(unitDir)) {
				paths.add(prefix + id + JSON);
			}
			for (String name : claimNames(unitDir)) {
				paths.add(prefix + CLAIMS + "/" + name + JSON);
			}
		}
		Collections.sort(paths);
		return paths;
	}

	/**
	 * Die Bytes einer Datei unter {@link #dataRoot()}.
	 *
	 * @param rel Pfad relativ zum Datenordner, mit {@code /} getrennt
	 * @throws IllegalArgumentException wenn der Pfad absolut ist oder den Datenordner verlässt
	 */
	public Optional<byte[]> readRelative(String rel) throws IOException {
		return files.read(resolve(rel));
	}

	/**
	 * Legt eine Datei unter {@link #dataRoot()} an, wenn es sie noch nicht gibt.
	 *
	 * @param rel Pfad relativ zum Datenordner, mit {@code /} getrennt
	 * @return {@code true}, wenn die Datei angelegt wurde; {@code false}, wenn sie schon da war
	 * @throws IllegalArgumentException wenn der Pfad absolut ist oder den Datenordner verlässt
	 */
	public boolean createRelative(String rel, byte[] bytes) throws IOException {
		return files.createNew(resolve(rel), bytes);
	}

	/**
	 * Die Namen der direkten Einträge eines Ordners unter {@link #dataRoot()}, sortiert und ohne temporäre Dateien; leer,
	 * wenn der Ordner fehlt.
	 *
	 * @param rel Pfad relativ zum Datenordner, mit {@code /} getrennt
	 * @throws IllegalArgumentException wenn der Pfad absolut ist oder den Datenordner verlässt
	 */
	public List<String> listRelative(String rel) throws IOException {
		return files.listNames(resolve(rel));
	}

	/**
	 * Der Pfad zu einer Datei unter {@link #dataRoot()}.
	 *
	 * @param rel Pfad relativ zum Datenordner, mit {@code /} getrennt
	 * @throws IllegalArgumentException wenn der Pfad leer oder absolut ist, leere, {@code .}- oder {@code ..}-Teile,
	 *                                  einen Rückwärtsstrich oder einen Doppelpunkt enthält
	 */
	public Path resolve(String rel) {
		if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.indexOf('\\') >= 0 || rel.indexOf(':') >= 0) {
			throw new IllegalArgumentException("Ungültiger relativer Pfad: " + rel);
		}
		for (String part : rel.split("/", -1)) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) {
				throw new IllegalArgumentException("Ungültiger relativer Pfad: " + rel);
			}
		}
		try {
			return dataRoot.resolve(rel);
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException("Ungültiger relativer Pfad: " + rel, e);
		}
	}

	private Path unitDir(UnitKey unit) {
		return resolve(unit.relativePath());
	}

	private Path versionFile(UnitKey unit, VersionId id) {
		return unitDir(unit).resolve(id + JSON);
	}

	private Path claimFile(UnitKey unit, String claimName) {
		if (!ROOT.equals(claimName) && !VersionId.isValid(claimName)) {
			throw new IllegalArgumentException("Ungültiger Name einer Anspruchsdatei: " + claimName);
		}
		return unitDir(unit).resolve(CLAIMS).resolve(claimName + JSON);
	}

	/** Die IDs der Versionsdateien im Einheitenordner, sortiert; fremde Namen und Ordner fehlen. */
	private List<VersionId> versionIds(Path unitDir) throws IOException {
		List<VersionId> ids = new ArrayList<>();
		for (String stem : jsonStems(unitDir)) {
			if (VersionId.isValid(stem)) {
				ids.add(VersionId.of(stem));
			}
		}
		return ids;
	}

	/** Die Namen der Anspruchsdateien unter {@code claims/}, sortiert; fremde Namen und Ordner fehlen. */
	private List<String> claimNames(Path unitDir) throws IOException {
		List<String> names = new ArrayList<>();
		for (String stem : jsonStems(unitDir.resolve(CLAIMS))) {
			if (ROOT.equals(stem) || VersionId.isValid(stem)) {
				names.add(stem);
			}
		}
		return names;
	}

	/** Die Namen der Dateien ({@code *.json}, keine Ordner) im Ordner ohne die Endung. */
	private List<String> jsonStems(Path folder) throws IOException {
		List<String> stems = new ArrayList<>();
		for (String name : files.listNames(folder)) {
			if (name.endsWith(JSON) && !files.isDirectory(folder.resolve(name))) {
				stems.add(name.substring(0, name.length() - JSON.length()));
			}
		}
		return stems;
	}
}
