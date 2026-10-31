package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.Optional;
import java.util.function.DoubleConsumer;

import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.store.ConnectionControl;

/**
 * Der Start mit dem gemeinsamen Ordner nach der Starttabelle (KARV-103, Abschnitt E "Marke 'verbunden' und Start"): Die
 * Einstellung {@code sharedDir} wird mit der Marke des Versionsspeichers ({@code connectedDir()},
 * {@code connectionComplete()}) verglichen.
 * <p>
 * <b>Vertrag:</b> {@code startup} läuft immer vor {@code connect}, sonst greift die Schutzregel des Beiseitelegens nie.
 * Deshalb trennt {@link #run} das Starten vom Verbinden: {@code startup} (und gegebenenfalls {@code disconnect}) laufen im
 * Ladethread, das Verbinden startet die Oberfläche danach mit {@link ConnectFlow#connectAtStart}, wenn
 * {@link Plan#connectDir()} gesetzt ist. Die Repositories werden dann erst geladen, wenn der Ablauf zu Ende ist
 * ({@link ConnectFlow.Screen#finished()}): Niemand arbeitet auf einem halb abgeglichenen Bestand.
 */
public final class SharedFolderStart {

	/**
	 * Was beim Start zu tun ist.
	 *
	 * @param startupDir  der Ordner für {@code startup}; {@code null} startet lokal
	 * @param connectDir  der Ordner, mit dem danach verbunden wird; {@code null}, wenn nicht verbunden wird
	 * @param mode        der Modus des Verbindens ({@code null}, wenn {@code connectDir} {@code null} ist)
	 * @param clearMarker ob die Marke "verbunden" zu löschen ist (Einstellung leer, Marke gesetzt): der Aufrufer ruft nach
	 *                    {@code startup} {@code disconnect()}
	 */
	public record Plan(String startupDir, String connectDir, ConnectMode mode, boolean clearMarker) {
	}

	/**
	 * Das Ergebnis von {@link #run}.
	 *
	 * @param plan      was beim Start zu tun war (und was die Oberfläche danach noch zu tun hat)
	 * @param reachable Rückgabe von {@code startup}; {@code false}, wenn der Ordner nicht erreichbar ist (Popup SYS-005)
	 */
	public record StartResult(Plan plan, boolean reachable) {
	}

	private SharedFolderStart() {
	}

	/**
	 * Die Starttabelle. Pfade werden verglichen, wie der Versionsspeicher sie versteht: Leerraum am Rand zählt nicht, leer
	 * heißt "kein Ordner".
	 * <ul>
	 * <li>Einstellung leer, Marke leer: {@code startup(null)}.
	 * <li>Einstellung leer, Marke X: {@code startup(null)}, Marke löschen.
	 * <li>X, Marke X vollständig: {@code startup(X)}.
	 * <li>X, Marke X unvollständig: {@code startup(null)}, dann {@code connect(X, MERGE)}.
	 * <li>X, Marke leer oder anderer Ordner: {@code startup(null)}, dann {@code connect(X, ASK)}.
	 * </ul>
	 *
	 * @param settingsDir    {@code sharedDir} aus den Einstellungen
	 * @param markerDir      der Ordner der Marke "verbunden"
	 * @param markerComplete ob der erste Lauf zu Ende kam ({@code connectionComplete()})
	 */
	public static Plan plan(String settingsDir, Optional<String> markerDir, boolean markerComplete) {
		String settings = clean(settingsDir);
		String marker = markerDir == null ? null : clean(markerDir.orElse(null));
		if (settings == null) {
			return new Plan(null, null, null, marker != null);
		}
		if (!settings.equals(marker)) {
			return new Plan(null, settings, ConnectMode.ASK, false);
		}
		if (markerComplete) {
			return new Plan(settings, null, null, false);
		}
		return new Plan(null, settings, ConnectMode.MERGE, false);
	}

	/**
	 * Im Ladethread (nicht auf dem FX-Thread): {@code startup} immer zuerst, dann gegebenenfalls {@code disconnect()}. Das
	 * Verbinden selbst bleibt der Oberfläche.
	 *
	 * @param settingsDir {@code sharedDir} aus den Einstellungen
	 * @param progress    Fortschritt des Starts
	 */
	public static StartResult run(ConnectionControl sync, String settingsDir, DoubleConsumer progress) {
		Plan plan = plan(settingsDir, sync.connectedDir(), sync.connectionComplete().get());
		boolean reachable = sync.startup(plan.startupDir(), progress);
		if (plan.clearMarker()) {
			sync.disconnect();
		}
		return new StartResult(plan, reachable);
	}

	// Leer und null heißen "kein Ordner", wie in SyncService
	private static String clean(String dir) {
		if (dir == null || dir.isBlank()) {
			return null;
		}
		return dir.trim();
	}
}
