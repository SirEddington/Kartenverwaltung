package de.eltviller_carneval_verein.karten.update;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Der Takt für Versionsabgleich und Update über den gemeinsamen Ordner (KARV-132): 30 s nach dem Start, auf Wunsch
 * (etwa nach dem Verbinden oder einer Update-Prüfung) und dann alle 10 Minuten.
 * <ul>
 * <li>Jede Kasse mit Version schreibt ihren Eintrag und liest die fremden (auch Linux, Windows-ZIP, Entwicklung).</li>
 * <li>Nur im Windows-Paket: Installer suchen, aufräumen, gegen die Quelle abwägen (mit höchstens einer Nachprüfung je
 * Kandidat und Lauf) und den Download anstoßen.</li>
 * </ul>
 * Lesen und Installieren brauchen nur einen vorhandenen Ordner; der Download prüft die Erreichbarkeit selbst. Jeder
 * Schritt fängt seine Fehler; der Takt läuft weiter.
 */
public final class FolderWatch {

	public static final Duration FIRST = Duration.ofSeconds(30);
	public static final Duration INTERVAL = Duration.ofMinutes(10);

	private static final Logger LOG = Logger.getLogger(FolderWatch.class.getName());

	private final Optional<Installation> inst;
	private final Optional<AppVersion> own;
	private final String fsname;
	private final SharedFolderAccess access;
	private final StationRegistry registry;
	private final Optional<FolderUpdates> updates;
	private final Optional<FolderDownloader> downloader;
	private final Supplier<Optional<UpdateSuccess>> lastSuccess;
	private final Supplier<UpdateState> checkState;
	private final Runnable checkNow;
	private final Scheduler scheduler;
	private final Executor ui;
	private final Clock clock;
	private final Map<String, Instant> recheckFor = new HashMap<>();
	private volatile FolderState state = FolderState.empty();
	private volatile Consumer<FolderState> listener = s -> {
	};
	private volatile boolean closed;

	/**
	 * @param own        die eigene Version; leer in der Entwicklung ohne {@code app.version} (dann kein Eintrag)
	 * @param updates    nur im Windows-Paket
	 * @param downloader nur im Windows-Paket
	 * @param checkState der Zustand der Update-Prüfung (nicht die FX-Property)
	 * @param checkNow   stößt eine Update-Prüfung an
	 */
	public FolderWatch(Optional<Installation> inst, Optional<AppVersion> own, String fsname, SharedFolderAccess access,
			StationRegistry registry, Optional<FolderUpdates> updates, Optional<FolderDownloader> downloader,
			Supplier<Optional<UpdateSuccess>> lastSuccess, Supplier<UpdateState> checkState, Runnable checkNow,
			Scheduler scheduler, Executor ui, Clock clock) {
		this.inst = inst;
		this.own = own;
		this.fsname = fsname == null ? "" : fsname;
		this.access = access;
		this.registry = registry;
		this.updates = updates;
		this.downloader = downloader;
		this.lastSuccess = lastSuccess;
		this.checkState = checkState;
		this.checkNow = checkNow;
		this.scheduler = scheduler;
		this.ui = ui;
		this.clock = clock;
		downloader.ifPresent(d -> {
			d.setOnChange(this::rescanNow);
			d.setRecheck(checkNow);
		});
	}

	/** Setzt den einen Listener; er läuft über {@code ui}. */
	public void setListener(Consumer<FolderState> listener) {
		this.listener = listener;
	}

	/** Startet den Takt. */
	public void start() {
		scheduler.scheduleWithFixedDelay(this::tick, FIRST, INTERVAL);
	}

	/** Durchsucht sofort neu (im Hintergrund). */
	public void rescanNow() {
		if (closed) {
			return;
		}
		try {
			scheduler.execute(this::tick);
		} catch (RejectedExecutionException e) {
			LOG.log(Level.FINE, "Durchsuchen des gemeinsamen Ordners nicht mehr möglich", e);
		}
	}

	/** Der zuletzt ermittelte Stand. */
	public FolderState state() {
		return state;
	}

	/** „Jetzt prüfen“: hebt die Sperre nach einem gescheiterten Download auf und durchsucht neu. */
	public void retryDownloadNow() {
		downloader.ifPresent(FolderDownloader::retryNow);
		rescanNow();
	}

	/** Beendet Takt und Download. */
	public void close() {
		closed = true;
		downloader.ifPresent(FolderDownloader::stop);
		scheduler.close();
	}

	private synchronized void tick() {
		if (closed) {
			return;
		}
		try {
			publish(compute());
		} catch (Throwable e) {
			LOG.log(Level.WARNING, "Durchsuchen des gemeinsamen Ordners unerwartet abgebrochen", e);
		}
	}

	private FolderState compute() {
		Optional<Path> configured = access.folder();
		if (configured.isEmpty() || !access.isPresent(configured.get())) {
			return FolderState.empty();
		}
		Path folder = configured.get();
		if (own.isPresent()) {
			try {
				registry.writeOwn(folder, own.get(), fsname, inst.map(Installation::plattform).orElse(Plattform.SONSTIGE));
			} catch (Exception e) {
				LOG.log(Level.WARNING, "Eigener Kasseneintrag ließ sich nicht schreiben", e);
			}
		}
		List<StationEntry> stations = registry.readAll(folder);
		Optional<StationEntry> newer = own
				.flatMap(o -> StationRegistry.newestForeign(stations, access.machineId(), fsname, o));
		Optional<FolderOffer> offer = Optional.empty();
		Optional<AppVersion> withdrawn = Optional.empty();
		Optional<AppVersion> reinstall = Optional.empty();
		boolean paket = inst.map(i -> i.plattform() == Plattform.WINDOWS_PAKET && i.packageFamilyName().isPresent())
				.orElse(false);
		if (paket && own.isPresent() && updates.isPresent()) {
			String family = inst.get().packageFamilyName().get();
			FolderUpdates.Scan scan = updates.get().scan(folder, family, own.get());
			reinstall = scan.otherFamilyNewer();
			guard("Aufräumen", () -> updates.get().cleanup(folder, family));
			downloader.ifPresent(d -> guard("Aufräumen der Ladedateien", () -> d.cleanupClaims(folder)));
			Optional<UpdateSuccess> success = Optional.empty();
			try {
				success = lastSuccess.get();
				FolderDecision.Result result = decide(scan.candidate(), success);
				if (result == FolderDecision.Result.FOLDER) {
					offer = scan.candidate();
				} else if (result == FolderDecision.Result.WITHDRAWN) {
					withdrawn = scan.candidate().map(FolderOffer::version);
				}
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Abgleich mit der Update-Quelle scheiterte", e);
			}
			Optional<UpdateSuccess> s = success;
			downloader.ifPresent(d -> s.ifPresent(
					src -> guard("Download anstoßen", () -> d.offer(folder, src, own.get(), scan.highestValidOwn()))));
		}
		Optional<AppVersion> loading = downloader.flatMap(FolderDownloader::loading);
		return new FolderState(stations, newer, offer, withdrawn, reinstall, loading);
	}

	/** Entscheidet und stößt im Fall „unklar“ höchstens einmal je Kandidat eine Prüfung an. */
	private FolderDecision.Result decide(Optional<FolderOffer> candidate, Optional<UpdateSuccess> success) {
		String key = candidate.map(c -> c.file() + "|" + c.version() + "|" + c.firstSeen()).orElse("");
		recheckFor.keySet().retainAll(List.of(key));
		Instant now = clock.instant();
		FolderDecision.Result result = FolderDecision.decide(candidate, success, checkState.get(), inst,
				Optional.ofNullable(recheckFor.get(key)), now);
		if (result == FolderDecision.Result.UNCLEAR && !recheckFor.containsKey(key)) {
			recheckFor.put(key, now);
			LOG.info("Installer im gemeinsamen Ordner ist neuer als die letzte Prüfung: Update-Prüfung angestoßen");
			checkNow.run();
		}
		return result;
	}

	private static void guard(String what, Runnable step) {
		try {
			step.run();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, what + " im gemeinsamen Ordner scheiterte", e);
		}
	}

	private void publish(FolderState next) {
		state = next;
		Consumer<FolderState> l = listener;
		ui.execute(() -> {
			try {
				l.accept(next);
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Listener des gemeinsamen Ordners warf eine Ausnahme", e);
			}
		});
	}
}
