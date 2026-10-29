package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@code {"indexFormat":3,"machine":…,"units":[…]}}. Je Einheit steht neben dem Stand die gültige Kette ohne Inhalt:
 * {@code "chain":[{"version","saveId","at","machineName","operator","deleted"}]}, ältestes Glied zuerst. Daraus entstehen die
 * Speicherstände eines Events, ohne eine Datei zu lesen ({@link SyncService#chains}). Dazu kommen seit Format 3 die
 * aufgegangenen Versionen ({@code "absorbed"}), die Versionen mit Konfliktvermerk ({@code "notes"}), {@code "seen"} der
 * aktuellen Löschversion, die Nummer der eigenen Änderungsliste der aktuellen Version ({@code "ownSeq"}, fehlt: unbekannt)
 * und die über eine Änderungsliste eingelesenen fremden Versionen ({@code "listed"}), siehe {@link Entry}. Eine Datei mit
 * anderem Format (auch dem ersten ohne Kette und dem zweiten ohne diese Angaben), von einem anderen Rechner oder unlesbar
 * gilt als leer und wird beim nächsten {@link #flush} neu geschrieben.
 * <p>
 * <b>Marke "sauber beendet":</b> Die Datei {@code <Index>/clean} (Inhalt: die Rechner-ID als Text, UTF-8) sagt, dass der
 * Index beim letzten Beenden vollständig geschrieben war und seither nichts am Bestand geändert wurde. {@link #loadIfClean}
 * übernimmt den Index dann ohne Durchlauf über den Verzeichnisbaum und löscht die Marke sofort, damit ein Absturz nicht auf
 * einen veralteten Index trifft. Die Marke entsteht nur in {@link #writeCleanMarker} (und nur ohne ungeschriebene
 * Änderungen). Wer danach den Bestand ändern will, ruft vor der ersten geschriebenen Datei {@link #invalidateCleanMarker}
 * auf (nicht erst beim Eintragen des Ergebnisses: bis dahin kann der Prozess enden); {@link #update} und {@link #clear}
 * löschen sie zusätzlich. Scheitert das Löschen, wird die Marke geleert, und eine leere Marke gilt als unbrauchbar. Temporäre Reste und
 * geänderte Dateien entstehen nur bei einem Absturz, dann fehlt die Marke und der nächste Start prüft wie gewohnt. Auch
 * die Marke macht den Index nicht zum Schiedsrichter: Die Entscheidung über ausstehende Versionen bleibt bei
 * {@link ChainResolver} auf den Dateien.
 * <p>
 * Alle Methoden sind synchronisiert, bis auf das Schreiben der Dateien in {@link #flush}.
 */
final class UnitIndex {

	private static final Logger LOGGER = Logger.getLogger(UnitIndex.class.getName());

	private static final int INDEX_FORMAT = 3;
	private static final String EVENTS = "events";
	private static final String HALLS = "halls";
	private static final String CLAIMS = "claims";
	private static final String JSON = ".json";
	private static final String CLEAN = "clean";

	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();

	/**
	 * Ein Eintrag des Index.
	 *
	 * @param snapshot    der zuletzt ermittelte Stand der Einheit
	 * @param fingerprint der Abdruck der Dateinamen, auf denen der Stand beruht
	 * @param pending     ob die Einheit eigene, noch nicht beanspruchte Versionen hat
	 * @param lost        ob die Einheit eigene, verlorene Versionen hat
	 * @param chain       die gültige Kette ({@link UnitState#chain()}), ältestes Glied zuerst, ohne Inhalt: daraus entstehen die
	 *                    Speicherstände, ohne eine Datei zu lesen
	 * @param absorbed    die Versionen, die vom aktuellen Stand ({@link UnitState#current()}) über beliebige Vorgänger
	 *                    erreichbar sind, transitiv, ohne die Kette: was in eine Zusammenführung (auch eine, die selbst
	 *                    zusammengeführt wurde) eingegangen ist
	 * @param notes       die Versionen mit Konfliktvermerk aus der Kette, aus {@code absorbed} und unter den eigenen
	 *                    ausstehenden Versionen
	 * @param seen        {@link Version#seen()} der aktuellen Version, wenn sie eine Löschung eines Event-Kopfs oder einer
	 *                    Vorstellung ist; sonst leer
	 * @param ownSeq      die Nummer der eigenen Änderungsliste, in der die aktuelle Version steht, wenn sie eigen ist: eine
	 *                    Zahl ab 1; {@code 0}: in keiner Liste; {@code null}: unbekannt (oder die aktuelle Version ist nicht eigen)
	 * @param listed      die fremden Versionen dieser Einheit, die über eine vollständig verarbeitete Änderungsliste kamen;
	 *                    nur für die Kette und {@code absorbed} gehalten
	 */
	record Entry(UnitSnapshot snapshot, String fingerprint, boolean pending, boolean lost, List<ChainStep> chain,
			Set<VersionId> absorbed, List<NotedVersion> notes, Map<String, Long> seen, Long ownSeq, Set<VersionId> listed) {

		/** Macht die Sammlungen unveränderlich. */
		Entry {
			chain = List.copyOf(chain);
			absorbed = Set.copyOf(absorbed);
			notes = List.copyOf(notes);
			seen = Map.copyOf(seen);
			listed = Set.copyOf(listed);
		}

		private Entry withOwnSeq(Long number) {
			return new Entry(snapshot, fingerprint, pending, lost, chain, absorbed, notes, seen, number, listed);
		}

		private Entry withListed(Set<VersionId> versions) {
			return new Entry(snapshot, fingerprint, pending, lost, chain, absorbed, notes, seen, ownSeq, versions);
		}
	}

	/**
	 * Eine Version mit Konfliktvermerk, so weit der Dialog sie braucht: Herkunft und Vermerk, ohne Inhalt.
	 *
	 * @param version     die ID der Version
	 * @param machine     die ID des Rechners, der sie geschrieben hat
	 * @param machineName der Anzeigename dieses Rechners
	 * @param operator    der Bediener (kann {@code null} sein)
	 * @param at          der Zeitpunkt des Speicherns
	 * @param saveId      die ID des Speichervorgangs
	 * @param parents     die Vorgänger der Version
	 * @param note        der Konfliktvermerk
	 */
	record NotedVersion(VersionId version, String machine, String machineName, String operator, Instant at, String saveId,
			List<VersionId> parents, ConflictNote note) {

		/** Macht die Vorgänger unveränderlich. */
		NotedVersion {
			parents = List.copyOf(parents);
		}

		static NotedVersion of(Version version) {
			return new NotedVersion(version.id(), version.machine(), version.machineName(), version.operator(), version.at(),
					version.saveId(), version.parents(), version.conflict());
		}
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
	/**
	 * Fremde Versionen, die über eine Änderungsliste kamen, bevor ihre Einheit einen Eintrag hat ({@link #markListed});
	 * {@link #update} übernimmt sie in den Eintrag. Nur im Speicher.
	 */
	private final Map<UnitKey, Set<VersionId>> unlistedYet = new HashMap<>();
	/**
	 * Listennummern eigener Versionen, die der Index noch nicht als aktuelle Version einer Einheit kennt
	 * ({@link #markOwnListed}); {@link #update} übernimmt sie und verwirft, was zum Stand gehört. Nur im Speicher.
	 */
	private final Map<UnitKey, Map<VersionId, Long>> ownListedYet = new HashMap<>();
	/** Die Gruppen ({@code events/<Event-ID>} bzw. {@code halls}), deren Datei schon gelesen wurde. */
	private final Set<String> loaded = new HashSet<>();
	/** Die Gruppen, deren Datei neu geschrieben werden muss. */
	private final Set<String> dirty = new TreeSet<>();
	/** Wie oft sich eine Gruppe seit dem Start geändert hat ({@link #markChanged}); so erkennt {@link #flush} ein Update während des Schreibens. */
	private final Map<String, Long> revisions = new HashMap<>();
	/** Lässt höchstens einen {@link #flush} zugleich schreiben; wird vor der Sperre des Index genommen, nie umgekehrt. */
	private final Object flushLock = new Object();
	/** Ob diese Instanz die Marke "sauber beendet" angelegt hat und sie noch gilt. */
	private boolean markerWritten;
	/** Ob die Reste abgebrochener Schreibvorgänge schon gelöscht wurden (einmal beim Öffnen). */
	private boolean tempsDeleted;
	private Deleter deleter = Files::deleteIfExists;

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
	 * @param progress bekommt den Anteil der behandelten Einheiten von 0.0 bis 1.0, zuletzt 1.0
	 * @return die Einheiten mit ausstehenden und mit verlorenen Versionen und die Zahl der neu aufgelösten Einheiten
	 */
	synchronized Refresh refresh(VersionStore local, DoubleConsumer progress) throws IOException {
		deleteLeftoverTemps();
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
				unlistedYet.remove(gone);
				ownListedYet.remove(gone);
				markChanged(groupOf(gone));
			}
		}

		int resolved = 0;
		int done = 0;
		int lastPercent = 0;
		progress.accept(0.0);
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
				progress.accept(percent / 100.0);
			}
		}
		if (lastPercent != 100) {
			progress.accept(1.0);
		}

		return refreshOf(units.keySet(), resolved);
	}

	/** Die Einheiten mit ausstehenden und mit verlorenen Versionen unter den genannten, nach den Flags ihrer Einträge. */
	private Refresh refreshOf(Collection<UnitKey> units, int resolved) {
		Set<UnitKey> pending = new HashSet<>();
		Set<UnitKey> lost = new HashSet<>();
		for (UnitKey unit : units) {
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
	 * Das Ergebnis nach einem erfolgreichen {@link #loadIfClean}: die Einheiten mit ausstehenden und mit verlorenen
	 * Versionen nach den Flags der geladenen Einträge; es wurde nichts neu aufgelöst ({@code resolved == 0}).
	 */
	synchronized Refresh cleanRefresh() {
		return refreshOf(entries.keySet(), 0);
	}

	/**
	 * Trägt den frisch ermittelten Stand einer Einheit ein und merkt ihre Datei zum Schreiben vor. Der Eintrag beruht auf den
	 * Dateien, die {@code unitFiles} enthält. Eine angelegte Marke "sauber beendet" gilt damit nicht mehr.
	 */
	synchronized void update(UnitFiles unitFiles, UnitState state) {
		UnitKey unit = state.unit();
		Entry previous = entries.get(unit);
		Version current = state.current();
		Set<VersionId> chainIds = state.chain().stream().map(Version::id).collect(Collectors.toSet());
		Set<VersionId> absorbed = absorbedOf(unitFiles, current, chainIds);
		entries.put(unit, new Entry(UnitSnapshot.of(state), fingerprint(unitFiles), !state.pending().isEmpty(),
				!state.lost().isEmpty(), state.chain().stream().map(ChainStep::of).toList(), absorbed,
				notesOf(unitFiles, state, absorbed), seenOf(unit, current), ownSeqOf(previous, unit, current, chainIds, absorbed),
				listedOf(previous, unit, unitFiles, state, chainIds, absorbed)));
		markChanged(groupOf(unit));
		// Absicherung: Wer den Bestand ändert, hat die Marke schon vorher mit invalidateCleanMarker entfernt
		invalidateCleanMarker();
	}

	/**
	 * Die Versionen, die vom aktuellen Stand über beliebige Vorgänger erreichbar sind (transitiv), ohne die Kette und ohne
	 * den aktuellen Stand selbst. Liest nur die Versionen, die {@code unitFiles} schon enthält; eine genannte, aber lokal
	 * fehlende oder unlesbare Version zählt als erreicht, ihre Vorgänger sind unbekannt.
	 * <p>
	 * Absichtlich weiter gefasst als die Regel "zusammengeführt" in {@link ChainResolver} (nur eine eigene oder beanspruchte
	 * nennende Version zählt): Hier zählt jede erreichte Nennung. Für {@code ForeignDecision} genügt das, weil sie nur fragt,
	 * ob ein früher gesehener Vergleichsstand im neuen Stand enthalten ist; ob er dort erledigt ist, entscheidet weiter
	 * {@link ChainResolver}.
	 */
	private static Set<VersionId> absorbedOf(UnitFiles unitFiles, Version current, Set<VersionId> chainIds) {
		Set<VersionId> reached = new HashSet<>();
		if (current == null) {
			return reached;
		}
		Deque<VersionId> open = new ArrayDeque<>(current.parents());
		Set<VersionId> visited = new HashSet<>();
		while (!open.isEmpty()) {
			VersionId id = open.pop();
			if (!visited.add(id)) {
				continue;
			}
			if (!chainIds.contains(id) && !id.equals(current.id())) {
				reached.add(id);
			}
			VersionFile file = unitFiles.versions().get(id);
			if (file != null && file.version() != null) {
				file.version().parents().forEach(open::push);
			}
		}
		return reached;
	}

	/** Die Versionen mit Vermerk aus der Kette, den aufgegangenen und den eigenen ausstehenden, in dieser Reihenfolge. */
	private static List<NotedVersion> notesOf(UnitFiles unitFiles, UnitState state, Set<VersionId> absorbed) {
		Map<VersionId, NotedVersion> notes = new LinkedHashMap<>();
		for (Version version : state.chain()) {
			addNote(notes, version);
		}
		for (VersionId id : new TreeSet<>(absorbed)) {
			VersionFile file = unitFiles.versions().get(id);
			if (file != null) {
				addNote(notes, file.version());
			}
		}
		for (Version version : state.pending()) {
			addNote(notes, version);
		}
		return List.copyOf(notes.values());
	}

	private static void addNote(Map<VersionId, NotedVersion> notes, Version version) {
		if (version != null && version.conflict() != null) {
			notes.putIfAbsent(version.id(), NotedVersion.of(version));
		}
	}

	/** {@code seen} der aktuellen Version, wenn sie eine Löschung eines Event-Kopfs oder einer Vorstellung ist. */
	private static Map<String, Long> seenOf(UnitKey unit, Version current) {
		return current != null && current.deleted() && unit.type().hasChildren() ? current.seen() : Map.of();
	}

	/**
	 * Die Listennummer der aktuellen Version, wenn sie eigen ist: die des bisherigen Eintrags, wenn er dieselbe Version
	 * hatte, verbunden mit einer vorgemerkten ({@link #markOwnListed}); sonst unbekannt. Die vorgemerkten Nummern von
	 * Versionen, die nun zum Stand gehören, entfallen.
	 */
	private Long ownSeqOf(Entry previous, UnitKey unit, Version current, Set<VersionId> chainIds, Set<VersionId> absorbed) {
		Map<VersionId, Long> remembered = ownListedYet.get(unit);
		Long number = null;
		if (current != null && machineId.equals(current.machine())) {
			if (previous != null && current.id().equals(previous.snapshot().version())) {
				number = previous.ownSeq();
			}
			if (remembered != null && remembered.containsKey(current.id())) {
				number = combine(number, remembered.get(current.id()));
			}
		}
		if (remembered != null) {
			remembered.keySet().removeIf(id -> current != null && id.equals(current.id()) || chainIds.contains(id) || absorbed.contains(id));
			if (remembered.isEmpty()) {
				ownListedYet.remove(unit);
			}
		}
		return number;
	}

	/**
	 * Die über eine Änderungsliste eingelesenen fremden Versionen: die des bisherigen Eintrags und die vorgemerkten, soweit
	 * die Version zur Kette oder zu den aufgegangenen gehört. Eine fremde Version der Einheit, die noch keinen Anspruch in
	 * der Kette hat (ihr Anspruch kommt erst), bleibt ebenfalls erhalten: Die Liste kam vor dem Anspruch.
	 */
	private Set<VersionId> listedOf(Entry previous, UnitKey unit, UnitFiles unitFiles, UnitState state,
			Set<VersionId> chainIds, Set<VersionId> absorbed) {
		Set<VersionId> candidates = new HashSet<>();
		if (previous != null) {
			candidates.addAll(previous.listed());
		}
		Set<VersionId> waiting = unlistedYet.remove(unit);
		if (waiting != null) {
			candidates.addAll(waiting);
		}
		candidates.removeIf(id -> !(chainIds.contains(id) || absorbed.contains(id) || isUnclaimedForeign(unitFiles, state, id)));
		return candidates;
	}

	private boolean isUnclaimedForeign(UnitFiles unitFiles, UnitState state, VersionId id) {
		VersionFile file = unitFiles.versions().get(id);
		return file != null && file.version() != null && !machineId.equals(file.version().machine())
				&& !state.claimed().contains(id);
	}

	/** Eine unveränderliche Kopie der Einträge. */
	synchronized Map<UnitKey, Entry> entries() {
		return Map.copyOf(entries);
	}

	/** Eine unveränderliche Kopie der Stände aller Einträge; kopiert nur einmal. */
	synchronized Map<UnitKey, UnitSnapshot> snapshots() {
		Map<UnitKey, UnitSnapshot> snapshots = new HashMap<>(entries.size() * 4 / 3 + 1);
		entries.forEach((unit, entry) -> snapshots.put(unit, entry.snapshot()));
		return Collections.unmodifiableMap(snapshots);
	}

	/**
	 * Die Stände der genannten Einheiten, soweit der Index sie kennt; Einheiten ohne Eintrag fehlen im Ergebnis. Greift nur auf
	 * die genannten Einträge zu und kopiert nicht den ganzen Bestand.
	 */
	synchronized Map<UnitKey, UnitSnapshot> snapshots(Collection<UnitKey> units) {
		Map<UnitKey, UnitSnapshot> snapshots = new HashMap<>();
		for (UnitKey unit : units) {
			Entry entry = entries.get(unit);
			if (entry != null) {
				snapshots.put(unit, entry.snapshot());
			}
		}
		return Collections.unmodifiableMap(snapshots);
	}

	/**
	 * Die Stände aller Einheiten eines Events (Kopf, Vorstellungen, Buchungen). Die Hallen gehören zu keinem Event. Kopiert nur
	 * die Stände dieses Events, nicht den ganzen Bestand.
	 */
	synchronized Map<UnitKey, UnitSnapshot> eventSnapshots(String eventId) {
		Map<UnitKey, UnitSnapshot> snapshots = new HashMap<>();
		for (Map.Entry<UnitKey, Entry> entry : entries.entrySet()) {
			UnitKey unit = entry.getKey();
			if (unit.type() != UnitType.HALL && eventId.equals(unit.eventId())) {
				snapshots.put(entry.getKey(), entry.getValue().snapshot());
			}
		}
		return Collections.unmodifiableMap(snapshots);
	}

	/**
	 * Die Ketten der genannten Einheiten, soweit der Index sie kennt; Einheiten ohne Eintrag fehlen im Ergebnis. Greift nur auf
	 * die genannten Einträge zu.
	 */
	synchronized Map<UnitKey, List<ChainStep>> chains(Collection<UnitKey> units) {
		Map<UnitKey, List<ChainStep>> chains = new LinkedHashMap<>();
		for (UnitKey unit : units) {
			Entry entry = entries.get(unit);
			if (entry != null) {
				chains.put(unit, entry.chain());
			}
		}
		return chains;
	}

	/**
	 * Die genannten Einheiten mit eigenen Versionen außerhalb ihrer Kette: verlorene ({@link Entry#lost()}), ausstehende
	 * ({@link Entry#pending()}) oder aufgegangene von diesem Rechner ({@link Entry#absorbed()}). Einheiten ohne Eintrag
	 * fehlen. Greift nur auf die genannten Einträge zu.
	 */
	synchronized Set<UnitKey> withOwnBesideChain(Collection<UnitKey> units, String machineId) {
		Set<UnitKey> result = new LinkedHashSet<>();
		for (UnitKey unit : units) {
			Entry entry = entries.get(unit);
			if (entry != null && (entry.lost() || entry.pending()
					|| entry.absorbed().stream().anyMatch(version -> version.isFrom(machineId)))) {
				result.add(unit);
			}
		}
		return result;
	}

	/**
	 * Die aufgegangenen Versionen ({@link Entry#absorbed()}) der genannten Einheiten, soweit der Index sie kennt; Einheiten
	 * ohne Eintrag fehlen im Ergebnis. Greift nur auf die genannten Einträge zu.
	 */
	synchronized Map<UnitKey, Set<VersionId>> absorbed(Collection<UnitKey> units) {
		Map<UnitKey, Set<VersionId>> absorbed = new LinkedHashMap<>();
		for (UnitKey unit : units) {
			Entry entry = entries.get(unit);
			if (entry != null) {
				absorbed.put(unit, entry.absorbed());
			}
		}
		return Collections.unmodifiableMap(absorbed);
	}

	/**
	 * Die Einheiten, deren Eintrag eigene, noch nicht beanspruchte Versionen anzeigt ({@link Entry#pending()}). Nur Kandidaten:
	 * Ob eine Version ausstehend ist, entscheidet {@link ChainResolver} auf den Dateien.
	 */
	synchronized Set<UnitKey> pendingUnits() {
		Set<UnitKey> units = new LinkedHashSet<>();
		entries.forEach((unit, entry) -> {
			if (entry.pending()) {
				units.add(unit);
			}
		});
		return Collections.unmodifiableSet(units);
	}

	/**
	 * Die Einheiten, deren Eintrag eigene, verlorene Versionen anzeigt ({@link Entry#lost()}). Nur Kandidaten: Ob eine Version
	 * verloren ist, entscheidet {@link ChainResolver} auf den Dateien.
	 */
	synchronized Set<UnitKey> lostUnits() {
		Set<UnitKey> units = new LinkedHashSet<>();
		entries.forEach((unit, entry) -> {
			if (entry.lost()) {
				units.add(unit);
			}
		});
		return Collections.unmodifiableSet(units);
	}

	/**
	 * Die Einheiten, deren gültige Kette ({@link Entry#chain()}) mindestens ein Glied hat, das {@code own} erfüllt und dessen
	 * Zeitstempel in der Versions-ID ({@link VersionId#epochMillis()}) nach {@code since} liegt (die Kopfversion zählt auch,
	 * falls sie nicht in der Kette steht): die Kandidaten für eigene junge Ansprüche, auch wenn inzwischen eine fremde Version
	 * darauf aufbaut. Nur Kandidaten; welche Ansprüche es gibt und wie alt sie sind, liest der Aufrufer aus den Dateien. Nicht
	 * erfasst: eine Version, die lange nach dem Schreiben beansprucht wurde (alte ID, junger Anspruch).
	 *
	 * @param own   ob die Version von diesem Rechner stammt
	 * @param since die Grenze; die ID muss jünger sein
	 */
	synchronized Set<UnitKey> recentOwnChainUnits(Predicate<VersionId> own, Instant since) {
		long limit = since.toEpochMilli();
		Predicate<VersionId> young = version -> own.test(version) && version.epochMillis() > limit;
		Set<UnitKey> units = new LinkedHashSet<>();
		entries.forEach((unit, entry) -> {
			VersionId head = entry.snapshot().version();
			if (head != null && young.test(head) || entry.chain().stream().anyMatch(step -> young.test(step.version()))) {
				units.add(unit);
			}
		});
		return Collections.unmodifiableSet(units);
	}

	/** Der Eintrag der Einheit (unveränderlich), oder {@code null}, wenn der Index sie nicht kennt. */
	synchronized Entry entry(UnitKey unit) {
		return entries.get(unit);
	}

	/**
	 * Lässt den Leser über alle Einträge laufen, ohne sie zu kopieren (die Einträge sind unveränderlich). Der Leser läuft unter
	 * der Sperre des Index: Er rechnet nur und ruft nichts zurück.
	 */
	synchronized <T> T read(Function<Collection<Entry>, T> reader) {
		return reader.apply(entries.values());
	}

	/**
	 * Setzt "in keiner Liste" ({@code 0}) für die geprüften Stände, die eigen sind und deren Listennummer weiter unbekannt ist,
	 * wie {@link #markOwnListed} mit {@code 0}. Gilt, nachdem alle eigenen Listen gelesen und ihre Nummern für diese Stände
	 * eingetragen sind: Was dann noch unbekannt ist, steht in keiner. Nur wo der Eintrag noch genau die geprüfte Version als
	 * aktuelle hat: Eine Version, die erst nach der Prüfung entstand oder die Einheit ablöste, wurde nicht gegen die Listen
	 * gelesen, und eine falsche {@code 0} liesse sie als dem Löschenden unbekannt erscheinen. Wie überall erkennt der Index
	 * eigene Versionen am Kürzel ihrer ID ({@link VersionId#isFrom}).
	 *
	 * @param examined die Einheiten mit der aktuellen Version, die beim Lesen der Listen galt
	 */
	synchronized void markUnknownOwnAsUnlisted(Map<UnitKey, VersionId> examined) {
		boolean changed = false;
		for (Map.Entry<UnitKey, VersionId> item : examined.entrySet()) {
			Entry entry = entries.get(item.getKey());
			VersionId version = item.getValue();
			if (entry != null && version != null && version.equals(entry.snapshot().version()) && entry.ownSeq() == null
					&& version.isFrom(machineId)) {
				entries.put(item.getKey(), entry.withOwnSeq(0L));
				markChanged(groupOf(item.getKey()));
				changed = true;
			}
		}
		if (changed) {
			invalidateCleanMarker();
		}
	}

	/**
	 * Hält fest, in welcher eigenen Änderungsliste die aktuelle Version der Einheiten steht. Nur Einträge, deren aktuelle
	 * Version die genannte ist, ändern sich sofort; für eine andere Version oder eine Einheit ohne Eintrag merkt sich der
	 * Index die Nummer, und der nächste {@link #update}, der die Version als aktuelle findet, übernimmt sie. Die Nummer
	 * {@code 0} heißt "in keiner Liste" und ersetzt nie eine bekannte Nummer ab 1; von mehreren Nummern ab 1 gilt die
	 * kleinste (siehe {@link #combine}).
	 *
	 * @param entries die Einträge der Liste (oder, bei {@code 0}, der Versionen, die ohne Liste vorliegen)
	 * @param seq     die Nummer der Liste; {@code 0}: keine
	 */
	synchronized void markOwnListed(Collection<FeedEntry> entries, long seq) {
		boolean changed = false;
		for (FeedEntry feedEntry : entries) {
			Optional<UnitKey> parsed = UnitKey.parse(feedEntry.unit());
			if (parsed.isEmpty()) {
				continue;
			}
			UnitKey unit = parsed.get();
			Entry entry = this.entries.get(unit);
			if (entry == null || !feedEntry.version().equals(entry.snapshot().version())) {
				// der Index kennt diese Version noch nicht (oder nicht als aktuelle): der nächste update übernimmt die Nummer
				ownListedYet.computeIfAbsent(unit, key -> new HashMap<>()).merge(feedEntry.version(), seq, UnitIndex::combine);
				continue;
			}
			Long number = combine(entry.ownSeq(), seq);
			if (!number.equals(entry.ownSeq())) {
				this.entries.put(unit, entry.withOwnSeq(number));
				markChanged(groupOf(unit));
				changed = true;
			}
		}
		if (changed) {
			invalidateCleanMarker();
		}
	}

	/**
	 * Verbindet eine bekannte Listennummer mit einer neuen: Von mehreren Nummern ab 1 gilt die kleinste (die Version steht
	 * schon in der ersten Liste; eine spätere ist eine Wiederholung nach einem Absturz), eine Nummer ab 1 ersetzt
	 * "keine Liste" ({@code 0}) und "unbekannt" ({@code null}), und {@code 0} ersetzt nur "unbekannt".
	 */
	private static Long combine(Long known, long seq) {
		if (known == null) {
			return seq;
		}
		if (seq == 0) {
			return known;
		}
		return known == 0 ? seq : Math.min(known, seq);
	}

	/**
	 * Hält fest, dass fremde Versionen über eine vollständig verarbeitete Änderungsliste eingelesen wurden. Für eine Einheit
	 * ohne Eintrag (der Anspruch kommt vielleicht erst) merkt sich der Index die Version, bis die Einheit eingetragen wird;
	 * {@link #update} behält davon, was zur Einheit gehört.
	 *
	 * @param entries die Einträge der verarbeiteten Listen
	 */
	synchronized void markListed(Collection<FeedEntry> entries) {
		boolean changed = false;
		for (FeedEntry feedEntry : entries) {
			Optional<UnitKey> parsed = UnitKey.parse(feedEntry.unit());
			if (parsed.isEmpty()) {
				continue;
			}
			UnitKey unit = parsed.get();
			Entry entry = this.entries.get(unit);
			if (entry == null) {
				unlistedYet.computeIfAbsent(unit, key -> new HashSet<>()).add(feedEntry.version());
			} else if (!entry.listed().contains(feedEntry.version())) {
				Set<VersionId> listed = new HashSet<>(entry.listed());
				listed.add(feedEntry.version());
				this.entries.put(unit, entry.withListed(listed));
				markChanged(groupOf(unit));
				changed = true;
			}
		}
		if (changed) {
			invalidateCleanMarker();
		}
	}

	/** Ob die Version der Einheit über eine vollständig verarbeitete Änderungsliste eingelesen wurde. */
	synchronized boolean isListed(UnitKey unit, VersionId version) {
		Entry entry = entries.get(unit);
		if (entry != null && entry.listed().contains(version)) {
			return true;
		}
		return unlistedYet.getOrDefault(unit, Set.of()).contains(version);
	}

	/** Ob es Änderungen gibt, die {@link #flush} noch schreiben müsste. */
	synchronized boolean hasChanges() {
		return !dirty.isEmpty();
	}

	/**
	 * Verwirft alle Einträge. Alle bekannten Gruppen (im Speicher und als Datei vorhanden) gelten als geändert; der nächste
	 * {@link #flush} schreibt sie leer. Die alten Dateien werden nie wieder gelesen: nötig, wenn der Datenordner ausgetauscht
	 * wird (Beiseitelegen), denn danach können gleiche Dateinamen anderen Inhalt haben. Die Marke "sauber beendet" wird
	 * gelöscht.
	 */
	synchronized void clear() {
		deleteCleanMarker();
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
		unlistedYet.clear();
		ownListedYet.clear();
		loaded.addAll(groups);
		groups.forEach(this::markChanged);
	}

	/**
	 * Schreibt die geänderten Index-Dateien, jede über eine temporäre Datei im selben Ordner und
	 * {@link AtomicFileReplace#replace}. Scheitert eine Datei, bleibt sie als geändert vorgemerkt und die Ausnahme geht
	 * weiter; die noch nicht geschriebenen bleiben ebenfalls vorgemerkt.
	 * <p>
	 * Die Sperre des Index hält nur das Sammeln der Einträge einer Gruppe auf, nicht das Serialisieren und Schreiben (bis
	 * 24 MB): Leser wie {@link #snapshots(Collection)} warten so nicht auf die Platte. Eine Gruppe, die sich während des Schreibens ändert,
	 * bleibt vorgemerkt und kommt beim nächsten Aufruf in die Datei. Mehrere Aufrufe schreiben nacheinander.
	 */
	void flush() throws IOException {
		// ein Schreiben nach dem anderen: Ein zweiter Aufruf wartet, bis der erste fertig ist (und die Gruppen geschrieben hat)
		synchronized (flushLock) {
			for (String group : changedGroups()) {
				Prepared prepared = prepare(group);
				if (prepared != null) {
					write(prepared);
				}
			}
		}
	}

	/** Die Gruppen, die geschrieben werden müssen, als Kopie. */
	private synchronized List<String> changedGroups() {
		return new ArrayList<>(dirty);
	}

	/**
	 * Bereitet die Gruppe unter der Sperre des Index auf: Nur das Sammeln ihrer Einträge hält die Leser auf. Die Einträge sind
	 * unveränderlich (ein {@link #update} ersetzt sie, ändert sie nie), das Serialisieren und Schreiben geschieht danach ohne
	 * die Sperre ({@link #write}).
	 *
	 * @return die Einträge mit dem Stand, auf dem sie beruhen; {@code null}, wenn die Gruppe inzwischen nicht mehr geändert ist
	 */
	private synchronized Prepared prepare(String group) {
		if (!dirty.contains(group)) {
			return null;
		}
		List<Entry> inGroup = entries.values().stream().filter(entry -> groupOf(entry.snapshot().unit()).equals(group))
				.toList();
		return new Prepared(group, revisionOf(group), inGroup);
	}

	/**
	 * Serialisiert die Gruppe und schreibt ihre Datei über eine temporäre Datei, ohne die Sperre des Index. Die Gruppe gilt
	 * erst danach als geschrieben, und nur, wenn sich inzwischen nichts an ihr geändert hat: Ein {@link #update} während des
	 * Schreibens lässt sie vorgemerkt, damit der nächste {@link #flush} den neuen Stand schreibt.
	 */
	private void write(Prepared prepared) throws IOException {
		Path file = fileOf(prepared.group());
		byte[] bytes = serialize(prepared.entries());
		Path temp = file.resolveSibling(FileLayer.TEMP_PREFIX + "index-" + UUID.randomUUID() + FileLayer.TEMP_SUFFIX);
		try {
			files.createNew(temp, bytes);
			AtomicFileReplace.replace(temp, file);
		} catch (IOException | RuntimeException e) {
			deleteQuietly(temp);
			throw e;
		}
		markWritten(prepared);
	}

	private synchronized void markWritten(Prepared prepared) {
		if (revisionOf(prepared.group()) == prepared.revision()) {
			dirty.remove(prepared.group());
		}
	}

	/** Merkt eine Gruppe zum Schreiben vor. */
	private void markChanged(String group) {
		dirty.add(group);
		revisions.merge(group, 1L, Long::sum);
	}

	private long revisionOf(String group) {
		return revisions.getOrDefault(group, 0L);
	}

	/** Die Einträge einer Gruppe und der Stand ({@link #revisionOf}), auf dem sie beruhen. */
	private record Prepared(String group, long revision, List<Entry> entries) {
	}

	private Path cleanMarker() {
		return indexDir.resolve(CLEAN);
	}

	/**
	 * Legt die Marke "sauber beendet" an (Inhalt: die Rechner-ID), aber nur, wenn es keine ungeschriebenen Änderungen gibt:
	 * Sonst stünde die Marke für einen Index, der nicht dem Stand der Dateien auf der Platte entspricht. Bis zur nächsten
	 * Änderung ({@link #update}, {@link #clear}) gilt sie.
	 *
	 * @return ob die Marke angelegt wurde ({@code false} bei ungeschriebenen Änderungen)
	 * @throws IOException wenn sie sich nicht schreiben lässt; dann gibt es keine (vollständige) Marke, und eine unvollständige
	 *                     gilt beim Lesen als fremd
	 */
	synchronized boolean writeCleanMarker() throws IOException {
		if (!dirty.isEmpty()) {
			return false;
		}
		Files.createDirectories(indexDir);
		Files.writeString(cleanMarker(), machineId, StandardCharsets.UTF_8);
		markerWritten = true;
		return true;
	}

	/**
	 * Löscht die Marke "sauber beendet", falls es sie gibt. Scheitert das, wird die Datei geleert: Eine leere Marke nennt keine
	 * Rechner-ID und gilt beim Start als unbrauchbar. Gelingt auch das nicht, steht eine Warnung im Log, und eine von dieser
	 * Instanz angelegte Marke gilt weiter als vorhanden, damit {@link #invalidateCleanMarker()} es beim nächsten Mal erneut
	 * versucht.
	 */
	synchronized void deleteCleanMarker() {
		try {
			deleter.delete(cleanMarker());
		} catch (IOException | RuntimeException e) {
			if (!emptyCleanMarker(e)) {
				return;
			}
		}
		markerWritten = false;
	}

	/**
	 * Leert die Marke, nachdem das Löschen gescheitert ist: Eine leere Marke nennt keine Rechner-ID und gilt beim Start als
	 * unbrauchbar.
	 *
	 * @return {@code false}, wenn auch das scheitert und die Marke deshalb noch gültig sein kann
	 */
	private boolean emptyCleanMarker(Exception deleteFailure) {
		Path marker = cleanMarker();
		LOGGER.log(Level.WARNING, "Die Marke 'sauber beendet' ließ sich nicht löschen und wird geleert: " + marker, deleteFailure);
		try {
			Files.write(marker, new byte[0]);
		} catch (IOException | RuntimeException again) {
			LOGGER.log(Level.WARNING, "Die Marke 'sauber beendet' ließ sich auch nicht leeren: " + marker, again);
			return false;
		}
		return true;
	}

	/**
	 * Muss vor jeder Änderung am lokalen Bestand aufgerufen werden: Eine von dieser Instanz angelegte Marke wird gelöscht,
	 * bevor die erste Datei geschrieben wird, damit ein Absturz oder Beenden mitten in der Änderung nie auf eine gültige Marke
	 * über einem geänderten Bestand trifft. Ohne angelegte Marke kostet der Aufruf keinen Dateizugriff.
	 */
	synchronized void invalidateCleanMarker() {
		if (markerWritten) {
			deleteCleanMarker();
		}
	}

	/** Löscht eine Datei, falls es sie gibt. */
	interface Deleter {
		void delete(Path file) throws IOException;
	}

	/** Für Tests: ersetzt das Löschen der Marke, um ein Scheitern nachzustellen. */
	synchronized void useDeleter(Deleter deleter) {
		this.deleter = deleter;
	}

	/**
	 * Übernimmt den Index ohne Durchlauf über den Verzeichnisbaum, wenn er beim letzten Beenden sauber geschrieben wurde. Prüft
	 * dazu die Marke und löscht sie sofort: Sie gilt nur für genau diesen Start. Eine fremde oder unlesbare Marke wird
	 * ebenfalls gelöscht (scheitert das, steht nur eine Warnung im Log), gilt aber nicht. Lässt sich die Marke nicht löschen,
	 * wird sie geleert wie in {@link #deleteCleanMarker()}; gelingt auch das nicht, gilt der Start als unsauber, und jedes
	 * spätere {@link #invalidateCleanMarker()} versucht es erneut. Danach werden alle Index-Dateien gelesen; ist eine unlesbar, hat ein anderes Format
	 * oder eine andere Rechner-ID, gilt der Start ebenfalls als unsauber, und nichts von dem Gelesenen wird übernommen.
	 * <p>
	 * Bei {@code true} sind alle Einträge geladen; {@link #cleanRefresh()} liefert die Einheiten mit ausstehenden und
	 * verlorenen Versionen. Bei {@code false} ist der Index unverändert und {@link #refresh} der Weg wie bisher. Wirft nie.
	 */
	synchronized boolean loadIfClean() {
		deleteLeftoverTemps();
		Path marker = cleanMarker();
		markerWritten = false;
		boolean own;
		try {
			if (!Files.exists(marker)) {
				return false;
			}
			own = machineId.equals(Files.readString(marker, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Die Marke 'sauber beendet' ist unlesbar: " + marker, e);
			own = false;
		}
		try {
			deleter.delete(marker);
		} catch (IOException | RuntimeException e) {
			if (!emptyCleanMarker(e)) {
				// Die Marke kann gültig geblieben sein: jede spätere Änderung am Bestand versucht es erneut
				markerWritten = true;
			}
			return false;
		}
		if (!own) {
			return false;
		}
		Map<String, List<Entry>> read = new LinkedHashMap<>();
		try {
			for (String group : storedGroups()) {
				if (!loaded.contains(group)) {
					Optional<byte[]> bytes = files.read(fileOf(group));
					read.put(group, bytes.isPresent() ? parse(bytes.get(), group) : List.of());
				}
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warning("Der Start-Index ist trotz Marke unbrauchbar, der Bestand wird durchsucht: " + e);
			return false;
		}
		read.forEach((group, list) -> {
			loaded.add(group);
			list.forEach(entry -> entries.putIfAbsent(entry.snapshot().unit(), entry));
		});
		return true;
	}

	/**
	 * Löscht die temporären Dateien {@code ~karv-index-*.tmp}, die ein abgebrochenes {@link #flush} im Index-Ordner und in
	 * {@code events/} zurückgelassen hat, gleich welchen Alters: Nur diese Klasse schreibt sie, und den Index nutzt nur ein
	 * Prozess (die Instanzsperre). Geschieht beim ersten Öffnen des Index ({@link #loadIfClean} oder {@link #refresh}) einmal;
	 * Fehler stehen nur im Log.
	 */
	private void deleteLeftoverTemps() {
		if (tempsDeleted) {
			return;
		}
		tempsDeleted = true;
		for (Path dir : List.of(indexDir, indexDir.resolve(EVENTS))) {
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir,
					FileLayer.TEMP_PREFIX + "index-*" + FileLayer.TEMP_SUFFIX)) {
				for (Path temp : stream) {
					deleteQuietly(temp);
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.log(Level.WARNING, "Temporäre Reste im Index-Ordner ließen sich nicht entfernen: " + dir, e);
			}
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
				boolean zeroReset = false;
				for (Entry entry : parse(bytes.get(), group)) {
					// "in keiner Liste" wurde vielleicht vor einem Absturz geschrieben, nachdem die Liste entstanden war: unbekannt
					if (entry.ownSeq() != null && entry.ownSeq() == 0) {
						entry = entry.withOwnSeq(null);
						zeroReset = true;
					}
					entries.putIfAbsent(entry.snapshot().unit(), entry);
				}
				if (zeroReset) {
					markChanged(group);
				}
			}
		} catch (IOException | RuntimeException e) {
			// Erwarteter Fall (anderer Rechner, anderes Format, beschädigt): eine Zeile genügt, kein Stapel
			LOGGER.warning("Der Start-Index " + file + " ist unbrauchbar und wird neu aufgebaut: " + e);
			markChanged(group);
		}
	}

	private List<Entry> parse(byte[] bytes, String group) throws IOException {
		JsonNode root = StoreCodec.readTree(bytes);
		if (!root.path("indexFormat").isInt() || root.get("indexFormat").asInt() != INDEX_FORMAT) {
			throw new IOException("Unbekanntes Format des Index: " + root.get("indexFormat"));
		}
		if (!machineId.equals(StoreCodec.text(root, "machine"))) {
			throw new IOException("Der Index stammt von einem anderen Rechner: " + root.get("machine"));
		}
		JsonNode units = root.get("units");
		if (units == null || !units.isArray()) {
			throw new IOException("Das Feld units fehlt oder ist keine Liste.");
		}
		List<Entry> result = new ArrayList<>();
		for (JsonNode item : units) {
			UnitKey unit = UnitKey.parse(StoreCodec.text(item, "unit"))
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
					version == null || version.isNull() ? null : VersionId.of(StoreCodec.text(item, "version")),
					UnitState.Status.valueOf(StoreCodec.text(item, "status")), StoreCodec.bool(item, "deleted"),
					payload == null || payload.isNull() ? null : (ObjectNode) payload);
			result.add(new Entry(snapshot, StoreCodec.text(item, "fingerprint"), StoreCodec.bool(item, "pending"),
					StoreCodec.bool(item, "lost"), chain(item.get("chain")), new HashSet<>(versionIds(item.get("absorbed"), "absorbed")),
					notes(item.get("notes")), StoreCodec.readSeen(item.get("seen")), ownSeq(item.get("ownSeq")),
					new HashSet<>(versionIds(item.get("listed"), "listed"))));
		}
		return result;
	}

	private static List<VersionId> versionIds(JsonNode node, String field) throws IOException {
		if (node == null || !node.isArray()) {
			throw new IOException("Das Feld " + field + " fehlt oder ist keine Liste.");
		}
		List<VersionId> ids = new ArrayList<>();
		for (JsonNode id : node) {
			if (!id.isTextual()) {
				throw new IOException("Das Feld " + field + " enthält keine Versions-ID: " + id);
			}
			ids.add(VersionId.of(id.asText()));
		}
		return ids;
	}

	private static List<NotedVersion> notes(JsonNode node) throws IOException {
		if (node == null || !node.isArray()) {
			throw new IOException("Das Feld notes fehlt oder ist keine Liste.");
		}
		List<NotedVersion> notes = new ArrayList<>();
		for (JsonNode item : node) {
			ConflictNote note = StoreCodec.readConflict(item.get("conflict"));
			if (note == null) {
				throw new IOException("Ein Eintrag in notes hat keinen Vermerk: " + item);
			}
			try {
				notes.add(new NotedVersion(VersionId.of(StoreCodec.text(item, "version")), StoreCodec.text(item, "machine"),
						StoreCodec.optionalText(item, "machineName"), StoreCodec.optionalText(item, "operator"),
						Instant.parse(StoreCodec.text(item, "at")), StoreCodec.text(item, "saveId"),
						versionIds(item.get("parents"), "parents"), note));
			} catch (DateTimeParseException e) {
				throw new IOException("Ungültiger Zeitpunkt in notes: " + item.get("at"), e);
			}
		}
		return notes;
	}

	/** Die Listennummer der aktuellen Version: fehlt das Feld oder ist es {@code null}, ist sie unbekannt. */
	private static Long ownSeq(JsonNode node) throws IOException {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0) {
			throw new IOException("Das Feld ownSeq ist keine Listennummer: " + node);
		}
		return node.asLong();
	}

	private static List<ChainStep> chain(JsonNode node) throws IOException {
		if (node == null || !node.isArray()) {
			throw new IOException("Das Feld chain fehlt oder ist keine Liste.");
		}
		List<ChainStep> chain = new ArrayList<>();
		for (JsonNode step : node) {
			try {
				chain.add(new ChainStep(VersionId.of(StoreCodec.text(step, "version")), StoreCodec.text(step, "saveId"),
						Instant.parse(StoreCodec.text(step, "at")), StoreCodec.optionalText(step, "machineName"),
						StoreCodec.optionalText(step, "operator"), StoreCodec.bool(step, "deleted")));
			} catch (DateTimeParseException e) {
				throw new IOException("Ungültiger Zeitpunkt in der Kette: " + step.get("at"), e);
			}
		}
		return chain;
	}

	private byte[] serialize(List<Entry> groupEntries) throws IOException {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("indexFormat", INDEX_FORMAT);
		root.put("machine", machineId);
		ArrayNode units = root.putArray("units");
		groupEntries.stream()
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
					ArrayNode chain = item.putArray("chain");
					for (ChainStep step : entry.chain()) {
						ObjectNode node = chain.addObject();
						node.put("version", step.version().value());
						node.put("saveId", step.saveId());
						node.put("at", step.at().toString());
						node.put("machineName", step.machineName());
						node.put("operator", step.operator());
						node.put("deleted", step.deleted());
					}
					ArrayNode absorbed = item.putArray("absorbed");
					entry.absorbed().stream().map(VersionId::value).sorted().forEach(absorbed::add);
					ArrayNode notes = item.putArray("notes");
					for (NotedVersion noted : entry.notes()) {
						ObjectNode node = notes.addObject();
						node.put("version", noted.version().value());
						node.put("machine", noted.machine());
						node.put("machineName", noted.machineName());
						node.put("operator", noted.operator());
						node.put("at", noted.at().toString());
						node.put("saveId", noted.saveId());
						ArrayNode parents = node.putArray("parents");
						noted.parents().forEach(parent -> parents.add(parent.value()));
						StoreCodec.writeConflict(node.putObject("conflict"), noted.note());
					}
					StoreCodec.writeSeen(item, entry.seen());
					if (entry.ownSeq() != null) {
						item.put("ownSeq", entry.ownSeq().longValue());
					}
					ArrayNode listed = item.putArray("listed");
					entry.listed().stream().map(VersionId::value).sorted().forEach(listed::add);
				});
		return MAPPER.writeValueAsBytes(root);
	}
}
