package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * Lädt den Installer der aktuellen Version im Hintergrund in {@code updates/} des gemeinsamen Ordners (KARV-132).
 * <ol>
 * <li>Abstimmung: eigene Ladedatei anlegen, 2 bis 5 Minuten warten, dann gewinnt die kleinste Rechner-ID; eine frische
 * „fertig“-Ladedatei verhindert das Laden.</li>
 * <li>{@code .appinstaller} frisch holen, Version und Zielname prüfen, {@code .msix} in eine tmp-Datei laden (SHA-256
 * beim Schreiben), Manifest und ZIP prüfen.</li>
 * <li>Ablegen nur unter freiem Namen ({@code Files.move} ohne Optionen), dann Beschreibung ({@code CREATE_NEW}), dann
 * die eigene Ladedatei auf „fertig“; sie bleibt stehen.</li>
 * </ol>
 * Nie mehr als ein Ablauf; nach einem Fehler frühestens nach einer Stunde wieder (außer nach {@link #retryNow()}).
 * {@link #offer} kehrt sofort zurück; die Arbeit läuft über {@link Tasks}.
 */
public final class FolderDownloader {

	/** Wo die Arbeit läuft: sofort im Hintergrund oder nach einer Wartezeit. */
	public interface Tasks {
		void run(Runnable task);

		void runLater(Runnable task, Duration delay);

		void shutdown();

		/** Über einen eigenen {@link ScheduledExecutorService}. */
		static Tasks of(ScheduledExecutorService executor) {
			return new Tasks() {
				@Override
				public void run(Runnable task) {
					executor.execute(task);
				}

				@Override
				public void runLater(Runnable task, Duration delay) {
					executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
				}

				@Override
				public void shutdown() {
					executor.shutdownNow();
				}
			};
		}
	}

	/** Freier Platz auf dem Laufwerk eines Ordners. */
	@FunctionalInterface
	interface FreeSpace {
		long usable(Path dir) throws IOException;
	}

	/** Ein Eingriff kurz vor dem Ablegen (nur für Tests). */
	@FunctionalInterface
	interface BeforeMove {
		void accept(Path target) throws IOException;
	}

	private enum Phase {
		IDLE, PENDING, WAITING, LOADING
	}

	/** Ein Abbruch mit Grund fürs Protokoll; zählt als Fehler (Sperre 1 h). */
	private static final class Failure extends Exception {
		private static final long serialVersionUID = 1L;

		Failure(String message) {
			super(message, null, false, false);
		}
	}

	/** Ein Rückzug ohne Fehler (andere Kasse lädt, Datei wächst noch …). */
	private static final class Withdraw extends Exception {
		private static final long serialVersionUID = 1L;

		Withdraw(String message) {
			super(message, null, false, false);
		}
	}

	private record Key(long size, FileTime modified) {
	}

	private record Stable(Key key, Instant since) {
	}

	static final Duration RETRY_AFTER = Duration.ofHours(1);
	static final Duration STABLE_AFTER = Duration.ofHours(1);
	static final long RESERVE_BYTES = 500L * 1024 * 1024;
	private static final int MAX_APPINSTALLER_BYTES = 65536;
	private static final Logger LOG = Logger.getLogger(FolderDownloader.class.getName());

	private final Installation inst;
	private final SharedFolderAccess access;
	private final OwnFiles own;
	private final LoadClaims claims;
	private final Clock clock;
	private final RandomGenerator random;
	private final Tasks tasks;
	private final Duration connect;
	private final Duration read;
	private final Duration total;
	private final long maxBytes;
	private final Map<Path, Stable> stable = new ConcurrentHashMap<>();
	private final Map<Path, Instant> doneClaims = new ConcurrentHashMap<>();
	private volatile FreeSpace freeSpace = dir -> Files.getFileStore(dir).getUsableSpace();
	private volatile BeforeMove beforeMove = target -> {
	};
	private volatile Runnable onChange = () -> {
	};
	private volatile Phase phase = Phase.IDLE;
	private volatile AppVersion loading;
	private volatile Instant failedAt;
	private volatile boolean stopped;
	private volatile Path claim;
	private volatile Path tmp;

	/**
	 * @param connect  Zeitlimit für den Verbindungsaufbau
	 * @param read     Zeitlimit je gelesenem Block
	 * @param total    Gesamtfrist eines Downloads
	 * @param maxBytes größte {@code .msix}
	 */
	public FolderDownloader(Installation inst, SharedFolderAccess access, OwnFiles own, Clock clock,
			RandomGenerator random, Tasks tasks, Duration connect, Duration read, Duration total, long maxBytes) {
		this(inst, access, own, new LoadClaims(clock), clock, random, tasks, connect, read, total, maxBytes);
	}

	FolderDownloader(Installation inst, SharedFolderAccess access, OwnFiles own, LoadClaims claims, Clock clock,
			RandomGenerator random, Tasks tasks, Duration connect, Duration read, Duration total, long maxBytes) {
		this.inst = inst;
		this.access = access;
		this.own = own;
		this.claims = claims;
		this.clock = clock;
		this.random = random;
		this.tasks = tasks;
		this.connect = connect;
		this.read = read;
		this.total = total;
		this.maxBytes = maxBytes;
	}

	/** Wird nach jeder Änderung von {@link #loading()} gerufen (auf dem Download-Thread). */
	public void setOnChange(Runnable onChange) {
		this.onChange = onChange;
	}

	void setFreeSpace(FreeSpace freeSpace) {
		this.freeSpace = freeSpace;
	}

	void setBeforeMove(BeforeMove beforeMove) {
		this.beforeMove = beforeMove;
	}

	/** Die Version, die gerade abgestimmt oder geladen wird. */
	public Optional<AppVersion> loading() {
		return Optional.ofNullable(loading);
	}

	/** Hebt die Sperre nach einem Fehler auf („Jetzt prüfen“). */
	public void retryNow() {
		failedAt = null;
	}

	/**
	 * Startet einen Ablauf, wenn die Quelle eine Version V ≥ der eigenen in derselben Familie meldet, im Ordner noch kein
	 * gültiger Installer ≥ V liegt und gerade keiner läuft. Kehrt sofort zurück.
	 *
	 * @param highestValidOwn der höchste gültige Installer der eigenen Familie im Ordner (aus dem letzten Durchsuchen)
	 */
	public void offer(Path folder, UpdateSuccess source, AppVersion own, Optional<AppVersion> highestValidOwn) {
		if (stopped || phase != Phase.IDLE) {
			return;
		}
		Instant failed = failedAt;
		if (failed != null && clock.instant().isBefore(failed.plus(RETRY_AFTER))) {
			return;
		}
		AppVersion v = source.info().version();
		if (inst.plattform() != Plattform.WINDOWS_PAKET || inst.packageFamilyName().isEmpty() || v.compareTo(own) < 0
				|| !inst.canUpdateInPlace(source.info()) || highestValidOwn.map(h -> h.compareTo(v) >= 0).orElse(false)) {
			return;
		}
		phase = Phase.PENDING;
		failedAt = null;
		tasks.run(() -> guarded(() -> begin(folder, v)));
	}

	/**
	 * Räumt Ladedateien auf: fremde nach einem Tag, eigene aus früheren Läufen oder unter früheren Rechner-IDs sofort, die
	 * eigene „fertig“-Datei dieses Laufs nach drei Stunden; die laufende nie.
	 */
	public void cleanupClaims(Path folder) {
		Path dir = folder.resolve(UpdatesDir.UPDATES);
		Instant now = clock.instant();
		for (LoadClaims.Claim c : claims.read(dir, null)) {
			boolean mine = c.machineId().equals(access.machineId()) || access.isOwnTempTag(c.machineId());
			if (mine) {
				if (own.contains(c.file())) {
					Instant done = doneClaims.get(c.file());
					if (done != null && Duration.between(done, now).compareTo(LoadClaims.DONE_FRESH) >= 0) {
						own.remove(c.file());
						doneClaims.remove(c.file());
						delete(c.file());
					}
				} else {
					delete(c.file());
				}
			} else if (claims.isExpired(c)) {
				delete(c.file());
			}
		}
	}

	/** Bricht ab und löscht eine eigene unfertige Ladedatei und tmp-Datei (höchstens 2 s, auf einem Daemon-Thread). */
	public void stop() {
		stopped = true;
		tasks.shutdown();
		if (phase == Phase.IDLE) {
			return;
		}
		Path c = claim;
		Path t = tmp;
		Thread cleaner = new Thread(() -> {
			delete(c);
			delete(t);
		}, "update-ordner-stopp");
		cleaner.setDaemon(true);
		cleaner.start();
		try {
			cleaner.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private interface Step {
		void run() throws Exception;
	}

	private void guarded(Step step) {
		try {
			step.run();
		} catch (Withdraw w) {
			LOG.info("Download in den gemeinsamen Ordner zurückgestellt: " + w.getMessage());
			withdraw();
		} catch (Failure f) {
			fail(f.getMessage(), null);
		} catch (Throwable e) {
			fail("unerwartet", e);
		}
	}

	private void begin(Path folder, AppVersion v) throws Exception {
		if (stopped) {
			phase = Phase.IDLE;
			return;
		}
		if (!access.isReachable(folder)) {
			throw new Withdraw("gemeinsamer Ordner nicht erreichbar");
		}
		Path dir = UpdatesDir.ensure(folder).orElseThrow(() -> new Withdraw("Ordner updates fehlt"));
		String quad = quad(v);
		String me = access.machineId();
		for (LoadClaims.Claim c : claims.read(dir, quad)) {
			if (!c.machineId().equals(me) && claims.isFresh(c)) {
				throw new Withdraw("Kasse " + c.machineId() + " lädt schon");
			}
		}
		Path path = dir.resolve(UpdatesDir.claimName(quad, me));
		own.add(path);
		claim = path;
		byte[] json = LoadClaims.json(me, access.machineName(), clock.instant(), null);
		try {
			Files.write(path, json, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		} catch (FileAlreadyExistsException e) {
			SharedFileReplace.write(path, json, me, own);
		}
		loading = v;
		phase = Phase.WAITING;
		changed();
		Duration wait = Duration.ofMinutes(2).plusMillis(random.nextLong(Duration.ofMinutes(3).toMillis() + 1));
		LOG.info("Download von " + v.display() + " in den gemeinsamen Ordner: Abstimmung, Wartezeit " + wait);
		tasks.runLater(() -> guarded(() -> afterWait(folder, dir, v, quad, path)), wait);
	}

	private void afterWait(Path folder, Path dir, AppVersion v, String quad, Path ownClaim) throws Exception {
		if (stopped) {
			return;
		}
		List<LoadClaims.Claim> all = claims.read(dir, quad);
		String me = access.machineId();
		String smallest = me;
		for (LoadClaims.Claim c : all) {
			if (c.file().equals(ownClaim) || !claims.isFresh(c)) {
				continue;
			}
			if (c.done()) {
				throw new Withdraw("Kasse " + c.machineId() + " hat schon geladen");
			}
			if (c.machineId().compareTo(smallest) < 0) {
				smallest = c.machineId();
			}
		}
		if (!smallest.equals(me)) {
			throw new Withdraw("Kasse " + smallest + " lädt (kleinere Rechner-ID)");
		}
		download(folder, dir, v, quad, ownClaim);
	}

	private void download(Path folder, Path dir, AppVersion v, String quad, Path ownClaim) throws Exception {
		phase = Phase.LOADING;
		Instant deadline = clock.instant().plus(total);
		AppInstallerFile ai = fetchAppInstaller();
		if (ai.version().compareTo(v) != 0) {
			// die Quelle hat sich seit der Prüfung geändert (z. B. während der Wartezeit): kein Fehler, das Neu-Durchsuchen
			// nach der Rückmeldung bietet die aktuelle Version an
			throw new Withdraw("Die .appinstaller-Datei nennt " + ai.version().display() + " statt " + v.display());
		}
		String name = targetName(ai.uri());
		Path target = dir.resolve(name);
		if (Files.exists(target)) {
			if (isValidInstaller(target, ai)) {
				throw new Withdraw("gültiger Installer liegt schon da: " + name);
			}
			if (!isStable(target)) {
				throw new Withdraw("Datei unter dem Zielnamen ändert sich noch: " + name);
			}
			LOG.info("Ungültige Datei unter dem Zielnamen wird ersetzt: " + target);
			delete(target);
			delete(dir.resolve(UpdatesDir.descriptionOf(name)));
		}
		String family = inst.packageFamilyName().orElseThrow();
		HttpURLConnection c = HttpGet.open(ai.uri(), connect, read);
		long n = 0;
		MessageDigest md = FolderUpdates.sha256();
		Path t = dir.resolve(UpdatesDir.tempName(access.machineId()));
		try {
			long length = c.getContentLengthLong();
			if (length > maxBytes) {
				throw new Failure("Installer größer als erlaubt: " + length + " Bytes");
			}
			long need = (length >= 0 ? length : maxBytes) * 2 + RESERVE_BYTES;
			if (freeSpace.usable(dir) < need) {
				throw new Failure("zu wenig freier Platz im gemeinsamen Ordner");
			}
			own.add(t);
			tmp = t;
			try (InputStream in = c.getInputStream();
					OutputStream out = Files.newOutputStream(t, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				byte[] buf = new byte[1 << 16];
				for (int k; (k = in.read(buf)) >= 0;) {
					n += k;
					if (n > maxBytes) {
						throw new Failure("Installer größer als erlaubt (ohne Längenangabe)");
					}
					if (stopped) {
						throw new Failure("App wird beendet");
					}
					if (clock.instant().isAfter(deadline)) {
						throw new Failure("Download dauert länger als " + total);
					}
					if (!access.folder().equals(Optional.of(folder))) {
						throw new Failure("gemeinsamer Ordner wurde gewechselt");
					}
					out.write(buf, 0, k);
					md.update(buf, 0, k);
				}
			}
			if (length >= 0 && n != length) {
				throw new Failure("Download unvollständig: " + n + " von " + length + " Bytes");
			}
		} finally {
			c.disconnect();
		}
		MsixManifest m = MsixManifest.read(t);
		MsixManifest.verifyComplete(t);
		if (!m.name().equals(ai.name()) || !m.publisher().equals(ai.publisher()) || m.version().compareTo(v) != 0
				|| !m.packageFamilyName().equals(family) || !"x64".equalsIgnoreCase(m.architecture())) {
			throw new Failure("Manifest passt nicht zur .appinstaller-Datei oder zur eigenen Familie");
		}
		beforeMove.accept(target);
		if (!access.folder().equals(Optional.of(folder))) {
			throw new Failure("gemeinsamer Ordner wurde gewechselt");
		}
		String sha = HexFormat.of().formatHex(md.digest());
		try {
			Files.move(t, target);
		} catch (FileAlreadyExistsException e) {
			delete(t);
			if (Files.size(target) == n && FolderUpdates.sha256Hex(target).equalsIgnoreCase(sha)) {
				writeDescription(dir, name, quad, family, sha, n);
				markDone(ownClaim);
				return;
			}
			throw new Withdraw("eine andere Kasse hat unter " + name + " eine andere Datei abgelegt");
		} finally {
			own.remove(t);
			tmp = null;
		}
		writeDescription(dir, name, quad, family, sha, n);
		LOG.info("Installer " + v.display() + " in den gemeinsamen Ordner gelegt: " + target + " (" + n + " Bytes)");
		markDone(ownClaim);
	}

	private AppInstallerFile fetchAppInstaller() throws IOException {
		HttpURLConnection c = HttpGet.open(inst.appInstallerUri(), connect, read);
		byte[] body;
		try (InputStream in = c.getInputStream()) {
			body = in.readNBytes(MAX_APPINSTALLER_BYTES + 1);
		} finally {
			c.disconnect();
		}
		if (body.length > MAX_APPINSTALLER_BYTES) {
			throw new InvalidUpdateDataException(".appinstaller-Datei zu groß");
		}
		return AppInstallerFile.parse(body);
	}

	/** Der letzte Pfadteil der Adresse, dekodiert; nur ein strenger Installer-Name ohne Pfadtrenner. */
	static String targetName(URI uri) throws Failure {
		String raw = uri.getRawPath();
		if (raw == null || raw.isEmpty()) {
			throw new Failure("Adresse ohne Dateinamen: " + uri);
		}
		String seg = raw.substring(raw.lastIndexOf('/') + 1);
		String name;
		try {
			name = URLDecoder.decode(seg.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			throw new Failure("Unlesbarer Dateiname in " + uri);
		}
		if (name.contains("/") || name.contains("\\") || name.contains("..") || !UpdatesDir.isInstaller(name)) {
			throw new Failure("Unzulässiger Dateiname: " + name);
		}
		return name;
	}

	private static boolean isValidInstaller(Path file, AppInstallerFile ai) {
		try {
			MsixManifest m = MsixManifest.read(file);
			if (m.version().compareTo(ai.version()) != 0 || !m.name().equals(ai.name())
					|| !m.publisher().equals(ai.publisher())) {
				return false;
			}
			MsixManifest.verifyComplete(file);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** Ob diese Kasse die Datei seit mindestens einer Stunde unverändert sieht. */
	private boolean isStable(Path file) throws IOException {
		Key key = new Key(Files.size(file), Files.getLastModifiedTime(file));
		Instant now = clock.instant();
		Stable s = stable.compute(file, (k, old) -> old != null && old.key().equals(key) ? old : new Stable(key, now));
		return Duration.between(s.since(), now).compareTo(STABLE_AFTER) >= 0;
	}

	private static void writeDescription(Path dir, String name, String quad, String family, String sha, long size)
			throws IOException {
		try {
			Files.write(dir.resolve(UpdatesDir.descriptionOf(name)), FolderUpdates.descriptionJson(quad, family, sha, size),
					StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		} catch (FileAlreadyExistsException e) {
			// eine andere Kasse war schneller; die Beschreibung ist bytegleich
		}
	}

	private void markDone(Path ownClaim) throws IOException {
		Instant now = clock.instant();
		Optional<LoadClaims.Claim> current = claims.read(ownClaim.getParent(), null).stream()
				.filter(c -> c.file().equals(ownClaim)).findFirst();
		Instant angelegt = current.flatMap(LoadClaims.Claim::angelegt).orElse(now);
		SharedFileReplace.write(ownClaim, LoadClaims.json(access.machineId(), access.machineName(), angelegt, now),
				access.machineId(), own);
		doneClaims.put(ownClaim, now);
		claim = null;
		loading = null;
		phase = Phase.IDLE;
		changed();
	}

	private void withdraw() {
		Path c = claim;
		if (c != null) {
			delete(c);
			own.remove(c);
		}
		claim = null;
		loading = null;
		phase = Phase.IDLE;
		changed();
	}

	private void fail(String message, Throwable e) {
		LOG.log(Level.WARNING, "Download in den gemeinsamen Ordner gescheitert: " + message, e);
		Path t = tmp;
		if (t != null) {
			delete(t);
			own.remove(t);
			tmp = null;
		}
		failedAt = clock.instant();
		withdraw();
	}

	private void changed() {
		try {
			onChange.run();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Rückmeldung des Downloads scheiterte", e);
		}
	}

	private static String quad(AppVersion v) {
		return v.version() + "." + v.revision();
	}

	private static void delete(Path p) {
		if (p == null) {
			return;
		}
		try {
			Files.deleteIfExists(p);
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Ließ sich nicht löschen: " + p, e);
		}
	}
}
