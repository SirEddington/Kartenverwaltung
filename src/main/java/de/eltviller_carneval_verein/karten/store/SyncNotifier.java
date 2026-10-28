package de.eltviller_carneval_verein.karten.store;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

/**
 * Zustand und Rückmeldungen des {@link SyncService}: hält den Zustand, entscheidet, welche Meldung wirklich hinaus
 * geht, und reicht alles über den {@code ui}-Executor weiter (Muster {@code MirrorService}).
 * <ul>
 * <li>Intern gilt immer der sofort gesetzte Zustand; die Eigenschaften folgen über den {@code ui}-Executor in der
 * Reihenfolge der Änderungen.</li>
 * <li>{@code unreachable} kommt einmal je Ausfall, {@code reconnected} beim ersten Erfolg danach.</li>
 * <li>{@code error} kommt bei einer Störung nur, wenn sich der Text gegenüber der zuletzt gemeldeten Störung ändert;
 * Meldungen zu einer Sache (Einheit, Datei) kommen einmal je Programmlauf.</li>
 * </ul>
 * Ein Fehler im Rückruf oder ein nicht laufender UI-Thread beendet nie den Aufrufer. Threadsicher.
 */
final class SyncNotifier {

	private static final Logger LOGGER = Logger.getLogger(SyncService.class.getName());

	private final Executor ui;
	private final ReadOnlyObjectWrapper<SyncState> stateProperty = new ReadOnlyObjectWrapper<>(SyncState.AUS);
	private final ReadOnlyObjectWrapper<Instant> lastSyncProperty = new ReadOnlyObjectWrapper<>();
	private volatile SyncListener listener = new SyncListener() {
	};

	private SyncState current = SyncState.AUS;
	/** Ob für den laufenden Ausfall schon gemeldet wurde; zurückgesetzt beim nächsten Erfolg. */
	private boolean unreachableNotified;
	/** Ob der Ordner ausgefallen war und die Rückkehr noch nicht gemeldet ist. */
	private boolean wasDown;
	/** Die zuletzt gemeldete Störung; zurückgesetzt beim nächsten Erfolg. */
	private String lastErrorMessage;
	/** Schlüssel der Meldungen, die in diesem Programmlauf schon einmal kamen. */
	private final Set<String> reportedOnce = new HashSet<>();

	SyncNotifier(Executor ui) {
		this.ui = ui;
	}

	ReadOnlyObjectProperty<SyncState> stateProperty() {
		return stateProperty.getReadOnlyProperty();
	}

	ReadOnlyObjectProperty<Instant> lastSyncProperty() {
		return lastSyncProperty.getReadOnlyProperty();
	}

	void setListener(SyncListener listener) {
		this.listener = listener != null ? listener : new SyncListener() {
		};
	}

	synchronized SyncState state() {
		return current;
	}

	/** Setzt den Zustand; die Eigenschaft folgt über den {@code ui}-Executor. */
	synchronized void setState(SyncState state) {
		if (current == state) {
			return;
		}
		current = state;
		notifyUi(() -> stateProperty.set(state));
	}

	/** Neuer Ordner oder Einzelbetrieb: vergisst Ausfall und Störung des vorigen Ordners; der Zustand bleibt. */
	synchronized void reset() {
		unreachableNotified = false;
		wasDown = false;
		lastErrorMessage = null;
	}

	/**
	 * Der Ordner ist nicht erreichbar: Zustand {@link SyncState#OFFLINE}, SYS-005 ins Log einmal je Ausfall.
	 *
	 * @param callback ob dabei auch {@link SyncListener#unreachable} kommt (nicht beim Start: das Popup zeigt der
	 *                 Aufrufer, der Ausfall gilt trotzdem als gemeldet)
	 */
	synchronized void unreachable(String path, boolean callback) {
		wasDown = true;
		setState(SyncState.OFFLINE);
		if (unreachableNotified) {
			return;
		}
		unreachableNotified = true;
		LOGGER.warning(StoreMessages.unreachable(path));
		if (callback) {
			deliver(l -> l.unreachable(path));
		}
	}

	/** Eine Störung bei erreichbarem Ordner: Zustand {@link SyncState#FEHLER}, gemeldet nur bei geändertem Text. */
	synchronized void failed(String message, Throwable cause) {
		setState(SyncState.FEHLER);
		if (message.equals(lastErrorMessage)) {
			LOGGER.log(Level.FINE, "Andauernder Fehler des Abgleichs: {0}", message);
			return;
		}
		lastErrorMessage = message;
		LOGGER.log(Level.WARNING, message, cause);
		deliver(l -> l.error(message));
	}

	/**
	 * Ein gelungener Takt: Zeitpunkt fortschreiben, Zustand setzen, Störung vergessen und eine Rückkehr nach einem
	 * Ausfall melden.
	 */
	synchronized void succeeded(Instant at, boolean pending) {
		lastErrorMessage = null;
		notifyUi(() -> lastSyncProperty.set(at));
		setState(pending ? SyncState.AUSSTEHEND : SyncState.AKTUELL);
		if (wasDown) {
			wasDown = false;
			unreachableNotified = false;
			deliver(SyncListener::reconnected);
		}
	}

	/** Meldet eine Störung zu einer Sache (Einheit, Datei) einmal je Programmlauf, ohne den Zustand zu ändern. */
	synchronized void errorOnce(String key, String message) {
		if (!reportedOnce.add(key)) {
			return;
		}
		LOGGER.warning(message);
		deliver(l -> l.error(message));
	}

	/** Meldet einen Fehler für die Fußzeile ohne Log und ohne Zustand; ob er einmal oder öfter kommt, regelt der Aufrufer. */
	void error(String message) {
		deliver(l -> l.error(message));
	}

	void conflictsChanged() {
		deliver(SyncListener::conflictsChanged);
	}

	void unitsChanged(Set<UnitKey> units) {
		if (!units.isEmpty()) {
			Set<UnitKey> copy = Set.copyOf(units);
			deliver(l -> l.unitsChanged(copy));
		}
	}

	void pending(PendingSighting sighting) {
		deliver(l -> l.pending(sighting.unit(), sighting.version(), sighting.machineName()));
	}

	void lost(LostVersion loss) {
		deliver(l -> l.lost(loss.unit(), List.copyOf(loss.mine()), loss.winner()));
	}

	void duplicateMachine(String message) {
		LOGGER.warning(message);
		deliver(l -> l.duplicateMachine(message));
	}

	private void deliver(Consumer<SyncListener> call) {
		notifyUi(() -> call.accept(listener));
	}

	private void notifyUi(Runnable action) {
		try {
			ui.execute(() -> {
				try {
					action.run();
				} catch (RuntimeException e) {
					LOGGER.log(Level.WARNING, "Rückruf des Versionsspeichers ist fehlgeschlagen", e);
				}
			});
		} catch (RuntimeException e) {
			LOGGER.log(Level.WARNING, "Die Oberfläche ist für den Versionsspeicher nicht erreichbar", e);
		}
	}
}
