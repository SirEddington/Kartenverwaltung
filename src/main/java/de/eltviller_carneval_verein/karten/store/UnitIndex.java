package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Der Start-Index des lokalen Bestands: je Einheit der zuletzt ermittelte Stand und ein Abdruck der Dateinamen, auf dem
 * er beruht. Beim Start muss so nicht jede Einheit gelesen und aufgelöst werden, sondern nur die, deren Dateinamen sich
 * seit dem letzten Speichern geändert haben.
 * <p>
 * Der Index ist ein reiner Zwischenspeicher und nie Schiedsrichter. {@link #refresh} entscheidet nur, ob eine Einheit
 * neu aufgelöst werden muss; den Stand liefert, wo immer er neu ermittelt wird, {@link ChainResolver} aus den Dateien.
 * Ein fehlender, unlesbarer oder fremder Index kostet nur Zeit, nie Richtigkeit. Er liegt ausschließlich lokal
 * ({@code <AppData>/index/}), nie im gemeinsamen Ordner.
 * <p>
 * Der Abdruck ist der SHA-256 über die sortierten Namen der Versions- und Anspruchsdateien einer Einheit
 * ({@link #fingerprint(Collection, Collection)}). Beim Start entsteht er aus dem Verzeichnisbaum
 * ({@link FileLayer#listTree}) mit denselben Namensregeln wie {@link VersionStore#readUnit}; für dieselbe Einheit ist er
 * gleich dem {@linkplain #fingerprint(UnitFiles) Abdruck der gelesenen Dateien}.
 * <p>
 * Wird eine Datei unter gleichem Namen ersetzt (späte Niederlage, Heilung; siehe {@link FileLayer#replaceDamaged}), bleibt
 * die alte als {@code <Name>.json.<Endung>} (z. B. {@code .corrupt-…}, {@code .verdraengt-…}) daneben liegen. Solche
 * Nebenkopien einer Versions- oder Anspruchsdatei gehen deshalb als Zeilen {@code s:<Name>} in den Abdruck aus dem
 * Verzeichnisbaum ein. Der gespeicherte Abdruck der gelesenen Dateien kennt sie nicht: Eine Einheit mit Nebenkopie
 * stimmt nie mit ihrem Eintrag überein und wird bei jedem Start neu aufgelöst, und zwar sofort, sobald die erste
 * Nebenkopie entsteht. Fremde Namen ({@code Thumbs.db}, {@code .tmp}, Konfliktkopien) zählen nicht. Bekannte,
 * hingenommene Lücke: Ein Eintrag, der weder normale Datei noch Ordner ist, wird von {@code readUnit} gesehen, vom
 * Verzeichnisbaum aber nicht; das führt höchstens zu einer erneuten Auflösung, nie zu einem falschen Stand.
 * <p>
 * Dateiformat: {@code <Index>/events/<Event-ID>.json} (alle Einheiten dieses Events) und {@code <Index>/halls.json}, je
 * {@code {"indexFormat":1,"machine":…,"units":[…]}}. Eine Datei mit anderem Format, von einem anderen Rechner oder
 * unlesbar gilt als leer und wird beim nächsten {@link #flush} neu geschrieben.
 * <p>
 * Alle Methoden sind synchronisiert.
 */
final class UnitIndex {

	private static final Logger LOGGER = Logger.getLogger(UnitIndex.class.getName());

	private static final int INDEX_FORMAT = 1;
	private static final String EVENTS = "events";
	private static final String HALLS = "halls";
	private static final String CLAIMS = "claims";
	private static final String JSON = ".json";

	private static final ObjectMapper MAPPER = JsonMapperFactory.create().copy()
			.disable(SerializationFeature.INDENT_OUTPUT);

	/**
	 * Ein Eintrag des Index.
	 *
	 * @param snapshot    der zuletzt ermittelte Stand der Einheit
	 * @param fingerprint der Abdruck der Dateinamen, auf denen der Stand beruht
	 * @param pending     ob die Einheit eigene, noch nicht beanspruchte Versionen hat
	 * @param lost        ob die Einheit eigene, verlorene Versionen hat
	 */
	record Entry(UnitSnapshot snapshot, String fingerprint, boolean pending, boolean lost) {
	}

	/**
	 * Das Ergebnis eines {@link #refresh}.
	 *
	 * @param pendingUnits die Einheiten mit eigenen, noch nicht beanspruchten Versionen
	 * @param lostUnits    die Einheiten mit eigenen, verlorenen Versionen
	 * @param resolved     wie viele Einheiten neu aufgelöst wurden
	 */
	record Refresh(Set<UnitKey> pendingUnits, Set<UnitKey> lostUnits, int resolved) {
	}

	private final Path indexDir;
	private final FileLayer files;
	private final String machineId;

	private final Map<UnitKey, Entry> entries = new HashMap<>();
	/** Die Gruppen ({@code events/<Event-ID>} bzw. {@code halls}), deren Datei schon gelesen wurde. */
	private final Set<String> loaded = new HashSet<>();
	/** Die Gruppen, deren Datei neu geschrieben werden muss. */
	private final Set<String> dirty = new TreeSet<>();

	/**
	 * @param indexDir  der Ordner des Index ({@code <AppData>/index})
	 * @param files     die Dateischicht, über die der Index gelesen und geschrieben wird
	 * @param machineId die ID dieses Rechners; Index-Dateien anderer Rechner werden nicht übernommen
	 */
	UnitIndex(Path indexDir, FileLayer files, String machineId) {
		this.indexDir = indexDir;
		this.files = files;
		this.machineId = machineId;
	}

	/**
	 * Der Abdruck einer Einheit: SHA-256 (Hex, Kleinbuchstaben) über die sortierten Zeilen {@code v:<ID>} der Versionen und
	 * danach {@code c:<Name>} der Ansprüche, jede Zeile mit {@code \n} abgeschlossen.
	 */
	static String fingerprint(Collection<VersionId> versions, Collection<String> claimNames) {
		return fingerprint(versions, claimNames, List.of());
	}

	/** Wie {@link #fingerprint(Collection, Collection)}, mit den Zeilen {@code s:<Name>} der Nebenkopien am Ende. */
	private static String fingerprint(Collection<VersionId> versions, Collection<String> claimNames,
			Collection<String> sideCopies) {
		StringBuilder text = new StringBuilder();
		versions.stream().map(VersionId::value).sorted().forEach(v -> text.append("v:").append(v).append('\n'));
		claimNames.stream().sorted().forEach(c -> text.append("c:").append(c).append('\n'));
		sideCopies.stream().sorted().forEach(s -> text.append("s:").append(s).append('\n'));
		return StoreCodec.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** Der Abdruck der gelesenen Dateien einer Einheit. */
	static String fingerprint(UnitFiles unitFiles) {
		return fingerprint(unitFiles.versions().keySet(), unitFiles.claims().keySet());
	}

	/**
	 * Der Abdruck einer Einheit aus einem Verzeichnisbaum ({@link FileLayer#listTree}), ohne eine Datei zu lesen. Es gelten
	 * die Namensregeln von {@link VersionStore#versionIdOf} und {@link VersionStore#claimNameOf}. Zusätzlich zählen die
	 * Nebenkopien ersetzter Dateien ({@code <Name>.json.<Endung>} im Einheitenordner bzw. unter {@code claims/}), siehe
	 * Klassenbeschreibung.
	 *
	 * @param tree     der Verzeichnisbaum des Datenordners
	 * @param unitPath der Ordner der Einheit relativ zum Datenordner (siehe {@link UnitKey#relativePath()})
	 */
	static String fingerprintFromListing(Map<String, List<String>> tree, String unitPath) {
		List<VersionId> versions = new ArrayList<>();
		List<String> sideCopies = new ArrayList<>();
		for (String name : tree.getOrDefault(unitPath, List.of())) {
			VersionStore.versionIdOf(name).ifPresent(versions::add);
			String base = sideCopyBase(name);
			if (base != null && VersionStore.versionIdOf(base).isPresent()) {
				sideCopies.add(name);
			}
		}
		List<String> claimNames = new ArrayList<>();
		for (String name : tree.getOrDefault(unitPath + "/" + CLAIMS, List.of())) {
			VersionStore.claimNameOf(name).ifPresent(claimNames::add);
			String base = sideCopyBase(name);
			if (base != null && VersionStore.claimNameOf(base).isPresent()) {
				sideCopies.add(CLAIMS + "/" + name);
			}
		}
		return fingerprint(versions, claimNames, sideCopies);
	}

	/**
	 * Bei einem Namen der Form {@code <Stamm>.json.<Endung>} (nicht leere Endung) der Teil bis einschließlich
	 * {@code .json}, den die Namensregeln des Versionsspeichers beurteilen; sonst {@code null}.
	 */
	private static String sideCopyBase(String name) {
		int dot = name.indexOf(JSON + ".");
		return dot < 0 || name.length() == dot + JSON.length() + 1 ? null : name.substring(0, dot + JSON.length());
	}

	/**
	 * Gleicht den Index mit dem Bestand ab: Einheiten, deren Abdruck zum Eintrag passt, bleiben, alle anderen werden aus den
	 * Dateien neu aufgelöst; Einträge ohne Ordner entfallen. Danach hat jede Einheit des Bestands einen Eintrag.
	 *
	 * @param local    der lokale Bestand
	 * @param progress bekommt den Anteil der behandelten Einheiten von 0.0 bis 1.0, zuletzt 1.0; darf {@code null} sein
	 * @return die Einheiten mit ausstehenden und mit verlorenen Versionen und die Zahl der neu aufgelösten Einheiten
	 */
	synchronized Refresh refresh(VersionStore local, DoubleConsumer progress) throws IOException {
		DoubleConsumer report = progress == null ? p -> {
		} : progress;
		Map<String, List<String>> tree = files.listTree(local.dataRoot());
		Map<UnitKey, String> units = new LinkedHashMap<>();
		for (String rel : new TreeSet<>(tree.keySet())) {
			UnitKey.parse(rel).ifPresent(unit -> units.put(unit, rel));
		}

		Set<String> groups = new TreeSet<>(storedGroups());
		units.keySet().forEach(unit -> groups.add(groupOf(unit)));
		groups.forEach(this::load);
		for (UnitKey gone : new ArrayList<>(entries.keySet())) {
			if (!units.containsKey(gone)) {
				entries.remove(gone);
				dirty.add(groupOf(gone));
			}
		}

		int resolved = 0;
		int done = 0;
		int lastPercent = 0;
		report.accept(0.0);
		for (Map.Entry<UnitKey, String> unit : units.entrySet()) {
			Entry entry = entries.get(unit.getKey());
			if (entry == null || !entry.fingerprint().equals(fingerprintFromListing(tree, unit.getValue()))) {
				UnitFiles unitFiles = local.readUnit(unit.getKey());
				update(unitFiles, ChainResolver.resolve(unitFiles, machineId));
				resolved++;
			}
			done++;
			int percent = (int) (done * 100L / units.size());
			if (percent != lastPercent) {
				lastPercent = percent;
				report.accept(percent / 100.0);
			}
		}
		if (lastPercent != 100) {
			report.accept(1.0);
		}

		Set<UnitKey> pending = new HashSet<>();
		Set<UnitKey> lost = new HashSet<>();
		for (UnitKey unit : units.keySet()) {
			Entry entry = entries.get(unit);
			if (entry.pending()) {
				pending.add(unit);
			}
			if (entry.lost()) {
				lost.add(unit);
			}
		}
		return new Refresh(Collections.unmodifiableSet(pending), Collections.unmodifiableSet(lost), resolved);
	}

	/**
	 * Trägt den frisch ermittelten Stand einer Einheit ein und merkt ihre Datei zum Schreiben vor. Der Eintrag beruht auf den
	 * Dateien, die {@code unitFiles} enthält.
	 */
	synchronized void update(UnitFiles unitFiles, UnitState state) {
		UnitKey unit = state.unit();
		entries.put(unit, new Entry(UnitSnapshot.of(state), fingerprint(unitFiles), !state.pending().isEmpty(),
				!state.lost().isEmpty()));
		dirty.add(groupOf(unit));
	}

	/** Eine unveränderliche Kopie der Einträge. */
	synchronized Map<UnitKey, Entry> entries() {
		return Map.copyOf(entries);
	}

	/** Ob es Änderungen gibt, die {@link #flush} noch schreiben müsste. */
	synchronized boolean hasChanges() {
		return !dirty.isEmpty();
	}

	/**
	 * Verwirft alle Einträge. Alle bekannten Gruppen (im Speicher und als Datei vorhanden) gelten als geändert; der nächste
	 * {@link #flush} schreibt sie leer. Die alten Dateien werden nie wieder gelesen: nötig, wenn der Datenordner ausgetauscht
	 * wird (Beiseitelegen), denn danach können gleiche Dateinamen anderen Inhalt haben.
	 */
	synchronized void clear() {
		Set<String> groups = new TreeSet<>(loaded);
		entries.keySet().forEach(unit -> groups.add(groupOf(unit)));
		try {
			for (String group : storedGroups()) {
				if (files.exists(fileOf(group))) {
					groups.add(group);
				}
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Der Index-Ordner " + indexDir + " ließ sich nicht auflisten", e);
		}
		entries.clear();
		loaded.addAll(groups);
		dirty.addAll(groups);
	}

	/**
	 * Schreibt die geänderten Index-Dateien, jede über eine temporäre Datei im selben Ordner und
	 * {@link AtomicFileReplace#replace}. Scheitert eine Datei, bleibt sie als geändert vorgemerkt und die Ausnahme geht
	 * weiter; die noch nicht geschriebenen bleiben ebenfalls vorgemerkt.
	 */
	synchronized void flush() throws IOException {
		for (String group : new ArrayList<>(dirty)) {
			Path file = fileOf(group);
			Path temp = file.resolveSibling(FileLayer.TEMP_PREFIX + "index-" + UUID.randomUUID() + FileLayer.TEMP_SUFFIX);
			try {
				files.createNew(temp, serialize(group));
				AtomicFileReplace.replace(temp, file);
			} catch (IOException | RuntimeException e) {
				deleteQuietly(temp);
				throw e;
			}
			dirty.remove(group);
		}
	}

	private static void deleteQuietly(Path temp) {
		try {
			Files.deleteIfExists(temp);
		} catch (IOException e) {
			LOGGER.log(Level.FINE, "Temporäre Index-Datei ließ sich nicht entfernen: " + temp, e);
		}
	}

	private static String groupOf(UnitKey unit) {
		return unit.type() == UnitType.HALL ? HALLS : EVENTS + "/" + unit.eventId();
	}

	private Path fileOf(String group) {
		return indexDir.resolve(group + JSON);
	}

	/** Die Gruppen, für die im Index-Ordner eine Datei liegt; so entfallen auch die Einträge ganz verschwundener Events. */
	private List<String> storedGroups() throws IOException {
		List<String> groups = new ArrayList<>();
		groups.add(HALLS);
		for (String name : files.listNames(indexDir.resolve(EVENTS))) {
			if (name.endsWith(JSON)) {
				String eventId = name.substring(0, name.length() - JSON.length());
				try {
					UnitKey.event(eventId);
					groups.add(EVENTS + "/" + eventId);
				} catch (IllegalArgumentException e) {
					// fremder Name im Index-Ordner: übergehen
				}
			}
		}
		return groups;
	}

	/** Liest die Datei der Gruppe einmal; was sich nicht übernehmen lässt, gilt als leer und wird neu geschrieben. */
	private void load(String group) {
		if (!loaded.add(group)) {
			return;
		}
		Path file = fileOf(group);
		try {
			Optional<byte[]> bytes = files.read(file);
			if (bytes.isPresent()) {
				for (Entry entry : parse(bytes.get(), group)) {
					entries.putIfAbsent(entry.snapshot().unit(), entry);
				}
			}
		} catch (IOException | RuntimeException e) {
			// Erwarteter Fall (anderer Rechner, anderes Format, beschädigt): eine Zeile genügt, kein Stapel
			LOGGER.warning("Der Start-Index " + file + " ist unbrauchbar und wird neu aufgebaut: " + e);
			dirty.add(group);
		}
	}

	private List<Entry> parse(byte[] bytes, String group) throws IOException {
		JsonNode root = MAPPER.readTree(bytes);
		if (root == null || !root.isObject()) {
			throw new IOException("Die Datei enthält kein JSON-Objekt.");
		}
		if (!root.path("indexFormat").isInt() || root.get("indexFormat").asInt() != INDEX_FORMAT) {
			throw new IOException("Unbekanntes Format des Index: " + root.get("indexFormat"));
		}
		if (!machineId.equals(text(root, "machine"))) {
			throw new IOException("Der Index stammt von einem anderen Rechner: " + root.get("machine"));
		}
		JsonNode units = root.get("units");
		if (units == null || !units.isArray()) {
			throw new IOException("Das Feld units fehlt oder ist keine Liste.");
		}
		List<Entry> result = new ArrayList<>();
		for (JsonNode item : units) {
			UnitKey unit = UnitKey.parse(text(item, "unit"))
					.orElseThrow(() -> new IOException("Unbekannte Einheit: " + item.get("unit")));
			if (!groupOf(unit).equals(group)) {
				throw new IOException("Die Einheit gehört nicht in diese Datei: " + unit.relativePath());
			}
			JsonNode version = item.get("version");
			JsonNode payload = item.get("payload");
			if (payload != null && !payload.isNull() && !payload.isObject()) {
				throw new IOException("Das Feld payload ist kein Objekt.");
			}
			UnitSnapshot snapshot = new UnitSnapshot(unit,
					version == null || version.isNull() ? null : VersionId.of(text(item, "version")),
					UnitState.Status.valueOf(text(item, "status")), bool(item, "deleted"),
					payload == null || payload.isNull() ? null : (ObjectNode) payload);
			result.add(new Entry(snapshot, text(item, "fingerprint"), bool(item, "pending"), bool(item, "lost")));
		}
		return result;
	}

	private byte[] serialize(String group) throws IOException {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("indexFormat", INDEX_FORMAT);
		root.put("machine", machineId);
		ArrayNode units = root.putArray("units");
		entries.values().stream().filter(entry -> groupOf(entry.snapshot().unit()).equals(group))
				.sorted((a, b) -> a.snapshot().unit().relativePath().compareTo(b.snapshot().unit().relativePath()))
				.forEach(entry -> {
					UnitSnapshot snapshot = entry.snapshot();
					ObjectNode item = units.addObject();
					item.put("unit", snapshot.unit().relativePath());
					item.put("fingerprint", entry.fingerprint());
					item.put("version", snapshot.version() == null ? null : snapshot.version().value());
					item.put("status", snapshot.status().name());
					item.put("deleted", snapshot.deleted());
					item.put("pending", entry.pending());
					item.put("lost", entry.lost());
					item.set("payload", snapshot.payload() == null ? MAPPER.nullNode() : snapshot.payload());
				});
		return MAPPER.writeValueAsBytes(root);
	}

	private static String text(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
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
}
