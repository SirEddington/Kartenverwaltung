package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.AppPaths;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;

/**
 * Der Versionsspeicher im Betrieb: lokales Speichern, Hochladen in den gemeinsamen Ordner, Abholen fremder Änderungen,
 * Zustände und Rückmeldungen. Die Dateilogik steckt in den Bausteinen ({@link Uploader}, {@link FeedReader},
 * {@link FullSync}, {@link DamageRepair}, {@link ChainResolver}); diese Klasse entscheidet, wann und auf welchem Thread
 * sie laufen, und meldet das Ergebnis.
 * <p>
 * <b>Regel:</b> Wer zuerst hochlädt, gewinnt. Jede Änderung wird zuerst als neue, unveränderliche Versionsdatei im
 * lokalen Bestand geschrieben. Im Einzelbetrieb (kein gemeinsamer Ordner) beansprucht {@link #append} sie sofort lokal;
 * sie gilt damit. Im gemeinsamen Betrieb ist sie vorläufig, bis sie hochgeladen ist: je Speichervorgang ({@code saveId})
 * ein Auftrag, in dieser Reihenfolge: <b>Versionsdatei → Änderungsliste → Anspruchsdatei</b>. Ein Anspruch ohne
 * vorherigen Listeneintrag kann so nicht entstehen. Wer den Anspruch nicht bekommt, hat verloren; die Version bleibt
 * liegen und wird mit {@link SyncListener#lost} gemeldet.
 * <p>
 * <b>Takt</b> (alle 2 Sekunden, {@link #tick()}): Erreichbarkeit prüfen (nur nach einem Fehler), Aufträge fortsetzen,
 * alle 30 Sekunden neue Rechner suchen, die Änderungslisten der anderen lesen, alle 10 Minuten (und beim Start, nach
 * der Rückkehr aus {@link SyncState#OFFLINE}, auf Wunsch) einen Vollabgleich, die eigenen Ansprüche der letzten 15
 * Minuten im gemeinsamen Ordner nachlesen (späte Niederlage bei einem Sync-Client; nach einem Neustart auch die aus den
 * lokalen Anspruchsdateien, beim Verbinden mit Mitnehmen die dabei hochgeladenen; höchstens 150 je Takt), vorgemerkte
 * beschädigte Einheiten aus der Kopie im gemeinsamen Ordner heilen und beschädigte Kopien dort aus der guten lokalen.
 * Trifft ein fremder Listeneintrag einen Platz, dessen lokaler Anspruch abweicht, wird sofort verglichen: So fällt eine
 * späte Niederlage auch nach Ablauf der Beobachtung auf. Deshalb überspringt auch der Vollabgleich keine Liste: Er
 * lässt die Positionen stehen, und jede Liste wird gelesen (nach einem Verlust von {@code sync-state.json} oder für einen
 * neuen Rechner einmal der ganze Rückstand, 200 Listen je Takt).
 * <p>
 * <b>Verluste</b> werden nicht nur aus Ereignissen gemeldet, sondern aus dem Zustand abgeleitet: Für jede neu
 * aufgelöste Einheit (beim ersten Vollabgleich alle) wird jede verlorene Strecke gemeldet, deren Versionen in diesem
 * Programmlauf noch nicht gemeldet wurden. So geht kein Verlust durch einen Absturz verloren, und keiner kommt doppelt.
 * <p>
 * <b>Threads:</b>
 * <ul>
 * <li>Jeder Zugriff auf den gemeinsamen Ordner läuft im {@code worker}, in der Anwendung ein einzelner Daemon-Thread,
 * der auch den Takt ausführt; auch die Heilung beschädigter Dateien aus der Kopie dort läuft im Takt. Die Oberfläche
 * wartet nie darauf, mit zwei Ausnahmen: {@link #startup} prüft die Erreichbarkeit (höchstens 5 Sekunden) und lädt
 * ausstehende Versionen hoch, bevor das Hauptmenü erscheint; {@link #needsConnectDecision} liest den gemeinsamen
 * Ordner.</li>
 * <li>{@link #append} und die Abfragen arbeiten nur auf dem lokalen Bestand und laufen auf dem Thread des Aufrufers.</li>
 * <li>Zustand ({@link #syncState()}, {@link #lastSync()}) und alle Rückrufe kommen über den {@code ui}-Executor an, in
 * der Anwendung {@code Platform::runLater}.</li>
 * </ul>
 */
public final class SyncService {

	private static final Logger LOGGER = Logger.getLogger(SyncService.class.getName());

	/** Abstand der Takte. */
	static final Duration TICK = Duration.ofSeconds(2);
	/** So oft wird nach neuen Rechnern gesucht. */
	static final Duration DISCOVERY_INTERVAL = Duration.ofSeconds(30);
	/** So oft läuft ein Vollabgleich. */
	static final Duration FULL_SYNC_INTERVAL = Duration.ofMinutes(10);
	/** So lange werden eigene gewonnene Ansprüche auf eine späte Niederlage geprüft. */
	static final Duration LATE_DEFEAT_WINDOW = Duration.ofMinutes(15);
	/** So viele beobachtete Ansprüche liest ein Takt höchstens, die am längsten nicht geprüften zuerst. */
	static final int LATE_DEFEAT_BUDGET = 150;
	/** Eigene temporäre Dateien, die älter sind, räumt der Start weg. */
	static final Duration TEMP_AGE = Duration.ofDays(1);

	private static final int MAX_REPAIRS = 10;
	private static final int MAX_ID_ATTEMPTS = 100;
	private static final String DATA = "data";
	private static final String SYNC_STATE = "sync-state.json";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private static SyncService instance;
	/** Die Sperre gegen einen zweiten Start; gehalten bis zum Programmende. */
	@SuppressWarnings("unused")
	private static InstanceLock instanceLock;

	/** Eine Aktion mit Dateizugriff. */
	private interface IoAction {
		void run() throws IOException;
	}

	/** Ein beobachteter eigener Anspruch: wann er zuletzt geprüft wurde und als wievielter er dazukam. */
	private static final class Watched {
		final LocalScan.OwnClaim claim;
		final long order;
		/** {@code null}, solange er nie geprüft wurde. */
		Instant lastChecked;

		Watched(LocalScan.OwnClaim claim, long order) {
			this.claim = claim;
			this.order = order;
		}
	}

	/**
	 * Die Verbindung zu einem gemeinsamen Ordner. Wird der Ordner gewechselt oder getrennt, entsteht eine neue (oder
	 * keine); was für eine alte Verbindung noch läuft, zählt nicht mehr. Leser und Schreiber benutzt nur der Thread, der
	 * die Arbeitssperre hält.
	 */
	private static final class Link {
		final String text;
		/** {@code null}, wenn der Text kein gültiger Pfad ist (gilt als nicht erreichbar). */
		final Path path;
		final VersionStore shared;
		final SyncPositions positions;
		volatile FeedReader reader;
		volatile FeedWriter writer;
		/** Beim Verbinden: die Entscheidung des Aufrufers, bis der erste Vollabgleich gelungen ist. */
		volatile ConnectMode connectMode;
		/** Vor der ersten Arbeit die Erreichbarkeit prüfen (nach dem Verbinden). */
		volatile boolean probeFirst;
		volatile boolean fullSyncWanted = true;
		/** Der erste Vollabgleich steht noch aus: danach werden die Verluste aller Einheiten abgeleitet. */
		volatile boolean allUnitsWanted = true;
		volatile Instant lastFullSync;
		volatile Instant lastDiscovery;

		Link(String text, Path path, VersionStore shared, SyncPositions positions) {
			this.text = text;
			this.path = path;
			this.shared = shared;
			this.positions = positions;
		}

		/** Leser und Schreiber der Änderungslisten für diese Rechner-Kennung. */
		void bind(String machineId, VersionStore local, Clock clock) {
			if (shared != null) {
				reader = new FeedReader(shared, local, machineId, positions, clock);
				writer = new FeedWriter(shared, machineId, clock);
			}
		}
	}

	private final Path appDataDir;
	private final FileLayer files;
	private final Executor worker;
	private final Predicate<Path> reachable;
	private final Clock clock;
	private final VersionStore local;
	private final SyncNotifier notifier;

	/** Serialisiert die Arbeit am gemeinsamen Ordner: Takt, Hochladen beim Start. */
	private final ReentrantLock workLock = new ReentrantLock();

	// Alles Folgende wird unter lock gelesen und geschrieben; Dateizugriffe geschehen nie darunter.
	private final Object lock = new Object();
	private MachineIdentity machine;
	private VersionIdGenerator ids;
	private Link link;
	/** Die Aufträge in der Reihenfolge des Hochladens. */
	private final List<UploadJob> jobs = new ArrayList<>();
	/** Die Versionen, die in einem Auftrag stehen. */
	private final Set<VersionId> queued = new HashSet<>();
	/** Die in diesem Programmlauf als verloren gemeldeten Versionen. */
	private final Set<VersionId> reportedLost = new HashSet<>();
	/** Eigene gewonnene Ansprüche, die noch auf eine späte Niederlage geprüft werden; Schlüssel: Einheit und Platz. */
	private final Map<String, Watched> ownClaims = new LinkedHashMap<>();
	private long watchOrder;
	/** Einheiten, deren Kette im gemeinsamen Ordner an einer beschädigten Version endet, mit dem Anspruch darauf. */
	private final Map<UnitKey, Claim> sharedRepairs = new LinkedHashMap<>();
	/** Gesperrte Einheiten, die der nächste Takt aus der Kopie im gemeinsamen Ordner zu heilen versucht. */
	private final Set<UnitKey> repairQueue = new LinkedHashSet<>();
	/**
	 * Gesperrte Einheiten, für die es bei der letzten Heilung keine gute Kopie gab; sie werden erst wieder vorgemerkt, wenn
	 * Abholen oder Vollabgleich sie als geändert melden.
	 */
	private final Set<UnitKey> unrepairable = new HashSet<>();

	/**
	 * Für Tests: Ordner, Kennung, Dateischicht, Threads, Erreichbarkeitsprüfung und Uhr frei wählbar. Es wird kein
	 * Thread gestartet und kein Takt; den Takt löst {@link #tick()} aus.
	 *
	 * @param appDataDir der Datenordner dieses Rechners; der lokale Bestand liegt in {@code data/} darunter
	 * @param machine    die Kennung dieses Rechners
	 * @param files      die Dateischicht für den lokalen Bestand und den gemeinsamen Ordner
	 * @param ui         der Thread für Zustand und Rückrufe
	 * @param worker     der Thread für die Arbeit am gemeinsamen Ordner
	 * @param reachable  die Prüfung der Erreichbarkeit (mit Zeitlimit)
	 * @param clock      die Uhr für Versions-IDs, Zeitpunkte und Fristen
	 */
	SyncService(Path appDataDir, MachineIdentity machine, FileLayer files, Executor ui, Executor worker,
			Predicate<Path> reachable, Clock clock) {
		this.appDataDir = appDataDir;
		this.files = files;
		this.worker = worker;
		this.reachable = reachable;
		this.clock = clock;
		this.local = new VersionStore(appDataDir.resolve(DATA), files);
		this.notifier = new SyncNotifier(ui);
		this.machine = machine;
		this.ids = new VersionIdGenerator(machine.id(), clock);
	}

	/**
	 * Die Instanz der Anwendung: nimmt zuerst die Sperre gegen einen zweiten Start, lädt dann die Kennung des Rechners,
	 * arbeitet auf {@code AppPaths.appDataDir()}, meldet über {@code Platform.runLater} und hat einen Daemon-Thread für
	 * die Arbeit und den Takt alle 2 Sekunden.
	 *
	 * @throws IllegalStateException mit dem Text von SYS-010, wenn die Anwendung auf diesem Rechner schon läuft
	 * @throws UncheckedIOException  wenn Sperre oder Kennung nicht gelesen oder angelegt werden können
	 */
	public static synchronized SyncService getInstance() {
		if (instance == null) {
			Path appData = AppPaths.appDataDir().toPath();
			MachineIdentity identity;
			try {
				// nach einem gescheiterten Versuch (etwa beim Lesen der Kennung) hält dieser Prozess die Sperre schon
				if (instanceLock == null) {
					Optional<InstanceLock> acquired = InstanceLock.tryAcquire(appData);
					if (acquired.isEmpty()) {
						LOGGER.severe(StoreMessages.alreadyRunning());
						throw new IllegalStateException(StoreMessages.alreadyRunning());
					}
					instanceLock = acquired.get();
				}
				identity = MachineIdentity.loadOrCreate(appData, () -> System.getenv("COMPUTERNAME"));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(SyncService::daemon);
			SyncService service = new SyncService(appData, identity, new NioFileLayer(), Platform::runLater, thread,
					Reachability::isReachable, Clock.systemDefaultZone());
			thread.scheduleWithFixedDelay(service::tickSafely, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS);
			instance = service;
		}
		return instance;
	}

	private static Thread daemon(Runnable runnable) {
		Thread thread = new Thread(runnable, "store-sync");
		thread.setDaemon(true);
		return thread;
	}

	// --- Abfragen des Zustands

	/** Setzt den Empfänger der Rückmeldungen; {@code null} heißt keiner. */
	public void setListener(SyncListener listener) {
		notifier.setListener(listener);
	}

	/** Der Zustand des Austauschs; Änderungen kommen über den {@code ui}-Executor an. */
	public ReadOnlyObjectProperty<SyncState> syncState() {
		return notifier.stateProperty();
	}

	/** Zeitpunkt des letzten erfolgreichen Takts, {@code null} solange keiner gelang. */
	public ReadOnlyObjectProperty<Instant> lastSync() {
		return notifier.lastSyncProperty();
	}

	/** Der gemeinsame Ordner, wie er eingestellt wurde (ohne Leerzeichen am Rand); {@code null} im Einzelbetrieb. */
	public String sharedDir() {
		synchronized (lock) {
			return link == null ? null : link.text;
		}
	}

	// --- Steuerung

	/**
	 * Beim Start der Anwendung. Räumt zuerst eigene temporäre Dateien älter als einen Tag aus dem lokalen Bestand. Ohne
	 * gemeinsamen Ordner gilt der Einzelbetrieb ({@link SyncState#AUS}); ausstehende eigene Versionen (etwa nach einem
	 * Absturz vor dem lokalen Anspruch) werden dann lokal beansprucht. Sonst wird die Erreichbarkeit geprüft
	 * (höchstens 5 Sekunden): Ist der Ordner erreichbar, werden die ausstehenden Versionen früherer Läufe hier synchron
	 * hochgeladen, der Vollabgleich folgt im Hintergrund. Ist er nicht erreichbar, ist der Zustand
	 * {@link SyncState#OFFLINE} und SYS-005 steht im Log; {@link SyncListener#unreachable} kommt nicht, das Popup zeigt
	 * der Aufrufer. Der Ausfall gilt damit als gemeldet; gelingt ein späterer Takt, kommt {@link SyncListener#reconnected}.
	 *
	 * @param sharedDir der gemeinsame Ordner aus den Einstellungen; leer oder {@code null} heißt Einzelbetrieb
	 * @return {@code false}, wenn der gemeinsame Ordner nicht erreichbar ist; sonst {@code true}
	 */
	public boolean startup(String sharedDir) {
		try {
			files.deleteOwnTemps(local.dataRoot(), TEMP_AGE);
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Alte temporäre Dateien im lokalen Bestand ließen sich nicht entfernen", e);
		}
		String dir = clean(sharedDir);
		if (dir == null) {
			deactivate();
			claimPendingLocally();
			return true;
		}
		Link current = activate(dir, null);
		if (current.path == null || !probe(current.path)) {
			notifier.unreachable(dir, false);
			return false;
		}
		workLock.lock();
		try {
			Round round = new Round(current);
			round.run(() -> {
				enqueuePending(current);
				runJobs(current, round);
			});
			reportChanges(round);
			if (isCurrent(current)) {
				if (round.unreachable) {
					notifier.unreachable(dir, false);
					return false;
				}
				if (round.failure != null) {
					notifier.failed(failureText(current, round.failure), round.failure);
				} else {
					notifier.setState(hasJobs() ? SyncState.AUSSTEHEND : SyncState.AKTUELL);
				}
			}
		} finally {
			workLock.unlock();
		}
		schedule();
		return true;
	}

	/**
	 * Ob der Aufrufer vor dem Verbinden fragen muss, ob der lokale Bestand mitgenommen oder beiseitegelegt wird: nur,
	 * wenn beide Seiten schon Einheiten haben. Liest den gemeinsamen Ordner auf dem Thread des Aufrufers.
	 *
	 * @throws IOException wenn der Ordner nicht gelesen werden kann oder kein gültiger Pfad ist
	 */
	public boolean needsConnectDecision(String sharedDir) throws IOException {
		String dir = clean(sharedDir);
		if (dir == null) {
			return false;
		}
		Path path = toPath(dir);
		if (path == null) {
			throw new IOException("Kein gültiger Pfad: " + dir);
		}
		return FullSync.bothHaveUnits(local, new VersionStore(path.resolve(DATA), files));
	}

	/**
	 * Verbindet mit einem gemeinsamen Ordner; die Arbeit läuft im Hintergrund. Hat eine Seite keine Einheiten, wird ohne
	 * Rücksicht auf den Modus vereinigt. Sonst gilt der Modus: {@link ConnectMode#MERGE} gleicht ab und legt die lokalen
	 * Anspruchsdateien auch im gemeinsamen Ordner an (ein anderer Anspruch dort gewinnt, die eigene Version ist dann
	 * verloren); {@link ConnectMode#SET_ASIDE} benennt den lokalen Bestand in {@code data-beiseite-<Zeitstempel>} um (nie
	 * gelöscht) und gleicht in einen leeren Bestand ab. Leer oder {@code null} trennt.
	 */
	public void connect(String sharedDir, ConnectMode mode) {
		Objects.requireNonNull(mode, "mode");
		String dir = clean(sharedDir);
		if (dir == null) {
			disconnect();
			return;
		}
		activate(dir, mode);
		schedule();
	}

	/**
	 * Beendet den gemeinsamen Betrieb: Der lokale Bestand bleibt, wie er ist (nichts wird gelöscht oder umgeschrieben),
	 * und ist ab jetzt Schiedsrichter ({@link SyncState#AUS}). Noch nicht hochgeladene eigene Versionen werden deshalb
	 * jetzt lokal beansprucht; verlorene bleiben verloren.
	 */
	public void disconnect() {
		deactivate();
		claimPendingLocally();
	}

	/**
	 * Einzelbetrieb: Ausstehende eigene Versionen (nach dem Trennen, nach einem Absturz zwischen Schreiben und lokalem
	 * Anspruch) werden lokal beansprucht, in der Reihenfolge der Aufträge. Nur lokal, deshalb auf dem Thread des
	 * Aufrufers.
	 */
	private void claimPendingLocally() {
		try {
			List<UploadJob.Item> items = new ArrayList<>();
			for (UploadJob job : LocalScan.pendingJobs(local, machineId(), Set.of())) {
				items.addAll(job.items());
			}
			claimLocally(items);
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Ausstehende Versionen ließen sich lokal nicht beanspruchen", e);
		}
	}

	/** Gleicht sofort vollständig ab (im Hintergrund). Ohne Wirkung im Einzelbetrieb. */
	public void syncNow() {
		Link current;
		synchronized (lock) {
			current = link;
		}
		if (current == null) {
			return;
		}
		current.fullSyncWanted = true;
		schedule();
	}

	// --- Speichern und Lesen

	/**
	 * Schreibt je Änderung eine neue Versionsdatei in den lokalen Bestand. Im Einzelbetrieb wird jede sofort lokal
	 * beansprucht und gilt; im gemeinsamen Betrieb kommt der Stapel als ein Auftrag in die Warteschlange und ist bis zum
	 * Hochladen vorläufig.
	 *
	 * @param saveId die ID des Speichervorgangs; alle Versionen tragen sie
	 * @return die neuen Versionen in der Reihenfolge der Änderungen
	 * @throws IOException wenn eine Datei nicht geschrieben werden kann; die schon geschriebenen Versionen desselben
	 *                     Aufrufs bleiben liegen und werden wie die übrigen beansprucht bzw. hochgeladen
	 */
	public List<Version> append(String saveId, List<Change> changes) throws IOException {
		Objects.requireNonNull(saveId, "saveId");
		MachineIdentity me;
		VersionIdGenerator generator;
		Link current;
		synchronized (lock) {
			me = machine;
			generator = ids;
			current = link;
		}
		List<UploadJob.Item> written = new ArrayList<>();
		IOException failure = null;
		for (Change change : changes) {
			try {
				written.add(writeLocal(saveId, change, me, generator));
			} catch (IOException e) {
				failure = e;
				break;
			}
		}
		try {
			if (current == null) {
				claimLocally(written);
			} else {
				enqueue(current, saveId, written);
			}
		} catch (IOException e) {
			if (failure == null) {
				throw e;
			}
			failure.addSuppressed(e);
		}
		if (failure != null) {
			throw failure;
		}
		return written.stream().map(UploadJob.Item::version).toList();
	}

	/** Schreibt die Versionsdatei; ist der Name (praktisch nie) schon vergeben, mit der nächsten ID. */
	private UploadJob.Item writeLocal(String saveId, Change change, MachineIdentity me, VersionIdGenerator generator)
			throws IOException {
		ObjectNode payload = change.deleted() || change.payload() == null ? JsonNodeFactory.instance.objectNode()
				: change.payload().deepCopy();
		for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
			Version version = new Version(StoreCodec.FORMAT, generator.next(), change.parents(), me.id(), me.name(),
					me.operator(), clock.instant(), saveId, change.deleted(), payload);
			byte[] bytes = StoreCodec.write(version);
			if (local.writeVersion(change.unit(), version.id(), bytes)) {
				return new UploadJob.Item(change.unit(), version, bytes);
			}
		}
		throw new IOException("Für " + change.unit().relativePath() + " ließ sich keine freie Versions-ID finden.");
	}

	/** Einzelbetrieb: Der lokale Bestand ist Schiedsrichter. */
	private void claimLocally(List<UploadJob.Item> items) throws IOException {
		LocalArbiter arbiter = new LocalArbiter(local, clock);
		Set<UnitKey> units = new LinkedHashSet<>();
		for (UploadJob.Item item : items) {
			arbiter.claim(item.unit(), item.version(), item.bytes());
			units.add(item.unit());
		}
		reportLosses(units);
	}

	private void enqueue(Link current, String saveId, List<UploadJob.Item> items) {
		if (items.isEmpty()) {
			return;
		}
		synchronized (lock) {
			if (link != current) {
				// inzwischen getrennt oder gewechselt: Die Versionen bleiben ausstehend und kommen beim nächsten Abgleich mit
				return;
			}
			jobs.add(new UploadJob(saveId, items));
			for (UploadJob.Item item : items) {
				queued.add(item.version().id());
			}
			if (notifier.state() == SyncState.AKTUELL) {
				notifier.setState(SyncState.AUSSTEHEND);
			}
		}
	}

	/**
	 * Der gültige Stand einer Einheit aus dem lokalen Bestand; liest nie den gemeinsamen Ordner. Ist die Kette wegen einer
	 * beschädigten Datei gesperrt, gilt: Im Einzelbetrieb gibt es keine zweite Kopie, SYS-008 kommt sofort. Im gemeinsamen
	 * Betrieb versucht der nächste Takt die Heilung aus der Kopie im gemeinsamen Ordner (SYS-007 im Log) und meldet die
	 * Einheit mit {@link SyncListener#unitsChanged}, wenn sich dadurch etwas geändert hat; bleibt sie gesperrt, kommt
	 * SYS-008, und ein neuer Versuch folgt erst, wenn Abholen oder Vollabgleich die Einheit verändern. SYS-008 und SYS-009
	 * (neueres Format) kommen einmal je Einheit und Programmlauf über {@link SyncListener#error}.
	 */
	public UnitState unitState(UnitKey unit) throws IOException {
		UnitState state = ChainResolver.resolve(local.readUnit(unit), machineId());
		if (state.status() == Status.LOCKED_DAMAGED) {
			boolean single;
			synchronized (lock) {
				single = link == null;
				if (!single && !unrepairable.contains(unit)) {
					repairQueue.add(unit);
				}
			}
			if (single) {
				reportDamaged(unit);
			}
		}
		if (state.status() == Status.LOCKED_FORMAT) {
			notifier.errorOnce(StoreMessages.SYS_NEWER_FORMAT + " " + unit.relativePath(),
					StoreMessages.newerFormat(unit.relativePath()));
		}
		return state;
	}

	private void reportDamaged(UnitKey unit) {
		notifier.errorOnce(StoreMessages.SYS_DAMAGED + " " + unit.relativePath(), StoreMessages.damaged(unit.relativePath()));
	}

	/**
	 * Im Takt: heilt die vorgemerkten gesperrten Einheiten aus der Kopie im gemeinsamen Ordner, meldet sie als geändert
	 * und, wenn sie gesperrt bleiben, mit SYS-008. Scheitert der Zugriff, bleibt die Einheit vorgemerkt.
	 */
	private void repairQueued(Link current, Round round) throws IOException {
		List<UnitKey> units;
		synchronized (lock) {
			units = List.copyOf(repairQueue);
		}
		String myId = machineId();
		for (UnitKey unit : units) {
			UnitState before = ChainResolver.resolve(local.readUnit(unit), myId);
			UnitState after = before;
			boolean replaced = false;
			if (before.status() == Status.LOCKED_DAMAGED) {
				Healing healing = heal(unit, before, myId, current.shared);
				after = healing.state();
				replaced = healing.replaced();
			}
			boolean locked = after.status() == Status.LOCKED_DAMAGED;
			synchronized (lock) {
				repairQueue.remove(unit);
				// ohne gute Kopie nicht bei jedem unitState erneut versuchen, erst wenn sich die Einheit wieder ändert
				if (locked) {
					unrepairable.add(unit);
				} else {
					unrepairable.remove(unit);
				}
			}
			// nur melden, was sich wirklich geändert hat: sonst fragte die Oberfläche erneut, und so fort
			if (replaced || after.status() != before.status() || !Objects.equals(headId(after), headId(before))) {
				round.changed.add(unit);
			}
			if (locked) {
				reportDamaged(unit);
			}
		}
	}

	private static VersionId headId(UnitState state) {
		return state.head() == null ? null : state.head().id();
	}

	/** Eine gesperrte Einheit darf wieder geheilt werden: Abholen oder Vollabgleich haben sie verändert. */
	private void forgetUnrepairable(Collection<UnitKey> changedUnits) {
		synchronized (lock) {
			unrepairable.removeAll(changedUnits);
		}
	}

	/**
	 * Ergebnis einer Heilung.
	 *
	 * @param state    der Stand danach
	 * @param replaced ob dabei eine Datei (lokal oder im gemeinsamen Ordner) ersetzt oder angelegt wurde
	 */
	private record Healing(UnitState state, boolean replaced) {
	}

	/** Heilt Datei um Datei, solange eine lokale Kopie ersetzt wird; liefert den Stand danach. */
	private Healing heal(UnitKey unit, UnitState damaged, String myId, VersionStore shared) throws IOException {
		DamageRepair repair = new DamageRepair(local, shared, files, clock);
		UnitState state = damaged;
		boolean replaced = false;
		for (int i = 0; i < MAX_REPAIRS && state.status() == Status.LOCKED_DAMAGED; i++) {
			UnitFiles unitFiles = local.readUnit(unit);
			String slot = LocalScan.stopSlot(unitFiles);
			DamageRepair.Result result;
			if (state.blockedAt() != null) {
				ClaimFile claim = unitFiles.claims().get(slot);
				if (claim == null || claim.claim() == null) {
					break;
				}
				result = repair.repairVersion(unit, claim.claim());
			} else {
				result = repair.repairClaim(unit, slot);
			}
			logRepaired(result);
			replaced |= result.replaced() != null;
			if (result.outcome() != DamageRepair.Outcome.REPAIRED_LOCAL) {
				break;
			}
			state = ChainResolver.resolve(local.readUnit(unit), myId);
		}
		return new Healing(state, replaced);
	}

	/** Die IDs aller Events im lokalen Bestand, sortiert. */
	public List<String> eventIds() throws IOException {
		return local.eventIds();
	}

	/** Die IDs aller Hallen im lokalen Bestand, sortiert. */
	public List<String> hallIds() throws IOException {
		return local.hallIds();
	}

	/** Alle Einheiten eines Events: das Event, dann je Vorstellung sie selbst und ihre Buchungen. */
	public List<UnitKey> eventUnits(String eventId) throws IOException {
		return local.eventUnits(eventId);
	}

	/** Alle lesbaren Versionen der Einheit (mit {@code saveId}) für den Verlauf, nach ID. */
	public List<Version> history(UnitKey unit) throws IOException {
		List<Version> versions = new ArrayList<>();
		for (VersionFile file : local.readUnit(unit).versions().values()) {
			if (file.version() != null) {
				versions.add(file.version());
			}
		}
		return versions;
	}

	/**
	 * Setzt eine wegen einer beschädigten Version gesperrte Einheit zurück (Heilung Stufe 2): neue Version mit dem Inhalt
	 * des letzten lesbaren Kettenglieds (ohne eines: gelöscht), mit der beschädigten Version als erstem Vorgänger.
	 * Beansprucht wird sie wie jede andere über {@link #append}. Verloren ist genau die eine beschädigte Änderung. Gibt es
	 * schon ein noch nicht beanspruchtes eigenes Zurücksetzen (gleicher erster Vorgänger), wird es zurückgegeben und
	 * kein zweites angelegt.
	 *
	 * @throws IllegalStateException wenn die Einheit nicht wegen einer beschädigten Version gesperrt ist
	 */
	public Version resetToLastReadable(UnitKey unit) throws IOException {
		UnitState state = unitState(unit);
		if (state.status() != Status.LOCKED_DAMAGED || state.blockedAt() == null) {
			throw new IllegalStateException(unit.relativePath() + " ist nicht wegen einer beschädigten Version gesperrt ("
					+ state.status() + ").");
		}
		for (Version pending : state.pending()) {
			if (state.blockedAt().equals(pending.firstParent())) {
				return pending;
			}
		}
		Version last = state.head();
		ObjectNode payload = last == null ? null : last.payload();
		boolean deleted = last == null || last.deleted();
		Change change = new Change(unit, List.of(state.blockedAt()), payload, deleted);
		return append(UUID.randomUUID().toString(), List.of(change)).get(0);
	}

	// --- Takt

	/**
	 * Ein Takt (in der Anwendung alle 2 Sekunden im {@code worker}): Erreichbarkeit nur nach einem Fehler prüfen, bei
	 * Bedarf Vollabgleich, Aufträge fortsetzen, alle 30 Sekunden neue Rechner, Listen lesen, eigene Ansprüche der letzten
	 * 15 Minuten nachprüfen; dann Verluste und geänderte Einheiten melden und den Zustand setzen. Ohne Wirkung im
	 * Einzelbetrieb.
	 */
	void tick() {
		workLock.lock();
		try {
			Link current;
			synchronized (lock) {
				current = link;
			}
			if (current != null) {
				runTick(current);
			}
		} finally {
			workLock.unlock();
		}
	}

	private void tickSafely() {
		try {
			tick();
		} catch (RuntimeException e) {
			LOGGER.log(Level.SEVERE, "Unerwarteter Fehler im Takt des Versionsspeichers", e);
		}
	}

	private void schedule() {
		try {
			worker.execute(this::tickSafely);
		} catch (RejectedExecutionException e) {
			LOGGER.log(Level.WARNING, "Der Hintergrund-Thread des Versionsspeichers nimmt keine Aufgaben mehr an", e);
		}
	}

	private void runTick(Link current) {
		if (current.path == null) {
			notifier.unreachable(current.text, true);
			return;
		}
		SyncState state = notifier.state();
		boolean returning = state == SyncState.OFFLINE;
		if (returning || state == SyncState.FEHLER || current.probeFirst) {
			if (!probe(current.path)) {
				if (isCurrent(current)) {
					notifier.unreachable(current.text, true);
				}
				return;
			}
			current.probeFirst = false;
		}
		if (returning) {
			// nach der Rückkehr zuerst der Vollabgleich mit allen ausstehenden Versionen
			current.fullSyncWanted = true;
			if (hasJobs() && isCurrent(current)) {
				notifier.setState(SyncState.AUSSTEHEND);
			}
		}
		Instant now = clock.instant();
		Round round = new Round(current);
		if (fullSyncDue(current, now)) {
			round.run(() -> fullSync(current, round));
		}
		round.run(() -> runJobs(current, round));
		if (current.lastDiscovery == null || !now.isBefore(current.lastDiscovery.plus(DISCOVERY_INTERVAL))) {
			round.run(() -> {
				current.reader.discoverMachines();
				current.lastDiscovery = now;
			});
		}
		round.run(() -> poll(current, round));
		round.run(() -> repairShared(current, round));
		round.run(() -> repairQueued(current, round));
		round.run(() -> checkLateDefeats(current, round, now));
		reportChanges(round);
		if (!isCurrent(current)) {
			return;
		}
		if (round.unreachable) {
			notifier.unreachable(current.text, true);
		} else if (round.failure != null) {
			notifier.failed(failureText(current, round.failure), round.failure);
		} else {
			notifier.succeeded(clock.instant(), hasJobs());
		}
	}

	private static boolean fullSyncDue(Link current, Instant now) {
		return current.fullSyncWanted || current.connectMode != null || current.lastFullSync == null
				|| !now.isBefore(current.lastFullSync.plus(FULL_SYNC_INTERVAL));
	}

	private static String failureText(Link current, Exception failure) {
		return StoreMessages.syncFailed(current.text, StoreMessages.describe(failure));
	}

	/**
	 * Der Vollabgleich: beim Verbinden zuerst Beiseitelegen bzw. Mitnehmen, dann beide Bäume abgleichen, die ausstehenden
	 * Versionen einreihen und hochladen. Die Positionen der Änderungslisten ändert er nicht.
	 */
	private void fullSync(Link current, Round round) throws IOException {
		boolean uploadLocalClaims = false;
		ConnectMode mode = current.connectMode;
		if (mode != null) {
			boolean both = FullSync.bothHaveUnits(local, current.shared);
			if (both && mode == ConnectMode.SET_ASIDE) {
				Path aside = FullSync.setAside(local.dataRoot(), files, clock);
				LOGGER.info("Der lokale Bestand wurde beiseitegelegt: " + aside);
			}
			uploadLocalClaims = !both || mode == ConnectMode.MERGE;
		}
		String myId = machineId();
		Instant now = clock.instant();
		// Beim Mitnehmen: genau die Ansprüche, die der Abgleich hochlädt, ab jetzt beobachten (je Anspruch sofort, damit
		// auch ein abgebrochener Abgleich nichts verliert)
		FullSyncResult result = FullSync.run(local, current.shared, uploadLocalClaims, myId, files, clock,
				uploaded -> watchOwnClaims(
						List.of(new LocalScan.OwnClaim(uploaded.unit(), uploaded.claimName(), uploaded.version(), now))));
		// Die Positionen der Listen bleiben stehen: Das Lesen des Eintrags des Gewinners deckt einen abweichenden lokalen
		// Anspruch sofort auf; schon kopierte Dateien kosten dabei nur einen Vergleich. Was keine Liste aufdeckt (Liste
		// übersprungen, Anspruch kam erst nach Ablauf der Beobachtung), meldet der Abgleich selbst als umstritten.
		current.connectMode = null;
		current.fullSyncWanted = false;
		current.lastFullSync = now;
		if (current.allUnitsWanted) {
			current.allUnitsWanted = false;
			round.allUnits = true;
			// nach einem Neustart: die eigenen Ansprüche der letzten 15 Minuten weiter auf eine späte Niederlage prüfen
			watchOwnClaims(LocalScan.recentOwnClaims(local, myId, now, LATE_DEFEAT_WINDOW));
		}
		// gesperrte Einheiten dürfen wieder geheilt werden: Die gemeinsame Kopie kann inzwischen geheilt sein
		synchronized (lock) {
			unrepairable.clear();
		}
		round.changed.addAll(result.changed());
		result.lost().forEach(this::reportLoss);
		settleAll(current, result.contested(), round);
		enqueuePending(current);
		runJobs(current, round);
	}

	/** Reiht die ausstehenden Versionen ein, die noch in keinem Auftrag stehen, vor den übrigen Aufträgen. */
	private void enqueuePending(Link current) throws IOException {
		Set<VersionId> skip;
		String myId;
		synchronized (lock) {
			skip = new HashSet<>(queued);
			myId = machine.id();
		}
		List<UploadJob> found = LocalScan.pendingJobs(local, myId, skip);
		synchronized (lock) {
			if (link != current) {
				return;
			}
			List<UploadJob> fresh = new ArrayList<>();
			for (UploadJob job : found) {
				// inzwischen über append eingereiht: nicht doppelt
				if (job.items().stream().noneMatch(item -> queued.contains(item.version().id()))) {
					fresh.add(job);
					job.items().forEach(item -> queued.add(item.version().id()));
				}
			}
			jobs.addAll(0, fresh);
		}
	}

	/**
	 * Lädt die Aufträge der Reihe nach hoch. Scheitert einer bei erreichbarem Ordner, kommen die übrigen trotzdem dran
	 * (ein späterer Takt setzt ihn fort); ist der Ordner weg, endet der Durchlauf.
	 */
	private void runJobs(Link current, Round round) throws IOException {
		List<UploadJob> pending;
		synchronized (lock) {
			pending = new ArrayList<>(jobs);
		}
		boolean regenerated = false;
		for (int i = 0; i < pending.size(); i++) {
			if (round.unreachable || !isCurrent(current)) {
				return;
			}
			UploadJob job = pending.get(i);
			try {
				List<LostVersion> losses = new Uploader(current.shared, current.writer,
						new SharedArbiter(current.shared, local, clock, this::damagedInShared)).run(job);
				completed(job, losses, round);
			} catch (ContentMismatchException e) {
				// nichts überschreiben: den Auftrag verwerfen, die Versionen bleiben lokal ausstehend
				notifier.errorOnce(StoreMessages.SYS_CONTENT_MISMATCH + " " + e.relativePath(),
						StoreMessages.contentMismatch(e.relativePath()));
				remove(job);
			} catch (DuplicateMachineException e) {
				if (regenerated) {
					round.survive(e);
					continue;
				}
				regenerated = true;
				regenerate(current);
				i--;
			} catch (IOException e) {
				// Niederlagen, die dieser Lauf schon fand, gleich melden (doppelte sortiert reportLoss aus)
				job.losses().forEach(this::reportLoss);
				round.survive(e);
			}
		}
	}

	/** Vom {@link SharedArbiter}: Die Kette im gemeinsamen Ordner endet an einer beschädigten Version. */
	private void damagedInShared(UnitKey unit, Claim claim) {
		synchronized (lock) {
			sharedRepairs.put(unit, claim);
		}
	}

	/**
	 * Im Takt: heilt beschädigte Versionsdateien im gemeinsamen Ordner aus der guten lokalen Kopie (oder umgekehrt, wenn
	 * die lokale die beschädigte ist); SYS-007 ins Log.
	 */
	private void repairShared(Link current, Round round) throws IOException {
		Map<UnitKey, Claim> todo;
		synchronized (lock) {
			todo = new LinkedHashMap<>(sharedRepairs);
		}
		if (todo.isEmpty()) {
			return;
		}
		DamageRepair repair = new DamageRepair(local, current.shared, files, clock);
		for (Map.Entry<UnitKey, Claim> entry : todo.entrySet()) {
			DamageRepair.Result result = repair.repairVersion(entry.getKey(), entry.getValue());
			logRepaired(result);
			synchronized (lock) {
				sharedRepairs.remove(entry.getKey(), entry.getValue());
			}
			if (result.outcome() == DamageRepair.Outcome.REPAIRED_LOCAL) {
				round.changed.add(entry.getKey());
			}
		}
	}

	private static void logRepaired(DamageRepair.Result result) {
		if (result.corruptCopy() != null) {
			LOGGER.warning(StoreMessages.repaired(result.replaced().toString(), result.corruptCopy().getFileName().toString()));
		}
	}

	private void completed(UploadJob job, List<LostVersion> losses, Round round) {
		remove(job);
		losses.forEach(this::reportLoss);
		Instant now = clock.instant();
		for (UploadJob.Item item : job.items()) {
			round.changed.add(item.unit());
			recordOwnClaim(item, now);
		}
	}

	private void remove(UploadJob job) {
		synchronized (lock) {
			jobs.remove(job);
			job.items().forEach(item -> queued.remove(item.version().id()));
		}
	}

	/** Nimmt Ansprüche in die Beobachtung auf; ein Platz, der schon beobachtet wird, kommt nicht doppelt. */
	private void watchOwnClaims(List<LocalScan.OwnClaim> claims) {
		synchronized (lock) {
			for (LocalScan.OwnClaim claim : claims) {
				ownClaims.computeIfAbsent(slotKey(claim.unit(), claim.claimName()), key -> new Watched(claim, watchOrder++));
			}
		}
	}

	/** Beobachtet den Anspruch von jetzt an neu (Fenster ab {@code claim.at()}), auch wenn der Platz schon beobachtet wird. */
	private void rearm(LocalScan.OwnClaim claim) {
		synchronized (lock) {
			ownClaims.put(slotKey(claim.unit(), claim.claimName()), new Watched(claim, watchOrder++));
		}
	}

	private void unwatch(UnitKey unit, String claimName) {
		synchronized (lock) {
			ownClaims.remove(slotKey(unit, claimName));
		}
	}

	private static String slotKey(UnitKey unit, String claimName) {
		return unit.relativePath() + "/claims/" + claimName;
	}

	/** Merkt sich einen gewonnenen Anspruch (er liegt jetzt lokal und nennt die Version) für die späte Niederlage. */
	private void recordOwnClaim(UploadJob.Item item, Instant now) {
		Version version = item.version();
		try {
			Optional<byte[]> bytes = local.readClaimBytes(item.unit(), version.claimName());
			if (bytes.isPresent() && StoreCodec.readClaim(bytes.get()).version().equals(version.id())) {
				watchOwnClaims(List.of(new LocalScan.OwnClaim(item.unit(), version.claimName(), version.id(), now)));
			}
		} catch (IOException e) {
			LOGGER.log(Level.FINE, "Der eigene Anspruch auf " + version.id() + " ist lokal nicht lesbar", e);
		}
	}

	/**
	 * Ein anderer Rechner verwendet dieselbe Kennung: neue Kennung, neuer Schreiber und Leser der Listen, neuer
	 * Generator der Versions-IDs, SYS-011, und ein Vollabgleich wird angefordert. Die Positionen werden nicht neu gesetzt:
	 * Die alte Kennung ist ab jetzt ein fremder Rechner, die Suche nach neuen Rechnern findet ihren Ordner und liest
	 * ihre Listen ab Nummer 1 nach (für schon vorhandene Dateien nur ein Vergleich). Ausstehende Versionen mit der alten
	 * Kennung gelten danach nicht mehr als eigene.
	 */
	private void regenerate(Link current) throws IOException {
		MachineIdentity old;
		synchronized (lock) {
			old = machine;
		}
		MachineIdentity renewed = old.regenerate();
		synchronized (lock) {
			machine = renewed;
			ids = new VersionIdGenerator(renewed.id(), clock);
		}
		current.bind(renewed.id(), local, clock);
		current.fullSyncWanted = true;
		notifier.duplicateMachine(StoreMessages.duplicateMachine());
	}

	private void poll(Link current, Round round) throws IOException {
		FeedPoll poll = current.reader.poll();
		round.changed.addAll(poll.changed());
		forgetUnrepairable(poll.changed());
		poll.pending().forEach(notifier::pending);
		if (poll.fullSyncNeeded()) {
			current.fullSyncWanted = true;
		}
		if (!poll.damaged().isEmpty()) {
			// die kopierte Datei passt nicht zum Anspruch: heilen versuchen (sonst SYS-008)
			synchronized (lock) {
				repairQueue.addAll(poll.damaged());
			}
		}
		settleAll(current, poll.contested(), round);
	}

	/**
	 * Klärt umstrittene Plätze, jeden für sich: Eine Störung bei einem verliert die übrigen nicht; der gestörte wird
	 * weiter beobachtet und gilt als geändert (vielleicht wurde er schon verdrängt). Die erste Störung wird am Ende
	 * geworfen.
	 */
	private void settleAll(Link current, Collection<FeedPoll.Contested> slots, Round round) throws IOException {
		IOException failure = null;
		for (FeedPoll.Contested slot : slots) {
			try {
				settleContested(current, slot, round);
			} catch (IOException e) {
				if (failure == null) {
					failure = e;
				}
				round.changed.add(slot.unit());
				rearmIfOwn(slot);
			}
		}
		if (failure != null) {
			throw failure;
		}
	}

	/** Beobachtet den lokalen Anspruch des Platzes ab jetzt neu, wenn er der eigene ist ({@code checkLateDefeats} prüft weiter). */
	private void rearmIfOwn(FeedPoll.Contested slot) {
		try {
			Optional<byte[]> bytes = local.readClaimBytes(slot.unit(), slot.claimName());
			Claim mine = bytes.isPresent() ? parseClaim(bytes.get()) : null;
			if (mine != null && machineId().equals(mine.machine())) {
				rearm(new LocalScan.OwnClaim(slot.unit(), slot.claimName(), mine.version(), clock.instant()));
			}
		} catch (IOException e) {
			LOGGER.log(Level.FINE, "Der lokale Anspruch " + slot + " ist nicht lesbar", e);
		}
	}

	/**
	 * Ein fremder Listeneintrag trifft einen Platz, dessen lokaler Anspruch abweicht. Nennt der gemeinsame Anspruch eine
	 * andere Version als der lokale, ist das eine späte Niederlage, auch lange nach Ablauf der Beobachtung: verdrängen.
	 * Sonst ist der lokale Anspruch der eigene, und der Sync-Client hat die Entscheidung vielleicht noch nicht
	 * übernommen: ab jetzt wieder beobachten.
	 */
	private void settleContested(Link current, FeedPoll.Contested slot, Round round) throws IOException {
		Optional<byte[]> localBytes = local.readClaimBytes(slot.unit(), slot.claimName());
		Claim mine = localBytes.isPresent() ? parseClaim(localBytes.get()) : null;
		if (mine == null) {
			return;
		}
		Optional<byte[]> sharedBytes = current.shared.readClaimBytes(slot.unit(), slot.claimName());
		Claim theirs = sharedBytes.isPresent() ? parseClaim(sharedBytes.get()) : null;
		if (theirs != null && !theirs.version().equals(mine.version())) {
			displace(current, slot.unit(), slot.claimName(), sharedBytes.get(), theirs, mine.version());
			unwatch(slot.unit(), slot.claimName());
			round.changed.add(slot.unit());
		} else if (machineId().equals(mine.machine())) {
			rearm(new LocalScan.OwnClaim(slot.unit(), slot.claimName(), mine.version(), clock.instant()));
		}
	}

	/**
	 * Liest beobachtete eigene Ansprüche im gemeinsamen Ordner neu, höchstens {@value #LATE_DEFEAT_BUDGET} je Takt, die am
	 * längsten nicht geprüften zuerst. Zeigt einer auf eine andere Version (ein Sync-Client hat die erste Fassung eines
	 * anderen Rechners übernommen), wird der lokale Anspruch durch den gemeinsamen ersetzt und bleibt als
	 * {@code <Name>.json.verdraengt-<Zeitstempel>} daneben liegen; die Gewinner-Version kommt nach lokal. Den Verlust
	 * meldet dann die Ableitung aus dem Zustand. Ein Anspruch verlässt die Beobachtung erst nach einer Prüfung zu oder
	 * nach Ablauf seines 15-Minuten-Fensters; so wird auch einer hinter einem großen Rückstand noch geprüft.
	 */
	private void checkLateDefeats(Link current, Round round, Instant now) throws IOException {
		List<Watched> due;
		synchronized (lock) {
			due = ownClaims.values().stream()
					.sorted(Comparator.comparing((Watched w) -> w.lastChecked, Comparator.nullsFirst(Comparator.naturalOrder()))
							.thenComparingLong(w -> w.order))
					.limit(LATE_DEFEAT_BUDGET).toList();
		}
		for (Watched watched : due) {
			LocalScan.OwnClaim own = watched.claim;
			String key = slotKey(own.unit(), own.claimName());
			Optional<byte[]> bytes = current.shared.readClaimBytes(own.unit(), own.claimName());
			Claim theirs = bytes.isPresent() ? parseClaim(bytes.get()) : null;
			if (theirs != null && !theirs.version().equals(own.version())) {
				displace(current, own.unit(), own.claimName(), bytes.get(), theirs, own.version());
				synchronized (lock) {
					ownClaims.remove(key, watched);
				}
				round.changed.add(own.unit());
				continue;
			}
			synchronized (lock) {
				if (!now.isBefore(own.at().plus(LATE_DEFEAT_WINDOW))) {
					ownClaims.remove(key, watched);
				} else {
					watched.lastChecked = now;
				}
			}
		}
	}

	/** Ersetzt den lokalen Anspruch durch den gemeinsamen (der alte bleibt daneben) und holt die Gewinner-Version. */
	private void displace(Link current, UnitKey unit, String claimName, byte[] sharedBytes, Claim theirs, VersionId mine)
			throws IOException {
		Path claimFile = local.resolve(unit.relativePath() + "/claims/" + claimName + ".json");
		Optional<byte[]> localBytes = local.readClaimBytes(unit, claimName);
		Claim present = localBytes.isPresent() ? parseClaim(localBytes.get()) : null;
		if (localBytes.isEmpty()) {
			local.createClaim(unit, claimName, sharedBytes);
		} else if (present == null || !present.version().equals(theirs.version())) {
			files.replaceDamaged(claimFile, sharedBytes, displacedName(claimFile));
		}
		Optional<byte[]> winner = current.shared.readVersionBytes(unit, theirs.version());
		if (winner.isPresent()) {
			local.writeVersion(unit, theirs.version(), winner.get());
		}
		LOGGER.warning("Späte Niederlage: Der Anspruch " + unit.relativePath() + "/claims/" + claimName
				+ ".json nennt im gemeinsamen Ordner " + theirs.version() + " statt " + mine + ".");
	}

	/** {@code <Dateiname>.verdraengt-<yyyy-MM-dd_HH-mm-ss>}, bei Namensgleichheit mit {@code -2}, {@code -3} … */
	private String displacedName(Path file) {
		String base = file.getFileName() + ".verdraengt-" + LocalDateTime.now(clock).format(STAMP);
		String name = base;
		for (int attempt = 2; files.exists(file.resolveSibling(name)); attempt++) {
			name = base + "-" + attempt;
		}
		return name;
	}

	private static Claim parseClaim(byte[] bytes) {
		try {
			return StoreCodec.readClaim(bytes);
		} catch (IOException e) {
			return null;
		}
	}

	// --- Meldungen

	/** Meldet Verluste (aus dem Zustand abgeleitet) und geänderte Einheiten eines Durchlaufs, auch nach einem Fehler. */
	private void reportChanges(Round round) {
		Collection<UnitKey> units = round.changed;
		if (round.allUnits) {
			try {
				units = local.allUnits();
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Die Einheiten des lokalen Bestands ließen sich nicht auflisten", e);
			}
		}
		reportLosses(units);
		notifier.unitsChanged(round.changed);
	}

	/** Leitet für jede Einheit die verlorenen Strecken aus dem Zustand ab und meldet, was noch nicht gemeldet wurde. */
	private void reportLosses(Collection<UnitKey> units) {
		String myId = machineId();
		for (UnitKey unit : units) {
			try {
				UnitFiles unitFiles = local.readUnit(unit);
				LocalScan.losses(unitFiles, ChainResolver.resolve(unitFiles, myId)).forEach(this::reportLoss);
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Der Stand von " + unit.relativePath() + " ließ sich nicht lesen", e);
			}
		}
	}

	/** Meldet die Versionen der Niederlage, die in diesem Programmlauf noch nicht gemeldet wurden. */
	private void reportLoss(LostVersion loss) {
		List<VersionId> fresh;
		synchronized (lock) {
			fresh = loss.mine().stream().filter(reportedLost::add).toList();
		}
		if (!fresh.isEmpty()) {
			notifier.lost(new LostVersion(loss.unit(), fresh, loss.winner()));
		}
	}

	// --- Verbindung

	private Link activate(String dir, ConnectMode mode) {
		Path path = toPath(dir);
		VersionStore shared = path == null ? null : new VersionStore(path.resolve(DATA), files);
		Link created = new Link(dir, path, shared, new SyncPositions(appDataDir.resolve(SYNC_STATE), dir));
		created.connectMode = mode;
		created.probeFirst = mode != null;
		synchronized (lock) {
			created.bind(machine.id(), local, clock);
			link = created;
			jobs.clear();
			queued.clear();
			ownClaims.clear();
			sharedRepairs.clear();
			repairQueue.clear();
			unrepairable.clear();
			notifier.reset();
		}
		return created;
	}

	private void deactivate() {
		synchronized (lock) {
			link = null;
			jobs.clear();
			queued.clear();
			ownClaims.clear();
			sharedRepairs.clear();
			repairQueue.clear();
			unrepairable.clear();
			notifier.reset();
			notifier.setState(SyncState.AUS);
		}
	}

	private boolean isCurrent(Link current) {
		synchronized (lock) {
			return link == current;
		}
	}

	private boolean hasJobs() {
		synchronized (lock) {
			return !jobs.isEmpty();
		}
	}

	private String machineId() {
		synchronized (lock) {
			return machine.id();
		}
	}

	// Die Prüfung der Erreichbarkeit; eine Prüfung, die wirft, zählt als nicht erreichbar
	private boolean probe(Path path) {
		try {
			return reachable.test(path);
		} catch (RuntimeException e) {
			return false;
		}
	}

	// Leer und null heißen "kein Ordner"
	private static String clean(String sharedDir) {
		if (sharedDir == null || sharedDir.isBlank()) {
			return null;
		}
		return sharedDir.trim();
	}

	// Ein unlesbarer Pfad ist kein Pfad; der Ordner gilt dann als nicht erreichbar
	private static Path toPath(String dir) {
		try {
			return Path.of(dir);
		} catch (InvalidPathException e) {
			LOGGER.log(Level.WARNING, "Ungültiger Pfad des gemeinsamen Ordners: " + dir, e);
			return null;
		}
	}

	/**
	 * Ein Durchlauf (Takt oder Hochladen beim Start): sammelt geänderte Einheiten und die erste Störung. Nach einer
	 * Störung wird die Erreichbarkeit geprüft; ist der Ordner weg, endet der Durchlauf, sonst laufen die übrigen Schritte
	 * weiter (Abholen geht auch, wenn das Hochladen scheitert).
	 */
	private final class Round {
		final Link link;
		final Set<UnitKey> changed = new LinkedHashSet<>();
		/** Der erste Vollabgleich gelang: Verluste aller Einheiten ableiten. */
		boolean allUnits;
		boolean unreachable;
		Exception failure;

		Round(Link link) {
			this.link = link;
		}

		void run(IoAction action) {
			if (unreachable || !isCurrent(link)) {
				return;
			}
			try {
				action.run();
			} catch (IOException | RuntimeException e) {
				survive(e);
			}
		}

		/**
		 * Nach einer Störung: Ist der Ordner nicht erreichbar, endet der Durchlauf; sonst wird die erste Störung gemerkt.
		 *
		 * @return ob der Ordner erreichbar ist
		 */
		boolean survive(Exception e) {
			if (!probe(link.path)) {
				LOGGER.log(Level.FINE, "Der gemeinsame Ordner ist nicht erreichbar", e);
				unreachable = true;
				return false;
			}
			if (failure == null) {
				failure = e;
			}
			return true;
		}
	}
}
