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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.AppPaths;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.store.ConnectOutcome.Kind;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
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
 * <b>Takt</b> (alle 2 Sekunden, {@link #tick()}): Erreichbarkeit prüfen (nach einem Fehler; sonst zuerst, ob der
 * gemeinsame Ordner noch da ist: fehlt er, ist der Zustand offline, und nichts legt ihn an), ein Stück des
 * Vollabgleichs, Aufträge fortsetzen, alle 30 Sekunden neue Rechner suchen, die Änderungslisten der anderen lesen, die
 * eigenen Ansprüche der letzten 15 Minuten im gemeinsamen Ordner nachlesen (späte Niederlage bei einem Sync-Client; nach
 * einem Neustart auch die aus den lokalen Anspruchsdateien, beim Verbinden mit Mitnehmen die dabei hochgeladenen;
 * höchstens 150 je Takt), vorgemerkte beschädigte Einheiten aus der Kopie im gemeinsamen Ordner heilen und beschädigte
 * Kopien dort aus der guten lokalen. Der <b>Vollabgleich</b> beginnt alle 10 Minuten (gezählt ab dem Ende des vorigen),
 * beim Start, nach der Rückkehr aus {@link SyncState#OFFLINE} und auf Wunsch; er läuft in Abschnitten ({@link FullSyncRun})
 * über mehrere Takte, je Takt höchstens {@link #FULL_SYNC_SLICE_BUDGET}, danach kommen die übrigen Schritte. Ein Abschnitt
 * ist ein Bündel Hallen, ein Event-Kopf, eine Vorstellung oder ein Block von höchstens {@code FullSyncRun.BOOKING_BLOCK}
 * Buchungsordnern; ein weiterer beginnt nur, wenn er nach der Dauer des vorigen noch ins Budget passt, der erste läuft
 * immer. Sein Zeiger hängt an der Verbindung: Nach einem Ausfall setzt er dort fort, eine neue Verbindung verwirft
 * ihn. Ein Wunsch während eines Laufs beginnt ihn nicht neu, sondern direkt nach seinem Ende einen neuen; den
 * Fortschritt zeigt {@link #fullSyncProgress()}. Im ersten Lauf einer Verbindung ({@link #connect}) folgen die Takte ohne
 * Pause aufeinander ({@link #tick()} liefert {@code true}, {@code tickSafely} wiederholt sofort), solange der Ordner
 * erreichbar ist und nichts scheitert; die Arbeitssperre wird dazwischen frei. Sein Ende liefert das Ergebnis von
 * {@link #connect} und vervollständigt die Marke "verbunden" ({@link #connectionComplete()}).
 * <p>
 * <b>Marke "verbunden"</b> ({@code <AppData>/connection.json}, nur lokal): Ordner, Zeitpunkt und ob der erste Lauf zu Ende
 * war. Der erste Lauf schreibt sie an seinem Beginn als unvollständig und an seinem Ende als vollständig,
 * {@link #disconnect} löscht sie; {@link #connectedDir()} und {@link #connectionComplete()} geben sie wieder (gelesen
 * beim Erzeugen des Dienstes, danach im Speicher nachgeführt), und der nächste
 * Start wählt seinen Weg nach ihr (die Oberfläche setzt einen unvollständigen ersten Lauf ohne Rückfrage fort). Im Modus
 * {@link ConnectMode#ASK} prüft der Beginn des ersten Laufs an den Dateien, ob beide Seiten Einheiten haben; dann
 * pausiert der Abgleich ({@link SyncState#ENTSCHEIDUNG}, noch ohne Marke), bis {@link #decideConnect} entscheidet; jeder
 * Takt prüft dann die Erreichbarkeit, löst lokal auf wie ohne Verbindung und schreibt den Index, wenn er fällig ist, lädt
 * aber nichts hoch und holt nichts ab. Die <b>Schutzregel</b> verbietet jedes Beiseitelegen, sobald dieser Programmlauf
 * nach dem Start eigene Versionen geschrieben hat (siehe {@link #connect}). Vertrag: {@link #startup} vor {@link #connect}
 * aufrufen; erst ab dem Ende von {@link #startup} zählen eigene Versionen für die Schutzregel, ohne Start greift sie nie.
 * <p>
 * Kein Takt schreibt über einer gültigen Marke "sauber beendet": Jeder macht sie vor der Arbeit am gemeinsamen Ordner und
 * vor der Auflösung ungültig ({@link UnitIndex#invalidateCleanMarker}); gelingt das nicht, prüft er nur die Erreichbarkeit.
 * <p>
 * Trifft ein fremder Listeneintrag einen Platz, dessen lokaler Anspruch
 * abweicht, wird sofort verglichen: So fällt eine späte Niederlage auch nach Ablauf der Beobachtung auf. Deshalb
 * überspringt auch der Vollabgleich keine Liste: Er lässt die Positionen stehen, und jede Liste wird gelesen (nach einem
 * Verlust von {@code sync-state.json} oder für einen neuen Rechner einmal der ganze Rückstand, 200 Listen je Takt).
 * <p>
 * <b>Verluste</b> werden nicht nur aus Ereignissen gemeldet, sondern aus dem Zustand abgeleitet: Für jede neu
 * aufgelöste Einheit (am Ende des ersten Vollabgleichs nach dem Start oder Verbinden außerdem jede, die der Index als
 * verloren kennt) wird jede verlorene Strecke gemeldet, deren Versionen in diesem
 * Programmlauf noch nicht gemeldet wurden. So geht kein Verlust durch einen Absturz verloren, und keiner kommt doppelt.
 * Jede gemeldete Einheit (auch eine schon gemeldete) kommt außerdem in die Warteschlange des {@link ConflictResolver}, der
 * die Strecke mit der {@link ConflictPolicy} ihrer Art zu einer Zusammenführung auflöst: im Takt nach dem Hochladen
 * (höchstens {@value #RESOLVE_BUDGET} Einheiten), beim Start, bevor {@link #startup} zurückkehrt (dann auch die
 * Einheiten, die der Index als verloren kennt).
 * <p>
 * <b>Start-Index:</b> Der Stand jeder Einheit steht zusätzlich in einem lokalen Index ({@link UnitIndex}, Ordner
 * {@code <AppData>/index}, neben {@code data/}), den {@link #snapshots()} ohne Dateizugriff liefert, mit der gültigen Kette ohne Inhalt
 * ({@link #chains}) für den Verlauf. {@link #startup} gleicht ihn in
 * einem Durchlauf über die Verzeichnisnamen mit dem Bestand ab und löst nur Einheiten neu auf, deren Dateinamen sich
 * geändert haben; nur die dabei gefundenen Kandidaten mit ausstehenden eigenen Versionen untersucht der Start auf dem
 * Thread des Aufrufers. Jede Stelle, die den Stand einer Einheit ermittelt, trägt ihn danach in den Index ein. Gespeichert
 * wird er von {@link #flushIndex()} und im Takt alle {@link #INDEX_FLUSH}, nicht vor dem Abgleich durch {@link #startup}.
 * Bei einer neuen Rechner-ID (SYS-011) wird er nicht neu aufgebaut: Er übernimmt sie, und die früheren IDs zählen bis zum
 * Wechsel weiter als eigen ({@link Ownership}). Beim Beenden ruft die Anwendung {@link #closeIndex()}: Es schreibt den Index und legt die Marke "sauber beendet" an. Findet
 * {@link #startup} sie vor, übernimmt es den Index ohne jeden Durchlauf über den Bestand (zweite Stufe); ohne Marke,
 * also nach einem Absturz, bleibt es beim Abgleich über die Verzeichnisnamen. Der Index ist nie Schiedsrichter: Jede Entscheidung über Vorgänger, Ansprüche und Verluste trifft {@link ChainResolver}
 * auf den Dateien. Er nennt nur, welche Einheiten gelesen werden: Die Abläufe im {@code worker} lesen die Kandidaten des
 * Index (ausstehende, verlorene, junge eigene Glieder) und die Einheiten, die nicht im Index stehen, dazu, was der
 * Vollabgleich gerade abgleicht und was sich geändert hat; nicht mehr in jedem Takt alle Einheiten.
 * <p>
 * <b>Offene Konflikte:</b> {@link #openConflicts()} leitet sie aus dem Index ab ({@link ConflictLedger}), bestätigt werden sie
 * mit {@link #acknowledge} ({@code <AppData>/conflicts-ack.json}). Eine Elternlöschung hängt an der Nummer der eigenen
 * Änderungsliste, in der der Verkauf steht; kennt der Index sie nicht (nach einem Neuaufbau), liest der Start bzw. der Takt
 * einmal die eigenen Listen im gemeinsamen Ordner und meldet dann {@link SyncListener#conflictsChanged}. Dieses Signal kommt
 * außerdem, wenn eine Liste mit einer fremden Version mit Vermerk oder einer Löschung eingelesen wurde.
 * <p>
 * <b>Threads:</b>
 * <ul>
 * <li>Jeder Zugriff auf den gemeinsamen Ordner läuft im {@code worker}, in der Anwendung ein einzelner Daemon-Thread,
 * der auch den Takt ausführt; auch die Heilung beschädigter Dateien aus der Kopie dort läuft im Takt. Die Oberfläche
 * wartet nie darauf, mit einer Ausnahme: {@link #startup} prüft die Erreichbarkeit (höchstens 5 Sekunden) und lädt
 * ausstehende Versionen hoch, bevor das Hauptmenü erscheint.</li>
 * <li>{@link #startup}, {@link #connect}, {@link #decideConnect} und {@link #disconnect} nehmen die Arbeitssperre
 * ({@code workLock}) und warten deshalb auf das Ende des laufenden Takts, damit kein Hochladen der alten Verbindung mit
 * dem Wechsel überlappt; sie dürfen nie auf dem FX-Thread laufen. Die Sperre ist fair: Sie kommen zwischen zwei Takten
 * dran, auch im ersten Lauf ohne Pause.</li>
 * <li>{@link #append} und die Abfragen arbeiten nur auf dem lokalen Bestand und laufen auf dem Thread des Aufrufers;
 * auch die Auflösung im {@code worker} ({@link ConflictResolver}) ruft {@link #append} auf, um ihre Zusammenführungen
 * zu schreiben, gleichzeitig mit der Oberfläche.</li>
 * <li>Zustand ({@link #syncState()}, {@link #lastSync()}) und alle Rückrufe kommen über den {@code ui}-Executor an, in
 * der Anwendung {@code Platform::runLater}.</li>
 * </ul>
 * <b>Sperrfolge</b> (wer mehrere hält, nimmt sie nur in dieser Reihenfolge): {@code workLock} (Takt, Hochladen beim
 * Start) → Sperre der Auflösung ({@link ConflictResolver#resolvePending}) → {@code resolveLock} (lesen, auflösen,
 * eintragen) → Monitor des Index bzw. {@code lock}. {@code lock} (Felder des Dienstes) und der Monitor des Index werden
 * nie ineinander genommen: Unter {@code lock} gibt es keinen Dateizugriff und keinen Index, nur kurz den Monitor des
 * {@link SyncNotifier}. {@code flushLock} des Index wird vor dessen Monitor genommen, nie darunter. {@link #append} nimmt
 * nur {@code resolveLock}, {@code lock} und den Index, jeweils kurz und nacheinander; niemand hält {@code lock}, während
 * {@link #append} läuft. Deshalb darf {@link #append} von der Oberfläche und aus dem {@code worker} zugleich kommen. Nur
 * wenn die Verbindung während {@link #append} gewechselt hat, nimmt es zuletzt {@code workLock} (ohne eine andere Sperre
 * zu halten) und wartet dann auf den laufenden Takt. Während der lokale Bestand beiseitegelegt wird (unter
 * {@code workLock}), wartet ein beginnendes {@link #append} an einer Bedingung von {@code lock} (ohne eine Sperre zu
 * halten), und das Beiseitelegen wartet dort auf das Ende laufender lokaler Schreibvorgänge; keiner von beiden braucht
 * dabei eine Sperre, die der andere hält.
 */
public final class SyncService {

	private static final Logger LOGGER = Logger.getLogger(SyncService.class.getName());
	/** Der Logtext, wenn {@link #closeIndex()} die Marke "sauber beendet" wegen eines laufenden {@link #append} auslässt. */
	private static final String SAVE_IN_PROGRESS =
			"Ein Speichervorgang schreibt gerade in den lokalen Bestand; die Marke 'sauber beendet' entfällt";

	/** Abstand der Takte. */
	static final Duration TICK = Duration.ofSeconds(2);
	/** So oft wird nach neuen Rechnern gesucht. */
	static final Duration DISCOVERY_INTERVAL = Duration.ofSeconds(30);
	/** So oft läuft ein Vollabgleich (gezählt ab dem Ende des vorigen). */
	static final Duration FULL_SYNC_INTERVAL = Duration.ofMinutes(10);
	/**
	 * So lange hält ein Takt höchstens die Arbeitssperre für die Abschnitte des Vollabgleichs, gerechnet ab Beginn des Takts:
	 * Ein weiterer Block beginnt nur, wenn er nach der Dauer des vorigen noch ins Budget passt. Der erste Block eines Takts
	 * läuft immer; ist ein einzelner Block länger als das Budget, dauert der Takt so lange. Danach kommen die übrigen Schritte
	 * des Takts, und der nächste Takt setzt den Lauf fort.
	 */
	static final Duration FULL_SYNC_SLICE_BUDGET = Duration.ofSeconds(2);
	/** So lange werden eigene gewonnene Ansprüche auf eine späte Niederlage geprüft. */
	static final Duration LATE_DEFEAT_WINDOW = Duration.ofMinutes(15);
	/**
	 * Zugabe für die Uhr, wenn der Index die Einheiten mit jungen eigenen Köpfen nennt: Die Versions-ID trägt die Zeit des
	 * Schreibens, der Anspruch kommt später, und die Uhr eines Rechners geht nicht ganz gleich.
	 */
	private static final Duration CLOCK_SLACK = Duration.ofMinutes(15);
	/** So viele beobachtete Ansprüche liest ein Takt höchstens, die am längsten nicht geprüften zuerst. */
	static final int LATE_DEFEAT_BUDGET = 150;
	/** Eigene temporäre Dateien, die älter sind, räumt der Start weg. */
	static final Duration TEMP_AGE = Duration.ofDays(1);
	/** So selten schreibt der Takt den Start-Index. */
	static final Duration INDEX_FLUSH = Duration.ofSeconds(5);
	/** So lange wartet {@link #closeIndex()} auf eine laufende Runde im Hintergrund. */
	static final Duration CLOSE_WAIT = Duration.ofSeconds(3);
	/** So viele eingereihte Einheiten löst ein Takt höchstens auf; der Rest kommt im nächsten. */
	static final int RESOLVE_BUDGET = 200;

	private static final int MAX_REPAIRS = 10;
	private static final int MAX_ID_ATTEMPTS = 100;
	private static final String DATA = "data";
	private static final String INDEX = "index";
	private static final String SYNC_STATE = "sync-state.json";
	private static final String CONFLICTS_ACK = "conflicts-ack.json";
	private static final String ACKNOWLEDGED = "acknowledged";
	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	/** Für Aufrufer ohne Fortschrittsanzeige. */
	private static final DoubleConsumer NO_PROGRESS = fraction -> {
	};

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
	 * Die Entscheidung am Beginn des ersten Laufs einer Verbindung, einmal je Verbindung gefasst ({@link Link#decision}).
	 *
	 * @param setAside          ob der lokale Bestand noch beiseitezulegen ist; nach dem Beiseitelegen {@code false}, damit
	 *                          nie ein zweites Mal beiseitegelegt wird
	 * @param uploadLocalClaims ob der erste Lauf alle lokalen Ansprüche hochlädt (Mitnehmen; auch, wenn eine Seite leer ist)
	 */
	private record ConnectDecision(boolean setAside, boolean uploadLocalClaims) {
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
		/**
		 * Beim Verbinden: die Entscheidung des Aufrufers, bis der erste Vollabgleich zu Ende ist. {@link ConnectMode#ASK}, bis
		 * der Beginn an den Dateien vereinigt oder {@link SyncService#decideConnect} antwortet; auch nach einem Beiseitelegen,
		 * das die Schutzregel verbot.
		 */
		volatile ConnectMode connectMode;
		/** Vor der ersten Arbeit die Erreichbarkeit prüfen (nach dem Verbinden). */
		volatile boolean probeFirst;
		volatile boolean fullSyncWanted = true;
		/**
		 * Der laufende Vollabgleich mit seinem Zeiger; {@code null} ohne Lauf. Gehört zu dieser Verbindung: Eine neue verwirft
		 * ihn. Benutzt nur unter der Arbeitssperre; andere Threads fragen nur, ob es ihn gibt.
		 */
		volatile FullSyncRun run;
		/** Ein Vollabgleich wurde gewünscht, während einer lief: Nach dessen Ende beginnt sofort ein neuer. */
		volatile boolean wantedDuringRun;
		/** Ob der laufende Vollabgleich alle lokalen Ansprüche hochlädt (Mitnehmen); entschieden bei seinem Beginn. */
		volatile boolean uploadLocalClaims;
		/**
		 * Die Entscheidung des Verbindens ({@link ConnectDecision}), gefasst beim ersten Beginn eines Laufs, sobald beide Seiten
		 * gelesen sind; {@code null} bis dahin. Ein wiederholter Beginn (nach einem Fehler danach) entscheidet nie neu, sondern
		 * setzt mit ihr fort: Neu entschieden sähe er einen inzwischen gespeicherten Verkauf als lokale Einheit und legte ihn
		 * beiseite, ob zuvor schon beiseitegelegt wurde oder nicht. Benutzt nur unter der Arbeitssperre.
		 */
		volatile ConnectDecision decision;
		/** Der erste Vollabgleich steht noch aus: danach werden die Verluste aller Einheiten abgeleitet. */
		volatile boolean allUnitsWanted = true;
		volatile Instant lastFullSync;
		volatile Instant lastDiscovery;
		/**
		 * Das Ergebnis von {@link SyncService#connect} bis zum ersten Ausgang; danach (und ohne {@code connect}) {@code null}.
		 * Festgelegt wird es nur unter der Arbeitssperre ({@link SyncService#settle}).
		 */
		volatile CompletableFuture<ConnectOutcome> outcome;
		/**
		 * Der erste Vollabgleich dieser Verbindung läuft (ab {@link SyncService#connect} bis zu seinem Ende): Er schreibt die
		 * Marke "verbunden", und seine Takte folgen ohne Pause aufeinander. Nicht bei einer Verbindung aus
		 * {@link SyncService#startup}.
		 */
		volatile boolean firstRun;
		/** Wohin diese Verbindung den lokalen Bestand beiseitegelegt hat; {@code null}, solange nicht. */
		volatile Path setAsidePath;
		/**
		 * Der erste Lauf ist zu Ende, sein Ergebnis aber noch nicht festgelegt. Gesetzt in {@code endRun}; der Takt legt es
		 * fest, auch wenn danach in ihm etwas Unerwartetes wirft ({@link SyncService#settleFirstRunEnd}). Unter der Arbeitssperre.
		 */
		volatile boolean endOutcomeDue;
		/**
		 * Ob der letzte Takt den Ordner erreichte und ohne Störung durchlief: Nur dann folgt im ersten Lauf der nächste Takt
		 * sofort. Eigenes Feld, unabhängig davon, ob der Takt die Auflösung schon erledigt hat.
		 */
		volatile boolean hurry;
		/**
		 * Der Abgleich pausiert vor dem Beginn des ersten Laufs (Zustand {@link SyncState#ENTSCHEIDUNG}), bis
		 * {@link SyncService#decideConnect} entscheidet: Beide Seiten haben Einheiten (Modus {@link ConnectMode#ASK}), oder die
		 * Schutzregel verbot das Beiseitelegen. Endet auch, wenn der Ordner nicht erreichbar ist; nach seiner Rückkehr prüft
		 * der Beginn die Dateien erneut und fragt erneut. Unter der Arbeitssperre.
		 */
		volatile boolean deciding;

		Link(String text, Path path, VersionStore shared, SyncPositions positions) {
			this.text = text;
			this.path = path;
			this.shared = shared;
			this.positions = positions;
		}

		/**
		 * Leser und Schreiber der Änderungslisten für diese Rechner-Kennung: Der Schreiber schreibt unter der aktuellen ID,
		 * der Leser weiß auch die früheren.
		 */
		void bind(Ownership own, VersionStore local, Clock clock) {
			if (shared != null) {
				reader = new FeedReader(shared, local, own, positions, clock);
				writer = new FeedWriter(shared, own.currentId(), clock);
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
	/** Löst die gemeldeten Verluste zu Zusammenführungen auf; eingereiht wird in {@link #reportLoss}. */
	private final ConflictResolver resolver;
	/**
	 * Die Marke "verbunden", wie sie in {@code connection.json} steht; {@code null} ohne (lesbare) Marke. Gelesen im
	 * Konstruktor, danach bei jedem gelungenen Schreiben ({@link #writeConnectionMarker}) und Löschen ({@link #disconnect})
	 * nachgeführt; ein gescheitertes ändert die Datei nicht (atomares Ersetzen) und deshalb auch dieses Feld nicht. Nur dieser
	 * Dienst schreibt die Datei (ein Programm je Rechner, {@link InstanceLock}); {@link #connectedDir()} liest deshalb
	 * ohne Dateizugriff.
	 */
	private volatile ConnectionMarker.State connection;

	/**
	 * Der Start-Index; hat eine eigene Sperre, deshalb nie unter {@code lock} benutzen. Bei einer neuen Rechner-ID bleibt er
	 * und übernimmt sie ({@link UnitIndex#adoptOwnership}); neu nur beim Notfall-Neuaufbau des Starts.
	 */
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
	/**
	 * Einheiten, deren Eintrag im Index fehlt oder veraltet ist, weil das Auflösen scheiterte (etwa ein gesperrtes Lesen nach dem
	 * Schreiben). Sie sind Kandidaten für Ausstehendes und Verlorenes, auch wenn der Index sie nicht nennt, und verhindern die
	 * Marke "sauber beendet". Geschrieben unter {@code resolveLock}; eine nebenläufige Menge ohne eigene Sperre, damit sie in
	 * keine Sperrfolge gerät.
	 */
	private final Set<UnitKey> unindexed = ConcurrentHashMap.newKeySet();
	/**
	 * Einheiten, die {@link #reportLosses} aufgelöst hat, deren Verluste es danach aber nicht ableiten oder melden konnte (ein
	 * ungeprüfter Fehler nach dem Auflösen). Jede Runde ({@link #reportChanges}) versucht es erneut, bis es gelingt; ein
	 * Auflösen ohne Melden (etwa {@link #unitState}) nimmt sie nicht heraus. Eine nebenläufige Menge ohne eigene Sperre.
	 */
	private final Set<UnitKey> unreported = ConcurrentHashMap.newKeySet();
	/**
	 * Die Einheiten, die der laufende Durchlauf ({@link Round}) schon aufgelöst, eingetragen und gemeldet hat: die geänderten
	 * Einheiten der Blöcke des Vollabgleichs ({@link #takeOverSection}). Das Melden am Ende ({@link #reportChanges}) löst sie
	 * nicht noch einmal auf. Gefüllt und gelesen nur auf dem Thread des Durchlaufs unter {@code workLock}; jeder Durchlauf
	 * beginnt leer. Wer eine Einheit danach ändert, nimmt sie heraus: der Takt vor dem Schreiben ({@link Round#unsettle},
	 * {@link Round#touch}), {@link #writeLocal} nach dem Schreiben, auch auf dem Thread der Oberfläche. Deshalb eine
	 * nebenläufige Menge ohne eigene Sperre: Sie gerät in keine Sperrfolge, und weil der Block eine Einheit vor dem Auflösen
	 * einträgt, nimmt ein Schreiben, das sein Auflösen nicht mehr sieht, sie danach sicher wieder heraus.
	 */
	private final Set<UnitKey> settled = ConcurrentHashMap.newKeySet();
	/** Schützt {@link #acknowledged}; die letzte Sperre der Folge (unter ihr nur die kleine Datei, keine weitere Sperre). */
	private final Object ackLock = new Object();
	/** Die bestätigten Konflikte ({@code conflicts-ack.json}); {@code null}, solange die Datei nicht gelesen wurde. */
	private Set<ConflictKey> acknowledged;
	/** Die zuletzt gemeldeten offenen Konflikte (siehe {@link #reportConflictChanges}); unter {@code ackLock}. */
	private Set<ConflictKey> reportedConflicts = new HashSet<>();
	/**
	 * Ob offene Elternlöschungen auf die eigenen Listen warten könnten: gesetzt, wenn eine Verbindung aktiviert, der Index
	 * neu aufgebaut oder die Rechner-ID neu vergeben wird (die Listennummern sind dann unbekannt), auch bei einem Start ohne
	 * Verbindung; der Takt prüft es und
	 * liest die Listen nur, wenn eine Entscheidung fehlt.
	 */
	private volatile boolean ownListsWanted;
	/** Ob {@link #startup} zu Ende ist (gesetzt am Ende jedes Wegs, auch nach einem Fehler); erst danach zählt die Schutzregel. */
	private volatile boolean startupDone;
	/**
	 * Schutzregel: Dieser Programmlauf hat nach dem Ende von {@link #startup} eigene Versionen geschrieben (öffentliches
	 * {@link #append}, nicht die Auflösung). Dann ist Beiseitelegen verboten, denn die Verkäufe verschwänden im
	 * beiseitegelegten Ordner. Wird nie zurückgesetzt.
	 */
	private volatile boolean wroteSinceStartup;

	/**
	 * Serialisiert die Arbeit am gemeinsamen Ordner und jeden Wechsel der Verbindung: Takt, {@link #startup} (ganz, samt
	 * Index und Hochladen), {@link #connect}, {@link #decideConnect}, {@link #disconnect}, {@link #closeIndex()}. Die
	 * Verbindung ({@code link}) ändert sich nur unter ihr. Fair, damit ein wartendes {@code connect},
	 * {@code disconnect} oder {@code closeIndex} zwischen zwei Takten drankommt und nicht hinter jedem neuen Takt wartet.
	 */
	private final ReentrantLock workLock = new ReentrantLock(true);
	/**
	 * Ergebnisse von {@link #connect}, die unter {@code workLock} feststehen und erst nach deren Freigabe geliefert werden
	 * ({@link #unlockWork}): Abhängige Stufen ohne eigenen Executor laufen auf dem liefernden Thread und sollen dabei keine
	 * Sperre des Dienstes vorfinden. Nur unter {@code workLock} benutzt.
	 */
	private final List<Runnable> outcomesDue = new ArrayList<>();
	/**
	 * Der Thread des Takts wurde unterbrochen, während das Beiseitelegen auf laufende Speichervorgänge wartete
	 * ({@link #setAsideLocal}); {@link #tick()} stellt die Unterbrechung nach der Freigabe der Arbeitssperre wieder her. Nur
	 * unter {@code workLock} benutzt.
	 */
	private boolean interruptAfterTick;

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
	 * Wie viele {@link #append} gerade im lokalen Bestand arbeiten: von der ersten Versionsdatei bis zum Einreihen bzw. zum
	 * lokalen Beanspruchen (ohne {@link #settleAfterConnectionChange}, das {@code workLock} nimmt). Das Beiseitelegen
	 * wartet, bis es keine mehr sind ({@link #setAsideLocal}); gewartet wird an {@code lock}.
	 */
	private int localWrites;
	/**
	 * Wie viele {@link #append} je mit dem lokalen Schreiben begonnen haben (nur steigend, unter {@code lock}): Daran erkennt
	 * {@link #closeIndex()}, ob eines während des Schreibens der Marke "sauber beendet" begann.
	 */
	private long localWritesBegun;
	/**
	 * Der lokale Bestand wird gerade beiseitegelegt (von der Prüfung der Schutzregel bis der neue Datenordner bereit ist);
	 * ein {@link #append}, das beginnen will, wartet an {@code lock}.
	 */
	private boolean settingAside;

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
		this.connection = ConnectionMarker.read(appDataDir).orElse(null);
		// eine unvollständige Marke "verbunden": Der erste Lauf einer Verbindung kam nicht zu Ende (auch über einen Neustart)
		this.notifier = new SyncNotifier(ui, connection == null || connection.complete());
		this.machine = machine;
		this.ids = new VersionIdGenerator(machine.id(), clock);
		this.index = newIndex(machine.ownership());
		this.resolver = new ConflictResolver(new ResolverHost(), notifier, clock);
	}

	/** Ein neuer, noch leerer Index im Index-Ordner dieser Installation, für die Kennung des Rechners. */
	private UnitIndex newIndex(Ownership own) {
		return new UnitIndex(appDataDir.resolve(INDEX), files, own);
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

	/**
	 * Setzt die Fachregel, mit der verlorene Strecken dieser Einheitenart zusammengeführt werden; vor {@link #startup}
	 * setzen, damit schon die Auflösung beim Start sie benutzt. {@code null} entfernt sie: Dann bleibt "Deren", und der
	 * Vermerk {@link ConflictNote.Kind#UNMERGED} sagt diesem Rechner, dass sein Inhalt weicht.
	 */
	public void setConflictPolicy(UnitType type, ConflictPolicy policy) {
		resolver.setPolicy(type, policy);
	}

	/** Für Tests: die Auflösung der Verluste. */
	ConflictResolver resolver() {
		return resolver;
	}

	/** Für Tests: der Start-Index (etwa um das Löschen der Marke "sauber beendet" scheitern zu lassen). */
	UnitIndex index() {
		return index;
	}

	/** Der Zustand des Austauschs; Änderungen kommen über den {@code ui}-Executor an. */
	public ReadOnlyObjectProperty<SyncState> syncState() {
		return notifier.stateProperty();
	}

	/** Zeitpunkt des letzten erfolgreichen Takts, {@code null} solange keiner gelang. */
	public ReadOnlyObjectProperty<Instant> lastSync() {
		return notifier.lastSyncProperty();
	}

	/**
	 * Der Fortschritt des laufenden Vollabgleichs: der Anteil erledigter Abschnitte von 0.0 bis 1.0, {@code null} ohne
	 * laufenden Lauf (auch im Einzelbetrieb). Änderungen kommen über den {@code ui}-Executor an.
	 */
	public ReadOnlyObjectProperty<Double> fullSyncProgress() {
		return notifier.fullSyncProgressProperty();
	}

	/**
	 * Ob der erste Vollabgleich der Verbindung zu Ende kam: {@code false} von {@link #connect} bis zum Ende dieses Laufs,
	 * auch über einen Neustart hinweg, solange die Marke "verbunden" unvollständig ist (Startwert aus der Marke);
	 * {@code true} im Einzelbetrieb, nach {@link #disconnect} und sobald die Marke vollständig ist. Änderungen kommen über
	 * den {@code ui}-Executor an.
	 */
	public ReadOnlyBooleanProperty connectionComplete() {
		return notifier.connectionCompleteProperty();
	}

	/**
	 * Der gemeinsame Ordner aus der Marke "verbunden" ({@code <AppData>/connection.json}): mit welchem Ordner zuletzt
	 * verbunden wurde, ob der erste Lauf zu Ende kam oder nicht; leer ohne (lesbare) Marke. Ohne Dateizugriff: Der Dienst
	 * liest die Marke beim Erzeugen und führt sie bei jedem Schreiben und Löschen nach ({@link #connection}), unabhängig
	 * davon, ob er gestartet ist.
	 */
	public Optional<String> connectedDir() {
		return Optional.ofNullable(connection).map(ConnectionMarker.State::dir);
	}

	/** Der gemeinsame Ordner, wie er eingestellt wurde (ohne Leerzeichen am Rand); {@code null} im Einzelbetrieb. */
	public String sharedDir() {
		Link current = currentLink();
		return current == null ? null : current.text;
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
	 * Zuletzt (in jedem Betrieb, auch ohne Verbindung) löst der Start die verlorenen Strecken auf, die er gefunden hat oder
	 * die der Index als verloren kennt ({@link ConflictResolver}), damit die Anwendung schon den aufgelösten Stand lädt.
	 *
	 * @param sharedDir der gemeinsame Ordner aus den Einstellungen; leer oder {@code null} heißt Einzelbetrieb
	 * @return {@code false}, wenn der gemeinsame Ordner nicht erreichbar ist; sonst {@code true}
	 */
	public boolean startup(String sharedDir) {
		return startup(sharedDir, NO_PROGRESS);
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
	 * <p>
	 * Der ganze Start läuft unter der Arbeitssperre; er wartet deshalb auf das Ende eines laufenden Takts, und kein Takt
	 * läuft neben ihm. Nicht auf dem FX-Thread aufrufen.
	 *
	 * @param progress bekommt den Anteil der abgeglichenen Einheiten von 0.0 bis 1.0, zuletzt 1.0; darf {@code null} sein
	 * @throws UncheckedIOException wenn der Bestand nicht vollständig gelesen werden kann; die Meldung nennt die Einheit
	 */
	public boolean startup(String sharedDir, DoubleConsumer progress) {
		Started started;
		// ein einziger Abschnitt unter der Arbeitssperre, vor jeder inneren Sperre: Kein Takt, kein closeIndex und kein
		// Wechsel der Verbindung überschneidet sich mit dem Öffnen, dem Index, der Verbindung oder dem Hochladen beim Start
		workLock.lock();
		try {
			started = startLocked(sharedDir, progress != null ? progress : NO_PROGRESS);
		} finally {
			// ab hier zählen eigene Versionen für die Schutzregel; was der Start selbst schrieb (Auflösung), zählt nicht
			startupDone = true;
			unlockWork();
		}
		if (started == Started.CONNECTED) {
			// ohne Sperre: Der Takt nimmt sie selbst
			schedule();
		}
		return started != Started.UNREACHABLE;
	}

	/** Wie {@link #startup} ausging: im Einzelbetrieb, ohne erreichbaren Ordner oder verbunden (der Takt folgt sofort). */
	private enum Started {
		SINGLE, UNREACHABLE, CONNECTED
	}

	/**
	 * Der Start unter {@code workLock}. Die Auflösung (Sperre der Auflösung) und ein append daraus folgen der Sperrfolge;
	 * die Verbindung wechselt nur unter {@code workLock} und bleibt deshalb bis zum Ende dieselbe.
	 */
	private Started startLocked(String sharedDir, DoubleConsumer progress) {
		// ein offenes Ergebnis eines connect (etwa nach closeIndex) endet, mit oder ohne Ordner: Der Start ersetzt die Verbindung
		abortPendingOutcome();
		reopen();
		loadAcknowledgedAtStart();
		UnitIndex.Refresh opened = openIndex(progress);
		String dir = clean(sharedDir);
		if (dir == null) {
			// die Marke "verbunden" bleibt (KARV-87 trennt ausdrücklich mit disconnect)
			deactivate();
			claimPendingLocally(withUnindexed(opened.pendingUnits()));
			resolveAtStartup(opened.lostUnits());
			return Started.SINGLE;
		}
		Link current = activate(dir, null);
		if (current.path == null || !probe(current.path)) {
			notifier.unreachable(dir, false);
			// ohne Verbindung wird lokal aufgelöst und später hochgeladen
			resolveAtStartup(opened.lostUnits());
			return Started.UNREACHABLE;
		}
		Round round = new Round(current);
		round.startup = true;
		round.run(() -> {
			enqueuePending(current, withUnindexed(opened.pendingUnits()));
			runJobs(current, round);
		});
		reportChanges(round);
		round.run(() -> decideUnknownListNumbers(round));
		resolveAtStartup(opened.lostUnits());
		if (isCurrent(current)) {
			if (round.unreachable) {
				notifier.unreachable(dir, false);
				return Started.UNREACHABLE;
			}
			if (round.failure != null) {
				notifier.failed(failureText(current, round.failure), round.failure);
			} else {
				notifier.setState(hasJobs() ? SyncState.AUSSTEHEND : SyncState.AKTUELL);
			}
		}
		return Started.CONNECTED;
	}

	/**
	 * Verbindet mit einem gemeinsamen Ordner; die Arbeit läuft im Hintergrund. Hat eine Seite keine Einheiten, wird ohne
	 * Rücksicht auf den Modus vereinigt. Sonst gilt der Modus: {@link ConnectMode#MERGE} gleicht ab und legt die lokalen
	 * Anspruchsdateien auch im gemeinsamen Ordner an (ein anderer Anspruch dort gewinnt, die eigene Version ist dann
	 * verloren); {@link ConnectMode#SET_ASIDE} benennt den lokalen Bestand in {@code data-beiseite-<Zeitstempel>} um (nie
	 * gelöscht) und gleicht in einen leeren Bestand ab; {@link ConnectMode#ASK} pausiert den Abgleich vor dem Beginn des
	 * ersten Laufs (Zustand {@link SyncState#ENTSCHEIDUNG}, Rückruf {@link SyncListener#connectDecisionNeeded}), bis
	 * {@link #decideConnect} entscheidet. Entschieden wird am Beginn des Laufs an den Dateien, sobald der Ordner erreichbar
	 * ist; bis dahin bleiben eigene Änderungen vorläufig. Leer oder {@code null} trennt.
	 * <p>
	 * <b>Schutzregel:</b> Hat dieser Programmlauf nach dem Ende von {@link #startup} eigene Versionen geschrieben (auch ein
	 * Speichervorgang, der gerade schreibt), wird nie beiseitegelegt, auch nicht mit {@link ConnectMode#SET_ASIDE}: Seine
	 * Verkäufe verschwänden sonst im beiseitegelegten Ordner. Geprüft wird beim Rückruf, in {@link #decideConnect} und
	 * unmittelbar vor jedem Versuch des Beiseitelegens; verbietet sie es dort, pausiert der Abgleich wie bei
	 * {@link ConnectMode#ASK}, mit {@code setAsideAllowed = false}. Was {@link #startup} und die Auflösung schreiben, zählt
	 * nicht. Ein Speichervorgang, der während des Beiseitelegens beginnt, wartet, bis der neue Datenordner bereit ist.
	 * Vertrag: {@link #startup} vor {@code connect} aufrufen. Erst ab dem Ende von {@link #startup} zählen eigene Versionen
	 * für die Schutzregel; ohne Start greift sie nie.
	 * <p>
	 * Der erste Vollabgleich der Verbindung schreibt an seinem Beginn (nach der Entscheidung und dem Beiseitelegen) die Marke
	 * "verbunden" als unvollständig, an seinem Ende als vollständig; bis dahin ist {@link #connectionComplete()}
	 * {@code false}, und seine Takte folgen ohne Pause aufeinander (die Arbeitssperre wird dazwischen frei). In der Pause gibt
	 * es noch keine Marke: Nach einem Neustart fragt der Start erneut.
	 * <p>
	 * Wartet auf das Ende des laufenden Takts; nicht auf dem FX-Thread aufrufen. So überlappt kein Hochladen der alten
	 * Verbindung mit dem Wechsel.
	 *
	 * @return das Ergebnis, genau einmal: {@link Kind#AKTUELL} bzw. {@link Kind#AUSSTEHEND} am Ende des ersten Laufs,
	 *         vorher {@link Kind#ENTSCHEIDUNG} (der Abgleich pausiert, siehe oben), {@link Kind#OFFLINE} (Ordner nicht
	 *         erreichbar), {@link Kind#FEHLER} (ein Abschnitt scheitert) oder
	 *         {@link Kind#ABGEBROCHEN} (getrennt oder neu verbunden); jeweils mit dem Pfad des beiseitegelegten Bestands,
	 *         falls schon beiseitegelegt. Bei leerem Pfad sofort {@link Kind#ABGEBROCHEN}. Nach {@link #closeIndex()} (der
	 *         Takt ruht dann) liefert ein noch offenes Ergebnis erst ein späteres {@code connect}, {@link #disconnect} oder
	 *         {@link #startup}, jeweils mit {@link Kind#ABGEBROCHEN}. Geliefert wird ohne gehaltene
	 *         Sperre des Dienstes; abhängige Stufen ohne eigenen Executor laufen auf dem liefernden Thread (im Takt dem
	 *         {@code worker}, sonst dem Aufrufer von {@code connect} bzw. {@link #disconnect}).
	 */
	public CompletableFuture<ConnectOutcome> connect(String sharedDir, ConnectMode mode) {
		Objects.requireNonNull(mode, "mode");
		String dir = clean(sharedDir);
		if (dir == null) {
			disconnect();
			return CompletableFuture.completedFuture(new ConnectOutcome(Kind.ABGEBROCHEN, false, null, null));
		}
		CompletableFuture<ConnectOutcome> outcome = new CompletableFuture<>();
		workLock.lock();
		try {
			reopen();
			abortPendingOutcome();
			Link created = activate(dir, mode);
			// noch hält niemand sonst die Arbeitssperre: Kein Takt sieht die Verbindung ohne diese Felder
			created.outcome = outcome;
			created.firstRun = true;
			notifier.setConnectionComplete(false);
		} finally {
			unlockWork();
		}
		// ohne Sperre: Der Takt nimmt sie selbst
		schedule();
		return outcome;
	}

	/**
	 * Beantwortet die Frage beim Verbinden ({@link SyncListener#connectDecisionNeeded}): Der Abgleich der aktuellen
	 * Verbindung setzt mit {@link ConnectMode#MERGE} (Mitnehmen) oder {@link ConnectMode#SET_ASIDE} (Beiseitelegen) fort,
	 * im Hintergrund wie nach {@link #connect}. Verbietet die Schutzregel das Beiseitelegen inzwischen (zwischen Frage und
	 * Antwort wurde gespeichert, oder ein Speichervorgang schreibt gerade), ändert sich nichts: Die Pause bleibt, die Frage
	 * kommt erneut mit {@code setAsideAllowed = false}, und das Ergebnis ist sofort {@link Kind#ENTSCHEIDUNG}. Vertrag wie bei
	 * {@link #connect}: {@link #startup} vorher aufrufen, sonst greift die Schutzregel nie.
	 * <p>
	 * Nimmt die Arbeitssperre und wartet deshalb auf das Ende des laufenden Takts; nicht auf dem FX-Thread aufrufen.
	 *
	 * @return das Ergebnis wie bei {@link #connect}, genau einmal; geliefert ohne gehaltene Sperre des Dienstes
	 * @throws IllegalArgumentException wenn {@code mode} weder {@link ConnectMode#MERGE} noch {@link ConnectMode#SET_ASIDE}
	 *                                  ist
	 * @throws IllegalStateException    wenn die aktuelle Verbindung nicht auf eine Entscheidung wartet (Zustand
	 *                                  {@link SyncState#ENTSCHEIDUNG}), etwa im Einzelbetrieb oder nach einer Entscheidung,
	 *                                  oder der Ordner wurde in der Pause unerreichbar ({@link SyncState#OFFLINE}); die
	 *                                  Frage kommt nach der Rückkehr erneut
	 */
	public CompletableFuture<ConnectOutcome> decideConnect(ConnectMode mode) {
		Objects.requireNonNull(mode, "mode");
		if (mode != ConnectMode.MERGE && mode != ConnectMode.SET_ASIDE) {
			throw new IllegalArgumentException("Entschieden wird mit MERGE oder SET_ASIDE, nicht mit " + mode);
		}
		CompletableFuture<ConnectOutcome> outcome = new CompletableFuture<>();
		workLock.lock();
		try {
			Link current = currentLink();
			if (current == null || !current.deciding) {
				throw new IllegalStateException("Keine Verbindung wartet auf eine Entscheidung (Zustand " + notifier.state() + ")");
			}
			if (mode == ConnectMode.SET_ASIDE && wroteSinceStartup) {
				// Schutzregel, zum zweiten Mal geprüft: Zwischen Frage und Antwort kann gespeichert worden sein
				notifier.connectDecisionNeeded(current.text, false);
				return CompletableFuture.completedFuture(new ConnectOutcome(Kind.ENTSCHEIDUNG, false, current.setAsidePath, null));
			}
			reopen();
			current.deciding = false;
			current.connectMode = mode;
			current.probeFirst = true;
			// das Ergebnis des Verbindens hat der Eintritt in die Pause festgelegt; dieses ist das neue
			current.outcome = outcome;
		} finally {
			unlockWork();
		}
		// ohne Sperre: Der Takt nimmt sie selbst
		schedule();
		return outcome;
	}

	/**
	 * Beendet den gemeinsamen Betrieb: Der lokale Bestand bleibt, wie er ist (nichts wird gelöscht oder umgeschrieben),
	 * und ist ab jetzt Schiedsrichter ({@link SyncState#AUS}). Noch nicht hochgeladene eigene Versionen werden deshalb
	 * jetzt lokal beansprucht; verlorene bleiben verloren. Die Marke "verbunden" wird gelöscht,
	 * {@link #connectionComplete()} ist danach {@code true}, und ein noch offenes Ergebnis eines {@link #connect} endet mit
	 * {@link Kind#ABGEBROCHEN} (geliefert, bevor {@code disconnect} zurückkehrt).
	 * <p>
	 * Wartet auf das Ende des laufenden Takts; nicht auf dem FX-Thread aufrufen. Erst danach steht fest, was hochgeladen
	 * ist und was nicht, und der lokale Anspruch widerspricht keinem gemeinsamen.
	 */
	public void disconnect() {
		workLock.lock();
		try {
			reopen();
			abortPendingOutcome();
			deactivate();
			claimPendingLocally(withUnindexed(index.pendingUnits()));
			try {
				ConnectionMarker.delete(appDataDir);
				connection = null;
			} catch (IOException | RuntimeException e) {
				LOGGER.log(Level.WARNING, "Die Marke 'verbunden' ließ sich nicht löschen", e);
			}
			notifier.setConnectionComplete(true);
		} finally {
			unlockWork();
		}
	}

	/** Ein noch offenes Ergebnis der aktuellen Verbindung endet mit {@link Kind#ABGEBROCHEN}; unter {@code workLock}. */
	private void abortPendingOutcome() {
		Link previous = currentLink();
		if (previous != null) {
			settle(previous, new ConnectOutcome(Kind.ABGEBROCHEN, false, previous.setAsidePath, null));
		}
	}

	/**
	 * Legt den Ausgang des Verbindens fest, wenn er noch offen ist (unter {@code workLock}; der erste Ausgang gilt, jeder
	 * weitere ist ohne Wirkung). Geliefert wird er erst nach der Freigabe der Sperre ({@link #unlockWork}).
	 */
	private void settle(Link current, ConnectOutcome outcome) {
		CompletableFuture<ConnectOutcome> future = current.outcome;
		if (future == null) {
			return;
		}
		current.outcome = null;
		outcomesDue.add(() -> future.complete(outcome));
	}

	/**
	 * Gibt {@code workLock} frei. Wer sie dabei ganz abgibt, liefert danach die Ergebnisse, die unter ihr feststanden
	 * ({@link #settle}): ohne gehaltene Sperre, damit abhängige Stufen auf diesem Thread jede Sperre der Folge nehmen dürfen.
	 */
	private void unlockWork() {
		List<Runnable> due = List.of();
		if (workLock.getHoldCount() == 1 && !outcomesDue.isEmpty()) {
			due = List.copyOf(outcomesDue);
			outcomesDue.clear();
		}
		workLock.unlock();
		for (Runnable delivery : due) {
			delivery.run();
		}
	}

	/**
	 * Beim Start, bevor {@link #startup} zurückkehrt: meldet die Verluste der Einheiten, die der Index als verloren kennt
	 * (etwa aus einem früheren Lauf; das reiht sie ein), und löst alle eingereihten vollständig auf. So lädt die Anwendung
	 * schon den aufgelösten Stand.
	 */
	private void resolveAtStartup(Collection<UnitKey> lostUnits) {
		reportLosses(lostUnits, List.of());
		resolver.resolvePending(Integer.MAX_VALUE);
	}

	/**
	 * Einzelbetrieb: Ausstehende eigene Versionen (nach dem Trennen, nach einem Absturz zwischen Schreiben und lokalem
	 * Anspruch) werden lokal beansprucht, in der Reihenfolge der Aufträge. Nur lokal, deshalb auf dem Thread des
	 * Aufrufers.
	 *
	 * @param candidates die Einheiten, die untersucht werden (die Kandidaten aus dem Index); ob eine Version ausstehend ist,
	 *                   entscheidet {@link ChainResolver} auf den Dateien
	 */
	private void claimPendingLocally(Collection<UnitKey> candidates) {
		try {
			List<UploadJob.Item> items = new ArrayList<>();
			for (UploadJob job : pendingJobs(ownership(), Set.of(), candidates)) {
				items.addAll(job.items());
			}
			claimLocally(items);
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Ausstehende Versionen ließen sich lokal nicht beanspruchen", e);
		}
	}

	/**
	 * Öffnet den Dienst nach {@link #closeIndex()} wieder: Die Marke "sauber beendet" wird gelöscht, bevor irgendetwas den
	 * Bestand ändert, und der Takt arbeitet wieder. Scheitern Löschen und Leeren, schreibt trotzdem kein Takt über ihr: Jeder
	 * versucht es zuerst erneut ({@link UnitIndex#invalidateCleanMarker}). Ohne vorheriges Schließen ohne Wirkung. Nur unter
	 * {@code workLock} ({@link #startup}, {@link #connect}, {@link #decideConnect}, {@link #disconnect}), damit es sich nicht
	 * mit einem Takt oder mit {@link #closeIndex()} überschneidet.
	 */
	private void reopen() {
		if (closed) {
			index.deleteCleanMarker();
			closed = false;
		}
	}

	/**
	 * Fordert sofort einen Vollabgleich an (im Hintergrund, in Abschnitten über mehrere Takte). Läuft gerade einer, wird er
	 * nicht neu begonnen; direkt nach seinem Ende beginnt ein neuer. Ohne Wirkung im Einzelbetrieb.
	 */
	public void syncNow() {
		Link current = currentLink();
		if (current == null) {
			return;
		}
		wishFullSync(current);
		schedule();
	}

	// --- Speichern und Lesen

	/**
	 * Schreibt je Änderung eine neue Versionsdatei in den lokalen Bestand. Im Einzelbetrieb wird jede sofort lokal
	 * beansprucht und gilt; im gemeinsamen Betrieb kommt der Stapel als ein Auftrag in die Warteschlange und ist bis zum
	 * Hochladen vorläufig (auch, solange der Abgleich auf eine Entscheidung wartet, {@link SyncState#ENTSCHEIDUNG}).
	 * <p>
	 * Nach dem Ende von {@link #startup} verbietet jeder Aufruf mit mindestens einer Änderung das Beiseitelegen für den Rest
	 * des Programmlaufs (Schutzregel, siehe {@link #connect}), schon bevor die erste Datei geschrieben ist. Wird der lokale
	 * Bestand gerade beiseitegelegt, wartet der Aufruf, bis der neue Datenordner bereit ist, und schreibt dorthin.
	 *
	 * @param saveId die ID des Speichervorgangs; alle Versionen tragen sie
	 * @return die neuen Versionen in der Reihenfolge der Änderungen
	 * @throws IOException wenn eine Datei nicht geschrieben werden kann; die schon geschriebenen Versionen desselben
	 *                     Aufrufs bleiben liegen und werden wie die übrigen beansprucht bzw. hochgeladen. Dasselbe gilt,
	 *                     wenn das Schreiben eine unerwartete {@link RuntimeException} wirft: Sie wird nach dem
	 *                     Beanspruchen bzw. Einreihen weitergeworfen, Folgefehler hängen als unterdrückte Ausnahmen an.
	 */
	public List<Version> append(String saveId, List<Change> changes) throws IOException {
		return append(saveId, changes, true);
	}

	/**
	 * {@link #append} für beide Schreiber: die Anwendung und die Auflösung ({@link ResolverHost#append}).
	 *
	 * @param own ob es ein Speichervorgang der Anwendung ist: Nur er zählt für die Schutzregel ({@link #wroteSinceStartup})
	 */
	private List<Version> append(String saveId, List<Change> changes, boolean own) throws IOException {
		Objects.requireNonNull(saveId, "saveId");
		Link current = beginLocalWrites(own && !changes.isEmpty());
		List<UploadJob.Item> written = new ArrayList<>();
		// der Fehler, der das Schreiben beendet hat: geprüft oder unerwartet (nie beide)
		IOException failure = null;
		RuntimeException unexpected = null;
		// gezählt bis zum Einreihen (bzw. lokalen Beanspruchen): Ein Beiseitelegen trifft nie Dateien ohne ihren Auftrag
		boolean counted = true;
		try {
			// vor der ersten Datei: eine Marke "sauber beendet" gilt nur über einem unveränderten Bestand
			index.invalidateCleanMarker();
			for (Change change : changes) {
				try {
					written.add(writeLocal(saveId, change, seenFor(current, change)));
				} catch (IOException e) {
					failure = e;
					break;
				} catch (RuntimeException e) {
					// die schon geschriebenen Versionen müssen trotzdem in den Index und in die Warteschlange: Spätere Schritte
					// nehmen die Kandidaten für Ausstehendes aus dem Index
					unexpected = e;
					break;
				}
			}
			try {
				if (current == null) {
					// der Index folgt dort: claimLocally löst jede Einheit über resolveLocal auf (Verlustableitung)
					claimLocally(written);
					// ohne gemeinsamen Ordner gibt es keine Listen: Die frisch geschriebenen Versionen stehen in keiner. Nur hier
					// ist das sicher; was claimPendingLocally findet, kann schon in einer Liste stehen (unbekannt)
					index.markOwnListed(feedEntries(written), 0);
				} else {
					// erst in den Index, dann in die Warteschlange: Sobald der Auftrag läuft, trägt der Index die Listennummer ein
					indexUnits(written);
					index.markOwnListed(feedEntries(written), 0);
					if (!enqueue(current, saveId, written)) {
						// nicht mehr gezählt: Das nimmt workLock, und ein wartendes Beiseitelegen hält sie
						counted = false;
						endLocalWrites();
						settleAfterConnectionChange(saveId, written);
					}
				}
			} catch (IOException | RuntimeException e) {
				// auch nach einem Fehler spiegelt der Index, was auf der Platte liegt (etwa eine Version ohne Anspruch). Nach dem
				// Ende der Zählung (Fehler in settleAfterConnectionChange) stehen die Einheiten schon darin: Jedes Eintragen eines
				// gezählten Speichervorgangs bleibt im gezählten Teil
				Throwable first = failure != null ? failure : unexpected;
				if (first == null) {
					if (counted) {
						indexUnits(written);
					}
					throw e;
				}
				if (counted) {
					try {
						indexUnits(written);
					} catch (RuntimeException indexFailure) {
						first.addSuppressed(indexFailure);
					}
				}
				first.addSuppressed(e);
			}
		} finally {
			if (counted) {
				endLocalWrites();
			}
		}
		if (failure != null) {
			throw failure;
		}
		if (unexpected != null) {
			throw unexpected;
		}
		return written.stream().map(UploadJob.Item::version).toList();
	}

	/**
	 * Beginn der lokalen Schreibvorgänge eines {@link #append}, in einem Block unter {@code lock}: Wird der lokale Bestand
	 * gerade beiseitegelegt, wartet es, bis der neue Datenordner bereit ist; dann setzt es für einen Speichervorgang der
	 * Anwendung nach dem Start den Merker der Schutzregel (vor der ersten Datei: Auch ein Speichervorgang, der gerade
	 * schreibt, verhindert das Beiseitelegen; scheitert er, bleibt der Merker, die sichere Richtung) und zählt sich zu den
	 * laufenden Schreibvorgängen ({@link #localWrites}, bis zum Einreihen). Wer {@code workLock} hält, findet hier nie ein
	 * laufendes Beiseitelegen vor (es läuft selbst unter {@code workLock}); gewartet wird nur ohne gehaltene Sperre.
	 *
	 * @param counts ob es ein Speichervorgang der Anwendung mit mindestens einer Änderung ist
	 * @return die Verbindung, mit der geschrieben wird
	 */
	private Link beginLocalWrites(boolean counts) {
		Link current;
		boolean interrupted;
		synchronized (lock) {
			// ein Speichervorgang bricht nicht ab: Das Beiseitelegen dauert nur eine Umbenennung
			interrupted = awaitUninterruptibly(() -> !settingAside);
			if (counts && startupDone) {
				wroteSinceStartup = true;
			}
			localWrites++;
			localWritesBegun++;
			current = link;
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
		return current;
	}

	/**
	 * Wartet an {@code lock}, bis {@code done} gilt; nur unter {@code lock} aufrufen. Eine Unterbrechung beendet das Warten
	 * nicht, sie wird nur gemerkt: Der Aufrufer stellt sie wieder her, wenn es passt.
	 *
	 * @return ob der Thread während des Wartens unterbrochen wurde
	 */
	private boolean awaitUninterruptibly(BooleanSupplier done) {
		boolean interrupted = false;
		while (!done.getAsBoolean()) {
			try {
				lock.wait();
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		return interrupted;
	}

	/** Ende der lokalen Arbeit eines {@link #append} (nach dem Einreihen); ein wartendes Beiseitelegen prüft danach erneut. */
	private void endLocalWrites() {
		synchronized (lock) {
			localWrites--;
			if (localWrites == 0) {
				lock.notifyAll();
			}
		}
	}

	/**
	 * {@code seen} einer Version: bei der Löschung eines Event-Kopfs oder einer Vorstellung die ausdrücklich genannten
	 * ({@link Change#seen()}), sonst im gemeinsamen Betrieb je fremdem Rechner die Nummer der zuletzt gelesenen Liste
	 * ({@link SyncPositions}); sonst leer. Eine Position rückt auch über Listen vor, die nach der Wartezeit übersprungen
	 * wurden (Entscheidung der Spezifikation, siehe "{@code seen} ist eine Annäherung").
	 */
	private static Map<String, Long> seenFor(Link current, Change change) {
		if (!change.deleted() || !change.unit().type().hasChildren()) {
			return Map.of();
		}
		if (change.seen() != null) {
			return change.seen();
		}
		if (current == null) {
			return Map.of();
		}
		Map<String, Long> seen = new TreeMap<>();
		for (String machineId : current.positions.machines()) {
			seen.put(machineId, current.positions.position(machineId));
		}
		return seen;
	}

	private static List<FeedEntry> feedEntries(List<UploadJob.Item> items) {
		return items.stream().map(UploadJob.Item::feedEntry).toList();
	}

	/**
	 * Schreibt die Versionsdatei; ist der Name (praktisch nie) schon vergeben, mit der nächsten ID. Versions-ID und
	 * Kennung entstehen zusammen unter {@code lock}, für jede Version neu: So stammt die ID immer vom Generator der Kennung,
	 * die in der Datei steht, und eine Neuvergabe (SYS-011) mitten im Speichervorgang sieht jede ID, die der alte Generator
	 * vergab ({@link #regenerate}).
	 */
	private UploadJob.Item writeLocal(String saveId, Change change, Map<String, Long> seen) throws IOException {
		ObjectNode payload = change.deleted() || change.payload() == null ? JsonNodeFactory.instance.objectNode()
				: change.payload().deepCopy();
		for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
			VersionId id;
			MachineIdentity me;
			synchronized (lock) {
				id = ids.next();
				me = machine;
			}
			Version version = new Version(StoreCodec.FORMAT, id, change.parents(), me.id(), me.name(),
					me.operator(), clock.instant(), saveId, change.deleted(), payload, change.note(), seen);
			byte[] bytes = StoreCodec.write(version);
			try {
				if (local.writeVersion(change.unit(), version.id(), bytes)) {
					return new UploadJob.Item(change.unit(), version, bytes);
				}
			} finally {
				// zur Vorsicht: Ein laufender Takt löst die Einheit am Ende erneut auf
				settled.remove(change.unit());
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
		reportLosses(units, List.of());
	}

	/**
	 * Reiht den Stapel für das Hochladen ein.
	 *
	 * @return {@code false}, wenn die Verbindung inzwischen gewechselt hat (dann ist nichts eingereiht); sonst {@code true}
	 */
	private boolean enqueue(Link current, String saveId, List<UploadJob.Item> items) {
		if (items.isEmpty()) {
			return true;
		}
		synchronized (lock) {
			if (link != current) {
				// inzwischen getrennt oder gewechselt: Die Versionen bleiben ausstehend und kommen beim nächsten Abgleich mit
				return false;
			}
			jobs.add(new UploadJob(saveId, items));
			for (UploadJob.Item item : items) {
				queued.add(item.version().id());
			}
			if (notifier.state() == SyncState.AKTUELL) {
				notifier.setState(SyncState.AUSSTEHEND);
			}
			return true;
		}
	}

	/**
	 * {@link #append} fand beim Einreihen eine andere Verbindung vor als beim Beginn. Ist inzwischen getrennt, beansprucht
	 * der lokale Bestand die Versionen jetzt, sonst würde sie nie jemand beanspruchen. Hat eine neue Verbindung begonnen,
	 * kommt der Stapel als Auftrag in deren Warteschlange und geht mit dem nächsten Takt hinaus: Ihr erster Vollabgleich hat
	 * die Version womöglich schon verpasst (der Index kannte sie beim Beginn noch nicht), und bis zum nächsten vergingen
	 * Minuten. Ein Auftrag, den der Vollabgleich ebenfalls findet, wird nicht doppelt eingereiht ({@code queued}). Unter
	 * {@code workLock}, damit das nicht mit einem Wechsel der Verbindung überlappt; der Aufrufer hält keine andere Sperre (im
	 * {@code worker} gehört sie ihm schon). Wartet deshalb in diesem seltenen Fall auf das Ende des laufenden Takts.
	 */
	private void settleAfterConnectionChange(String saveId, List<UploadJob.Item> items) throws IOException {
		workLock.lock();
		try {
			// die Verbindung wechselt nur unter workLock (auch beim Start): Die Schleife läuft höchstens einmal und dient nur
			// noch der Vorsicht
			while (true) {
				Link now = currentLink();
				if (now == null) {
					claimLocally(items);
					return;
				}
				if (enqueue(now, saveId, items)) {
					return;
				}
			}
		} finally {
			workLock.unlock();
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
		reportLocked(unit, state);
		return state;
	}

	/**
	 * Behandelt eine gesperrte Einheit: beschädigt im gemeinsamen Betrieb zur Heilung vormerken (der nächste Takt versucht
	 * sie), im Einzelbetrieb sofort SYS-008; neueres Format SYS-009. Nimmt nur kurz {@code lock}, sonst keine Sperre; ein
	 * Stand ohne Sperre bleibt unberührt.
	 */
	private void reportLocked(UnitKey unit, UnitState state) {
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
				round.unsettle(unit);
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
				round.touch(unit);
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
	 * Eine lesbare Version der Einheit aus dem lokalen Bestand (wie in {@link #history}), etwa um ihre Vorgänger und ihren
	 * Rechner zu prüfen. Liest nur diese eine Datei.
	 *
	 * @return die Version; {@code null}, wenn es sie lokal nicht lesbar gibt
	 */
	public Version version(UnitKey unit, VersionId id) throws IOException {
		return local.readVersion(unit, id).orElse(null);
	}

	/**
	 * Die Einheiten unter {@code units}, die laut Start-Index eine eigene verlorene Strecke haben (noch nicht zusammengeführt).
	 * Liest keine Datei; der Index spiegelt nur, den Stand der Strecke liefert {@link #unitState}.
	 */
	public Set<UnitKey> lostUnits(Collection<UnitKey> units) {
		Set<UnitKey> lost = new LinkedHashSet<>();
		for (UnitKey unit : units) {
			UnitIndex.Entry entry = index.entry(unit);
			if (entry != null && entry.lost()) {
				lost.add(unit);
			}
		}
		return Collections.unmodifiableSet(lost);
	}

	/**
	 * Ob der Stand {@code version} der Einheit eine eigene Zusammenführung ist, die einen Konflikt dieses Rechners
	 * auflöst: von diesem Rechner (auch unter einer früheren ID vor ihrer Neuvergabe, siehe {@link Ownership}), mit mehr als
	 * einem Vorgänger, und sie selbst oder eine in ihr aufgegangene Version trägt einen Vermerk, der diesen Rechner betrifft
	 * ({@link ConflictNote#affected()}, {@link ConflictLedger#affectsMe}). Diesen Konflikt zeigt der Dialog. Die
	 * Vermerke stammen aus dem Start-Index; kennt er keinen passenden, wird keine Datei gelesen. Sonst liest es höchstens
	 * diese eine Version (wenn sie selbst keinen Vermerk trägt, kennt der Index Rechner und Vorgänger nicht).
	 *
	 * @param version ein Stand der Einheit; {@code null} ist nie eine Zusammenführung
	 */
	public boolean isOwnNotedMerge(UnitKey unit, VersionId version) throws IOException {
		UnitIndex.Entry entry = index.entry(unit);
		if (version == null || entry == null) {
			return false;
		}
		Ownership own = ownership();
		UnitIndex.NotedVersion self = null;
		boolean noted = false;
		for (UnitIndex.NotedVersion candidate : entry.notes()) {
			boolean itself = candidate.version().equals(version);
			if (itself) {
				self = candidate;
			}
			if ((itself || entry.absorbed().contains(candidate.version())) && ConflictLedger.affectsMe(own, candidate)) {
				noted = true;
			}
		}
		if (!noted) {
			return false;
		}
		if (self != null) {
			return own.isOwn(self.machine(), self.version()) && self.parents().size() > 1;
		}
		Version merge = version(unit, version);
		return merge != null && own.isOwn(merge) && merge.parents().size() > 1;
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
		return index.snapshots();
	}

	/**
	 * Die Stände der genannten Einheiten aus dem Start-Index, ohne Dateizugriff und ohne den ganzen Bestand zu kopieren.
	 * Einheiten ohne Eintrag im Index fehlen im Ergebnis (sie werden nicht aus den Dateien aufgelöst).
	 *
	 * @return eine unveränderliche Kopie der Stände dieser Einheiten
	 */
	public Map<UnitKey, UnitSnapshot> snapshots(Collection<UnitKey> units) {
		return index.snapshots(units);
	}

	/**
	 * Die Stände aller Einheiten eines Events (Kopf, Vorstellungen, Buchungen) aus dem Start-Index, ohne Dateizugriff und ohne
	 * den ganzen Bestand zu kopieren.
	 *
	 * @return eine unveränderliche Kopie; leer, wenn der Index zu diesem Event nichts kennt
	 */
	public Map<UnitKey, UnitSnapshot> eventSnapshots(String eventId) {
		return index.eventSnapshots(eventId);
	}

	/**
	 * Die gültigen Ketten der genannten Einheiten ohne Inhalt (Rechner, Bediener, Zeitpunkt, Speichervorgang), ältestes Glied
	 * zuerst: der Verlauf eines Events, ohne eine Datei zu lesen. Sie stammen aus dem Start-Index; nur eine Einheit ohne
	 * Eintrag wird aus den Dateien aufgelöst ({@link #resolveLocal}) und dabei in den Index übernommen. Eine Einheit ohne
	 * Dateien hat eine leere Kette. Ist eine solche Einheit gesperrt, behandelt es sie wie {@link #unitState} (Heilung
	 * vormerken, SYS-008, SYS-009). Der Index ist hier nur Zwischenspeicher für die Anzeige; was wiederhergestellt wird, liest
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
				UnitState state = resolveLocal(unit);
				// wie unitState: eine beschädigte oder neuere unbekannte Einheit melden, nicht still als leere Kette zeigen
				reportLocked(unit, state);
				chain = state.chain().stream().map(ChainStep::of).toList();
			}
			chains.put(unit, chain);
		}
		return Collections.unmodifiableMap(chains);
	}

	/**
	 * Die aufgegangenen Versionen der genannten Einheiten aus dem Start-Index, ohne Dateizugriff: alle Versionen, die vom
	 * aktuellen Stand (Ende der Kette, bei ausstehenden eigenen Versionen die letzte davon) über beliebige Vorgänger erreichbar
	 * sind, transitiv, ohne die Kette selbst. Einheiten ohne Eintrag im Index fehlen im Ergebnis.
	 *
	 * @return eine unveränderliche Kopie
	 */
	public Map<UnitKey, Set<VersionId>> absorbed(Collection<UnitKey> units) {
		return index.absorbed(units);
	}

	// --- Offene Konflikte

	/**
	 * Die offenen Konflikte dieses Rechners aus dem Start-Index ({@link ConflictLedger}), ohne Zugriff auf den Bestand:
	 * Konfliktvermerke, die diesen Rechner betreffen, und fremde Löschungen eines Event-Kopfs oder einer Vorstellung über
	 * eigenen Verkäufen, die der Löschende nicht kannte, jeweils solange sie nicht bestätigt sind. Eine Elternlöschung, für
	 * die noch eine Listennummer fehlt, erscheint erst, wenn der Takt die eigenen Listen gelesen hat
	 * ({@link SyncListener#conflictsChanged}). Die erste Abfrage liest einmal die kleine Datei {@code conflicts-ack.json}.
	 *
	 * @return unveränderlich, nach Zeitpunkt, dann ID sortiert
	 */
	public List<OpenConflict> openConflicts() {
		return deriveConflicts().conflicts();
	}

	/**
	 * Bestätigt Konflikte (Schlüssel aus {@link OpenConflict#key()}: Version und Art): Sie erscheinen nicht mehr in
	 * {@link #openConflicts()}, auch nicht nach einem Neustart. Ein Vermerk und eine Elternlöschung derselben Version werden
	 * einzeln bestätigt. Schreibt {@code <AppData>/conflicts-ack.json} atomar und meldet dann
	 * {@link SyncListener#conflictsChanged}. Scheitert das Schreiben, gilt nichts als bestätigt; ist die vorhandene Datei
	 * nicht lesbar (etwa von einem anderen Programm gesperrt), wird sie auch nicht überschrieben.
	 *
	 * @throws NullPointerException wenn ein Element {@code null} ist
	 * @throws IOException          wenn die Datei nicht gelesen oder nicht geschrieben werden kann
	 */
	public void acknowledge(Collection<ConflictKey> keys) throws IOException {
		Objects.requireNonNull(keys, "keys");
		for (ConflictKey key : keys) {
			Objects.requireNonNull(key, "Ein zu bestätigender Konflikt darf nicht null sein");
		}
		synchronized (ackLock) {
			Set<ConflictKey> next = new HashSet<>(loadedAcknowledged());
			if (!next.addAll(keys)) {
				return;
			}
			writeAcknowledged(next);
			acknowledged = next;
			// was bestätigt ist, ist nicht mehr offen: Der nächste Vergleich meldet es nicht noch einmal
			reportedConflicts.removeAll(keys);
		}
		notifier.conflictsChanged();
	}

	private ConflictLedger.Derivation deriveConflicts() {
		Ownership me = ownership();
		Set<ConflictKey> done;
		synchronized (ackLock) {
			try {
				done = Set.copyOf(loadedAcknowledged());
			} catch (IOException e) {
				// nicht als "nichts bestätigt" merken: Der nächste Aufruf liest die Datei erneut
				LOGGER.log(Level.WARNING, "Die bestätigten Konflikte (" + CONFLICTS_ACK
						+ ") ließen sich nicht lesen; alle Konflikte gelten vorerst als offen", e);
				done = Set.of();
			}
		}
		Set<ConflictKey> acknowledgedNow = done;
		return index.read(entries -> ConflictLedger.derive(entries, me, acknowledgedNow));
	}

	/**
	 * Merkt sich die offenen Konflikte als zuletzt gemeldet.
	 *
	 * @return ob sie sich gegenüber den vorher gemeldeten geändert haben (neue, weggefallene, andere Art)
	 */
	private boolean replaceReportedConflicts(ConflictLedger.Derivation derivation) {
		Set<ConflictKey> now = new HashSet<>();
		derivation.conflicts().forEach(conflict -> now.add(conflict.key()));
		synchronized (ackLock) {
			boolean changed = !now.equals(reportedConflicts);
			reportedConflicts = now;
			return changed;
		}
	}

	/**
	 * Die bestätigten Konflikte; liest die Datei beim ersten Aufruf. Nur unter {@code ackLock}.
	 *
	 * @throws IOException wenn die Datei nicht gelesen werden kann (nicht: wenn sie defekt ist); dann bleibt sie ungelesen
	 */
	private Set<ConflictKey> loadedAcknowledged() throws IOException {
		if (acknowledged == null) {
			acknowledged = readAcknowledged();
		}
		return acknowledged;
	}

	/**
	 * Fehlt die Datei oder ist sie defekt (kein JSON, falsche Form, ungültiger Eintrag), gilt nichts als bestätigt (lieber
	 * einmal zu viel als verschluckt), bei einer defekten mit WARNING.
	 *
	 * @throws IOException wenn das Lesen selbst scheitert: Die Datei kann gültig sein
	 */
	private Set<ConflictKey> readAcknowledged() throws IOException {
		Set<ConflictKey> keys = new HashSet<>();
		Optional<byte[]> bytes = files.read(appDataDir.resolve(CONFLICTS_ACK));
		if (bytes.isEmpty()) {
			return keys;
		}
		try {
			JsonNode list = MAPPER.readTree(bytes.get()).get(ACKNOWLEDGED);
			if (list == null || !list.isArray()) {
				throw new IOException("Das Feld '" + ACKNOWLEDGED + "' fehlt oder ist keine Liste");
			}
			for (JsonNode item : list) {
				if (!item.isTextual()) {
					throw new IOException("Ungültiger Eintrag in '" + ACKNOWLEDGED + "': " + item);
				}
				keys.add(ConflictKey.parse(item.asText()));
			}
			return keys;
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Die bestätigten Konflikte (" + CONFLICTS_ACK
					+ ") sind defekt; alle Konflikte gelten als offen", e);
			return new HashSet<>();
		}
	}

	/** Liest die Bestätigungen beim Start, damit die erste Abfrage (womöglich auf dem FX-Thread) keine Datei liest. */
	private void loadAcknowledgedAtStart() {
		synchronized (ackLock) {
			try {
				loadedAcknowledged();
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Die bestätigten Konflikte (" + CONFLICTS_ACK + ") ließen sich nicht lesen", e);
			}
		}
	}

	/** Schreibt die Datei über eine temporäre Datei, die die vorhandene atomar ersetzt. */
	private void writeAcknowledged(Set<ConflictKey> keys) throws IOException {
		ObjectNode root = MAPPER.createObjectNode();
		ArrayNode list = root.putArray(ACKNOWLEDGED);
		keys.stream().map(ConflictKey::asText).sorted().forEach(list::add);
		files.replace(appDataDir.resolve(CONFLICTS_ACK), MAPPER.writeValueAsBytes(root), "conflicts-ack");
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
	 * keine Marke, denn ihre Änderungen am Bestand fehlten im Index. Dasselbe gilt, solange das Auflösen einer Einheit
	 * gescheitert ist und der Index sie nicht kennt (WARNING im Log): Der nächste Start durchsucht den Bestand. Ebenso,
	 * solange ein {@link #append} im lokalen Bestand schreibt ({@link #localWrites}) oder eines während des Schreibens der
	 * Marke beginnt (dann wird sie gleich wieder ungültig gemacht): Seine Dateien stünden sonst unter einer Marke, deren
	 * Index sie nicht kennt.
	 * <p>
	 * Mit der Marke ist der Dienst geschlossen: Der Takt tut nichts mehr, denn eine Runde, die nach der Marke Dateien in den
	 * Bestand schriebe, und ein Prozessende mitten darin ließen die Marke über einem geänderten Bestand stehen. Ein späteres
	 * {@link #append} löscht die Marke vor dem Schreiben, wird aber in diesem Programmlauf nicht mehr hochgeladen, sondern
	 * beim nächsten Start; {@link #startup}, {@link #connect} und {@link #disconnect} öffnen den Dienst wieder (und löschen
	 * die Marke zuerst). Wirft nie; Fehler stehen als {@code WARNING} im Log.
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
			if (!unindexed.isEmpty()) {
				// der Index kennt nicht alle Einheiten: Der nächste Start muss den Bestand durchsuchen
				LOGGER.warning("Der Start-Index kennt nicht alle Einheiten; die Marke 'sauber beendet' entfällt");
				return;
			}
			// ein append schreibt ohne workLock: Zwischen seinen Dateien und dem Eintrag in den Index stünde die Marke über einer
			// Version, die der Index nicht kennt. Gezählt wird unter lock, geschrieben ohne
			long writesBegun;
			boolean writing;
			synchronized (lock) {
				writing = localWrites > 0;
				writesBegun = localWritesBegun;
			}
			if (writing) {
				LOGGER.warning(SAVE_IN_PROGRESS);
				return;
			}
			if (index.writeCleanMarker()) {
				boolean overlapped;
				synchronized (lock) {
					overlapped = localWritesBegun != writesBegun;
				}
				if (overlapped) {
					// ein append begann zwischen Zählen und Schreiben: Hat es die Marke ungültig gemacht, bevor sie dastand, gälte sie
					// über seinen Dateien
					if (index.invalidateCleanMarker()) {
						LOGGER.warning(SAVE_IN_PROGRESS);
					} else {
						// weder gelöscht noch geleert (die Warnungen des Index stehen schon im Log): Sie kann über den Dateien des
						// Speichervorgangs gelten. Der Dienst bleibt offen, und jede weitere Änderung versucht es erneut (KARV-104)
						LOGGER.warning("Ein Speichervorgang begann während des Schreibens der Marke 'sauber beendet', und sie ließ "
								+ "sich weder löschen noch leeren; sie kann noch gelten: " + index.cleanMarker());
					}
					return;
				}
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
	 * Sonst gleicht der Start den Index ab und räumt dabei im selben Durchlauf über den Bestand die alten temporären
	 * Dateien auf ({@link UnitIndex#refresh}); im Notfall baut er den
	 * Index neu auf.
	 *
	 * @return die Einheiten mit ausstehenden und mit verlorenen eigenen Versionen
	 */
	private UnitIndex.Refresh openIndex(DoubleConsumer progress) {
		try {
			if (index.loadIfClean()) {
				UnitIndex.Refresh refresh = index.cleanRefresh();
				indexRefreshed = true;
				progress.accept(1.0);
				return refresh;
			}
		} catch (RuntimeException e) {
			LOGGER.log(Level.WARNING, "Die Marke 'sauber beendet' ließ sich nicht auswerten; der Bestand wird durchsucht", e);
		}
		UnitIndex.Refresh refreshed = refreshIndex(progress);
		// war der Index nicht zu gebrauchen: jede Einheit einzeln aus den Dateien, oder der Start scheitert
		return refreshed != null ? refreshed : rebuildIndex(progress);
	}

	/**
	 * Gleicht den Index mit dem Bestand ab.
	 *
	 * @return die Einheiten mit ausstehenden und mit verlorenen eigenen Versionen; {@code null}, wenn der Abgleich
	 *         scheiterte und der Index nicht zu gebrauchen ist
	 */
	private UnitIndex.Refresh refreshIndex(DoubleConsumer progress) {
		try {
			UnitIndex.Refresh refresh = index.refresh(local, progress);
			indexRefreshed = true;
			return refresh;
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Der Start-Index ließ sich nicht abgleichen; alle Einheiten werden untersucht", e);
			return null;
		}
	}

	/**
	 * Baut den Index neu auf, wenn der Abgleich scheiterte: ein neuer, leerer Index, jede Einheit des Bestands einzeln über
	 * {@link #resolveLocal}. Der Index ist danach vollständig, sonst scheitert der Start.
	 *
	 * @return die Einheiten mit ausstehenden und mit verlorenen eigenen Versionen
	 * @throws UncheckedIOException wenn die Einheiten nicht aufgelistet oder eine nicht gelesen werden kann; der Bestand wäre
	 *                              unvollständig (eine fehlende Buchung hieße einen freien Platz)
	 */
	private UnitIndex.Refresh rebuildIndex(DoubleConsumer progress) {
		// der Abgleich, der sonst aufräumt, ist gescheitert: die alten temporären Dateien hier entfernen
		try {
			files.deleteOwnTemps(local.dataRoot(), TEMP_AGE);
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.WARNING, "Alte temporäre Dateien im lokalen Bestand ließen sich nicht entfernen", e);
		}
		synchronized (resolveLock) {
			// konnte der alte Index die Marke "sauber beendet" nicht entfernen (etwa schon in loadIfClean), kann sie noch
			// gelten: Der neue versucht es weiter und meldet sie bis dahin, damit kein Takt über ihr schreibt
			boolean mayStand = !index.invalidateCleanMarker();
			index = newIndex(ownership());
			if (mayStand) {
				index.assumeCleanMarkerWritten();
			}
			unindexed.clear();
		}
		Set<UnitKey> pending = new LinkedHashSet<>();
		Set<UnitKey> lost = new LinkedHashSet<>();
		List<UnitKey> units;
		try {
			units = local.allUnits();
		} catch (IOException e) {
			throw new UncheckedIOException("Die Einheiten des lokalen Bestands ließen sich nicht auflisten", e);
		}
		// Fortschritt wie beim Abgleich je volles Prozent: Der Neuaufbau liest jede Einheit und kann lange dauern
		int done = 0;
		int lastPercent = 0;
		progress.accept(0.0);
		for (UnitKey unit : units) {
			try {
				UnitState state = resolveLocal(unit);
				if (!state.pending().isEmpty()) {
					pending.add(unit);
				}
				if (!state.lost().isEmpty()) {
					lost.add(unit);
				}
			} catch (IOException e) {
				throw new UncheckedIOException("Der Stand von " + unit.relativePath() + " ließ sich nicht lesen", e);
			}
			done++;
			int percent = (int) (done * 100L / units.size());
			if (percent != lastPercent) {
				lastPercent = percent;
				progress.accept(percent / 100.0);
			}
		}
		indexRefreshed = true;
		if (lastPercent != 100) {
			progress.accept(1.0);
		}
		return new UnitIndex.Refresh(Collections.unmodifiableSet(pending), Collections.unmodifiableSet(lost), units.size());
	}

	/**
	 * Verwirft den Index, weil der Datenordner ausgetauscht wird (Beiseitelegen). Der leere Index ist vollständig und
	 * darf deshalb auch ohne Abgleich geschrieben werden: Er ersetzt die alten Dateien. Unter {@code resolveLock}: Ein Stand,
	 * der noch aus dem alten Datenordner gelesen wurde, kommt nicht danach in den Index.
	 */
	private void clearIndex() {
		synchronized (resolveLock) {
			index.clear();
			unindexed.clear();
			settled.clear();
			indexRefreshed = true;
		}
	}

	/** Die Dateien einer Einheit und ihr Stand daraus. */
	record Resolved(UnitFiles files, UnitState state) {
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
		// Monitor des Index; ownership() nimmt nur kurz lock, und unter lock wartet nie jemand auf resolveLock.
		synchronized (resolveLock) {
			try {
				UnitFiles unitFiles = local.readUnit(unit);
				UnitState state = ChainResolver.resolve(unitFiles, ownership());
				// ein Ordner ohne Dateien ist keine Einheit des Bestands: kein Eintrag (etwa bei einer Abfrage nach einer
				// unbekannten)
				if (!unitFiles.versions().isEmpty() || !unitFiles.claims().isEmpty()) {
					index.update(unitFiles, state);
				}
				unindexed.remove(unit);
				return new Resolved(unitFiles, state);
			} catch (IOException | RuntimeException e) {
				// der Index kennt den Stand dieser Einheit nicht (mehr): Sie bleibt Kandidat, bis eine Auflösung gelingt
				unindexed.add(unit);
				throw e;
			}
		}
	}

	/** Trägt die Einheiten der geschriebenen Versionen in den Index ein; was sich nicht lesen lässt, kostet nur den Eintrag. */
	private void indexUnits(List<UploadJob.Item> items) {
		indexUnitKeys(items.stream().map(UploadJob.Item::unit).distinct().toList());
	}

	/** Trägt die Einheiten in den Index ein, ohne etwas zu melden; was sich nicht lesen lässt, kostet nur den Eintrag. */
	private void indexUnitKeys(Collection<UnitKey> units) {
		for (UnitKey unit : units) {
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
	 * Bedarf einen Vollabgleich beginnen und ein Stück davon abarbeiten, Aufträge fortsetzen, alle 30 Sekunden neue
	 * Rechner, Listen lesen, eigene Ansprüche der letzten 15 Minuten nachprüfen; dann Verluste und geänderte Einheiten
	 * melden und den Zustand setzen. Am Anfang, auch im
	 * Einzelbetrieb, wird der Start-Index geschrieben, wenn er sich geändert hat und das letzte Schreiben
	 * {@link #INDEX_FLUSH} her ist. Nach dem Hochladen löst der Takt höchstens {@value #RESOLVE_BUDGET} eingereihte
	 * Verluste auf; im Einzelbetrieb und ohne Verbindung ebenso, nur lokal (hochgeladen wird später). Ein Ergebnis von
	 * {@link #connect}, das in diesem Takt feststeht, wird nach der Freigabe der Arbeitssperre geliefert.
	 * <p>
	 * Kein Takt schreibt, solange eine gültige Marke "sauber beendet" dieser Instanz stehen kann: Vor jeder Arbeit am
	 * gemeinsamen Ordner und vor der Auflösung macht er sie ungültig ({@link UnitIndex#invalidateCleanMarker}); gelingt
	 * das nicht, prüft er nur die Erreichbarkeit (siehe {@code runTick}), und der nächste versucht es erneut.
	 *
	 * @return ob der nächste Takt sofort folgen soll: Der erste Lauf einer Verbindung läuft noch, und dieser Takt erreichte
	 *         den Ordner ohne Störung. Sonst (auch nach einer Störung, damit ein dauerhaft scheiternder Block den
	 *         {@code worker} nicht ohne Pause beschäftigt) gilt der Abstand {@link #TICK}.
	 */
	boolean tick() {
		flushIndexIfDue();
		workLock.lock();
		try {
			if (closed) {
				return false;
			}
			Link current = currentLink();
			if (current == null) {
				// auch die Auflösung schreibt in den Bestand: nicht, solange eine gültige Marke "sauber beendet" stehen kann
				if (index.invalidateCleanMarker()) {
					resolver.resolvePending(RESOLVE_BUDGET);
				}
				return false;
			}
			try {
				if (!runTick(current)) {
					resolver.resolvePending(RESOLVE_BUDGET);
				}
			} finally {
				// das Ende des ersten Laufs liefert sein Ergebnis, auch wenn danach im Takt etwas Unerwartetes warf (etwa in der
				// Ableitung der Verluste, die keine Absicherung des Durchlaufs umgibt)
				settleFirstRunEnd(current);
			}
			return current.firstRun && current.hurry && isCurrent(current);
		} finally {
			boolean interrupt = interruptAfterTick;
			interruptAfterTick = false;
			unlockWork();
			if (interrupt) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Ein Takt im {@code worker}, im ersten Lauf einer Verbindung mehrere ohne Pause ({@link #tick()} liefert dann
	 * {@code true}). Die Arbeitssperre ist zwischen zwei Takten frei; weil sie fair ist, kommen ein wartendes
	 * {@link #connect}, {@link #disconnect} oder {@link #closeIndex()} dazwischen dran. Liefert {@link #tick()}
	 * {@code false} (Lauf zu Ende, Ordner weg, Störung, getrennt, geschlossen) oder ist der Thread danach unterbrochen, endet
	 * die Schleife; der nächste eingeplante Takt setzt fort.
	 */
	private void tickSafely() {
		try {
			boolean again;
			do {
				again = tick();
			} while (again && !Thread.currentThread().isInterrupted());
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

	/**
	 * Der Takt im gemeinsamen Betrieb. Im ersten Lauf einer Verbindung legt er dessen Ergebnis fest: am Ende des Laufs
	 * {@link Kind#AKTUELL} bzw. {@link Kind#AUSSTEHEND} (je nachdem, ob danach noch Aufträge warten), vorher
	 * {@link Kind#OFFLINE}, wenn der Ordner nicht erreichbar ist, {@link Kind#ENTSCHEIDUNG} beim Eintritt in die Pause vor
	 * der Entscheidung und {@link Kind#FEHLER} nach einer Störung; jeweils nur, solange das Ergebnis offen ist. Im Modus
	 * {@link ConnectMode#ASK} prüft er nach der Erreichbarkeit, ob beide Seiten Einheiten haben ({@link #askBeforeTheRun}).
	 * In der Pause ({@link Link#deciding}) endet er nach der Prüfung der Erreichbarkeit: kein Lauf, kein Hochladen, kein
	 * Abholen. Setzt {@link Link#hurry}.
	 * <p>
	 * Zuerst, vor jeder Arbeit am gemeinsamen Ordner und vor der Auflösung, macht er die Marke "sauber beendet" ungültig
	 * ({@link UnitIndex#invalidateCleanMarker}). Gelingt das nicht, schreibt dieser Takt nichts: Er prüft nur die
	 * Erreichbarkeit und meldet sonst eine Störung (im ersten Lauf auch als Ergebnis von {@link #connect}, damit es nicht
	 * ausbleibt); der nächste Takt versucht es erneut, nach dem Abstand {@link #TICK}.
	 *
	 * @return ob die Auflösung der Verluste lief (nach dem Hochladen) oder ausbleiben muss, weil die Marke stehen blieb;
	 *         {@code false}, wenn der Ordner nicht erreichbar war oder der Abgleich pausiert (dann löst {@link #tick()}
	 *         lokal auf, wie ohne Verbindung)
	 */
	private boolean runTick(Link current) {
		current.hurry = false;
		// eine gültige Marke darf über keinem Bestand stehen, den Abholen, Vollabgleich oder Auflösung verändert haben
		boolean markerGone = index.invalidateCleanMarker();
		if (current.path == null) {
			reportUnreachable(current);
			return !markerGone;
		}
		SyncState state = notifier.state();
		boolean returning = state == SyncState.OFFLINE;
		if (returning || state == SyncState.FEHLER || current.probeFirst) {
			if (!probe(current.path)) {
				if (isCurrent(current)) {
					reportUnreachable(current);
				}
				return !markerGone;
			}
			current.probeFirst = false;
		} else if (!files.isDirectory(current.path) && !probe(current.path)) {
			// ein Takt ohne eigene Änderung liest nur, und ein fehlender Ordner sähe dabei leer aus: Er gälte als aktuell
			if (isCurrent(current)) {
				reportUnreachable(current);
			}
			return !markerGone;
		}
		if (returning) {
			// nach der Rückkehr ein Vollabgleich; ein laufender setzt am Zeiger fort, und danach beginnt ein neuer
			wishFullSync(current);
			if (hasJobs() && isCurrent(current)) {
				notifier.setState(SyncState.AUSSTEHEND);
			}
		}
		if (!markerGone) {
			// nur die Erreichbarkeit war dran; in der Pause vor der Entscheidung schreibt der Takt ohnehin nichts, und ihr
			// Ergebnis steht schon fest
			if (!current.deciding && isCurrent(current)) {
				reportFailure(current, new LocalFileException(cleanMarkerStuck(), null));
			}
			return true;
		}
		if (current.connectMode == ConnectMode.ASK && !current.deciding && !askBeforeTheRun(current)) {
			return false;
		}
		if (current.deciding) {
			// Pause bis zur Entscheidung: kein Lauf, kein Hochladen, kein Abholen; der Takt prüft nur die Erreichbarkeit (oben),
			// aufgelöst wird lokal wie ohne Verbindung
			return false;
		}
		Instant now = clock.instant();
		Round round = new Round(current);
		if (current.run == null && fullSyncDue(current, now)) {
			round.run(() -> beginRun(current));
			if (current.deciding) {
				// die Schutzregel verbot das Beiseitelegen: Pause wie oben
				return false;
			}
			if (setAsidePending(current)) {
				// das Beiseitelegen steht noch aus (das Umbenennen oder schon das Entscheiden scheiterte): Der Bestand, der
				// beiseitegelegt werden kann, lädt nichts hoch, holt nichts ab und heilt nichts; der nächste Takt versucht es
				// erneut (nach dem Abstand TICK, denn der Takt gilt als gestört)
				if (isCurrent(current)) {
					reportRound(current, round);
				}
				return false;
			}
		}
		if (current.run != null) {
			// Verkäufe zuerst: Ein Stück des Laufs dauert bis zu FULL_SYNC_SLICE_BUDGET und einen Block, und ein Verkauf soll
			// nach höchstens 5 Sekunden oben sein
			if (hasJobs()) {
				round.run(() -> runJobs(current, round));
			}
			round.run(() -> runSlice(current, round, now));
			if (current.run == null) {
				// der Lauf ist zu Ende: Eigene ausstehende Versionen, die er hereinkopiert hat (etwa nach dem Beiseitelegen aus
				// einem abgebrochenen Hochladen), nennt jetzt der Index; einmal je Lauf, damit ein Speichervorgang ein Auftrag bleibt
				round.run(() -> enqueuePending(current, withUnindexed(index.pendingUnits())));
			}
		}
		round.run(() -> runJobs(current, round));
		// nach dem Hochladen, auch wenn der Ordner dabei wegfiel (die Auflösung ist lokal): Die Zusammenführungen gehen mit
		// dem nächsten Takt hinaus
		resolver.resolvePending(RESOLVE_BUDGET);
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
		round.run(() -> decideUnknownListNumbers(round));
		if (!isCurrent(current)) {
			return true;
		}
		// das Ende des ersten Laufs ist sein Ergebnis, auch wenn danach im selben Takt etwas scheiterte
		settleFirstRunEnd(current);
		if (!reportRound(current, round)) {
			notifier.succeeded(clock.instant(), hasJobs());
			current.hurry = true;
		}
		return true;
	}

	/**
	 * Meldet die Störung eines Durchlaufs: Ordner nicht erreichbar ({@link #reportUnreachable}) oder eine andere
	 * ({@link #reportFailure}, die erste).
	 *
	 * @return ob es eine Störung gab
	 */
	private boolean reportRound(Link current, Round round) {
		if (round.unreachable) {
			reportUnreachable(current);
			return true;
		}
		if (round.failure != null) {
			reportFailure(current, round.failure);
			return true;
		}
		return false;
	}

	/**
	 * Ob ein Beiseitelegen noch aussteht: entschieden und noch nicht geschehen, oder mit {@link ConnectMode#SET_ASIDE}
	 * verlangt (über {@link #connect} oder {@link #decideConnect}) und noch nicht entschieden, weil der Beginn vorher
	 * scheiterte. Mit {@link ConnectMode#MERGE} nie. Unter {@code workLock}.
	 */
	private static boolean setAsidePending(Link current) {
		ConnectDecision decision = current.decision;
		return decision != null ? decision.setAside() : current.connectMode == ConnectMode.SET_ASIDE;
	}

	/**
	 * Modus {@link ConnectMode#ASK} vor dem Beginn des ersten Laufs: Haben beide Seiten Einheiten
	 * ({@link FullSync#bothHaveUnits}), pausiert der Abgleich ({@link #enterDecision}, Beiseitelegen angeboten nach der
	 * Schutzregel); sonst wird vereinigt wie mit {@link ConnectMode#MERGE}, und der Takt setzt fort. Unter {@code workLock}.
	 *
	 * @return ob der Takt fortsetzt (auch in der Pause, die er dann selbst erkennt); {@code false}, wenn die Prüfung
	 *         scheiterte (Zustand und Ergebnis sind dann gesetzt) oder die Verbindung nicht mehr die aktuelle ist
	 */
	private boolean askBeforeTheRun(Link current) {
		Round check = new Round(current);
		boolean[] both = new boolean[1];
		check.run(() -> both[0] = FullSync.bothHaveUnits(local, current.shared));
		if (!isCurrent(current) || reportRound(current, check)) {
			return false;
		}
		if (both[0]) {
			enterDecision(current, !wroteSinceStartup);
		} else {
			current.connectMode = ConnectMode.MERGE;
		}
		return true;
	}

	/**
	 * Der Abgleich pausiert vor dem Beginn des ersten Laufs, bis {@link #decideConnect} entscheidet: Zustand
	 * {@link SyncState#ENTSCHEIDUNG}, Rückruf {@link SyncListener#connectDecisionNeeded} (einmal je Eintritt in die Pause),
	 * ein noch offenes Ergebnis endet mit {@link Kind#ENTSCHEIDUNG}. Die Entscheidung des Verbindens ist wieder offen: Die
	 * Antwort fasst sie neu ({@link #beginRun}); ein Beiseitelegen verhindert dann die Schutzregel, sobald gespeichert wurde.
	 * Unter {@code workLock}.
	 */
	private void enterDecision(Link current, boolean setAsideAllowed) {
		current.deciding = true;
		current.connectMode = ConnectMode.ASK;
		current.decision = null;
		notifier.setState(SyncState.ENTSCHEIDUNG);
		notifier.connectDecisionNeeded(current.text, setAsideAllowed);
		settle(current, new ConnectOutcome(Kind.ENTSCHEIDUNG, setAsideAllowed, current.setAsidePath, null));
	}

	/**
	 * Eine Störung bei erreichbarem Ordner: Zustand {@link SyncState#FEHLER} mit SYS-006 (oder dem Text einer lokalen Datei);
	 * im ersten Lauf einer Verbindung ist das ihr Ergebnis, falls noch keins feststeht.
	 */
	private void reportFailure(Link current, Exception failure) {
		String text = failureText(current, failure);
		notifier.failed(text, failure);
		if (current.firstRun) {
			settle(current, new ConnectOutcome(Kind.FEHLER, false, current.setAsidePath, text));
		}
	}

	/**
	 * Legt nach dem Ende des ersten Laufs ({@link Link#endOutcomeDue}) dessen Ergebnis fest: {@link Kind#AUSSTEHEND}, wenn
	 * noch Aufträge warten, sonst {@link Kind#AKTUELL}; {@link Kind#ABGEBROCHEN}, wenn die Verbindung nicht mehr die aktuelle
	 * ist (wie bei {@link #connect} "getrennt oder neu verbunden"). Einmal; danach ohne Wirkung. Unter {@code workLock}.
	 */
	private void settleFirstRunEnd(Link current) {
		if (!current.endOutcomeDue) {
			return;
		}
		current.endOutcomeDue = false;
		// ist die Verbindung nicht mehr die aktuelle, wurde getrennt oder neu verbunden: dann ABGEBROCHEN (hat das schon
		// abortPendingOutcome festgelegt, ist dieses ohne Wirkung). Nur noch zur Vorsicht, denn die Verbindung wechselt nur
		// unter workLock, die der Takt hält
		Kind kind = !isCurrent(current) ? Kind.ABGEBROCHEN : hasJobs() ? Kind.AUSSTEHEND : Kind.AKTUELL;
		settle(current, new ConnectOutcome(kind, false, current.setAsidePath, null));
	}

	/**
	 * Der Ordner ist nicht erreichbar: Zustand {@link SyncState#OFFLINE} (SYS-005 einmal je Ausfall). Im ersten Lauf einer
	 * Verbindung ist das ihr Ergebnis, falls noch keins feststeht. Eine Pause vor der Entscheidung endet: Nach der Rückkehr
	 * prüft der Beginn die Dateien erneut und fragt erneut.
	 */
	private void reportUnreachable(Link current) {
		current.deciding = false;
		notifier.unreachable(current.text, true);
		if (current.firstRun) {
			settle(current, new ConnectOutcome(Kind.OFFLINE, false, current.setAsidePath, StoreMessages.unreachable(current.text)));
		}
	}

	/** Ob ein neuer Vollabgleich beginnen soll (gefragt nur, solange keiner läuft). */
	private static boolean fullSyncDue(Link current, Instant now) {
		return current.fullSyncWanted || current.wantedDuringRun || current.connectMode != null
				|| current.lastFullSync == null || !now.isBefore(current.lastFullSync.plus(FULL_SYNC_INTERVAL));
	}

	/**
	 * Wünscht einen Vollabgleich (sofort abgleichen, Liste verlangt es, Rückkehr aus {@link SyncState#OFFLINE}, neue
	 * Kennung): Läuft einer, wird er nicht neu begonnen, sondern nach seinem Ende beginnt ein neuer.
	 */
	private static void wishFullSync(Link current) {
		if (current.run != null) {
			current.wantedDuringRun = true;
		} else {
			current.fullSyncWanted = true;
		}
	}

	/** SYS-006 mit dem gemeinsamen Ordner; bei einem Fehler an einer lokalen Datei des Dienstes dessen eigener Text. */
	private static String failureText(Link current, Exception failure) {
		if (failure instanceof LocalFileException) {
			return failure.getMessage();
		}
		return StoreMessages.syncFailed(current.text, StoreMessages.describe(failure));
	}

	/**
	 * Beginn eines Vollabgleichs, in einem Takt: beim Verbinden zuerst die Entscheidung über Beiseitelegen und Mitnehmen
	 * (einmal je Verbindung, {@link Link#decision}; das Mitnehmen gilt für alle Abschnitte des Laufs) und das Beiseitelegen
	 * (der einzige Schritt, der den ganzen lokalen Bestand ändert, vor allen Abschnitten; {@link #setAsideLocal}). Verbietet
	 * die Schutzregel das Beiseitelegen, beginnt kein Lauf: Der Abgleich pausiert mit der Frage ({@link #enterDecision}). Im
	 * ersten Lauf einer Verbindung danach die Marke "verbunden" als unvollständig. Dann die ausstehenden Versionen
	 * einreihen, nach einem Neustart die jungen eigenen Ansprüche beobachten und die Abschnitte bestimmen
	 * ({@link FullSyncRun#begin}). Ein Wunsch nach einem Vollabgleich gilt ab hier dem nächsten Lauf; scheitert der Beginn,
	 * bleibt er offen. Die Positionen der Änderungslisten ändert der Vollabgleich nicht.
	 */
	private void beginRun(Link current) throws IOException {
		current.fullSyncWanted = false;
		current.wantedDuringRun = false;
		try {
			ConnectMode mode = current.connectMode;
			if (mode != null) {
				if (current.decision == null) {
					// einmal je Verbindung, beim ersten Treffen: Ein wiederholter Beginn (etwa nach einem Ausfall danach) setzt mit
					// dieser Entscheidung fort. Neu entschieden lägen inzwischen gespeicherte Verkäufe im lokalen Bestand, und er
					// würde ihretwegen (erneut) beiseitegelegt.
					boolean both = FullSync.bothHaveUnits(local, current.shared);
					current.decision = new ConnectDecision(both && mode == ConnectMode.SET_ASIDE, !both || mode == ConnectMode.MERGE);
				}
				ConnectDecision decision = current.decision;
				if (decision.setAside() && !setAsideLocal(current, decision)) {
					// die Schutzregel verbietet es inzwischen (vor jedem Versuch geprüft, auch nach einem gescheiterten
					// Umbenennen): Pause mit der Frage, nur noch Mitnehmen ist angeboten
					enterDecision(current, false);
					return;
				}
			}
			current.uploadLocalClaims = mode != null && current.decision.uploadLocalClaims();
			if (current.firstRun) {
				// nach der Entscheidung und dem Beiseitelegen, vor allen Abschnitten: Bricht der Lauf ab, setzt der nächste Start
				// mit Mitnehmen fort; fehlt die Marke (Abbruch davor), fragt er neu. Ohne Marke beginnt kein Lauf: Der Takt scheitert
				// mit einem Text über die lokale Datei, und der nächste versucht es erneut
				try {
					writeConnectionMarker(current, false);
				} catch (IOException | RuntimeException e) {
					throw new LocalFileException(markerWriteFailed(), e);
				}
				notifier.setConnectionComplete(false);
			}
			// gesperrte Einheiten dürfen wieder geheilt werden: Die gemeinsame Kopie kann inzwischen geheilt sein
			synchronized (lock) {
				unrepairable.clear();
			}
			// Kandidaten für ausstehende Versionen nennt der Index. Am Beginn und am Ende des Laufs, nicht je Abschnitt: Ein
			// Speichervorgang über mehrere Einheiten bleibt so ein Auftrag (eine Änderungsliste)
			enqueuePending(current, withUnindexed(index.pendingUnits()));
			if (current.allUnitsWanted) {
				// nach einem Neustart: die eigenen Ansprüche der letzten 15 Minuten weiter auf eine späte Niederlage prüfen;
				// gelesen werden nur die Einheiten, in deren gültiger Kette der Index ein junges eigenes Glied kennt (der
				// Anspruch entscheidet dann selbst). Eine Version, die lange nach dem Schreiben hochgeladen wurde (alte ID,
				// junger Anspruch), wird nach einem Neustart nicht beobachtet; den Vergleich der eigenen Ansprüche beider Seiten
				// (checkOwnClaim) im nächsten Vollabgleich fängt sie. Eigen sind auch die Ansprüche einer früheren ID von vor
				// ihrer Neuvergabe.
				Ownership own = ownership();
				Instant now = clock.instant();
				Set<UnitKey> recent = index.recentOwnChainUnits(own::isOwnId,
						now.minus(LATE_DEFEAT_WINDOW).minus(CLOCK_SLACK));
				watchOwnClaims(LocalScan.recentOwnClaims(local, own, now, LATE_DEFEAT_WINDOW, recent));
			}
			FullSyncRun run = new FullSyncRun(local, current.shared, clock);
			run.begin();
			current.run = run;
			publishProgress(current, run.progress());
		} catch (IOException | RuntimeException e) {
			current.fullSyncWanted = true;
			throw e;
		}
	}

	/**
	 * Legt den lokalen Bestand beiseite, wenn die Schutzregel es erlaubt; unter {@code workLock}. Prüfung und Umbenennen
	 * bilden einen Schritt, mit dem sich kein lokales Schreiben eines {@link #append} überschneidet: Unter {@code lock} wird
	 * der Merker geprüft und {@link #settingAside} gesetzt, dann gewartet, bis kein {@link #append} mehr im lokalen Bestand
	 * arbeitet ({@link #localWrites}: Dateien, Index, Einreihen; das betrifft nur Speichervorgänge, die nicht zählen; einer,
	 * der zählt, hat den Merker schon gesetzt). Der gezählte Teil nimmt nur {@code resolveLock}, den Monitor des Index, kurz
	 * {@code lock} sowie die Monitore der Schlange der Auflösung und des {@link SyncNotifier} (beide ohne weitere Sperre
	 * darunter), nie {@code workLock}; wer sie hält, wartet nie auf {@code workLock}, die hier allein gehalten wird. Deshalb
	 * endet das Warten immer. Ein {@link #append}, das danach beginnen will, wartet, bis der neue Datenordner bereit ist
	 * (umbenannt, Index geleert), und schreibt dann dorthin. Die Aufträge des alten Bestands (etwa Zusammenführungen der
	 * Auflösung während der Pause) gehen mit ihm beiseite und nicht hinaus. Scheitert das Umbenennen, bleibt die
	 * Entscheidung, und der nächste Beginn prüft erneut.
	 *
	 * @return {@code false}, wenn die Schutzregel es verbietet (nichts ist geschehen); sonst {@code true}
	 */
	private boolean setAsideLocal(Link current, ConnectDecision decision) throws IOException {
		boolean interrupted;
		synchronized (lock) {
			if (wroteSinceStartup) {
				return false;
			}
			settingAside = true;
			interrupted = awaitUninterruptibly(() -> localWrites <= 0);
		}
		if (interrupted) {
			// erst nach dem Takt wiederherstellen: Mit gesetztem Merker scheiterte sonst jeder unterbrechbare Dateizugriff darin
			interruptAfterTick = true;
		}
		try {
			Path aside = FullSync.setAside(local.dataRoot(), files, clock);
			// ab hier ist beiseitegelegt: nie ein zweites Mal, auch wenn danach etwas scheitert
			current.decision = new ConnectDecision(false, decision.uploadLocalClaims());
			current.setAsidePath = aside;
			LOGGER.info("Der lokale Bestand wurde beiseitegelegt: " + aside);
			// der Datenordner wird neu gefüllt, und gleiche Dateinamen können anderen Inhalt haben
			clearIndex();
			synchronized (lock) {
				if (link == current) {
					jobs.clear();
					queued.clear();
				}
			}
		} finally {
			synchronized (lock) {
				settingAside = false;
				lock.notifyAll();
			}
		}
		return true;
	}

	/**
	 * Ein Stück des laufenden Vollabgleichs: Abschnitte und Blöcke, solange der nächste nach der Dauer des vorigen noch vor
	 * Ablauf von {@link #FULL_SYNC_SLICE_BUDGET} seit Beginn des Takts fertig würde ({@link FullSyncRun#step}), mindestens
	 * aber ein Block. Scheitert ein Block (etwa weil der Ordner wegfällt), bleibt der
	 * Zeiger davor stehen, und der nächste erreichbare Takt wiederholt ihn; jeder Schritt darin ist wiederholbar. Nach dem
	 * letzten Abschnitt endet der Lauf.
	 *
	 * @param sliceStart der Beginn des Takts
	 */
	private void runSlice(Link current, Round round, Instant sliceStart) throws IOException {
		FullSyncRun run = current.run;
		Instant end = sliceStart.plus(FULL_SYNC_SLICE_BUDGET);
		boolean finished;
		try {
			finished = run.step(end, units -> syncSection(current, round, units));
		} finally {
			publishProgress(current, run.progress());
		}
		if (finished) {
			if (current.firstRun) {
				try {
					writeConnectionMarker(current, true);
				} catch (IOException | RuntimeException e) {
					// der Lauf endet trotzdem: Die Marke bleibt unvollständig, und der nächste Start nimmt ohne Rückfrage mit (die
					// sichere Richtung)
					LOGGER.log(Level.WARNING, markerWriteFailed(), e);
				}
			}
			endRun(current, round);
		}
	}

	/**
	 * Schreibt die Marke "verbunden" für die Verbindung, mit dem jetzigen Zeitpunkt, und führt {@link #connection} nach. Am
	 * Beginn wie am Ende des ersten Laufs (auch dort, wo ein Scheitern nur im Log steht) geht jedes Schreiben hierüber.
	 */
	private void writeConnectionMarker(Link current, boolean complete) throws IOException {
		ConnectionMarker.State state = new ConnectionMarker.State(current.text, clock.instant(), complete);
		ConnectionMarker.write(appDataDir, files, state);
		connection = state;
	}

	/** Der Text, wenn die Marke "verbunden" sich nicht schreiben lässt; nennt die lokale Datei. */
	private String markerWriteFailed() {
		return "Die Marke 'verbunden' ließ sich nicht schreiben: " + appDataDir.resolve(ConnectionMarker.FILE_NAME);
	}

	/** Der Text der Störung, solange ein Takt die Marke "sauber beendet" weder löschen noch leeren kann. */
	private String cleanMarkerStuck() {
		return "Die Marke 'sauber beendet' ließ sich weder löschen noch leeren: " + index.cleanMarker();
	}

	/**
	 * Ein Takt scheiterte an einer lokalen Datei des Dienstes (der Marke "verbunden" oder "sauber beendet"), nicht am
	 * gemeinsamen Ordner: Der Text
	 * ist fertig und nennt die Datei; {@link #failureText} gibt ihn unverändert weiter statt SYS-006.
	 */
	private static final class LocalFileException extends IOException {
		private static final long serialVersionUID = 1L;

		LocalFileException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * Ein Block des Vollabgleichs: gleicht die Einheiten ab ({@link FullSync#syncUnits}, mit der Entscheidung über das
	 * Mitnehmen vom Beginn des Laufs), löst die geänderten auf, trägt sie in den Index ein, meldet die Verluste und klärt
	 * umstrittene Plätze.
	 * <p>
	 * Geändert ist auch jede lokale Einheit des Blocks, die der Index nicht kennt ({@link UnitIndex#entry}, eine Abfrage im
	 * Speicher): Kam sie an der App vorbei in den lokalen Bestand, während der Index als sauber beendet galt (etwa eine
	 * zurückgespielte Sicherung), nimmt der Block sie wie eine kopierte auf und meldet sie der Oberfläche.
	 * <p>
	 * Jede geänderte Einheit wird je Takt einmal aufgelöst, hier im Block (zählt zu seiner Dauer und damit zum Budget):
	 * Das Melden am Ende des Takts ({@link #reportChanges}) übergeht sie ({@link #settled}), außer ein späterer Schritt des
	 * Takts hat sie wieder geändert.
	 * <p>
	 * Scheitert der Block mittendrin, werden die geänderten Einheiten und die Verluste bis dahin (das Teilergebnis)
	 * trotzdem eingetragen und gemeldet, bevor der Fehler weitergeht: Ein ausgeglichener Anspruch ist danach auf beiden
	 * Seiten gleich, und die Wiederholung des Blocks fände ihn nicht mehr; doppelt meldet sie nichts, weil sie nur noch
	 * findet, was noch nicht erledigt ist (und {@link #reportLoss} je Version nur einmal meldet). Umstrittene Plätze und
	 * temporäre Dateien bleiben der Wiederholung überlassen, sie finden sich dabei wieder. Die Vorstellung des Blocks gilt
	 * bis zur gelungenen Wiederholung als in Arbeit ({@link FullSyncRun#inProgress}), die Auflösung ihrer Verluste wartet.
	 */
	private void syncSection(Link current, Round round, List<UnitKey> units) throws IOException {
		Instant now = clock.instant();
		// Hochgeladene Ansprüche (beim Mitnehmen alle, sonst die eigenen, die im gemeinsamen Ordner fehlten) ab jetzt
		// beobachten, je Anspruch sofort, damit auch ein abgebrochener Abgleich nichts verliert
		FullSyncResult result = FullSync.syncUnits(local, current.shared, units, current.uploadLocalClaims, ownership(),
				files, clock, unit -> index.entry(unit) != null, uploaded -> watchOwnClaims(
						List.of(new LocalScan.OwnClaim(uploaded.unit(), uploaded.claimName(), uploaded.version(), now))),
				partial -> takeOverSection(round, partial));
		takeOverSection(round, result);
		settleAll(current, result.contested(), round);
		deleteOwnOldTemps(current, result.sharedTemps());
	}

	/**
	 * Übernimmt aus dem (Teil-)Ergebnis eines Blocks die geänderten Einheiten in Index und Takt und meldet die Verluste:
	 * zuerst die, die der Abgleich gefunden hat, dann die aus den Dateien abgeleiteten ({@link #reportLosses}). Die Einheiten
	 * gelten danach in diesem Takt als erledigt ({@link #settled}).
	 */
	private void takeOverSection(Round round, FullSyncResult result) {
		// Die Positionen der Listen bleiben stehen: Das Lesen des Eintrags des Gewinners deckt einen abweichenden lokalen
		// Anspruch sofort auf; schon kopierte Dateien kosten dabei nur einen Vergleich. Was keine Liste aufdeckt (Liste
		// übersprungen, Anspruch kam erst nach Ablauf der Beobachtung), meldet der Abgleich selbst als umstritten.
		round.changed.addAll(result.changed());
		// vor dem Auflösen: Ein Schreiben, das das Auflösen nicht mehr sieht, nimmt die Einheit danach wieder heraus
		settled.addAll(result.changed());
		// Die Auflösung im selben Takt liest die Geschwister einer Buchung aus dem Index: Was der Abgleich kopiert hat, steht
		// schon vorher darin, nicht erst nach dem Melden am Ende des Takts (sonst sähe ein fremdes Umsetzen wie ein Storno aus)
		reportLosses(result.changed(), result.lost());
	}

	/**
	 * Räumt im gemeinsamen Ordner die eigenen alten temporären Dateien auf, die der Abgleich dieses Blocks beim Auflisten
	 * der Einheiten ohnehin gesehen hat ({@link FullSyncResult#sharedTemps}; kein weiterer Listenzugriff): Name mit einer
	 * eigenen Kennung ({@code ~karv-<Kennung>-<UUID>.tmp}; die aktuelle oder eine frühere, {@link Ownership#isOwnTempTag}),
	 * seit mehr als {@link #TEMP_AGE} nicht geändert. Neue temporäre Dateien tragen nur die aktuelle. Nur für diese
	 * Dateien wird das Alter abgefragt. Fremde und kennungslose temporäre Dateien bleiben unberührt: Sie gehören einem
	 * anderen Rechner oder sind nicht zuzuordnen. Ein Fehler hier (etwa weil der Ordner wegfällt oder das Löschen nicht
	 * erlaubt ist) lässt den Block nicht scheitern: Das Aufräumen ist nur Pflege, und scheitert der Zugriff wirklich, meldet
	 * ihn der nächste Schritt des Abgleichs. Er betrifft nur seinen Ordner; die übrigen Ordner des Blocks kommen trotzdem
	 * dran, und der gescheiterte beim nächsten Lauf wieder.
	 *
	 * @param sharedTemps Pfade relativ zum Datenordner des gemeinsamen Ordners
	 */
	private void deleteOwnOldTemps(Link current, List<String> sharedTemps) {
		Map<String, List<String>> byFolder = new LinkedHashMap<>();
		for (String rel : sharedTemps) {
			int slash = rel.lastIndexOf('/');
			byFolder.computeIfAbsent(rel.substring(0, slash), folder -> new ArrayList<>()).add(rel.substring(slash + 1));
		}
		// die Kennung einmal je Block, nicht je Datei (ownership() nimmt lock)
		files.deleteOldTemps(current.shared.dataRoot(), byFolder, ownership()::isOwnTempTag, TEMP_AGE, "im gemeinsamen Ordner");
	}

	/**
	 * Ende des Vollabgleichs nach dem letzten Abschnitt: Die Entscheidung des Verbindens ist erledigt, der Abstand bis zum
	 * nächsten Lauf zählt ab jetzt, und ein Wunsch während des Laufs lässt sofort den nächsten beginnen. Nach dem ersten Lauf
	 * leitet der Takt die Verluste der Einheiten ab, die der Index als verloren kennt, und die offenen Konflikte neu. Nach
	 * dem ersten Lauf einer Verbindung meldet es {@link #connectionComplete()}, und der Takt liefert das Ergebnis von
	 * {@link #connect}. Die Marke "verbunden" wurde dann als vollständig geschrieben, oder das Schreiben scheiterte und steht
	 * nur im Log: Sie bleibt unvollständig, und der nächste Start nimmt ohne Rückfrage mit ({@code runSlice}).
	 */
	private void endRun(Link current, Round round) {
		current.connectMode = null;
		current.uploadLocalClaims = false;
		current.run = null;
		current.lastFullSync = clock.instant();
		if (current.wantedDuringRun) {
			current.wantedDuringRun = false;
			current.fullSyncWanted = true;
		}
		if (current.allUnitsWanted) {
			current.allUnitsWanted = false;
			round.allUnits = true;
		}
		if (current.firstRun) {
			current.firstRun = false;
			current.endOutcomeDue = true;
			notifier.setConnectionComplete(true);
		}
		publishProgress(current, null);
	}

	/** Meldet den Fortschritt des Vollabgleichs, solange die Verbindung noch die aktuelle ist. */
	private void publishProgress(Link current, Double progress) {
		synchronized (lock) {
			if (link == current) {
				notifier.setFullSyncProgress(progress);
			}
		}
	}

	/**
	 * Eine Einheit, die sich bei der Suche nach Ausstehendem nicht lesen ließ, bleibt Kandidat ({@link #unindexed}), bis
	 * eine Auflösung gelingt; bis dahin gibt es auch keine Marke "sauber beendet".
	 */
	private void keepCandidate(UnitKey unit) {
		synchronized (resolveLock) {
			unindexed.add(unit);
		}
	}

	/**
	 * Die ausstehenden Versionen der Kandidaten ({@link LocalScan#pendingJobs}). Was sich dabei nicht lesen lässt, bleibt
	 * Kandidat ({@link #keepCandidate}). Ein Kandidat aus {@link #unindexed}, der sich jetzt lesen ließ, wird danach über
	 * {@link #resolveLocal} neu aufgelöst: So kennt der Index seinen Stand wieder, und er verlässt {@link #unindexed}, auch
	 * wenn sonst nichts an ihm geschieht (nichts ausstehend). Ohne das bliebe er bis zum Programmende Kandidat, und
	 * {@link #closeIndex()} schriebe nie die Marke "sauber beendet". Nur herauszunehmen genügte nicht: Scheiterte zuvor das
	 * Auflösen, ist sein Eintrag im Index veraltet. Nie unter {@code lock} aufrufen.
	 *
	 * @param candidates die Einheiten, die untersucht werden
	 */
	private List<UploadJob> pendingJobs(Ownership own, Set<VersionId> skip, Collection<UnitKey> candidates) {
		Set<UnitKey> unreadable = new HashSet<>();
		List<UploadJob> found = LocalScan.pendingJobs(local, own, skip, candidates, unit -> {
			unreadable.add(unit);
			keepCandidate(unit);
		});
		List<UnitKey> readAgain = candidates.stream().filter(unit -> !unreadable.contains(unit) && unindexed.contains(unit))
				.toList();
		indexUnitKeys(readAgain);
		return found;
	}

	/** Die Kandidaten des Index und die Einheiten, die der Index wegen eines Fehlers beim Auflösen nicht kennt. */
	private Set<UnitKey> withUnindexed(Collection<UnitKey> indexed) {
		Set<UnitKey> units = new LinkedHashSet<>(indexed);
		units.addAll(unindexed);
		return units;
	}

	/**
	 * Reiht die ausstehenden Versionen ein, die noch in keinem Auftrag stehen, vor den übrigen Aufträgen.
	 *
	 * @param candidates die Einheiten, die untersucht werden (die Kandidaten aus dem Index); ob eine Version ausstehend ist,
	 *                   entscheidet {@link ChainResolver} auf den Dateien
	 */
	private void enqueuePending(Link current, Collection<UnitKey> candidates) throws IOException {
		Set<VersionId> skip;
		Ownership own;
		synchronized (lock) {
			skip = new HashSet<>(queued);
			own = machine.ownership();
		}
		List<UploadJob> found = pendingJobs(own, skip, candidates);
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
			// vor dem Hochladen: Es schreibt die lokalen Ansprüche
			job.items().forEach(item -> round.unsettle(item.unit()));
			try {
				List<LostVersion> losses = new Uploader(current.shared, current.writer,
						new SharedArbiter(current.shared, local, clock, this::damagedInShared),
						(entries, seq) -> index.markOwnListed(entries, seq)).run(job);
				completed(job, losses, round);
			} catch (ContentMismatchException e) {
				// nichts überschreiben: den Auftrag verwerfen, die Versionen bleiben lokal ausstehend
				notifier.errorOnce(StoreMessages.SYS_CONTENT_MISMATCH + " " + e.relativePath(),
						StoreMessages.contentMismatch(e.relativePath()));
				remove(job);
			} catch (DuplicateMachineException e) {
				if (regenerated) {
					claimedBeforeAbort(job, round);
					round.survive(e);
					continue;
				}
				regenerated = true;
				regenerate(current);
				i--;
			} catch (IOException e) {
				// Niederlagen, die dieser Lauf schon fand, gleich melden (doppelte sortiert reportLoss aus)
				job.losses().forEach(this::reportLoss);
				claimedBeforeAbort(job, round);
				round.survive(e);
			}
		}
	}

	/**
	 * Ein Auftrag brach in der Stufe {@code CLAIMS} ab: Die Einheiten der schon beanspruchten Versionen ({@code 0 …
	 * nextClaim()-1}) gelten als geändert. Ihr Anspruch ist entschieden (gewonnen oder verloren) und liegt lokal; ohne die
	 * Meldung erführe die Oberfläche davon erst, wenn der Auftrag fertig ist.
	 */
	private static void claimedBeforeAbort(UploadJob job, Round round) {
		if (job.stage() != UploadJob.Stage.CLAIMS) {
			return;
		}
		for (int i = 0; i < job.nextClaim(); i++) {
			round.touch(job.items().get(i).unit());
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
			round.unsettle(entry.getKey());
			DamageRepair.Result result = repair.repairVersion(entry.getKey(), entry.getValue());
			logRepaired(result);
			synchronized (lock) {
				sharedRepairs.remove(entry.getKey(), entry.getValue());
			}
			if (result.outcome() == DamageRepair.Outcome.REPAIRED_LOCAL) {
				round.touch(entry.getKey());
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
			round.touch(item.unit());
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
	 * Die alte Kennung ist ab jetzt ein fremder Rechner (der, der sie weiter trägt), die Suche nach neuen Rechnern findet
	 * ihren Ordner und liest ihre Listen ab Nummer 1 nach (für schon vorhandene Dateien nur ein Vergleich). Versionen, die
	 * dieser Rechner bis zur Neuvergabe mit der alten Kennung schrieb, bleiben eigene ({@link Ownership}): Ausstehende werden
	 * weiter hochgeladen, verlorene gemeldet und aufgelöst.
	 * <p>
	 * Die Grenze der alten Kennung ({@link Ownership.Previous#until()}) deckt jede Version, die ihr Generator vergab oder
	 * noch vergibt: Unter {@code lock} wird er angehalten ({@link VersionIdGenerator#freeze()}); die Grenze ist die Uhrzeit,
	 * nie früher als seine späteste ID, und keine spätere ID trägt eine spätere Zeit. Dann wird {@code machine.json}
	 * geschrieben (ohne {@code lock}), danach tauscht ein zweiter Block unter {@code lock} Kennung und Generator und legt den
	 * alten still. Weil jede Version ihre ID und ihre Kennung zusammen unter {@code lock} holt ({@link #writeLocal}),
	 * bekommt ein Speichervorgang, der vor dem Austausch begann, danach IDs der neuen Kennung. Scheitert das Schreiben, läuft
	 * der alte Generator weiter, als wäre nichts gewesen.
	 * <p>
	 * Der Index wird nicht neu aufgebaut ({@link UnitIndex#adoptOwnership}): Versionen der alten Kennung bleiben bis zum
	 * Wechsel eigen, die Stände gelten weiter. Ihre Listennummern gelten als unbekannt, denn sie stammen aus den Listen der
	 * alten Kennung; der Takt liest danach die eigenen Listen ({@link #ownListsWanted}). Ob der Start den Index abgeglichen
	 * hat ({@link #isStarted()}), bleibt unberührt. Der Index übernimmt die neue Ownership vor dem Austausch der Kennung:
	 * {@link #deriveConflicts} liest erst {@link #ownership()}, dann den Index; sieht es die neue Kennung, sind die Nummern
	 * der alten schon vergessen (höchstens "unentschieden", nie ein verpasster Konflikt).
	 */
	private void regenerate(Link current) throws IOException {
		MachineIdentity old;
		VersionIdGenerator retiring;
		Instant until;
		synchronized (lock) {
			old = machine;
			retiring = ids;
			until = retiring.freeze();
		}
		MachineIdentity renewed;
		VersionIdGenerator fresh;
		try {
			renewed = old.regenerate(until);
			// vor dem Austausch: Ein Fehler hier lässt den alten Generator laufen, nie eine neue Kennung ohne Generator
			fresh = new VersionIdGenerator(renewed.id(), clock);
		} catch (IOException | RuntimeException e) {
			synchronized (lock) {
				retiring.thaw();
			}
			throw e;
		}
		index.deleteCleanMarker();
		// unter resolveLock: Kein Auflösen liegt zwischen Übernahme und Austausch
		synchronized (resolveLock) {
			// kein Neuaufbau: Eigen bleiben dieselben Versionen; die Listennummern der alten ID werden unbekannt. Erst der Index,
			// dann die Kennung: Wer ownership() schon als neue sieht, liest danach auch den übernommenen Index (unbekannte
			// Nummern, also "unentschieden"), nie Nummern der alten ID gegen seen der neuen
			index.adoptOwnership(renewed.ownership());
			synchronized (lock) {
				// seit freeze liegt keine ID nach until: die späteste vergebene braucht keine Korrektur der Grenze
				retiring.retire();
				machine = renewed;
				ids = fresh;
			}
		}
		ownListsWanted = true;
		current.bind(renewed.ownership(), local, clock);
		wishFullSync(current);
		notifier.duplicateMachine(StoreMessages.duplicateMachine());
	}

	private void poll(Link current, Round round) throws IOException {
		FeedPoll poll;
		try {
			poll = current.reader.poll();
		} catch (IOException | RuntimeException e) {
			// welche Einheiten das Abholen bis zum Fehler lokal geändert hat, steht erst im nächsten Ergebnis: Alles, was der
			// Takt schon erledigt hat, löst das Melden am Ende erneut auf
			settled.clear();
			throw e;
		}
		index.markListed(poll.listed());
		round.listed.addAll(poll.listed());
		poll.changed().forEach(round::touch);
		forgetUnrepairable(poll.changed());
		poll.pending().forEach(notifier::pending);
		if (poll.fullSyncNeeded()) {
			wishFullSync(current);
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
				round.touch(slot.unit());
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
			if (mine != null && ownership().isOwn(mine)) {
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
			round.unsettle(slot.unit());
			displace(current, slot.unit(), slot.claimName(), sharedBytes.get(), theirs, mine.version());
			unwatch(slot.unit(), slot.claimName());
			round.touch(slot.unit());
		} else if (ownership().isOwn(mine)) {
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
				round.unsettle(own.unit());
				displace(current, own.unit(), own.claimName(), bytes.get(), theirs, own.version());
				synchronized (lock) {
					ownClaims.remove(key, watched);
				}
				round.touch(own.unit());
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
	 * Ausgenommen sind die, die der Durchlauf schon so aufgelöst hat ({@link #settled}). Dazu kommen die Einheiten, deren
	 * Meldung zuvor scheiterte ({@link #unreported}).
	 */
	private void reportChanges(Round round) {
		Set<UnitKey> units = new LinkedHashSet<>(round.changed);
		units.removeAll(settled);
		if (round.allUnits) {
			// nach dem ersten Vollabgleich auch die, die der Index als verloren kennt (Kandidaten; die Verluste leitet
			// reportLosses aus den Dateien ab)
			units.addAll(withUnindexed(index.lostUnits()));
		}
		// in jeder Runde die mit gescheiterter Meldung
		units.addAll(unreported);
		reportLosses(units, List.of());
		notifier.unitsChanged(round.changed);
		// Hat der Durchlauf etwas geändert (oder ist es der Start), stehen die Einheiten jetzt im Index: Die offenen Konflikte
		// werden neu abgeleitet und mit den zuletzt gemeldeten verglichen. Das fängt jeden Weg ab (Liste, später eintreffender
		// Anspruch, Vollabgleich, Aufgehen in eine Zusammenführung, Wegfall), nicht nur die Ankunft aus einer Liste.
		if (round.startup || round.allUnits || !round.changed.isEmpty() || !round.listed.isEmpty()) {
			round.derivation = deriveConflicts();
			if (replaceReportedConflicts(round.derivation)) {
				notifier.conflictsChanged();
			}
		}
	}

	/**
	 * Entscheidet die Elternlöschungen, die an einer unbekannten Listennummer einer eigenen Version hängen ({@link
	 * ConflictLedger.Derivation#undecided()}; nach einem Neuaufbau des Index, einem unsauberen Ende oder einer Neuvergabe der
	 * Rechner-ID kennt er die Nummern nicht): Liest die eigenen Listen ({@code feeds/<eigene ID>/}), trägt die Nummern ein und meldet
	 * {@link SyncListener#conflictsChanged}. Nutzt die Ableitung, die {@link #reportChanges} in diesem Durchlauf gemacht hat;
	 * gab es keine, wird nur dann abgeleitet, wenn noch eine Entscheidung aussteht ({@link #ownListsWanted}: nach dem
	 * Aktivieren der Verbindung, auch nach einem Start ohne Verbindung, und nach einem gescheiterten Versuch). Sonst kostet der
	 * Takt nichts.
	 */
	private void decideUnknownListNumbers(Round round) throws IOException {
		ConflictLedger.Derivation derivation = round.derivation;
		if (derivation == null && ownListsWanted) {
			derivation = deriveConflicts();
		}
		if (derivation == null) {
			return;
		}
		ownListsWanted = derivation.undecided();
		if (!ownListsWanted) {
			return;
		}
		readOwnLists(round.link);
		ownListsWanted = false;
		replaceReportedConflicts(deriveConflicts());
		notifier.conflictsChanged();
	}

	/**
	 * Liest alle eigenen Listen im gemeinsamen Ordner und trägt ihre Nummern für die aktuellen Versionen ein. Was danach noch
	 * unbekannt ist, steht in keiner Liste ({@link UnitIndex#markUnknownOwnAsUnlisted}), sonst ginge ein Verkauf, der offline
	 * entstand und nie in einer Liste stand, nie als dem Löschenden unbekannt ein. Eine unlesbare Liste wird übersprungen
	 * (WARNING): ihre Versionen gelten dann als in keiner Liste, lieber einmal zu viel als verschluckt. Gelesen werden nur
	 * die Listen der aktuellen ID (die Nummern gelten für sie, {@link ConflictLedger}); eine eigene Version einer früheren
	 * ID, die nicht nach der Neuvergabe noch einmal gelistet wurde, gilt deshalb ebenfalls als in keiner Liste. Ihre Nummer
	 * aus den Listen der früheren ID hat der Index bei der Neuvergabe vergessen ({@link UnitIndex#adoptOwnership}).
	 */
	private void readOwnLists(Link current) throws IOException {
		String me = machineId();
		Ownership own = ownership();
		Map<UnitKey, UnitSnapshot> snapshots = index.snapshots();
		// die Stände, gegen die die Listen gelesen werden: nur für sie darf "in keiner Liste" gelten
		Map<UnitKey, VersionId> examined = new HashMap<>();
		snapshots.forEach((unit, snapshot) -> {
			if (snapshot.version() != null && own.isOwnId(snapshot.version())) {
				examined.put(unit, snapshot.version());
			}
		});
		for (long seq : Feeds.feedNumbers(current.shared, me)) {
			Optional<byte[]> bytes = current.shared.readRelative(Feeds.feedPath(me, seq));
			if (bytes.isEmpty()) {
				continue;
			}
			Feed feed;
			try {
				feed = StoreCodec.readFeed(bytes.get());
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Die eigene Liste " + Feeds.feedPath(me, seq) + " ist unlesbar", e);
				continue;
			}
			if (!feed.machine().equals(me) || feed.seq() != seq) {
				LOGGER.warning("Die eigene Liste " + Feeds.feedPath(me, seq) + " gehört nicht zu ihrem Pfad");
				continue;
			}
			// nur die aktuellen Versionen: für ältere merkte sich der Index die Nummer ohne Nutzen
			List<FeedEntry> currentOnes = feed.entries().stream().filter(entry -> {
				Optional<UnitKey> unit = UnitKey.parse(entry.unit());
				UnitSnapshot snapshot = unit.isEmpty() ? null : snapshots.get(unit.get());
				return snapshot != null && entry.version().equals(snapshot.version());
			}).toList();
			index.markOwnListed(currentOnes, seq);
		}
		index.markUnknownOwnAsUnlisted(examined);
	}

	/**
	 * Leitet für jede Einheit die verlorenen Strecken aus dem Zustand ab und meldet, was noch nicht gemeldet wurde. Ein
	 * Fehler bei einer Einheit (auch ein ungeprüfter) kostet nur ihre Meldung, und der Rest des Takts läuft weiter. Scheitert
	 * das Auflösen, bleibt sie Kandidat ({@link #unindexed}) und gilt im laufenden Durchlauf nicht als erledigt
	 * ({@link #settled}); scheitert erst das Ableiten oder Melden danach, holt die nächste Runde die Meldung nach
	 * ({@link #unreported}). Zuerst werden alle Einheiten aufgelöst (Index), dann {@code found} gemeldet, dann die
	 * abgeleiteten Verluste; eine Version meldet {@link #reportLoss} nur einmal.
	 *
	 * @param found Verluste, die der Aufrufer schon kennt (etwa aus dem Abgleich eines Blocks); wirft das Melden eines
	 *              davon, kommen die abgeleiteten trotzdem dran, und der Fehler wird danach weitergeworfen
	 */
	private void reportLosses(Collection<UnitKey> units, Collection<LostVersion> found) {
		// je Einheit nur die abgeleiteten Verluste, nicht ihre Dateien
		Map<UnitKey, List<LostVersion>> derived = new LinkedHashMap<>();
		for (UnitKey unit : units) {
			Resolved resolved;
			try {
				resolved = resolveWithFiles(unit);
			} catch (IOException | RuntimeException e) {
				// nicht erledigt: Ein laufender Durchlauf löst sie am Ende erneut auf
				settled.remove(unit);
				LOGGER.log(Level.WARNING, "Der Stand von " + unit.relativePath() + " ließ sich nicht lesen", e);
				continue;
			}
			try {
				derived.put(unit, LocalScan.losses(resolved.files(), resolved.state()));
			} catch (RuntimeException e) {
				notReported(unit, e);
			}
		}
		RuntimeException failure = null;
		try {
			found.forEach(this::reportLoss);
		} catch (RuntimeException e) {
			failure = e;
		}
		derived.forEach((unit, losses) -> {
			try {
				losses.forEach(this::reportLoss);
				unreported.remove(unit);
			} catch (RuntimeException e) {
				notReported(unit, e);
			}
		});
		if (failure != null) {
			throw failure;
		}
	}

	/** Der Index kennt den Stand, nur die Meldung fehlt (und das Einreihen zur Auflösung): nächste Runde erneut. */
	private void notReported(UnitKey unit, RuntimeException e) {
		unreported.add(unit);
		LOGGER.log(Level.WARNING, "Die Verluste von " + unit.relativePath() + " ließen sich nicht melden", e);
	}

	/**
	 * Reiht die Einheit zur Auflösung ein, auch wenn die Niederlage schon gemeldet war (nach einem Fehler beim Schreiben der
	 * Zusammenführung, nach einem Fehler der Strategie), und meldet die Versionen, die in diesem Programmlauf noch nicht
	 * gemeldet wurden.
	 */
	private void reportLoss(LostVersion loss) {
		resolver.enqueue(loss.unit());
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
		VersionStore shared = path == null ? null : new VersionStore(path.resolve(DATA), files, path, this::machineId);
		Link created = new Link(dir, path, shared, new SyncPositions(appDataDir.resolve(SYNC_STATE), dir));
		created.connectMode = mode;
		created.probeFirst = mode != null;
		synchronized (lock) {
			created.bind(machine.ownership(), local, clock);
			link = created;
			jobs.clear();
			queued.clear();
			ownClaims.clear();
			sharedRepairs.clear();
			repairQueue.clear();
			unrepairable.clear();
			notifier.reset();
			// ein Vollabgleich der vorigen Verbindung ist verworfen (er hing an ihrem Link)
			notifier.setFullSyncProgress(null);
		}
		// die Listennummern eigener Versionen sind womöglich unbekannt (neuer Index, unsauberes Ende): der Takt prüft es
		ownListsWanted = true;
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
			notifier.setFullSyncProgress(null);
		}
	}

	/** Die aktuelle Verbindung; {@code null} im Einzelbetrieb. Nimmt nur kurz {@code lock}. */
	private Link currentLink() {
		synchronized (lock) {
			return link;
		}
	}

	private boolean isCurrent(Link current) {
		return currentLink() == current;
	}

	private boolean hasJobs() {
		synchronized (lock) {
			return !jobs.isEmpty();
		}
	}

	/**
	 * Die aktuelle ID dieses Rechners ({@link MachineIdentity}); sie steht in jeder neuen eigenen Version. Ob eine Version
	 * eigen ist, entscheidet {@link #ownership()}, nicht der Vergleich mit dieser ID.
	 */
	public String machineId() {
		synchronized (lock) {
			return machine.id();
		}
	}

	/**
	 * Was dieser Rechner als eigen ansieht: die aktuelle ID und die früheren bis zu ihrer Neuvergabe (SYS-011, siehe
	 * {@link Ownership}). Nimmt nur kurz {@code lock}; darf deshalb unter jeder anderen Sperre der Folge aufgerufen werden.
	 */
	Ownership ownership() {
		synchronized (lock) {
			return machine.ownership();
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

	/** Was der {@link ConflictResolver} vom Dienst braucht: lokaler Bestand, Index und {@link #append}. */
	private final class ResolverHost implements ConflictResolver.Host {

		@Override
		public Resolved resolve(UnitKey unit) throws IOException {
			return resolveWithFiles(unit);
		}

		@Override
		public boolean shared() {
			return currentLink() != null;
		}

		@Override
		public boolean isListed(UnitKey unit, VersionId version) {
			return index.isListed(unit, version);
		}

		@Override
		public String machineId() {
			return SyncService.this.machineId();
		}

		@Override
		public Ownership ownership() {
			return SyncService.this.ownership();
		}

		@Override
		public Map<UnitKey, ConflictInput.Sibling> siblings(UnitKey booking) {
			Map<UnitKey, UnitSnapshot> event = index.eventSnapshots(booking.eventId());
			List<UnitKey> keys = event.keySet().stream()
					.filter(unit -> unit.type() == UnitType.BOOKING && unit.presentationId().equals(booking.presentationId())
							&& !unit.equals(booking))
					.sorted(Comparator.comparing(UnitKey::relativePath)).toList();
			Map<UnitKey, List<ChainStep>> chains = index.chains(keys);
			Map<UnitKey, ConflictInput.Sibling> siblings = new LinkedHashMap<>();
			for (UnitKey unit : keys) {
				List<ChainStep> chain = chains.getOrDefault(unit, List.of());
				ChainStep head = chain.isEmpty() ? null : chain.get(chain.size() - 1);
				siblings.put(unit, new ConflictInput.Sibling(event.get(unit), head, Set.of()));
			}
			return siblings;
		}

		@Override
		public Set<UnitKey> withOwnVersionsBesideChain(Collection<UnitKey> units) {
			return index.withOwnBesideChain(units);
		}

		/** Schreibt ohne den Merker der Schutzregel: Was die Auflösung schreibt, zählt nicht. */
		@Override
		public List<Version> append(String saveId, List<Change> changes) throws IOException {
			return SyncService.this.append(saveId, changes, false);
		}

		/** Ob der laufende Vollabgleich die Vorstellung der Einheit erst zum Teil abgeglichen hat ({@link FullSyncRun#inProgress}). */
		@Override
		public boolean deferred(UnitKey unit) {
			Link current = currentLink();
			if (current == null) {
				return false;
			}
			FullSyncRun run = current.run;
			return run != null && run.inProgress(unit);
		}

		/** SYS-006 mit dem gemeinsamen Ordner; im Einzelbetrieb mit dem lokalen Bestand, in den geschrieben wird. */
		@Override
		public String failureText(Exception failure) {
			Link current = currentLink();
			return current != null ? SyncService.failureText(current, failure)
					: StoreMessages.syncFailed(local.dataRoot().toString(), StoreMessages.describe(failure));
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
		/** Die fremden Versionen, deren Liste in diesem Durchlauf vollständig verarbeitet wurde. */
		final List<FeedEntry> listed = new ArrayList<>();
		/** Ob es der Durchlauf beim Start ist: Die offenen Konflikte werden dann auch ohne Änderung abgeleitet. */
		boolean startup;
		/** Die Ableitung der offenen Konflikte nach diesem Durchlauf ({@link #reportChanges}); {@code null}, wenn es keine gab. */
		ConflictLedger.Derivation derivation;
		/** Der erste Vollabgleich ist in diesem Takt zu Ende gegangen: Verluste aller Einheiten ableiten. */
		boolean allUnits;
		boolean unreachable;
		Exception failure;

		Round(Link link) {
			this.link = link;
			// nur ein Durchlauf zur Zeit (unter workLock): Was ein früherer erledigt hat, gilt für diesen nicht
			settled.clear();
		}

		/** Die Einheit hat sich geändert; hat dieser Durchlauf sie schon aufgelöst ({@link #settled}), am Ende erneut. */
		void touch(UnitKey unit) {
			changed.add(unit);
			settled.remove(unit);
		}

		/** Vor dem Schreiben in die Einheit: Bricht es mittendrin ab, gilt sie trotzdem nicht als erledigt. */
		void unsettle(UnitKey unit) {
			settled.remove(unit);
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
