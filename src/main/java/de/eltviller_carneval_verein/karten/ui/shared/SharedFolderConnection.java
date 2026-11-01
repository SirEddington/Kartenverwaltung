package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.eltviller_carneval_verein.karten.AppPaths;
import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.store.Reachability;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * Das Verbinden mit dem gemeinsamen Ordner in der App: hält den einen {@link ConnectFlow} mit seiner
 * {@link ConnectProgressView} und dem Hintergrund-Thread "gemeinsamer-ordner", auf dem alles läuft, was auf den Takt des
 * Versionsspeichers wartet ({@code connect}, {@code decideConnect}, {@code disconnect}, {@code renameMachine}, die
 * Prüfungen). Zurück auf den FX-Thread geht es über {@code Platform.runLater}, dieselbe Warteschlange, über die der
 * Versionsspeicher seine Eigenschaften ändert.
 * <p>
 * Wohin es nach dem Ablauf geht: Beim Start entscheidet der {@link StartHandler} (die Repositories sind dann noch nicht
 * geladen); nach dem Knopf lädt die App neu und zeigt das Hauptmenü, nach einem Abbruch oder einer gescheiterten Prüfung
 * kehrt sie in die Einstellungen zurück. Die Einstellungen werden dabei neu aufgebaut, weil die Frage nach ungespeicherten
 * Änderungen die Änderungserkennung zurückgesetzt (und bei "Nicht speichern" das Einstellungsobjekt ausgetauscht) hat; den
 * eingegebenen Pfad und den Fehlertext nimmt der neue Abschnitt über {@link #takePending()} mit.
 */
public final class SharedFolderConnection implements ConnectProgressView.Host {

	/** Was nach dem Verbinden beim Start geschieht; beide Rückrufe auf dem FX-Thread, höchstens einer je Start. */
	public interface StartHandler {

		/** Der erste Lauf ist zu Ende oder "Trotzdem öffnen": Bestand laden und den Start abschließen. */
		void finished();

		/** Abgebrochen: Bestand laden, den Start abschließen und die Einstellungen zeigen. */
		void cancelled();
	}

	/**
	 * Was der nächste Aufbau des Abschnitts zeigen soll.
	 *
	 * @param path  der eingegebene Pfad; {@code null}: der aus den Einstellungen
	 * @param error der Fehlertext unter dem Pfad; {@code null}: keiner
	 */
	public record Pending(String path, String error) {
	}

	private static SharedFolderConnection instance;

	private final SyncService sync;
	private final ExecutorService background;
	private final ConnectProgressView view;
	private final ConnectFlow flow;
	private final Runnable reloadAndShowMenu;
	private StartHandler start;
	/** Der Pfad, mit dem zuletzt über den Knopf verbunden wurde. */
	private String typedPath;
	private Pending pending;

	private SharedFolderConnection(SyncService sync, Runnable reloadAndShowMenu) {
		this.sync = sync;
		this.reloadAndShowMenu = reloadAndShowMenu;
		this.background = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "gemeinsamer-ordner");
			thread.setDaemon(true);
			return thread;
		});
		this.view = new ConnectProgressView(sync, this);
		this.flow = new ConnectFlow(sync, background, Platform::runLater, view, LeaveGuard::confirmLeave,
				dir -> SettingsStore.getInstance().saveOnly(settings -> settings.setSharedDir(dir)),
				AppPaths.appDataDir().toPath(), Reachability::isReachable);
		view.setFlow(flow);
	}

	/**
	 * Einmal beim Start, nachdem der Versionsspeicher erzeugt ist (FX-Thread).
	 *
	 * @param reloadAndShowMenu nach dem Verbinden über den Knopf: neu laden, Ladewarnungen zeigen, Hauptmenü
	 */
	public static SharedFolderConnection install(SyncService sync, Runnable reloadAndShowMenu) {
		instance = new SharedFolderConnection(sync, reloadAndShowMenu);
		return instance;
	}

	/** Die Verbindung der App; leer, solange der Start sie nicht eingerichtet hat. */
	public static Optional<SharedFolderConnection> instance() {
		return Optional.ofNullable(instance);
	}

	/** Der Versionsspeicher, mit dem verbunden wird. */
	public SyncService sync() {
		return sync;
	}

	/** Der Ablauf des Verbindens. */
	public ConnectFlow flow() {
		return flow;
	}

	/** Der Hintergrund-Thread für Aufrufe, die auf den Takt warten (etwa {@code renameMachine}, {@code disconnect}). */
	public Executor background() {
		return background;
	}

	/** Ob gerade die Ladeansicht des Verbindens zu sehen ist; solange öffnet sich der Konflikt-Dialog nicht von selbst. */
	public boolean connecting() {
		return view.isShown();
	}

	/**
	 * Der Knopf "Verbinden" mit dem eingegebenen Pfad. Solange die Prüfungen im Hintergrund laufen (bis zu 5 s), ist der
	 * Einstellungsbildschirm gesperrt: Wer inzwischen in eine andere Ansicht wechselte und dort etwas änderte, verlöre es
	 * sonst, wenn danach die Ladeansicht erscheint und der Bestand neu geladen wird. Die Sperre endet mit dem Bildschirm
	 * (Ladeansicht oder neu aufgebaute Einstellungen).
	 */
	public void connectByButton(String typed) {
		typedPath = typed;
		flow.connectByButton(typed);
		Scene scene = MainApp.primaryStage().getScene();
		if (flow.running() && scene != null) {
			scene.getRoot().setDisable(true);
		}
	}

	/**
	 * Verbinden beim Start ({@code startup} lief schon im Ladethread); danach geht es über {@code handler} weiter.
	 */
	public void connectAtStart(String dir, ConnectMode mode, StartHandler handler) {
		start = handler;
		flow.connectAtStart(dir, mode);
	}

	/**
	 * Zeigt die Einstellungen neu aufgebaut, mit dem eingegebenen Pfad und einem Fehlertext am Abschnitt.
	 *
	 * @param path  der eingegebene Pfad; {@code null}: der aus den Einstellungen
	 * @param error der Fehlertext; {@code null}: keiner
	 */
	public void reopenSettings(String path, String error) {
		pending = new Pending(path, error);
		MainApp.showSettingsView();
		// Kam der Abschnitt nicht zustande (Fehler beim Laden der Ansicht), gilt der Rest nicht für einen späteren Aufbau
		pending = null;
	}

	/** Was der Abschnitt beim Aufbau zeigen soll; einmalig, danach leer. */
	public Optional<Pending> takePending() {
		Optional<Pending> taken = Optional.ofNullable(pending);
		pending = null;
		return taken;
	}

	/** Die App wird geschlossen: Ergebnisse des Ablaufs zählen nicht mehr. */
	public void close() {
		flow.cancel();
		ConnectDecisionDialog.closeOpen();
		background.shutdown();
	}

	// --- ConnectProgressView.Host

	@Override
	public void show(Parent content) {
		MainApp.showLoadingView(content);
	}

	@Override
	public Window owner() {
		return MainApp.primaryStage();
	}

	@Override
	public void checkFailed(String text) {
		reopenSettings(typedPath, text);
	}

	@Override
	public void finished() {
		StartHandler handler = start;
		start = null;
		if (handler != null) {
			handler.finished();
		} else {
			reloadAndShowMenu.run();
		}
	}

	@Override
	public void cancelled() {
		StartHandler handler = start;
		start = null;
		if (handler != null) {
			handler.cancelled();
		} else {
			reopenSettings(typedPath, null);
		}
	}
}
