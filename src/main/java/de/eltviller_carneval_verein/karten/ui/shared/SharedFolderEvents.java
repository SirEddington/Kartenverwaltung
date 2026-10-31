package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.store.ConnectionControl;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncListener;
import de.eltviller_carneval_verein.karten.store.SyncState;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.validation.Severity;

/**
 * Verteilt die Rückmeldungen des Versionsspeichers in der Oberfläche (Spec "Popups und Fußzeilentexte"), ohne JavaFX. Was
 * {@code LiveUpdates} schon behandelt ({@code unitsChanged}, {@code conflictsChanged}, {@code error}, {@code lost},
 * {@code pending}), geht unverändert an dessen Listener; der Rest geht an {@link Ui}:
 * <ul>
 * <li>{@code unreachable}: Popup, solange kein solches offen ist;
 * <li>{@code reconnected}: Fußzeile;
 * <li>{@code duplicateMachine}: Popup;
 * <li>{@code connectDecisionNeeded}: Läuft ein Ablauf des Verbindens, stellt dessen Dialog die Frage, sonst öffnet {@link Ui}
 * sie (schließbar), und die Antwort beginnt einen neuen Ablauf mit Ladeansicht;
 * <li>{@code skipped} und {@code locked}: Fußzeile mit der lesbaren Bezeichnung statt des Pfades.
 * </ul>
 * Dazu merkt sie sich die letzte Störung des Abgleichs (SYS-006) als Ursache für Statuszeile und Info-Fenster und meldet
 * über {@code onSyncChanged}, wenn sich ändern kann, was das Info-Fenster zeigt: bei {@code skipped}, {@code reconnected},
 * und, wenn man sie mit {@link #watch} an die Verbindung hängt, bei jeder Änderung von Zustand, Vollständigkeit der
 * Verbindung und letztem Abgleich (der Versionsspeicher meldet {@code skipped} nur einmal je Eintrag und Programmlauf, auch
 * über ein Trennen und Verbinden hinweg).
 * <p>
 * Alle Rückrufe kommen auf dem FX-Thread an (der {@code ui}-Executor des Versionsspeichers).
 */
public final class SharedFolderEvents implements SyncListener {

	private static final Logger LOG = Logger.getLogger(SharedFolderEvents.class.getName());

	private static SharedFolderEvents instance;

	/** Die Oberfläche, soweit sie nicht schon der Ablauf des Verbindens zeigt. */
	public interface Ui {

		/** Ob das Popup "nicht erreichbar" gerade offen ist. */
		boolean unreachableOpen();

		/** Öffnet das Popup "Gemeinsamer Ordner nicht erreichbar" (nicht blockierend). */
		void showUnreachable(String text);

		/** Öffnet das Popup "Neue Rechner-Kennung" (nicht blockierend). */
		void showDuplicateMachine(String text);

		/**
		 * Öffnet die Frage "Mitnehmen" / "Beiseitelegen" / "Abbrechen"; man kann sie schließen, ohne zu antworten.
		 *
		 * @param decide die Antwort "Mitnehmen" bzw. "Beiseitelegen"
		 * @param cancel "Abbrechen"
		 */
		void askDecision(String dir, boolean setAsideAllowed, Consumer<ConnectMode> decide, Runnable cancel);

		/** Zeigt einen Text in der Fußzeile. */
		void footer(Severity severity, String text);
	}

	private final SyncListener live;
	private final Function<UnitKey, String> labels;
	private final Ui ui;
	private final ConnectFlow flow;
	private final Runnable onSyncChanged;
	private final Runnable onLockedChanged;
	private String lastFailure;

	/**
	 * @param live            der Listener von {@code LiveUpdates}
	 * @param labels          die lesbare Bezeichnung einer Einheit ({@code ConflictLabels}); darf {@code null} liefern
	 * @param flow            der Ablauf des Verbindens
	 * @param onSyncChanged   was geschieht, wenn sich Zustand, Vollständigkeit, letzter Abgleich oder die Liste der
	 *                        übersprungenen Einträge geändert haben können (Hinweis in der Fußzeile, Info-Fenster)
	 * @param onLockedChanged was geschieht, wenn eine Einheit gesperrt gemeldet wurde (Link "gesperrte Einträge")
	 */
	public SharedFolderEvents(SyncListener live, Function<UnitKey, String> labels, Ui ui, ConnectFlow flow,
			Runnable onSyncChanged, Runnable onLockedChanged) {
		this.live = live;
		this.labels = labels;
		this.ui = ui;
		this.flow = flow;
		this.onSyncChanged = onSyncChanged;
		this.onLockedChanged = onLockedChanged;
	}

	/** Legt die Instanz der App an (FX-Thread); {@link #instance()} liefert sie. */
	public static SharedFolderEvents install(SharedFolderEvents events) {
		instance = events;
		return events;
	}

	/** Die Instanz der App; leer, solange der Start sie nicht angelegt hat. */
	public static Optional<SharedFolderEvents> instance() {
		return Optional.ofNullable(instance);
	}

	/**
	 * Die letzte Störung des Abgleichs (Text von SYS-006), als Ursache in der Statuszeile ("Abgleich gestört: …") und im
	 * Info-Fenster; {@code null}, wenn keine bekannt ist oder der Abgleich wieder gelang.
	 */
	public String lastFailure() {
		return lastFailure;
	}

	/**
	 * Meldet {@code onSyncChanged}, wenn sich Zustand, Vollständigkeit der Verbindung oder letzter Abgleich ändern: Ein Lauf
	 * endet, die Verbindung wechselt. Ein Zustand ohne Störung vergisst auch die gemerkte Ursache.
	 */
	public void watch(ConnectionControl sync) {
		sync.syncState().addListener((observable, oldState, newState) -> {
			if (newState != SyncState.FEHLER) {
				lastFailure = null;
			}
			syncChanged();
		});
		sync.connectionComplete().addListener((observable, oldValue, newValue) -> syncChanged());
		sync.lastSync().addListener((observable, oldValue, newValue) -> syncChanged());
	}

	// --- Was LiveUpdates behandelt

	@Override
	public void unitsChanged(Set<UnitKey> units) {
		live.unitsChanged(units);
	}

	@Override
	public void conflictsChanged() {
		live.conflictsChanged();
	}

	@Override
	public void pending(UnitKey unit, VersionId version, String machineName) {
		live.pending(unit, version, machineName);
	}

	@Override
	public void lost(UnitKey unit, List<VersionId> mine, VersionId winner) {
		live.lost(unit, mine, winner);
	}

	@Override
	public void error(String message) {
		if (message != null && message.startsWith(StoreMessages.SYS_SYNC_FAILED)) {
			lastFailure = message;
		}
		live.error(message);
	}

	// --- Was hier behandelt wird

	@Override
	public void unreachable(String path) {
		if (!ui.unreachableOpen()) {
			ui.showUnreachable(StoreMessages.unreachable(path));
		}
	}

	@Override
	public void reconnected() {
		lastFailure = null;
		ui.footer(Severity.INFO, SharedFolderTexts.RECONNECTED);
		syncChanged();
	}

	@Override
	public void duplicateMachine(String message) {
		ui.showDuplicateMachine(message);
	}

	@Override
	public void connectDecisionNeeded(String dir, boolean setAsideAllowed) {
		if (flow.running()) {
			// Der Ablauf des Knopfes (oder des Starts) fragt selbst; ein zweiter Dialog neben seinem wäre doppelt
			flow.decisionNeeded(setAsideAllowed);
			return;
		}
		ui.askDecision(dir, setAsideAllowed, mode -> answer(dir, setAsideAllowed, mode), flow::cancelDecision);
	}

	/**
	 * Die Antwort der Frage im Betrieb: Der Ablauf beginnt mit der Ladeansicht. Hat {@code LeaveGuard} verweigert, ist die
	 * Frage zu, aber unbeantwortet; sie kommt wieder, damit die Entscheidung nicht verloren geht.
	 */
	private void answer(String dir, boolean setAsideAllowed, ConnectMode mode) {
		if (!flow.decide(mode) && !flow.running()) {
			ui.askDecision(dir, setAsideAllowed, next -> answer(dir, setAsideAllowed, next), flow::cancelDecision);
		}
	}

	@Override
	public void skipped(UnitKey unit, boolean subtree, String cause) {
		ui.footer(Severity.WARNING, SharedFolderTexts.skippedFooter(label(unit), subtree));
		syncChanged();
	}

	@Override
	public void locked(UnitKey unit, UnitState.Status status) {
		String label = label(unit);
		String text = switch (status) {
		case LOCKED_DAMAGED -> StoreMessages.damaged(label);
		case LOCKED_FORMAT -> StoreMessages.newerFormat(label);
		case WAITING, OK -> null;
		};
		if (text == null) {
			return;
		}
		ui.footer(Severity.ERROR, text);
		try {
			onLockedChanged.run();
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Die gesperrten Einträge ließen sich nicht anzeigen", e);
		}
	}

	/** Die Bezeichnung der Einheit; ihr Pfad, wenn sie sich nicht benennen lässt. */
	private String label(UnitKey unit) {
		try {
			String label = labels.apply(unit);
			if (label != null) {
				return label;
			}
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Keine Bezeichnung für " + unit.relativePath(), e);
		}
		return unit.relativePath();
	}

	private void syncChanged() {
		try {
			onSyncChanged.run();
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Die Anzeige des Abgleichs ließ sich nicht auffrischen", e);
		}
	}
}
