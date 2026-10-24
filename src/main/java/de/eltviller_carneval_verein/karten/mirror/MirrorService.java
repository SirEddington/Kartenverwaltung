package de.eltviller_carneval_verein.karten.mirror;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

/**
 * Der Zustandsautomat der Spiegelung. Die Anwendung arbeitet auf den lokalen Ordnern (Master); der Spiegelordner
 * ist eine Kopie, die nach jedem Speichern und Löschen nachgezogen wird und beim Start sowie nach einem Ausfall
 * komplett abgeglichen ({@link MirrorSync#reconcile}) wird. Die Dateilogik steckt in {@link MirrorSync}; diese
 * Klasse entscheidet, wann und auf welchem Thread sie läuft, und meldet das Ergebnis.
 * <p>
 * <b>Threads:</b>
 * <ul>
 * <li>Jeder Dateizugriff auf den Spiegelordner läuft im {@code worker}, in der Anwendung ein einzelner
 * Daemon-Thread. Die Oberfläche wartet nie darauf. Einzige Ausnahme ist die Prüfung in
 * {@link #startup(String)}, die höchstens das Zeitlimit von {@link MirrorReachability} blockiert.</li>
 * <li>Zustand ({@link #state()}, {@link #lastSync()}) und die Rückrufe aus {@link #setHandlers} kommen über den
 * {@code ui}-Executor an, in der Anwendung {@code Platform::runLater}. Intern gilt immer der sofort gesetzte
 * Zustand; die Eigenschaft folgt ihm in der Reihenfolge der Änderungen.</li>
 * </ul>
 * <b>Warteschlange:</b> Eine Entität steht höchstens einmal darin ({@code kind}/{@code id}); wird sie erneut
 * gemeldet, rückt sie ans Ende und die letzte Operation (Speichern oder Löschen) gilt. Weil
 * {@link MirrorSync#pushEntity} ohnehin den lokalen Stand überträgt, ist die Reihenfolge von Speichern und
 * Löschen derselben Entität damit eindeutig. Ein Abgleich steht ebenfalls höchstens einmal darin.
 * <p>
 * <b>Ausfall:</b> Ist der Spiegelordner nicht erreichbar, wird die Warteschlange verworfen und der Zustand ist
 * {@link MirrorState#NICHT_ERREICHBAR}. Das ist kein Datenverlust: Der Abgleich nach der Rückkehr (Timer alle 60
 * Sekunden) holt alles nach. Solange der Ausfall dauert, bleibt jede Einzelübertragung ohne Wirkung und ohne
 * erneute Prüfung. Zur Laufzeit legt dieser Dienst nie den Basisordner an; ein fehlender Basisordner ist ein
 * Ausfall.
 */
public final class MirrorService {

	private static final Logger LOGGER = Logger.getLogger(MirrorService.class.getName());

	/** Abstand der Wiederholversuche in Sekunden. */
	private static final long RETRY_SECONDS = 60;

	/** Schlüssel des Abgleichs in der Warteschlange; kann mit {@code kind/id} nicht kollidieren. */
	private static final String RECONCILE_KEY = "*";

	private static MirrorService instance;

	/** Ergebnis von {@link MirrorService#startup(String)}. */
	public enum StartupResult {
		/** Keine Spiegelung eingerichtet. */
		OFF,
		/** Der Spiegelordner ist erreichbar; der Abgleich läuft im Hintergrund an. */
		REACHABLE,
		/** Der Spiegelordner ist nicht erreichbar; es wird lokal weitergearbeitet. */
		UNREACHABLE
	}

	private enum OpKind {
		RECONCILE, SAVE, DELETE
	}

	// Eine Aufgabe des Hintergrund-Threads; kind und id nur bei SAVE und DELETE
	private record Op(OpKind op, String kind, String id) {
	}

	private final Path localRoot;
	private final Executor ui;
	private final Executor worker;
	private final Predicate<Path> reachable;

	private final ReadOnlyObjectWrapper<MirrorState> stateProperty = new ReadOnlyObjectWrapper<>(MirrorState.AUS);
	private final ReadOnlyObjectWrapper<Instant> lastSyncProperty = new ReadOnlyObjectWrapper<>();

	private volatile Consumer<String> onUnreachable;
	private volatile Runnable onReconnected;
	private volatile Consumer<String> onError;

	// Alles Folgende wird unter lock gelesen und geschrieben; Dateizugriffe geschehen nie darunter.
	private final Object lock = new Object();
	private final Map<String, Op> queue = new LinkedHashMap<>();
	private MirrorState current = MirrorState.AUS;
	private String targetText;
	private Path targetPath;
	// Zählt Umstellungen des Ziels: Ergebnisse eines Auftrags für ein früheres Ziel werden verworfen
	private long generation;
	private boolean drainScheduled;
	// Ob für den laufenden Ausfall schon onUnreachable kam; zurückgesetzt, wenn der Zustand AKTUELL wird
	private boolean unreachableNotified;
	// Ob der Spiegelordner ausgefallen war und die Rückkehr noch nicht gemeldet ist
	private boolean wasDown;
	// Die zuletzt gemeldete Fehlermeldung dieser Störung: Dauert sie an, wiederholt der Timer sie nicht;
	// zurückgesetzt, wenn der Zustand AKTUELL wird oder das Ziel wechselt
	private String lastErrorMessage;

	/**
	 * Für Tests: lokaler Ordner, Thread der Oberfläche, Thread der Arbeit und Erreichbarkeitsprüfung frei wählbar.
	 * Es wird kein Thread gestartet und kein Timer; den Wiederholversuch löst {@link #retryTick()} aus.
	 */
	MirrorService(Path localRoot, Executor ui, Executor worker, Predicate<Path> reachable) {
		this.localRoot = localRoot;
		this.ui = ui;
		this.worker = worker;
		this.reachable = reachable;
	}

	/**
	 * Die Instanz der Anwendung: arbeitet auf {@code AppPaths.appDataDir()}, meldet über
	 * {@code Platform.runLater}, hat einen Daemon-Thread für die Arbeit und einen für den Wiederholversuch.
	 */
	public static synchronized MirrorService getInstance() {
		if (instance == null) {
			Executor worker = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "mirror-worker"));
			instance = new MirrorService(AppPaths.appDataDir().toPath(), Platform::runLater, worker,
					MirrorReachability::isReachable);
			instance.startRetryTimer();
		}
		return instance;
	}

	private static Thread daemon(Runnable runnable, String name) {
		Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
	}

	private void startRetryTimer() {
		ScheduledExecutorService timer = Executors
				.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "mirror-retry"));
		timer.scheduleWithFixedDelay(() -> {
			try {
				retryTick();
			} catch (RuntimeException e) {
				LOGGER.log(Level.WARNING, "Wiederholversuch der Spiegelung ist fehlgeschlagen", e);
			}
		}, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS);
	}

	// --- Abfragen

	/** Der Zustand; Änderungen kommen über den {@code ui}-Executor an. */
	public ReadOnlyObjectProperty<MirrorState> state() {
		return stateProperty.getReadOnlyProperty();
	}

	/** Zeitpunkt des letzten erfolgreichen Abgleichs, {@code null} solange keiner gelang. */
	public ReadOnlyObjectProperty<Instant> lastSync() {
		return lastSyncProperty.getReadOnlyProperty();
	}

	/** Der aktive Spiegelordner, wie er eingestellt wurde (ohne Leerzeichen am Rand); {@code null} bei {@link MirrorState#AUS}. */
	public String target() {
		synchronized (lock) {
			return targetText;
		}
	}

	/**
	 * Setzt die Rückrufe (alle über den {@code ui}-Executor, jeder darf {@code null} sein).
	 *
	 * @param onUnreachable mit dem Pfad, höchstens einmal je Ausfall; zurückgesetzt, wenn der Zustand wieder
	 *        {@link MirrorState#AKTUELL} wird
	 * @param onReconnected bei der Rückkehr aus {@link MirrorState#NICHT_ERREICHBAR} oder
	 *        {@link MirrorState#AUSSTEHEND}, sobald der Abgleich gelang
	 * @param onError mit dem fertigen Text von {@link MirrorMessages#syncFailed} oder {@link MirrorMessages#aborted}
	 */
	public void setHandlers(Consumer<String> onUnreachable, Runnable onReconnected, Consumer<String> onError) {
		this.onUnreachable = onUnreachable;
		this.onReconnected = onReconnected;
		this.onError = onError;
	}

	// --- Steuerung

	/**
	 * Beim Start der Anwendung: prüft den Spiegelordner synchron (blockiert höchstens das Zeitlimit der Prüfung)
	 * und stößt bei Erfolg den Abgleich im Hintergrund an. Ruft {@code onUnreachable} <b>nicht</b>; der Aufrufer
	 * zeigt das Startpopup selbst. Der Ausfall gilt damit aber als gemeldet und der Zustand ist
	 * {@link MirrorState#NICHT_ERREICHBAR}: Gelingt ein späterer Wiederholversuch, kommt {@code onReconnected}.
	 *
	 * @param mirrorDir der Spiegelordner aus den Einstellungen; leer oder {@code null} heißt aus
	 */
	public StartupResult startup(String mirrorDir) {
		String dir = clean(mirrorDir);
		if (dir == null) {
			deactivate();
			return StartupResult.OFF;
		}
		Path path = toPath(dir);
		boolean up = path != null && probe(path);
		synchronized (lock) {
			activate(dir, path);
			if (!up) {
				wasDown = true;
				unreachableNotified = true;
				setState(MirrorState.NICHT_ERREICHBAR);
			}
		}
		if (!up) {
			return StartupResult.UNREACHABLE;
		}
		enqueue(new Op(OpKind.RECONCILE, null, null), true);
		return StartupResult.REACHABLE;
	}

	/**
	 * Setzt den Spiegelordner und startet einen Abgleich; leer oder {@code null} schaltet die Spiegelung ab
	 * ({@link MirrorState#AUS}), ohne Dateien zu verändern. Nicht Erreichbares meldet der Abgleich als Ausfall.
	 */
	public void configure(String mirrorDir) {
		String dir = clean(mirrorDir);
		if (dir == null) {
			deactivate();
			return;
		}
		synchronized (lock) {
			activate(dir, toPath(dir));
		}
		enqueue(new Op(OpKind.RECONCILE, null, null), true);
	}

	/** Meldet, dass die Entität {@code kind}/{@code id} gespeichert wurde und lokal in der Datei steht. */
	public void entitySaved(String kind, String id) {
		enqueue(new Op(OpKind.SAVE, kind, id), false);
	}

	/** Meldet, dass die Entität {@code kind}/{@code id} gelöscht wurde (samt dem dabei entstandenen Backup). */
	public void entityDeleted(String kind, String id) {
		enqueue(new Op(OpKind.DELETE, kind, id), false);
	}

	/** Gleicht sofort ab. Ohne Wirkung bei {@link MirrorState#AUS} und während eines laufenden Abgleichs. */
	public void syncNow() {
		synchronized (lock) {
			if (targetText == null || current == MirrorState.ABGLEICH) {
				return;
			}
		}
		enqueue(new Op(OpKind.RECONCILE, null, null), true);
	}

	/**
	 * Der Wiederholversuch, vom 60-Sekunden-Timer gerufen: Ist der Zustand {@link MirrorState#NICHT_ERREICHBAR},
	 * {@link MirrorState#AUSSTEHEND} oder {@link MirrorState#FEHLER}, wird ein Abgleich eingereiht (er prüft
	 * zuerst die Erreichbarkeit). In jedem anderen Zustand geschieht nichts.
	 */
	void retryTick() {
		synchronized (lock) {
			if (current != MirrorState.NICHT_ERREICHBAR && current != MirrorState.AUSSTEHEND
					&& current != MirrorState.FEHLER) {
				return;
			}
		}
		enqueue(new Op(OpKind.RECONCILE, null, null), false);
	}

	// --- Ziel und Warteschlange

	// Leer und null heißen "kein Ziel"
	private static String clean(String mirrorDir) {
		if (mirrorDir == null || mirrorDir.isBlank()) {
			return null;
		}
		return mirrorDir.trim();
	}

	// Ein unlesbarer Pfad ist kein Pfad; das Ziel gilt dann als nicht erreichbar
	private static Path toPath(String dir) {
		try {
			return Path.of(dir);
		} catch (InvalidPathException e) {
			LOGGER.log(Level.WARNING, "Ungültiger Pfad des Spiegelordners: " + dir, e);
			return null;
		}
	}

	private void deactivate() {
		synchronized (lock) {
			generation++;
			queue.clear();
			targetText = null;
			targetPath = null;
			unreachableNotified = false;
			wasDown = false;
			lastErrorMessage = null;
			setState(MirrorState.AUS);
		}
	}

	// Unter lock: ein neues Ziel; was für das alte Ziel noch läuft oder wartet, zählt nicht mehr
	private void activate(String dir, Path path) {
		generation++;
		queue.clear();
		targetText = dir;
		targetPath = path;
		unreachableNotified = false;
		wasDown = false;
		lastErrorMessage = null;
	}

	/**
	 * Reiht eine Aufgabe ein und sorgt dafür, dass der Hintergrund-Thread läuft. Gleiche Entitäten und der
	 * Abgleich stehen nur einmal in der Warteschlange.
	 *
	 * @param showProgress ob der Zustand sofort auf {@link MirrorState#ABGLEICH} wechselt (nur beim Abgleich auf
	 *        Wunsch des Nutzers; der Wiederholversuch tut das erst, wenn der Ordner wieder da ist)
	 */
	private void enqueue(Op op, boolean showProgress) {
		boolean start;
		synchronized (lock) {
			if (targetText == null) {
				return;
			}
			if (op.op() == OpKind.RECONCILE) {
				queue.putIfAbsent(RECONCILE_KEY, op);
				if (showProgress) {
					setState(MirrorState.ABGLEICH);
				}
			} else {
				// Entfernen und neu einfügen: Die letzte Operation einer Entität gilt und steht hinten
				String key = op.kind() + "/" + op.id();
				queue.remove(key);
				queue.put(key, op);
			}
			start = !drainScheduled;
			drainScheduled = true;
		}
		if (start) {
			try {
				worker.execute(this::drain);
			} catch (RejectedExecutionException e) {
				LOGGER.log(Level.WARNING, "Der Hintergrund-Thread der Spiegelung nimmt keine Aufgaben mehr an", e);
				synchronized (lock) {
					drainScheduled = false;
				}
			}
		}
	}

	// Läuft im worker: arbeitet die Warteschlange ab, bis sie leer ist. Während dessen eingereihte Aufgaben
	// werden noch in diesem Durchlauf erledigt (drainScheduled bleibt bis zum Schluss gesetzt).
	private void drain() {
		boolean finished = false;
		try {
			while (true) {
				Op op;
				long gen;
				Path path;
				String text;
				synchronized (lock) {
					Iterator<Op> next = queue.values().iterator();
					if (!next.hasNext()) {
						drainScheduled = false;
						finished = true;
						return;
					}
					op = next.next();
					next.remove();
					gen = generation;
					path = targetPath;
					text = targetText;
				}
				try {
					execute(op, gen, path, text);
				} catch (RuntimeException e) {
					// Sollte nicht vorkommen (execute fängt alles ab); die Warteschlange muss weiterlaufen
					LOGGER.log(Level.SEVERE, "Unerwarteter Fehler in der Spiegelung", e);
				}
			}
		} finally {
			if (!finished) {
				synchronized (lock) {
					drainScheduled = false;
				}
			}
		}
	}

	// --- Ausführung (im worker)

	private void execute(Op op, long gen, Path path, String text) {
		if (path == null) {
			markUnreachable(gen, text);
			return;
		}
		if (op.op() != OpKind.RECONCILE) {
			synchronized (lock) {
				// Veraltet, oder der Ordner ist ohnehin weg: Der nächste Abgleich holt die Änderung nach
				if (gen != generation || current == MirrorState.NICHT_ERREICHBAR) {
					return;
				}
			}
		}
		if (!probe(path)) {
			markUnreachable(gen, text);
			return;
		}
		if (op.op() == OpKind.RECONCILE) {
			synchronized (lock) {
				if (gen != generation) {
					return;
				}
				// Aus FEHLER heraus bleibt der Zustand während eines Wiederholversuchs stehen (kein Flackern
				// der Anzeige); auf Wunsch des Nutzers steht ABGLEICH schon seit dem Einreihen
				if (current != MirrorState.FEHLER) {
					setState(MirrorState.ABGLEICH);
				}
			}
		}
		try {
			switch (op.op()) {
			case RECONCILE -> MirrorSync.reconcile(localRoot, path);
			case SAVE -> MirrorSync.pushEntity(localRoot, path, op.kind(), op.id());
			case DELETE -> MirrorSync.deleteEntity(localRoot, path, op.kind(), op.id());
			}
		} catch (MirrorAbortedException e) {
			markError(gen, MirrorMessages.aborted(text), null);
			return;
		} catch (IOException | RuntimeException e) {
			if (!probe(path)) {
				markUnreachable(gen, text);
			} else {
				markError(gen, MirrorMessages.syncFailed(text, describe(e)), e);
			}
			return;
		}
		markSuccess(gen, op);
	}

	// Die Prüfung der Erreichbarkeit; eine Prüfung, die wirft, zählt als nicht erreichbar
	private boolean probe(Path path) {
		try {
			return reachable.test(path);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName()
				: e.getClass().getSimpleName() + ": " + message;
	}

	private void markSuccess(long gen, Op op) {
		synchronized (lock) {
			if (gen != generation) {
				return;
			}
			if (op.op() == OpKind.RECONCILE) {
				setLastSync(Instant.now());
				setState(MirrorState.AKTUELL);
				lastErrorMessage = null;
				boolean back = wasDown;
				wasDown = false;
				unreachableNotified = false;
				if (back) {
					notifyUi(() -> {
						Runnable handler = onReconnected;
						if (handler != null) {
							handler.run();
						}
					});
				}
			} else if (current == MirrorState.AKTUELL) {
				// Eine einzelne Entität sagt über einen früheren Fehler nichts; nur ein aktueller Stand wird fortgeschrieben
				setLastSync(Instant.now());
			}
		}
	}

	private void markUnreachable(long gen, String text) {
		synchronized (lock) {
			if (gen != generation) {
				return;
			}
			// Ohne Ordner ist jede Übertragung wirkungslos; der Abgleich nach der Rückkehr holt alles nach
			queue.clear();
			wasDown = true;
			setState(MirrorState.NICHT_ERREICHBAR);
			if (!unreachableNotified) {
				unreachableNotified = true;
				notifyUi(() -> {
					Consumer<String> handler = onUnreachable;
					if (handler != null) {
						handler.accept(text);
					}
				});
			}
		}
	}

	// Meldet und protokolliert einen Fehler nur, wenn er sich vom zuletzt gemeldeten unterscheidet: Eine
	// andauernde Störung (zum Beispiel SYS-004) käme sonst mit jedem Wiederholversuch erneut in die Fußzeile
	private void markError(long gen, String message, Exception cause) {
		synchronized (lock) {
			if (gen != generation) {
				return;
			}
			setState(MirrorState.FEHLER);
			if (message.equals(lastErrorMessage)) {
				LOGGER.log(Level.FINE, "Andauernder Fehler der Spiegelung: {0}", message);
				return;
			}
			lastErrorMessage = message;
			LOGGER.log(Level.WARNING, message, cause);
			notifyUi(() -> {
				Consumer<String> handler = onError;
				if (handler != null) {
					handler.accept(message);
				}
			});
		}
	}

	// --- Zustand und Rückrufe

	// Unter lock: setzt den internen Zustand sofort, die Eigenschaft folgt über den ui-Executor in derselben Reihenfolge
	private void setState(MirrorState state) {
		if (current == state) {
			return;
		}
		current = state;
		notifyUi(() -> stateProperty.set(state));
	}

	private void setLastSync(Instant instant) {
		notifyUi(() -> lastSyncProperty.set(instant));
	}

	// Ein Fehler im Rückruf oder ein nicht laufender UI-Thread darf den Hintergrund-Thread nicht beenden
	private void notifyUi(Runnable action) {
		try {
			ui.execute(() -> {
				try {
					action.run();
				} catch (RuntimeException e) {
					LOGGER.log(Level.WARNING, "Rückruf der Spiegelung ist fehlgeschlagen", e);
				}
			});
		} catch (RuntimeException e) {
			LOGGER.log(Level.WARNING, "Die Oberfläche ist für die Spiegelung nicht erreichbar", e);
		}
	}
}
