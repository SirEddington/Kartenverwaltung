package de.eltviller_carneval_verein.karten.update;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Prüft im Hintergrund, ob die Update-Quelle eine neuere Version hat (KARV-131): 10 s nach dem Start, danach jeweils 4 h
 * nach dem Ende der vorigen Prüfung und auf „Jetzt prüfen“. Es läuft nie mehr als eine Prüfung; eine Ausnahme beendet
 * den Takt nicht. Der eine Listener erfährt jeden Zustand über {@code ui}.
 */
public final class UpdateChecker {

	/** Abstand vom fertigen Start bis zur ersten Prüfung. */
	public static final Duration FIRST_CHECK = Duration.ofSeconds(10);
	/** Abstand zwischen dem Ende einer Prüfung und der nächsten. */
	public static final Duration INTERVAL = Duration.ofHours(4);

	private static final Logger LOG = Logger.getLogger(UpdateChecker.class.getName());

	private final Installation inst;
	private final UpdateSource source;
	private final Scheduler scheduler;
	private final Executor ui;
	private final Clock clock;
	private final AtomicBoolean busy = new AtomicBoolean();
	private volatile boolean closed;
	private volatile UpdateState state = new UpdateState.NotChecked();
	private volatile Consumer<UpdateState> listener = s -> {
	};

	public UpdateChecker(Installation inst, UpdateSource source, Scheduler scheduler, Executor ui, Clock clock) {
		this.inst = inst;
		this.source = source;
		this.scheduler = scheduler;
		this.ui = ui;
		this.clock = clock;
	}

	/** Setzt den einen Listener; er läuft über {@code ui}. */
	public void setListener(Consumer<UpdateState> listener) {
		this.listener = listener;
	}

	/** Räumt alte Update-Dateien auf und startet den Takt. */
	public void start() {
		scheduler.execute(() -> {
			try {
				source.cleanup();
			} catch (Throwable e) {
				LOG.log(Level.WARNING, "Aufräumen alter Update-Dateien gescheitert", e);
			}
		});
		scheduler.scheduleWithFixedDelay(() -> {
			if (busy.compareAndSet(false, true)) {
				check();
			}
		}, FIRST_CHECK, INTERVAL);
	}

	/** Stößt eine Prüfung an, wenn gerade keine läuft. */
	public void checkNow() {
		if (closed || !busy.compareAndSet(false, true)) {
			return;
		}
		try {
			scheduler.execute(this::check);
		} catch (RuntimeException e) {
			busy.set(false);
			LOG.log(Level.WARNING, "Update-Prüfung ließ sich nicht anstoßen", e);
		}
	}

	/** Der aktuelle Zustand. */
	public UpdateState state() {
		return state;
	}

	/** Beendet den Takt; Ergebnisse danach werden verworfen. */
	public void close() {
		closed = true;
		scheduler.close();
	}

	/** Eine Prüfung; der Aufrufer hält die Sperre, sie wird hier freigegeben. */
	private void check() {
		try {
			if (closed) {
				return;
			}
			UpdateState before = state;
			publish(new UpdateState.Checking(before));
			UpdateState result;
			try {
				UpdateInfo info = source.fetchInfo();
				if (info.version().compareTo(inst.version()) > 0) {
					result = new UpdateState.Available(info, clock.instant());
					LOG.info("Update-Prüfung: Version " + info.version().display() + " verfügbar (installiert "
							+ inst.version().display() + ")");
				} else {
					result = new UpdateState.UpToDate(clock.instant());
					LOG.info("Update-Prüfung: aktuell (" + inst.version().display() + ", Quelle "
							+ info.version().display() + ")");
				}
			} catch (Throwable e) {
				Grund grund = Grund.of(e);
				result = new UpdateState.CheckFailed(grund, clock.instant(), before.knownUpdate());
				LOG.log(Level.WARNING, "Update-Prüfung gescheitert (" + grund + ")", e);
			}
			if (!closed) {
				publish(result);
			}
		} catch (Throwable e) {
			LOG.log(Level.WARNING, "Update-Prüfung unerwartet abgebrochen", e);
		} finally {
			busy.set(false);
		}
	}

	private void publish(UpdateState next) {
		state = next;
		Consumer<UpdateState> l = listener;
		ui.execute(() -> {
			try {
				l.accept(next);
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Listener der Update-Prüfung warf eine Ausnahme", e);
			}
		});
	}
}
