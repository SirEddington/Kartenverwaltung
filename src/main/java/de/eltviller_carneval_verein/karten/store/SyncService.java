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
import java.util.Collections;
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
import java.util.function.DoubleConsumer;
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
 * <b>Start-Index:</b> Der Stand jeder Einheit steht zusätzlich in einem lokalen Index ({@link UnitIndex}, Ordner
 * {@code <AppData>/index}, neben {@code data/}), den {@link #snapshots()} ohne Dateizugriff liefert, mit der gültigen Kette ohne Inhalt
 * ({@link #chains}) für den Verlauf. {@link #startup} gleicht ihn in
 * einem Durchlauf über die Verzeichnisnamen mit dem Bestand ab und löst nur Einheiten neu auf, deren Dateinamen sich
 * geändert haben; nur die dabei gefundenen Kandidaten mit ausstehenden eigenen Versionen untersucht der Start auf dem
 * Thread des Aufrufers. Jede Stelle, die den Stand einer Einheit ermittelt, trägt ihn danach in den Index ein. Gespeichert
 * wird er von {@link #flushIndex()} und im Takt alle {@link #INDEX_FLUSH}, nicht vor dem Abgleich durch {@link #startup}.
 * Beim Beenden ruft die Anwendung {@link #closeIndex()}: Es schreibt den Index und legt die Marke "sauber beendet" an. Findet
 * {@link #startup} sie vor, übernimmt es den Index ohne jeden Durchlauf über den Bestand (zweite Stufe); ohne Marke,
 * also nach einem Absturz, bleibt es beim Abgleich über die Verzeichnisnamen. Der Index ist nie Schiedsrichter: Jede Entscheidung über Vorgänger, Ansprüche und Verluste trifft {@link ChainResolver}
 * auf den Dateien; die Abläufe im {@code worker} lesen weiterhin alle Einheiten.
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
	/** So selten schreibt der Takt den Start-Index. */
	static final Duration INDEX_FLUSH = Duration.ofSeconds(5);
	/** So lange wartet {@link #closeIndex()} auf eine laufende Runde im Hintergrund. */
	static final Duration CLOSE_WAIT = Duration.ofSeconds(3);

	private static final int MAX_REPAIRS = 10;
	private static final int MAX_ID_ATTEMPTS = 100;
	private static final String DATA = "data";
	private static final String INDEX = "index";
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

	/** Der Start-Index; hat eine eigene Sperre, deshalb nie unter {@code lock} benutzen. Neu bei einer neuen Rechner-ID. */
	private volatile UnitIndex index;
	/** Ob {@link #startup} den Index mit dem Bestand abgeglichen hat; vorher schreibt {@link #flushIndex()} nichts. */
	private volatile boolean indexRefreshed;
	/** Wann zuletzt versucht wurde, den Index zu schreiben; {@code null}, solange noch nie. */
	private volatile Instant lastIndexFlush;
	/** Ob {@link #closeIndex()} die Marke "sauber beendet" angelegt hat; gesetzt unter {@code workLock}, der Takt tut dann nichts. */
	private volatile boolean closed;
	/**
	 * Macht "lokal lesen, auflösen, in den Index eintragen" je Aufruf unteilbar. Hält nur kurze Dateizugriffe auf den
	 * lokalen Bestand; wer sie hält, nimmt höchstens kurz {@code lock} (Rechner-ID) und den Monitor des Index.
	 */
	private final Object resolveLock = new Object();

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
		this.index = new UnitIndex(appDataDir.resolve(INDEX), files, machine.id());
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

	/**
	 * Die Instanz der Anwendung, falls {@link #getInstance()} sie schon erzeugt hat; legt selbst nie eine an. Für das
	 * Beenden der Anwendung, das nichts starten darf, wenn der Start gar nicht bis zum Dienst gekommen ist.
	 */
	public static synchronized Optional<SyncService> instanceIfCreated() {
		return Optional.ofNullable(instance);
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
	 * Beim Start der Anwendung. Macht zuerst den Start-Index benutzbar, nach einem sauberen Beenden ohne Durchlauf über den
	 * Bestand, sonst nach dem Aufräumen eigener temporärer Dateien älter als einen Tag (Einzelheiten siehe
	 * {@link #startup(String, DoubleConsumer)}). Ohne
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
		return startup(sharedDir, p -> {
		});
	}

	/**
	 * Wie {@link #startup(String)}, mit Fortschritt. Zuerst prüft der Start die Marke "sauber beendet" (siehe
	 * {@link #closeIndex()}) und löscht sie dabei sofort. Gilt sie und sind alle Index-Dateien lesbar, wird der Index
	 * ohne Durchlauf über den Bestand übernommen: Weder die eigenen temporären Dateien werden aufgeräumt noch die
	 * Verzeichnisnamen durchlaufen; die Kandidaten für ausstehende Versionen sind die Einträge des Index mit entsprechender
	 * Kennzeichnung. Sonst (nach einem Absturz, ohne oder mit fremder Marke, bei unbrauchbarem Index) räumt der Start zuerst
	 * eigene temporäre Dateien älter als einen Tag auf und gleicht den Start-Index mit dem lokalen
	 * Bestand ab (ein Durchlauf über die Verzeichnisnamen; nur Einheiten mit geänderten Dateinamen werden neu aufgelöst).
	 * Ausstehende eigene Versionen werden nur bei den Einheiten gesucht, die der Index dafür nennt; die Entscheidung trifft
	 * wie überall {@link ChainResolver} auf den Dateien. Scheitert der Abgleich, baut der Start den Index neu auf: Er löst jede
	 * Einheit des Bestands einzeln aus den Dateien auf. Lässt sich dabei eine nicht lesen (oder der Bestand nicht auflisten),
	 * scheitert der Start, denn mit einem unvollständigen Bestand wäre ein belegter Platz frei.
	 *
	 * @param progress bekommt den Anteil der abgeglichenen Einheiten von 0.0 bis 1.0, zuletzt 1.0; darf {@code null} sein
	 * @throws UncheckedIOException wenn der Bestand nicht vollständig gelesen werden kann; die Meldung nennt die Einheit
	 */
	public boolean startup(String sharedDir, DoubleConsumer progress) {
		reopen();
		Set<UnitKey> candidates = openIndex(progress);
		String dir = clean(sharedDir);
		if (dir == null) {
			deactivate();
			claimPendingLocally(candidates);
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
				enqueuePending(current, candidates);
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
		reopen();
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
		reopen();
		deactivate();
		claimPendingLocally(null);
	}

	/**
	 * Einzelbetrieb: Ausstehende eigene Versionen (nach dem Trennen, nach einem Absturz zwischen Schreiben und lokalem
	 * Anspruch) werden lokal beansprucht, in der Reihenfolge der Aufträge. Nur lokal, deshalb auf dem Thread des
	 * Aufrufers.
	 *
	 * @param candidates die Einheiten, die untersucht werden; {@code null} heißt alle
	 */
	private void claimPendingLocally(Collection<UnitKey> candidates) {
		try {
			List<UploadJob.Item> items = new ArrayList<>();
			for (UploadJob job : scanPending(machineId(), Set.of(), candidates)) {
				items.addAll(job.items());
			}
			claimLocally(items);
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Ausstehende Versionen ließen sich lokal nicht beanspruchen", e);
		}
	}

	/**
	 * Öffnet den Dienst nach {@link #closeIndex()} wieder: Die Marke "sauber beendet" wird gelöscht, bevor irgendetwas den
	 * Bestand ändert, und der Takt arbeitet wieder. Ohne vorheriges Schließen ohne Wirkung.
	 */
	private void reopen() {
		if (closed) {
			index.deleteCleanMarker();
			closed = false;
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
		// vor der ersten Datei: eine Marke "sauber beendet" gilt nur über einem unveränderten Bestand
		index.invalidateCleanMarker();
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
				// der Index folgt dort: claimLocally löst jede Einheit über resolveLocal auf (Verlustableitung)
				claimLocally(written);
			} else {
				enqueue(current, saveId, written);
				indexUnits(written);
			}
		} catch (IOException e) {
			// auch nach einem Fehler spiegelt der Index, was auf der Platte liegt (etwa eine Version ohne Anspruch)
			indexUnits(written);
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
		UnitState state = resolveLocal(unit);
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
		for (UnitKey unit : units) {
			UnitState before = resolveLocal(unit);
			UnitState after = before;
			boolean replaced = false;
			if (before.status() == Status.LOCKED_DAMAGED) {
				Healing healing = heal(unit, before, current.shared);
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
	private Healing heal(UnitKey unit, UnitState damaged, VersionStore shared) throws IOException {
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
			state = resolveLocal(unit);
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

	// --- Start-Index

	/**
	 * Der Stand aller Einheiten des lokalen Bestands aus dem Start-Index, ohne Dateizugriff: nach {@link #startup} der
	 * ganze Bestand, danach fortgeschrieben von {@link #append}, Abholen, Vollabgleich und Heilung.
	 *
	 * @return eine unveränderliche Kopie
	 */
	public Map<UnitKey, UnitSnapshot> snapshots() {
		Map<UnitKey, UnitSnapshot> snapshots = new LinkedHashMap<>();
		index.entries().forEach((unit, entry) -> snapshots.put(unit, entry.snapshot()));
		return Collections.unmodifiableMap(snapshots);
	}

	/**
	 * Die gültigen Ketten der genannten Einheiten ohne Inhalt (Rechner, Bediener, Zeitpunkt, Speichervorgang), ältestes Glied
	 * zuerst: der Verlauf eines Events, ohne eine Datei zu lesen. Sie stammen aus dem Start-Index; nur eine Einheit ohne
	 * Eintrag wird aus den Dateien aufgelöst ({@link #resolveLocal}) und dabei in den Index übernommen. Eine Einheit ohne
	 * Dateien hat eine leere Kette. Der Index ist hier nur Zwischenspeicher für die Anzeige; was wiederhergestellt wird, liest
	 * {@link #unitState} frisch aus den Dateien.
	 *
	 * @return je genannter Einheit ihre Kette
	 */
	public Map<UnitKey, List<ChainStep>> chains(Collection<UnitKey> units) throws IOException {
		Map<UnitKey, List<ChainStep>> known = index.chains(units);
		Map<UnitKey, List<ChainStep>> chains = new LinkedHashMap<>();
		for (UnitKey unit : units) {
			List<ChainStep> chain = known.get(unit);
			if (chain == null) {
				chain = resolveLocal(unit).chain().stream().map(ChainStep::of).toList();
			}
			chains.put(unit, chain);
		}
		return Collections.unmodifiableMap(chains);
	}

	/**
	 * Ob {@link #startup} den Index mit dem lokalen Bestand abgeglichen (oder neu aufgebaut) hat. Erst dann liefert
	 * {@link #snapshots()} den ganzen Bestand.
	 */
	public boolean isStarted() {
		return indexRefreshed;
	}

	/**
	 * Schreibt den Start-Index, soweit er sich geändert hat. Vor dem Abgleich durch {@link #startup} schreibt es nichts: Ein
	 * Index, der nur die bisher berührten Einheiten kennt, würde den vollständigen auf der Platte ersetzen. Wirft nie; ein
	 * Fehler kommt als {@code WARNING} ins Log, der Index kostet dann nur beim nächsten Start Zeit.
	 */
	public void flushIndex() {
		if (indexRefreshed) {
			writeIndex();
		}
	}

	/**
	 * Schreibt den Index und merkt sich den Zeitpunkt.
	 *
	 * @return ob das Schreiben ohne Fehler durchlief
	 */
	private boolean writeIndex() {
		lastIndexFlush = clock.instant();
		try {
			index.flush();
			return true;
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Der Start-Index ließ sich nicht schreiben", e);
			return false;
		}
	}

	/**
	 * Beim Beenden der Anwendung: wie {@link #flushIndex()}, und gelingt das Schreiben vollständig, bleibt nichts
	 * ungeschrieben, so legt es die Marke "sauber beendet" an ({@code <Index>/clean}, Inhalt: die eigene Rechner-ID). Der
	 * nächste {@link #startup} übernimmt den Index dann ohne Durchlauf über den Bestand. Ohne Marke (Fehler beim Schreiben,
	 * Index nie abgeglichen) startet er wie nach einem Absturz.
	 * <p>
	 * Eine gerade laufende Runde im Hintergrund wartet es kurz ab ({@link #CLOSE_WAIT}); ist sie dann nicht fertig, gibt es
	 * keine Marke, denn ihre Änderungen am Bestand fehlten im Index.
	 * <p>
	 * Mit der Marke ist der Dienst geschlossen: Der Takt tut nichts mehr, denn eine Runde, die nach der Marke Dateien in den
	 * Bestand schriebe, und ein Prozessende mitten darin ließen die Marke über einem geänderten Bestand stehen. Ein späteres
	 * {@link #append} löscht die Marke vor dem Schreiben; {@link #startup}, {@link #connect} und {@link #disconnect} öffnen
	 * den Dienst wieder (und löschen die Marke zuerst). Wirft nie; Fehler stehen als {@code WARNING} im Log.
	 */
	public void closeIndex() {
		boolean idle = false;
		try {
			idle = workLock.tryLock(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		try {
			if (!indexRefreshed) {
				return;
			}
			boolean written = writeIndex();
			if (!written || !idle) {
				return;
			}
			if (index.writeCleanMarker()) {
				// ab jetzt ändert niemand mehr den Bestand: kein Takt, bis startup, connect oder disconnect den Dienst wieder öffnen
				closed = true;
			} else {
				LOGGER.warning("Der Start-Index hat noch ungeschriebene Änderungen; die Marke 'sauber beendet' entfällt");
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Die Marke 'sauber beendet' ließ sich nicht schreiben", e);
		} finally {
			if (idle) {
				workLock.unlock();
			}
		}
	}

	/** Im Takt: schreibt den Index, wenn er sich geändert hat und das letzte Schreiben {@link #INDEX_FLUSH} her ist. */
	private void flushIndexIfDue() {
		Instant last = lastIndexFlush;
		if (indexRefreshed && (last == null || !clock.instant().isBefore(last.plus(INDEX_FLUSH))) && index.hasChanges()) {
			flushIndex();
		}
	}

	/**
	 * Macht den Index beim Start benutzbar. Zuerst gilt die Marke "sauber beendet" ({@link UnitIndex#loadIfClean}): Ist sie
	 * da und der Index lesbar, wird er ohne Durchlauf über den Bestand übernommen; das Aufräumen und der Verzeichnisbaum entfallen, denn
	 * temporäre Reste und Änderungen am Bestand ohne den Index gibt es nur nach einem Absturz, und dann fehlt die Marke.
	 * Sonst räumt der Start eigene temporäre Dateien auf und gleicht den Index ab, im Notfall baut er ihn neu auf.
	 *
	 * @return die Einheiten mit ausstehenden eigenen Versionen
	 */
	private Set<UnitKey> openIndex(DoubleConsumer progress) {
		try {
			if (index.loadIfClean()) {
				Set<UnitKey> pending = index.cleanRefresh().pendingUnits();
				indexRefreshed = true;
				if (progress != null) {
					progress.accept(1.0);
				}
				return pending;
			}
		} catch (RuntimeException e) {
			LOGGER.log(Level.WARNING, "Die Marke 'sauber beendet' ließ sich nicht auswerten; der Bestand wird durchsucht", e);
		}
		try {
			files.deleteOwnTemps(local.dataRoot(), TEMP_AGE);
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Alte temporäre Dateien im lokalen Bestand ließen sich nicht entfernen", e);
		}
		Set<UnitKey> refreshed = refreshIndex(progress);
		// war der Index nicht zu gebrauchen: jede Einheit einzeln aus den Dateien, oder der Start scheitert
		return refreshed != null ? refreshed : rebuildIndex(progress);
	}

	/**
	 * Gleicht den Index mit dem Bestand ab.
	 *
	 * @return die Einheiten mit ausstehenden eigenen Versionen; {@code null}, wenn der Abgleich scheiterte und der Index
	 *         nicht zu gebrauchen ist
	 */
	private Set<UnitKey> refreshIndex(DoubleConsumer progress) {
		try {
			UnitIndex.Refresh refresh = index.refresh(local, progress);
			indexRefreshed = true;
			return refresh.pendingUnits();
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Der Start-Index ließ sich nicht abgleichen; alle Einheiten werden untersucht", e);
			return null;
		}
	}

	/**
	 * Baut den Index neu auf, wenn der Abgleich scheiterte: ein neuer, leerer Index, jede Einheit des Bestands einzeln über
	 * {@link #resolveLocal}. Der Index ist danach vollständig, sonst scheitert der Start.
	 *
	 * @return die Einheiten mit ausstehenden eigenen Versionen
	 * @throws UncheckedIOException wenn die Einheiten nicht aufgelistet oder eine nicht gelesen werden kann; der Bestand wäre
	 *                              unvollständig (eine fehlende Buchung hieße einen freien Platz)
	 */
	private Set<UnitKey> rebuildIndex(DoubleConsumer progress) {
		synchronized (resolveLock) {
			index = new UnitIndex(appDataDir.resolve(INDEX), files, machineId());
		}
		Set<UnitKey> pending = new LinkedHashSet<>();
		List<UnitKey> units;
		try {
			units = local.allUnits();
		} catch (IOException e) {
			throw new UncheckedIOException("Die Einheiten des lokalen Bestands ließen sich nicht auflisten", e);
		}
		for (UnitKey unit : units) {
			try {
				if (!resolveLocal(unit).pending().isEmpty()) {
					pending.add(unit);
				}
			} catch (IOException e) {
				throw new UncheckedIOException("Der Stand von " + unit.relativePath() + " ließ sich nicht lesen", e);
			}
		}
		indexRefreshed = true;
		if (progress != null) {
			progress.accept(1.0);
		}
		return pending;
	}

	/**
	 * Verwirft den Index, weil der Datenordner ausgetauscht wird (Beiseitelegen). Der leere Index ist vollständig und
	 * darf deshalb auch ohne Abgleich geschrieben werden: Er ersetzt die alten Dateien. Unter {@code resolveLock}: Ein Stand,
	 * der noch aus dem alten Datenordner gelesen wurde, kommt nicht danach in den Index.
	 */
	private void clearIndex() {
		synchronized (resolveLock) {
			index.clear();
			indexRefreshed = true;
		}
	}

	/** Die Dateien einer Einheit und ihr Stand daraus. */
	private record Resolved(UnitFiles files, UnitState state) {
	}

	/**
	 * Der Stand einer Einheit aus den Dateien des lokalen Bestands ({@link ChainResolver}), eingetragen in den Index. Jede
	 * Stelle, die einen Stand aus dem lokalen Bestand ermittelt, geht darüber. Nie unter {@code lock} aufrufen.
	 */
	private UnitState resolveLocal(UnitKey unit) throws IOException {
		return resolveWithFiles(unit).state();
	}

	/** Wie {@link #resolveLocal}, mit den gelesenen Dateien. */
	private Resolved resolveWithFiles(UnitKey unit) throws IOException {
		// Lesen, Auflösen und Eintragen am Stück: Wer nach dem eigenen Schreiben auflöst, trägt auch zuletzt ein. Sonst
		// überschriebe der ältere Stand eines anderen Threads den neueren. Reihenfolge der Sperren: workLock, resolveLock,
		// Monitor des Index; machineId() nimmt nur kurz lock, und unter lock wartet nie jemand auf resolveLock.
		synchronized (resolveLock) {
			UnitFiles unitFiles = local.readUnit(unit);
			UnitState state = ChainResolver.resolve(unitFiles, machineId());
			// ein Ordner ohne Dateien ist keine Einheit des Bestands: kein Eintrag (etwa bei einer Abfrage nach einer
			// unbekannten)
			if (!unitFiles.versions().isEmpty() || !unitFiles.claims().isEmpty()) {
				index.update(unitFiles, state);
			}
			return new Resolved(unitFiles, state);
		}
	}

	/** Trägt die Einheiten der geschriebenen Versionen in den Index ein; was sich nicht lesen lässt, kostet nur den Eintrag. */
	private void indexUnits(List<UploadJob.Item> items) {
		for (UnitKey unit : items.stream().map(UploadJob.Item::unit).distinct().toList()) {
			try {
				resolveLocal(unit);
			} catch (IOException | RuntimeException e) {
				LOGGER.log(Level.WARNING, "Der Stand von " + unit.relativePath() + " ließ sich nicht in den Index übernehmen", e);
			}
		}
	}

	// --- Takt

	/**
	 * Ein Takt (in der Anwendung alle 2 Sekunden im {@code worker}): Erreichbarkeit nur nach einem Fehler prüfen, bei
	 * Bedarf Vollabgleich, Aufträge fortsetzen, alle 30 Sekunden neue Rechner, Listen lesen, eigene Ansprüche der letzten
	 * 15 Minuten nachprüfen; dann Verluste und geänderte Einheiten melden und den Zustand setzen. Am Anfang, auch im
	 * Einzelbetrieb, wird der Start-Index geschrieben, wenn er sich geändert hat und das letzte Schreiben
	 * {@link #INDEX_FLUSH} her ist; sonst ohne Wirkung im Einzelbetrieb.
	 */
	void tick() {
		flushIndexIfDue();
		workLock.lock();
		try {
			if (closed) {
				return;
			}
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
				// der Datenordner wird neu gefüllt, und gleiche Dateinamen können anderen Inhalt haben
				clearIndex();
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
		enqueuePending(current, null);
		runJobs(current, round);
	}

	/** Die ausstehenden eigenen Versionen als Aufträge, in den genannten Einheiten ({@code null}: in allen). */
	private List<UploadJob> scanPending(String myId, Set<VersionId> skip, Collection<UnitKey> candidates)
			throws IOException {
		return candidates == null ? LocalScan.pendingJobs(local, myId, skip)
				: LocalScan.pendingJobs(local, myId, skip, candidates);
	}

	/**
	 * Reiht die ausstehenden Versionen ein, die noch in keinem Auftrag stehen, vor den übrigen Aufträgen.
	 *
	 * @param candidates die Einheiten, die untersucht werden; {@code null} heißt alle
	 */
	private void enqueuePending(Link current, Collection<UnitKey> candidates) throws IOException {
		Set<VersionId> skip;
		String myId;
		synchronized (lock) {
			skip = new HashSet<>(queued);
			myId = machine.id();
		}
		List<UploadJob> found = scanPending(myId, skip, candidates);
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
		// der Index gehört zu einer Kennung (die Stände hängen davon ab, was "eigen" ist): neu aufbauen, die alten Dateien
		// gelten als fremd
		index.deleteCleanMarker();
		index = new UnitIndex(appDataDir.resolve(INDEX), files, renewed.id());
		refreshIndex(null);
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

	/**
	 * Meldet Verluste (aus dem Zustand abgeleitet) und geänderte Einheiten eines Durchlaufs, auch nach einem Fehler. Die
	 * Ableitung löst jede geänderte Einheit über {@link #resolveLocal} auf: So steht sie im Index, bevor sie gemeldet wird.
	 */
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
		for (UnitKey unit : units) {
			try {
				Resolved resolved = resolveWithFiles(unit);
				LocalScan.losses(resolved.files(), resolved.state()).forEach(this::reportLoss);
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
