package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Der Vollabgleich zwischen dem lokalen Bestand und dem gemeinsamen Ordner: das Sicherheitsnetz für alles, was die
 * Änderungslisten verpasst haben. Er kopiert nur fehlende Dateien und verändert oder löscht nie etwas; die einzige
 * Ausnahme ist ein lokaler Anspruch, der beim Hochladen gegen einen anderen im gemeinsamen Ordner verliert (er bleibt
 * unter neuem Namen liegen). Vorhandene Dateien werden nicht verglichen. Was nicht wie eine Versions- oder
 * Anspruchsdatei heißt, wird übergangen ({@link VersionStore#allDataFiles()}).
 */
public final class FullSync {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final String CLAIMS = "/claims";
	private static final String JSON = ".json";
	private static final String ROOT = "root";

	private FullSync() {
	}

	/**
	 * Gleicht die beiden Bestände ab.
	 * <ol>
	 * <li>Versionsdateien, die auf einer Seite fehlen, werden auf die andere kopiert.</li>
	 * <li>Anspruchsdateien, die lokal fehlen, werden aus dem gemeinsamen Ordner kopiert.</li>
	 * <li>Nur mit {@code uploadLocalClaims}: Anspruchsdateien, die im gemeinsamen Ordner fehlen, werden dort angelegt.
	 * Liegt dort schon ein Anspruch auf eine andere Version, gewinnt er: Der lokale wird unter
	 * {@code <Name>.json.verdraengt-<yyyy-MM-dd_HH-mm-ss>} beiseitegelegt, durch den gemeinsamen ersetzt, und die
	 * eigenen Versionen darauf werden als verloren gemeldet. Ein Anspruch auf dieselbe Version mit anderen Bytes
	 * (etwa anderer Zeitpunkt) ist kein Widerspruch.</li>
	 * </ol>
	 * Unlesbare Anspruchsdateien auf beiden Seiten werden übergangen (die Heilung ist Sache eines späteren Schritts).
	 *
	 * @param local             der lokale Bestand
	 * @param shared            der gemeinsame Bestand
	 * @param uploadLocalClaims ob lokale Anspruchsdateien auch in den gemeinsamen Ordner gelegt werden (Verbinden mit
	 *                          Mitnehmen); sonst fließen Ansprüche nur nach innen
	 * @param ownMachineId      die ID des eigenen Rechners (für die Ermittlung der verlorenen Versionen)
	 * @param files             die Dateischicht, über die ein verdrängter lokaler Anspruch ersetzt wird
	 * @param clock             die Uhr für den Zeitstempel im Namen verdrängter Ansprüche
	 */
	public static FullSyncResult run(VersionStore local, VersionStore shared, boolean uploadLocalClaims,
			String ownMachineId, FileLayer files, Clock clock) throws IOException {
		return run(local, shared, uploadLocalClaims, ownMachineId, files, clock, claim -> {
		});
	}

	/**
	 * Wie {@link #run(VersionStore, VersionStore, boolean, String, FileLayer, Clock)}; meldet zusätzlich jeden lokalen
	 * Anspruch, sobald er im gemeinsamen Ordner angelegt ist, auch wenn der Abgleich danach abbricht.
	 *
	 * @param uploaded erfährt jeden hochgeladenen Anspruch sofort
	 */
	public static FullSyncResult run(VersionStore local, VersionStore shared, boolean uploadLocalClaims,
			String ownMachineId, FileLayer files, Clock clock, Consumer<FullSyncResult.UploadedClaim> uploaded)
			throws IOException {
		return new Run(local, shared, ownMachineId, files, clock, uploaded).execute(uploadLocalClaims);
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
	 * Erkennt Art und Einheit einer Datei aus {@link VersionStore#allDataFiles()}. Die Versionsdatei wird zuerst
	 * geprüft: Eine Einheit darf {@code claims} heißen (etwa eine Buchung auf dem Sitz {@code claims}), und ihr Ordner
	 * ist dann nicht der Anspruchsordner einer Einheit, weil dessen Elternpfad keine Einheit ist.
	 */
	private static Optional<DataPath> classify(String path) {
		int slash = path.lastIndexOf('/');
		if (slash < 0 || !path.endsWith(JSON)) {
			return Optional.empty();
		}
		String parent = path.substring(0, slash);
		String name = path.substring(slash + 1, path.length() - JSON.length());
		Optional<UnitKey> unit = UnitKey.parse(parent);
		if (unit.isPresent() && VersionId.isValid(name)) {
			return Optional.of(new DataPath(path, unit.get(), name, false));
		}
		if (parent.endsWith(CLAIMS) && (ROOT.equals(name) || VersionId.isValid(name))) {
			unit = UnitKey.parse(parent.substring(0, parent.length() - CLAIMS.length()));
			if (unit.isPresent()) {
				return Optional.of(new DataPath(path, unit.get(), name, true));
			}
		}
		return Optional.empty();
	}

	private static List<DataPath> classifyAll(List<String> paths) {
		List<DataPath> result = new ArrayList<>();
		for (String path : paths) {
			classify(path).ifPresent(result::add);
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
		private final String ownMachineId;
		private final FileLayer files;
		private final Clock clock;

		private final Set<UnitKey> changed = new LinkedHashSet<>();
		private final List<LostVersion> lost = new ArrayList<>();
		/** Je Einheit die Versions-IDs der als verloren gemeldeten eigenen Versionen. */
		private final Map<UnitKey, Set<String>> lostIds = new HashMap<>();
		private final List<FullSyncResult.UploadedClaim> uploaded = new ArrayList<>();
		private final Consumer<FullSyncResult.UploadedClaim> onUpload;

		Run(VersionStore local, VersionStore shared, String ownMachineId, FileLayer files, Clock clock,
				Consumer<FullSyncResult.UploadedClaim> onUpload) {
			this.local = local;
			this.shared = shared;
			this.ownMachineId = ownMachineId;
			this.files = files;
			this.clock = clock;
			this.onUpload = onUpload;
		}

		FullSyncResult execute(boolean uploadLocalClaims) throws IOException {
			List<DataPath> localFiles = classifyAll(local.allDataFiles());
			List<DataPath> sharedFiles = classifyAll(shared.allDataFiles());
			Set<String> localPaths = paths(localFiles);
			Set<String> sharedPaths = paths(sharedFiles);

			for (DataPath file : sharedFiles) {
				if (!file.claim() && !localPaths.contains(file.path()) && copy(shared, local, file.path())) {
					changed.add(file.unit());
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
			if (uploadLocalClaims) {
				// zuerst die Ansprüche, die auf beiden Seiten liegen: Ihre Niederlagen bestimmen, was danach nicht mehr hochgeladen wird
				for (DataPath file : localFiles) {
					if (file.claim() && sharedPaths.contains(file.path())) {
						compareBoth(file);
					}
				}
				for (DataPath file : localFiles) {
					if (file.claim() && !sharedPaths.contains(file.path())) {
						uploadClaim(file);
					}
				}
			}
			return new FullSyncResult(changed, lost, uploaded);
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

		private void compareBoth(DataPath file) throws IOException {
			Claim mine = parseClaim(local.readRelative(file.path()));
			Optional<byte[]> sharedBytes = shared.readRelative(file.path());
			Claim theirs = parseClaim(sharedBytes);
			if (mine != null && theirs != null && !mine.version().equals(theirs.version())) {
				settle(file, mine, sharedBytes.get(), theirs);
			}
		}

		private void uploadClaim(DataPath file) throws IOException {
			Optional<byte[]> localBytes = local.readRelative(file.path());
			Claim mine = parseClaim(localBytes);
			if (mine == null) {
				return;
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
		 * Der gemeinsame Anspruch gewinnt: Der lokale wird beiseitegelegt und durch ihn ersetzt, die Version des
		 * Gewinners kommt (falls nötig) nach lokal, und die eigenen Versionen auf dem verdrängten Anspruch werden als
		 * verloren gemeldet.
		 */
		private void settle(DataPath file, Claim mine, byte[] sharedBytes, Claim theirs) throws IOException {
			String fileName = file.path().substring(file.path().lastIndexOf('/') + 1);
			String stamp = LocalDateTime.now(clock).format(STAMP);
			files.replaceDamaged(local.resolve(file.path()), sharedBytes, fileName + ".verdraengt-" + stamp);
			copy(shared, local, file.unit().relativePath() + "/" + theirs.version() + JSON);
			changed.add(file.unit());

			List<VersionId> stretch = stretchOf(file.unit(), mine.version());
			LostVersion entry = new LostVersion(file.unit(), stretch, theirs.version());
			if (!lost.contains(entry)) {
				lost.add(entry);
			}
			Set<String> ids = lostIds.computeIfAbsent(file.unit(), u -> new HashSet<>());
			for (VersionId id : stretch) {
				ids.add(id.value());
			}
		}

		/** Die Strecke eigener verlorener Versionen, in der die Version steht; sonst nur sie selbst. */
		private List<VersionId> stretchOf(UnitKey unit, VersionId version) throws IOException {
			UnitState state = ChainResolver.resolve(local.readUnit(unit), ownMachineId);
			for (List<VersionId> stretch : state.lost()) {
				if (stretch.contains(version)) {
					return stretch;
				}
			}
			return List.of(version);
		}
	}
}
