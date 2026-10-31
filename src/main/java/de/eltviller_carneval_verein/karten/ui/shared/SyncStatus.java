package de.eltviller_carneval_verein.karten.ui.shared;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.store.ConnectionControl;
import de.eltviller_carneval_verein.karten.store.SyncState;
import javafx.beans.value.ChangeListener;

/**
 * Das, was die Fußzeilen-Hinweise ({@link SyncIndicator}) über den Abgleich wissen müssen und nicht je Fensterinhalt neu
 * lernen dürfen: seit wann der Zustand {@code AUSSTEHEND} dauert und ob seit dem Verbinden ein Lauf zu Ende gegangen ist
 * ({@link FirstRunWatch}, neu angelegt bei jedem Wechsel von {@code connectionComplete()} auf {@code false}). Jede Ansicht
 * bekommt beim Wechsel eine eigene Fußzeile; ein Hinweis, der erst dann zu zählen begänne, zeigte nach dem Wechsel "Rang 4"
 * statt "Rang 5" und zählte die 30 s von vorn. Es gibt eine Instanz je App.
 * <p>
 * Die übersprungenen Einträge liest {@link #hint()} bei jedem Aufruf aus {@code skippedUnits()} (der Rückruf
 * {@code skipped} kommt nur einmal je Eintrag und Programmlauf). Nicht threadsicher: alle Aufrufe auf dem FX-Thread.
 */
public final class SyncStatus {

	private static final Logger LOG = Logger.getLogger(SyncStatus.class.getName());

	private final ConnectionControl sync;
	private final Clock clock;
	private final Runnable onChange;
	private final ChangeListener<SyncState> stateListener = (observable, oldState, newState) -> noteState(newState);
	private final ChangeListener<Boolean> completeListener = (observable, oldValue, newValue) -> noteComplete(newValue);

	private SyncState seenState;
	private Instant pendingSince;
	private FirstRunWatch watch;
	private Runnable tracked;

	/**
	 * @param sync     die Verbindung
	 * @param clock    die Uhr für die 30 s von {@code AUSSTEHEND}
	 * @param onChange was geschieht, wenn ein Lauf zu Ende geht, ohne dass sich sonst etwas meldet (die Anzeige wechselt von
	 *                 Vorrang 4 auf 5)
	 */
	public SyncStatus(ConnectionControl sync, Clock clock, Runnable onChange) {
		this.sync = sync;
		this.clock = clock;
		this.onChange = onChange;
		noteState(sync.syncState().get());
		if (!sync.connectionComplete().get()) {
			startWatch();
		}
		sync.syncState().addListener(stateListener);
		sync.connectionComplete().addListener(completeListener);
	}

	/** Der Hinweis für die Fußzeile nach dem Stand von jetzt; leer, wenn sie nichts zeigt. */
	public Optional<SyncHint> hint() {
		SyncState state = sync.syncState().get();
		// ein Wechsel, den der Listener nicht sah (etwa vor dem Anlegen), zählt ab jetzt
		noteState(state);
		Duration pendingFor = state == SyncState.AUSSTEHEND && pendingSince != null
				? Duration.between(pendingSince, clock.instant()) : Duration.ZERO;
		return SyncHint.of(state, pendingFor, sync.connectionComplete().get(), sync.fullSyncProgress().get(),
				watch != null && watch.ended(), sync.skippedUnits().size());
	}

	/**
	 * Merkt, was der 1-s-Takt der App auffrischen soll: den Hinweis der aktuellen Ansicht. Ein neuer Aufruf ersetzt den
	 * vorigen; eine ersetzte Ansicht wird nicht mehr aufgefrischt und von hier nicht mehr gehalten.
	 *
	 * @param refresh frischt den Hinweis der Ansicht auf; {@code null} vergisst ihn
	 */
	public void track(Runnable refresh) {
		tracked = refresh;
	}

	/** Frischt die gemerkte Ansicht auf ({@link #track}); eine Ausnahme dort stoppt den Takt nicht. */
	public void refreshTracked() {
		Runnable refresh = tracked;
		if (refresh == null) {
			return;
		}
		try {
			refresh.run();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Der Hinweis zum Abgleich ließ sich nicht auffrischen", e);
		}
	}

	/** Entfernt die Listener und vergisst die gemerkte Ansicht; danach meldet nichts mehr. */
	public void dispose() {
		sync.syncState().removeListener(stateListener);
		sync.connectionComplete().removeListener(completeListener);
		stopWatch();
		tracked = null;
	}

	private void noteState(SyncState state) {
		if (state == seenState) {
			return;
		}
		seenState = state;
		pendingSince = state == SyncState.AUSSTEHEND ? clock.instant() : null;
	}

	private void noteComplete(boolean complete) {
		stopWatch();
		if (!complete) {
			startWatch();
		}
	}

	private void startWatch() {
		watch = new FirstRunWatch(sync.fullSyncProgress());
		watch.onEnded(onChange);
	}

	private void stopWatch() {
		if (watch != null) {
			watch.dispose();
			watch = null;
		}
	}
}
