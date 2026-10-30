package de.eltviller_carneval_verein.karten.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;

/**
 * Was die Oberfläche vom Versionsspeicher zum Verbinden mit dem gemeinsamen Ordner braucht: Steuerung und Zustand der
 * Verbindung. {@link SyncService} implementiert sie; Tests der Oberfläche setzen ein Test-Doppel ein. Die Verträge stehen
 * an den Methoden des {@link SyncService}.
 * <p>
 * {@link #startup} läuft immer vor {@link #connect}, sonst greift die Schutzregel des Beiseitelegens nie.
 * {@link #connect}, {@link #decideConnect}, {@link #disconnect} und {@link #startup} warten auf den laufenden Takt und
 * laufen deshalb nie auf dem FX-Thread.
 */
public interface ConnectionControl {

	/** Verbindet mit dem gemeinsamen Ordner, im Hintergrund; leer oder {@code null} trennt. Nicht auf dem FX-Thread. */
	CompletableFuture<ConnectOutcome> connect(String sharedDir, ConnectMode mode);

	/** Beantwortet die Frage beim Verbinden ({@link ConnectMode#MERGE} oder {@link ConnectMode#SET_ASIDE}). Nicht auf dem FX-Thread. */
	CompletableFuture<ConnectOutcome> decideConnect(ConnectMode mode);

	/** Beendet den gemeinsamen Betrieb; der lokale Bestand bleibt. Nicht auf dem FX-Thread. */
	void disconnect();

	/** Beim Start der Anwendung, vor {@link #connect}; {@code false}, wenn der Ordner nicht erreichbar ist. Nicht auf dem FX-Thread. */
	boolean startup(String sharedDir, DoubleConsumer progress);

	/** Der Zustand des Austauschs. */
	ReadOnlyObjectProperty<SyncState> syncState();

	/** Der Fortschritt des laufenden Vollabgleichs von 0.0 bis 1.0, {@code null} ohne laufenden Lauf. */
	ReadOnlyObjectProperty<Double> fullSyncProgress();

	/** Ob der erste Vollabgleich der Verbindung zu Ende kam. */
	ReadOnlyBooleanProperty connectionComplete();

	/** Der gemeinsame Ordner aus der Marke "verbunden"; leer ohne (lesbare) Marke. */
	Optional<String> connectedDir();

	/** Der gemeinsame Ordner, wie er eingestellt wurde; {@code null} im Einzelbetrieb. */
	String sharedDir();

	/** Zeitpunkt des letzten erfolgreichen Takts, {@code null} solange keiner gelang. */
	ReadOnlyObjectProperty<Instant> lastSync();

	/** Fordert sofort einen Vollabgleich an; ohne Wirkung im Einzelbetrieb. */
	void syncNow();

	/** Die Einheiten, die der Vollabgleich übersprungen hat. */
	List<SkippedUnit> skippedUnits();
}
