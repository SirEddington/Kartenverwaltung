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
import java.util.function.Supplier;

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
	/** Der Ordner der Auftritte unter einem Event (siehe {@link #childNames}). */
	static final String PRESENTATIONS = "presentations";
	/** Der Ordner der Buchungen unter einem Auftritt (siehe {@link #childNames}). */
	static final String BOOKINGS = "bookings";

	private final Path dataRoot;
	private final FileLayer files;
	private final Path anchor;
	private final Supplier<String> tempTag;

	/**
	 * Ein Bestand ohne Anker: Beim Schreiben werden fehlende Ordner angelegt, die temporären Dateien tragen keine Kennung.
	 *
	 * @param dataRoot der Ordner {@code data} dieses Bestands
	 * @param files    die Dateischicht, über die gelesen und geschrieben wird
	 */
	public VersionStore(Path dataRoot, FileLayer files) {
		this(dataRoot, files, null, null);
	}

	/**
	 * @param dataRoot der Ordner {@code data} dieses Bestands
	 * @param files    die Dateischicht, über die gelesen und geschrieben wird
	 * @param anchor   der Ordner, in dem {@code data} liegt (beim gemeinsamen Bestand der gemeinsame Ordner selbst); er wird
	 *                 beim Schreiben nie angelegt, nur {@code data} und tiefer. {@code null}: kein Anker
	 * @param tempTag  liefert die Kennung für die Namen der temporären Dateien beim Schreiben (siehe
	 *                 {@link FileLayer#createNew(Path, byte[], Path, String)}); {@code null}: keine Kennung
	 */
	public VersionStore(Path dataRoot, FileLayer files, Path anchor, Supplier<String> tempTag) {
		this.dataRoot = dataRoot;
		this.files = files;
		this.anchor = anchor;
		this.tempTag = tempTag;
	}

	/** Der Ordner {@code data} dieses Bestands. */
	public Path dataRoot() {
		return dataRoot;
	}

	/** Der Anker, unter dem dieser Bestand schreibt (nie angelegt); {@code null}, wenn er keinen hat. */
	public Path anchor() {
		return anchor;
	}

	private boolean create(Path file, byte[] bytes) throws IOException {
		return files.createNew(file, bytes, anchor, tempTag == null ? null : tempTag.get());
	}

	/**
	 * Legt die Versionsdatei an, wenn es sie noch nicht gibt.
	 *
	 * @return {@code true}, wenn sie angelegt wurde; {@code false}, wenn die Version schon da war (unverändert)
	 */
	public boolean writeVersion(UnitKey unit, VersionId id, byte[] bytes) throws IOException {
		return create(versionFile(unit, id), bytes);
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
		return create(claimFile(unit, claimName), bytes);
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

	/**
	 * Liest eine einzelne Version der Einheit.
	 *
	 * @return die Version; leer, wenn die Datei fehlt oder nicht lesbar ist (beschädigt, höheres Format, falsche ID)
	 * @throws IOException wenn das Lesen der Datei selbst scheitert
	 */
	public Optional<Version> readVersion(UnitKey unit, VersionId id) throws IOException {
		return files.read(versionFile(unit, id)).map(bytes -> parseVersion(id, bytes).version());
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

	/** Ob die Einheit mindestens eine Versionsdatei hat (nur ihr eigener Ordner wird aufgelistet, nichts gelesen). */
	public boolean hasVersions(UnitKey unit) throws IOException {
		return !versionIds(unitDir(unit)).isEmpty();
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
		List<UnitKey> children = new ArrayList<>();
		for (String name : childNames(parent, folder)) {
			children.add(childUnit(parent, folder, name).orElseThrow());
		}
		return children;
	}

	/**
	 * Die Namen der Unterordner von {@code <parent>/<folder>}, die eine Einheit bezeichnen (gültiger Name), sortiert;
	 * leer, wenn der Ordner fehlt.
	 *
	 * @param folder {@code presentations} unter einem Event oder {@code bookings} unter einem Auftritt
	 */
	public List<String> childNames(UnitKey parent, String folder) throws IOException {
		Path childDir = unitDir(parent).resolve(folder);
		List<String> names = new ArrayList<>();
		for (String name : files.listNames(childDir)) {
			if (files.isDirectory(childDir.resolve(name)) && childUnit(parent, folder, name).isPresent()) {
				names.add(name);
			}
		}
		return names;
	}

	/** Die Einheit im Unterordner {@code <parent>/<folder>/<name>}; leer, wenn der Pfad keine Einheit bezeichnet. */
	static Optional<UnitKey> childUnit(UnitKey parent, String folder, String name) {
		return UnitKey.parse(parent.relativePath() + "/" + folder + "/" + name);
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
			paths.addAll(unitDataFiles(unit));
		}
		Collections.sort(paths);
		return paths;
	}

	/**
	 * Die Pfade der Versions- und Anspruchsdateien genau dieser Einheit (ohne die ihrer Unterordner, etwa der Auftritte
	 * eines Events), relativ zu {@link #dataRoot()} und mit {@code /} getrennt, sortiert. Es gelten die Namensregeln von
	 * {@link #allDataFiles()}; fehlt der Ordner, ist die Liste leer.
	 */
	public List<String> unitDataFiles(UnitKey unit) throws IOException {
		Path unitDir = unitDir(unit);
		String prefix = unit.relativePath() + "/";
		List<String> paths = new ArrayList<>();
		for (VersionId id : versionIds(unitDir)) {
			paths.add(prefix + id + JSON);
		}
		for (String name : claimNames(unitDir)) {
			paths.add(prefix + CLAIMS + "/" + name + JSON);
		}
		Collections.sort(paths);
		return paths;
	}

	/**
	 * Die Pfade der temporären Dateien dieser Schicht ({@code ~karv-*.tmp}, eigene wie fremde) im Ordner der Einheit und
	 * in ihrem {@code claims/}, relativ zu {@link #dataRoot()} und mit {@code /} getrennt: zuerst die des Einheitenordners,
	 * dann die unter {@code claims/}, je nach Namen sortiert. Unterordner der Einheit zählen nicht.
	 */
	public List<String> unitTempFiles(UnitKey unit) throws IOException {
		Path unitDir = unitDir(unit);
		String prefix = unit.relativePath() + "/";
		List<String> paths = new ArrayList<>();
		addTempNames(paths, unitDir, prefix);
		addTempNames(paths, unitDir.resolve(CLAIMS), prefix + CLAIMS + "/");
		return paths;
	}

	private void addTempNames(List<String> paths, Path dir, String prefix) throws IOException {
		for (String name : files.listNamesWithTemps(dir)) {
			if (name.startsWith(FileLayer.TEMP_PREFIX) && name.endsWith(FileLayer.TEMP_SUFFIX)) {
				paths.add(prefix + name);
			}
		}
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
		return create(resolve(rel), bytes);
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
		for (String name : files.listNames(unitDir)) {
			Optional<VersionId> id = versionIdOf(name);
			if (id.isPresent() && !files.isDirectory(unitDir.resolve(name))) {
				ids.add(id.get());
			}
		}
		return ids;
	}

	/** Die Namen der Anspruchsdateien unter {@code claims/}, sortiert; fremde Namen und Ordner fehlen. */
	private List<String> claimNames(Path unitDir) throws IOException {
		Path claimsDir = unitDir.resolve(CLAIMS);
		List<String> names = new ArrayList<>();
		for (String name : files.listNames(claimsDir)) {
			Optional<String> claimName = claimNameOf(name);
			if (claimName.isPresent() && !files.isDirectory(claimsDir.resolve(name))) {
				names.add(claimName.get());
			}
		}
		return names;
	}

	/**
	 * Die ID, wenn der Dateiname der einer Versionsdatei ist: {@code <VersionId>.json}, genau in dieser Schreibweise.
	 * Dies ist die eine Regel, nach der Versionsdateien erkannt werden; ob der Name zu einer Datei oder einem Ordner
	 * gehört, prüft sie nicht.
	 *
	 * @param fileName der Name der Datei (kein Pfad)
	 * @return die ID; leer bei jedem anderen Namen ({@code Thumbs.db}, Konfliktkopien, Großschreibung, {@code .tmp})
	 */
	public static Optional<VersionId> versionIdOf(String fileName) {
		return stemOf(fileName).filter(VersionId::isValid).map(VersionId::of);
	}

	/**
	 * Der Name des Anspruchs, wenn der Dateiname der einer Anspruchsdatei unter {@code claims/} ist: {@code root.json}
	 * oder {@code <VersionId>.json}. Der Name des Anspruchs ist {@code "root"} bzw. die ID als Text.
	 *
	 * @param fileName der Name der Datei (kein Pfad)
	 * @return der Name des Anspruchs; leer bei jedem anderen Namen
	 */
	public static Optional<String> claimNameOf(String fileName) {
		return stemOf(fileName).filter(stem -> ROOT.equals(stem) || VersionId.isValid(stem));
	}

	/** Der Dateiname ohne die Endung {@code .json}; leer, wenn er nicht auf {@code .json} endet. */
	private static Optional<String> stemOf(String fileName) {
		if (fileName == null || !fileName.endsWith(JSON)) {
			return Optional.empty();
		}
		return Optional.of(fileName.substring(0, fileName.length() - JSON.length()));
	}
}
