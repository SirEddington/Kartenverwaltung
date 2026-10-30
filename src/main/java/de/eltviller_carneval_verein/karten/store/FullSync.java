package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Der Vollabgleich zwischen dem lokalen Bestand und dem gemeinsamen Ordner: das Sicherheitsnetz für alles, was die
 * Änderungslisten verpasst haben. Er kopiert nur fehlende Dateien und verändert oder löscht nie etwas; die einzige
 * Ausnahme ist ein lokaler Anspruch, der beim Hochladen gegen einen anderen im gemeinsamen Ordner verliert (er bleibt
 * unter neuem Namen liegen). Vorhandene Dateien werden nicht verglichen. Was nicht wie eine Versions- oder
 * Anspruchsdatei heißt, wird übergangen ({@link VersionStore#unitDataFiles}). Er arbeitet Einheit für Einheit
 * ({@link #syncUnits}).
 */
public final class FullSync {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final String CLAIMS = "/claims";
	private static final String JSON = ".json";
	private static final String ROOT = "root";

	private FullSync() {
	}

	/**
	 * Gleicht die genannten Einheiten ab, eine nach der anderen in der gegebenen Reihenfolge, je Einheit in diesen
	 * Schritten:
	 * <ol>
	 * <li>Versionsdateien, die auf einer Seite fehlen, werden auf die andere kopiert.</li>
	 * <li>Anspruchsdateien, die lokal fehlen, werden aus dem gemeinsamen Ordner kopiert.</li>
	 * <li>Ohne {@code uploadLocalClaims}: Eigene lokale Ansprüche (nach {@code own}, auch die einer früheren ID von vor ihrer
	 * Neuvergabe), die auch im gemeinsamen
	 * Ordner liegen, werden verglichen; nennt der gemeinsame eine andere Version, steht der Platz in
	 * {@link FullSyncResult#contested()} (nichts wird ersetzt). Das fängt späte Niederlagen, die keine Änderungsliste
	 * aufgedeckt hat; Kosten: zwei Lesezugriffe je eigenem Anspruch auf beiden Seiten.</li>
	 * <li>Mit {@code uploadLocalClaims}: Anspruchsdateien, die auf beiden Seiten liegen und verschiedene Versionen nennen,
	 * werden geklärt wie beim Hochladen (der gemeinsame gewinnt, siehe unten).</li>
	 * <li>Anspruchsdateien, die im gemeinsamen Ordner fehlen, werden dort angelegt, {@code root} zuerst: mit
	 * {@code uploadLocalClaims} alle, ohne nur die eigenen (der gemeinsame Ordner wurde geleert oder zurückgespielt, oder
	 * der Anspruch entstand ohne Verbindung) und nur, wenn der Platz der Einheit nicht gerade umstritten ist. Nicht
	 * hochgeladen wird ein Anspruch, der auf einer verlorenen Version aufbaut oder eine nennt (ohne
	 * {@code uploadLocalClaims} auch eine, die der lokale Stand schon als verloren zeigt). Liegt im gemeinsamen Ordner
	 * inzwischen ein Anspruch auf eine andere Version, gewinnt er: Der lokale wird unter
	 * {@code <Name>.json.verdraengt-<yyyy-MM-dd_HH-mm-ss>} beiseitegelegt, durch den gemeinsamen ersetzt, und die
	 * eigenen Versionen darauf werden als verloren gemeldet. Ein Anspruch auf dieselbe Version mit anderen Bytes
	 * (etwa anderer Zeitpunkt) ist kein Widerspruch.</li>
	 * </ol>
	 * Unlesbare Anspruchsdateien auf beiden Seiten werden übergangen (die Heilung ist Sache eines späteren Schritts).
	 * Betrachtet werden nur die Dateien im Ordner der Einheit und in ihrem {@code claims/}
	 * ({@link VersionStore#unitDataFiles}), nicht die ihrer Unterordner. Eine Einheit, die dieser Aufruf als umstritten
	 * meldet, lädt keinen eigenen Anspruch hoch: Die Klärung ist Sache des Aufrufers, und der nächste Lauf lädt hoch, was
	 * danach noch gilt.
	 * <p>
	 * Eine Einheit mit Dateien im lokalen Bestand, die {@code known} nicht kennt, steht ebenfalls in
	 * {@link FullSyncResult#changed()}, auch wenn nichts zu kopieren war: So nimmt der Aufrufer Einheiten auf, die an ihm
	 * vorbei in den lokalen Bestand kamen (etwa eine zurückgespielte Sicherung). Gefragt wird nach dem Auflisten, das
	 * ohnehin geschieht; die Frage selbst kostet keinen Dateizugriff.
	 *
	 * @param local             der lokale Bestand
	 * @param shared            der gemeinsame Bestand
	 * @param units             die Einheiten; ob es sie auf einer Seite gibt, ist gleich
	 * @param uploadLocalClaims ob alle lokalen Anspruchsdateien in den gemeinsamen Ordner gelegt werden (Verbinden mit
	 *                          Mitnehmen); sonst fließen nach außen nur eigene Ansprüche, die dort fehlen
	 * @param own               was dieser Rechner als eigen ansieht (für eigene Ansprüche und die verlorenen Versionen)
	 * @param files             die Dateischicht, über die ein verdrängter lokaler Anspruch ersetzt wird
	 * @param clock             die Uhr für den Zeitstempel im Namen verdrängter Ansprüche
	 * @param known             ob der Aufrufer die Einheit schon kennt (eine Abfrage im Speicher, ohne Dateizugriff)
	 * @param uploaded          erfährt jeden hochgeladenen Anspruch sofort, auch wenn der Abgleich danach abbricht
	 * @param onAbort           bekommt, wenn ein Schritt scheitert, das Teilergebnis bis dahin, bevor der Fehler weitergeht:
	 *                          Was der Abgleich erledigt hat, steht schon auf der Platte (ein ausgeglichener Anspruch ist
	 *                          danach auf beiden Seiten gleich, die Wiederholung fände ihn nicht mehr), und ohne das
	 *                          Teilergebnis gingen Verlust und Indexeintrag verloren. {@link FullSyncResult#changed()} und
	 *                          {@link FullSyncResult#lost()} sind vollständig, die übrigen Sammlungen so weit, wie er kam.
	 *                          Wirft der Rückruf selbst, hängt sein Fehler als unterdrückte Ausnahme am ursprünglichen
	 * @throws IOException wenn ein Schritt scheitert; ebenso unverändert eine {@link RuntimeException} aus einem Schritt
	 */
	public static FullSyncResult syncUnits(VersionStore local, VersionStore shared, Collection<UnitKey> units,
			boolean uploadLocalClaims, Ownership own, FileLayer files, Clock clock, Predicate<UnitKey> known,
			Consumer<FullSyncResult.UploadedClaim> uploaded, Consumer<FullSyncResult> onAbort) throws IOException {
		Run run = new Run(local, shared, own, files, clock, known, uploaded);
		try {
			for (UnitKey unit : units) {
				run.syncUnit(unit, uploadLocalClaims);
			}
		} catch (IOException | RuntimeException e) {
			try {
				onAbort.accept(run.result());
			} catch (RuntimeException inner) {
				e.addSuppressed(inner);
			}
			throw e;
		}
		return run.result();
	}

	/** Die Unterordner-Einheiten von {@code <parent>/<folder>}, die es auf mindestens einer Seite gibt, nach Namen sortiert. */
	static List<UnitKey> childrenOfBoth(VersionStore local, VersionStore shared, UnitKey parent, String folder)
			throws IOException {
		List<UnitKey> children = new ArrayList<>();
		for (String name : union(local.childNames(parent, folder), shared.childNames(parent, folder))) {
			children.add(VersionStore.childUnit(parent, folder, name).orElseThrow());
		}
		return children;
	}

	/** Die Namen beider Listen ohne Doppelte, sortiert. */
	static List<String> union(List<String> first, List<String> second) {
		Set<String> names = new TreeSet<>(first);
		names.addAll(second);
		return new ArrayList<>(names);
	}

	/**
	 * Ob beide Seiten schon Einheiten haben, also mindestens eine Versionsdatei. Nur dann muss der Aufrufer fragen,
	 * ob er mitnehmen oder beiseitelegen will; sonst wird ohne Rückfrage vereinigt. Je Seite endet die Suche bei der
	 * ersten Einheit mit einer Versionsdatei.
	 */
	public static boolean bothHaveUnits(VersionStore local, VersionStore shared) throws IOException {
		return hasVersionFile(local) && hasVersionFile(shared);
	}

	private static boolean hasVersionFile(VersionStore store) throws IOException {
		for (UnitKey unit : store.allUnits()) {
			if (store.hasVersions(unit)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Benennt den lokalen Datenordner in einen Nachbarordner {@code data-beiseite-<yyyy-MM-dd_HH-mm-ss>} um (bei
	 * Namensgleichheit mit Zusatz {@code -2}, {@code -3} …). Es wird nichts gelöscht; der Aufrufer legt einen leeren
	 * Bestand neu an.
	 *
	 * @return der neue Pfad; {@code null}, wenn es den Datenordner nicht gibt und nichts beiseitezulegen ist
	 */
	public static Path setAside(Path localDataRoot, FileLayer files, Clock clock) throws IOException {
		if (!files.exists(localDataRoot)) {
			return null;
		}
		String base = "data-beiseite-" + LocalDateTime.now(clock).format(STAMP);
		Path target = localDataRoot.resolveSibling(base);
		for (int n = 2; files.exists(target); n++) {
			target = localDataRoot.resolveSibling(base + "-" + n);
		}
		files.rename(localDataRoot, target);
		return target;
	}

	/**
	 * Eine Datei eines Bestands.
	 *
	 * @param path  Pfad relativ zum Datenordner
	 * @param unit  die Einheit, zu der sie gehört
	 * @param name  Dateiname ohne Endung: die Versions-ID, bei Ansprüchen auch {@code root}
	 * @param claim ob es eine Anspruchsdatei ist (sonst eine Versionsdatei)
	 */
	private record DataPath(String path, UnitKey unit, String name, boolean claim) {
	}

	/**
	 * Die Dateien aus {@link VersionStore#unitDataFiles} der Einheit mit ihrer Art: Was unter {@code claims/} der Einheit
	 * liegt, ist eine Anspruchsdatei, alles andere eine Versionsdatei. Eine Einheit darf {@code claims} heißen (etwa eine
	 * Buchung auf dem Sitz {@code claims}); weil nur die Dateien dieser einen Einheit kommen, ist das eindeutig.
	 */
	private static List<DataPath> dataPaths(UnitKey unit, List<String> paths) {
		String claimsPrefix = unit.relativePath() + CLAIMS + "/";
		List<DataPath> result = new ArrayList<>();
		for (String path : paths) {
			String name = path.substring(path.lastIndexOf('/') + 1, path.length() - JSON.length());
			result.add(new DataPath(path, unit, name, path.startsWith(claimsPrefix)));
		}
		return result;
	}

	/** Der Inhalt einer Anspruchsdatei, oder {@code null}, wenn es sie nicht gibt oder sie unlesbar ist. */
	private static Claim parseClaim(Optional<byte[]> bytes) {
		if (bytes.isEmpty()) {
			return null;
		}
		try {
			return StoreCodec.readClaim(bytes.get());
		} catch (IOException e) {
			return null;
		}
	}

	/** Ein Lauf des Abgleichs mit seinen Zwischenständen. */
	private static final class Run {

		private final VersionStore local;
		private final VersionStore shared;
		private final Ownership own;
		private final FileLayer files;
		private final Clock clock;

		private final Set<UnitKey> changed = new LinkedHashSet<>();
		private final List<LostVersion> lost = new ArrayList<>();
		/**
		 * Je Einheit die Versions-IDs der eigenen Versionen, die dieser Lauf als verloren gemeldet hat oder (ohne Mitnehmen)
		 * der lokale Stand schon als verloren zeigt.
		 */
		private final Map<UnitKey, Set<String>> lostIds = new HashMap<>();
		/** Die Einheiten, deren lokal schon verlorene Versionen in {@link #lostIds} stehen. */
		private final Set<UnitKey> lostChecked = new HashSet<>();
		private final List<FullSyncResult.UploadedClaim> uploaded = new ArrayList<>();
		private final List<FeedPoll.Contested> contested = new ArrayList<>();
		/** Die temporären Dateien, die das Auflisten der Einheiten im gemeinsamen Ordner mitgeliefert hat. */
		private final List<String> sharedTemps = new ArrayList<>();
		private final Predicate<UnitKey> known;
		private final Consumer<FullSyncResult.UploadedClaim> onUpload;

		Run(VersionStore local, VersionStore shared, Ownership own, FileLayer files, Clock clock, Predicate<UnitKey> known,
				Consumer<FullSyncResult.UploadedClaim> onUpload) {
			this.local = local;
			this.shared = shared;
			this.own = own;
			this.files = files;
			this.clock = clock;
			this.known = known;
			this.onUpload = onUpload;
		}

		/** Gleicht eine Einheit ab (siehe {@link FullSync#syncUnits}). */
		void syncUnit(UnitKey unit, boolean uploadLocalClaims) throws IOException {
			List<DataPath> localFiles = dataPaths(unit, local.unitDataFiles(unit));
			if (!localFiles.isEmpty() && !known.test(unit)) {
				// an der App vorbei in den lokalen Bestand gekommen: Der Aufrufer nimmt sie auf, auch wenn nichts zu kopieren ist
				changed.add(unit);
			}
			// Im gemeinsamen Ordner ein Listenzugriff je Ordner für Dateien und temporäre Reste zugleich
			VersionStore.UnitScan sharedScan = shared.scanUnit(unit);
			sharedTemps.addAll(sharedScan.tempFiles());
			List<DataPath> sharedFiles = dataPaths(unit, sharedScan.dataFiles());
			Set<String> localPaths = paths(localFiles);
			Set<String> sharedPaths = paths(sharedFiles);

			for (DataPath file : sharedFiles) {
				if (!file.claim() && !localPaths.contains(file.path()) && copy(shared, local, file.path())) {
					changed.add(unit);
				}
			}
			for (DataPath file : localFiles) {
				if (!file.claim() && !sharedPaths.contains(file.path())) {
					copy(local, shared, file.path());
				}
			}
			for (DataPath file : sharedFiles) {
				if (file.claim() && !localPaths.contains(file.path())) {
					copyClaimInward(file);
				}
			}
			List<DataPath> onBothSides = new ArrayList<>();
			List<DataPath> missingInShared = new ArrayList<>();
			for (DataPath file : localFiles) {
				if (file.claim()) {
					(sharedPaths.contains(file.path()) ? onBothSides : missingInShared).add(file);
				}
			}
			// root zuerst: Verliert er beim Hochladen, steht seine Strecke fest, bevor die Ansprüche dahinter an der Reihe sind
			missingInShared.sort(Comparator.comparing(file -> !ROOT.equals(file.name())));
			if (!uploadLocalClaims) {
				// Sicherheitsnetz für die späte Niederlage: eigene Ansprüche, die auf beiden Seiten liegen, vergleichen
				int contestedBefore = contested.size();
				for (DataPath file : onBothSides) {
					checkOwnClaim(file);
				}
				if (contested.size() > contestedBefore) {
					// umstritten: Die Klärung macht der Aufrufer, der nächste Lauf lädt hoch, was danach noch gilt
					return;
				}
				// fehlen eigene Ansprüche im gemeinsamen Ordner (geleert, zurückgespielt, Einzelbetrieb), wieder anlegen
				for (DataPath file : missingInShared) {
					uploadClaim(file, true);
				}
			} else {
				// zuerst die Ansprüche, die auf beiden Seiten liegen: Ihre Niederlagen bestimmen, was danach nicht mehr hochgeladen wird
				for (DataPath file : onBothSides) {
					compareBoth(file);
				}
				for (DataPath file : missingInShared) {
					uploadClaim(file, false);
				}
			}
		}

		FullSyncResult result() {
			return new FullSyncResult(changed, lost, uploaded, contested, sharedTemps);
		}

		private static Set<String> paths(List<DataPath> files) {
			Set<String> paths = new HashSet<>();
			for (DataPath file : files) {
				paths.add(file.path());
			}
			return paths;
		}

		/** Kopiert die Datei, wenn sie am Ziel fehlt. {@code false}, wenn sie am Ziel schon da war oder an der Quelle fehlt. */
		private static boolean copy(VersionStore from, VersionStore to, String rel) throws IOException {
			Optional<byte[]> bytes = from.readRelative(rel);
			return bytes.isPresent() && to.createRelative(rel, bytes.get());
		}

		private void copyClaimInward(DataPath file) throws IOException {
			Optional<byte[]> bytes = shared.readRelative(file.path());
			if (parseClaim(bytes) != null && local.createRelative(file.path(), bytes.get())) {
				changed.add(file.unit());
			}
		}

		/**
		 * Ist der lokale Anspruch lesbar und der eigene und nennt der gemeinsame (lesbar) eine andere Version, ist der Platz
		 * umstritten. Unlesbare Kopien bleiben der Heilung überlassen.
		 */
		private void checkOwnClaim(DataPath file) throws IOException {
			Claim mine = parseClaim(local.readRelative(file.path()));
			if (mine == null || !own.isOwn(mine)) {
				return;
			}
			Claim theirs = parseClaim(shared.readRelative(file.path()));
			if (theirs != null && !theirs.version().equals(mine.version())) {
				contested.add(new FeedPoll.Contested(file.unit(), file.name()));
			}
		}

		private void compareBoth(DataPath file) throws IOException {
			Claim mine = parseClaim(local.readRelative(file.path()));
			Optional<byte[]> sharedBytes = shared.readRelative(file.path());
			Claim theirs = parseClaim(sharedBytes);
			if (mine != null && theirs != null && !mine.version().equals(theirs.version())) {
				settle(file, mine, sharedBytes.get(), theirs);
			}
		}

		/**
		 * Legt den lokalen Anspruch im gemeinsamen Ordner an, außer er baut auf einer verlorenen Version auf. Liegt dort
		 * inzwischen ein Anspruch auf eine andere Version, gewinnt dieser ({@link #settle}).
		 *
		 * @param ownOnly nur einen eigenen Anspruch (nach {@link Ownership#isOwn(String, VersionId)}) hochladen; dann zählen
		 *                als verloren auch die Strecken, die der lokale Stand schon als verloren zeigt
		 */
		private void uploadClaim(DataPath file, boolean ownOnly) throws IOException {
			Optional<byte[]> localBytes = local.readRelative(file.path());
			Claim mine = parseClaim(localBytes);
			if (mine == null || ownOnly && !own.isOwn(mine)) {
				return;
			}
			if (ownOnly) {
				noteLocallyLost(file.unit());
			}
			Set<String> lostHere = lostIds.getOrDefault(file.unit(), Set.of());
			if (lostHere.contains(file.name()) || lostHere.contains(mine.version().value())) {
				// baut auf einer verlorenen Version auf: dieser Anspruch hätte in der gültigen Kette keinen Platz
				return;
			}
			if (shared.createRelative(file.path(), localBytes.get())) {
				FullSyncResult.UploadedClaim claim = new FullSyncResult.UploadedClaim(file.unit(), file.name(), mine.version());
				uploaded.add(claim);
				onUpload.accept(claim);
				return;
			}
			// jemand war schneller
			Optional<byte[]> sharedBytes = shared.readRelative(file.path());
			Claim theirs = parseClaim(sharedBytes);
			if (theirs != null && !mine.version().equals(theirs.version())) {
				settle(file, mine, sharedBytes.get(), theirs);
			}
		}

		/**
		 * Trägt einmal je Einheit die eigenen Versionen in {@link #lostIds} ein, die der lokale Stand schon als verloren
		 * zeigt: Ohne Mitnehmen kann eine frühere Niederlage lokal schon geklärt sein (der Anspruch ersetzt), ohne dass
		 * dieser Lauf sie findet; die Ansprüche dahinter hätten in der gültigen Kette keinen Platz. Gemeldet werden diese
		 * Versionen hier nicht, sie sind schon bekannt.
		 */
		private void noteLocallyLost(UnitKey unit) throws IOException {
			if (!lostChecked.add(unit)) {
				return;
			}
			UnitState state = ChainResolver.resolve(local.readUnit(unit), own);
			for (List<VersionId> stretch : state.lost()) {
				Set<String> ids = lostIds.computeIfAbsent(unit, u -> new HashSet<>());
				for (VersionId id : stretch) {
					ids.add(id.value());
				}
			}
		}

		/**
		 * Der gemeinsame Anspruch gewinnt: Der lokale wird beiseitegelegt und durch ihn ersetzt, die Version des
		 * Gewinners kommt (falls nötig) nach lokal, und die eigenen Versionen auf dem verdrängten Anspruch werden als
		 * verloren gemeldet. Sobald der lokale Anspruch ersetzt ist, sind Einheit und Verlust vermerkt, auch wenn ein Schritt
		 * danach scheitert: Die Wiederholung fände beide Ansprüche gleich und meldete nichts mehr. Scheitert das Bestimmen der
		 * Strecke, steht im Verlust nur die verdrängte Version; den Rest leitet der Aufrufer aus den Dateien ab.
		 */
		private void settle(DataPath file, Claim mine, byte[] sharedBytes, Claim theirs) throws IOException {
			String fileName = file.path().substring(file.path().lastIndexOf('/') + 1);
			String stamp = LocalDateTime.now(clock).format(STAMP);
			files.replaceDamaged(local.resolve(file.path()), sharedBytes, fileName + ".verdraengt-" + stamp);
			changed.add(file.unit());
			List<VersionId> stretch = List.of(mine.version());
			try {
				copy(shared, local, file.unit().relativePath() + "/" + theirs.version() + JSON);
				stretch = stretchOf(file.unit(), mine.version());
			} finally {
				noteLost(file.unit(), stretch, theirs.version());
			}
		}

		/** Vermerkt die verlorene Strecke (ohne Doppelte) und ihre IDs für die Ansprüche, die danach an der Reihe sind. */
		private void noteLost(UnitKey unit, List<VersionId> stretch, VersionId winner) {
			LostVersion entry = new LostVersion(unit, stretch, winner);
			if (!lost.contains(entry)) {
				lost.add(entry);
			}
			Set<String> ids = lostIds.computeIfAbsent(unit, u -> new HashSet<>());
			for (VersionId id : stretch) {
				ids.add(id.value());
			}
		}

		/** Die Strecke eigener verlorener Versionen, in der die Version steht; sonst nur sie selbst. */
		private List<VersionId> stretchOf(UnitKey unit, VersionId version) throws IOException {
			UnitState state = ChainResolver.resolve(local.readUnit(unit), own);
			for (List<VersionId> stretch : state.lost()) {
				if (stretch.contains(version)) {
					return stretch;
				}
			}
			return List.of(version);
		}
	}
}
