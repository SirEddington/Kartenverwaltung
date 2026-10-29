package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Predicate;

/**
 * Test-Hilfe: ein {@link SyncService} für den Einzelbetrieb, ohne Threads und ohne Takt. Oberfläche und Hintergrund
 * laufen auf dem Thread des Aufrufers ({@code Runnable::run}), der gemeinsame Ordner gilt immer als erreichbar. Der
 * Dienst ist nicht gestartet; wer den Start braucht, ruft {@link SyncService#startup(String)} selbst auf.
 */
public final class TestSyncServices {

	private TestSyncServices() {
	}

	/** Ein Dienst mit der Systemuhr (UTC) und der Kennung {@code test}, falls im Datenordner noch keine liegt. */
	public static SyncService singleMode(Path appData) {
		return singleMode(appData, Clock.systemUTC());
	}

	/** Wie {@link #singleMode(Path)}, mit einer Uhr nach Wahl. */
	public static SyncService singleMode(Path appData, Clock clock) {
		return singleMode(appData, new NioFileLayer(), clock);
	}

	/** Wie {@link #singleMode(Path)}, mit einer Dateischicht nach Wahl (etwa {@link FaultyFileLayer}). */
	public static SyncService singleMode(Path appData, FileLayer files) {
		return singleMode(appData, files, Clock.systemUTC());
	}

	/** Wie {@link #singleMode(Path)}, mit Dateischicht und Uhr nach Wahl. */
	public static SyncService singleMode(Path appData, FileLayer files, Clock clock) {
		try {
			MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> "test");
			return new SyncService(appData, identity, files, Runnable::run, Runnable::run, path -> true, clock);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Ein gestarteter Dienst im gemeinsamen Betrieb: Oberfläche und Hintergrund laufen auf dem Thread des Aufrufers, der
	 * gemeinsame Ordner gilt immer als erreichbar, der Rechner heißt {@code machineName}. Mehrere solche Dienste mit
	 * demselben {@code sharedDir} (und je eigenem {@code appData}) verhalten sich wie mehrere Rechner; der Takt wird mit
	 * {@link #tick(SyncService)} von Hand ausgelöst.
	 */
	public static SyncService sharedMode(Path appData, Path sharedDir, String machineName, Clock clock) {
		try {
			MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> machineName);
			SyncService service = new SyncService(appData, identity, new NioFileLayer(), Runnable::run, Runnable::run,
					path -> true, clock);
			service.startup(sharedDir.toString());
			return service;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Ein noch nicht gestarteter Dienst für den gemeinsamen Betrieb, mit Dateischicht und Erreichbarkeitsprüfung nach Wahl
	 * (etwa {@link FaultyFileLayer} und ein Schalter, der den gemeinsamen Ordner wegnimmt). Oberfläche und Hintergrund
	 * laufen auf dem Thread des Aufrufers, der Rechner heißt {@code machineName}. Wer Strategien braucht, setzt sie und
	 * ruft dann {@link SyncService#startup(String)} mit dem gemeinsamen Ordner auf.
	 */
	public static SyncService unstarted(Path appData, String machineName, FileLayer files, Predicate<Path> reachable,
			Clock clock) {
		try {
			MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> machineName);
			return new SyncService(appData, identity, files, Runnable::run, Runnable::run, reachable, clock);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Löst einen Takt des Dienstes aus: lädt hoch, holt Fremdes ab und meldet die Änderungen. */
	public static void tick(SyncService service) {
		service.tick();
	}
}
