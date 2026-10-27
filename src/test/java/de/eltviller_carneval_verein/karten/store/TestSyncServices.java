package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;

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

	private static SyncService singleMode(Path appData, FileLayer files, Clock clock) {
		try {
			MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> "test");
			return new SyncService(appData, identity, files, Runnable::run, Runnable::run, path -> true, clock);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
