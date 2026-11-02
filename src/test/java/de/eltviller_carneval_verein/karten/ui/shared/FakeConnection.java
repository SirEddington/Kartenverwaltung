package de.eltviller_carneval_verein.karten.ui.shared;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.store.ConnectOutcome;
import de.eltviller_carneval_verein.karten.store.ConnectionControl;
import de.eltviller_carneval_verein.karten.store.SkippedUnit;
import de.eltviller_carneval_verein.karten.store.SyncState;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Test-Doppel für {@link ConnectionControl}: Die Eigenschaften setzt der Test direkt (sie sind echte JavaFX-Eigenschaften
 * und brauchen keinen laufenden FX-Thread), die Aufrufe landen in {@link #calls()}. {@link #connect} und
 * {@link #decideConnect} liefern Futures, die der Test mit {@link #outcome} abschließt.
 */
public final class FakeConnection implements ConnectionControl {

	private final SimpleObjectProperty<SyncState> state = new SimpleObjectProperty<>(SyncState.AUS);
	private final SimpleObjectProperty<Double> progress = new SimpleObjectProperty<>();
	private final SimpleBooleanProperty complete = new SimpleBooleanProperty(true);
	private final SimpleBooleanProperty catchingUp = new SimpleBooleanProperty();
	private final SimpleObjectProperty<Instant> lastSync = new SimpleObjectProperty<>();
	private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
	private final List<CompletableFuture<ConnectOutcome>> pending = new ArrayList<>();

	private String markerDir;
	private String sharedDir;
	private boolean startupResult = true;
	private List<SkippedUnit> skipped = List.of();

	// --- Zustand setzen

	public FakeConnection state(SyncState value) {
		state.set(value);
		return this;
	}

	public FakeConnection progress(Double value) {
		progress.set(value);
		return this;
	}

	public FakeConnection complete(boolean value) {
		complete.set(value);
		return this;
	}

	public FakeConnection lastSync(Instant value) {
		lastSync.set(value);
		return this;
	}

	public FakeConnection catchingUp(boolean value) {
		catchingUp.set(value);
		return this;
	}

	/** Die Marke "verbunden": der Ordner (oder {@code null} ohne Marke) und ob der erste Lauf zu Ende kam. */
	public FakeConnection marker(String dir, boolean markerComplete) {
		markerDir = dir;
		complete.set(markerComplete);
		return this;
	}

	/** Der eingestellte gemeinsame Ordner, den {@link #sharedDir()} liefert. */
	public FakeConnection sharedDir(String dir) {
		sharedDir = dir;
		return this;
	}

	/** Was {@link #startup} liefert; Standard {@code true}. */
	public FakeConnection startupResult(boolean value) {
		startupResult = value;
		return this;
	}

	public FakeConnection skipped(List<SkippedUnit> units) {
		skipped = List.copyOf(units);
		return this;
	}

	/** Schließt alle offenen Futures von {@link #connect} und {@link #decideConnect} mit diesem Ergebnis ab. */
	public void outcome(ConnectOutcome value) {
		List<CompletableFuture<ConnectOutcome>> open;
		synchronized (pending) {
			open = new ArrayList<>(pending);
			pending.clear();
		}
		open.forEach(future -> future.complete(value));
	}

	/** Schließt alle offenen Futures von {@link #connect} und {@link #decideConnect} mit diesem Fehler ab. */
	public void fail(Throwable error) {
		List<CompletableFuture<ConnectOutcome>> open;
		synchronized (pending) {
			open = new ArrayList<>(pending);
			pending.clear();
		}
		open.forEach(future -> future.completeExceptionally(error));
	}

	/** Die Aufrufe in der Reihenfolge: {@code "startup:<Ordner>"}, {@code "connect:<Ordner>:<Modus>"}, {@code "decide:<Modus>"}, {@code "disconnect"}, {@code "syncNow"}. */
	public List<String> calls() {
		synchronized (calls) {
			return new ArrayList<>(calls);
		}
	}

	// --- ConnectionControl

	@Override
	public CompletableFuture<ConnectOutcome> connect(String dir, ConnectMode mode) {
		calls.add("connect:" + dir + ":" + mode);
		return openFuture();
	}

	@Override
	public CompletableFuture<ConnectOutcome> decideConnect(ConnectMode mode) {
		calls.add("decide:" + mode);
		return openFuture();
	}

	@Override
	public void disconnect() {
		calls.add("disconnect");
	}

	@Override
	public boolean startup(String dir, DoubleConsumer progressConsumer) {
		calls.add("startup:" + dir);
		return startupResult;
	}

	@Override
	public ReadOnlyObjectProperty<SyncState> syncState() {
		return state;
	}

	@Override
	public ReadOnlyObjectProperty<Double> fullSyncProgress() {
		return progress;
	}

	@Override
	public ReadOnlyBooleanProperty connectionComplete() {
		return complete;
	}

	@Override
	public ReadOnlyBooleanProperty catchingUp() {
		return catchingUp;
	}

	@Override
	public Optional<String> connectedDir() {
		return Optional.ofNullable(markerDir);
	}

	@Override
	public String sharedDir() {
		return sharedDir;
	}

	@Override
	public ReadOnlyObjectProperty<Instant> lastSync() {
		return lastSync;
	}

	@Override
	public void syncNow() {
		calls.add("syncNow");
	}

	@Override
	public List<SkippedUnit> skippedUnits() {
		return skipped;
	}

	private CompletableFuture<ConnectOutcome> openFuture() {
		CompletableFuture<ConnectOutcome> future = new CompletableFuture<>();
		synchronized (pending) {
			pending.add(future);
		}
		return future;
	}
}
