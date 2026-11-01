package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
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
 * <li>{@code unreachable}: Popup, solange kein solches offen ist und kein Ablauf des Verbindens läuft (dessen Ladeansicht
 * zeigt den Hinweis);
 * <li>{@code error} mit SYS-006 (Abgleich gescheitert): nur Fußzeile, nie ein Dialog;
 * <li>{@code reconnected}: Fußzeile;
 * <li>{@code duplicateMachine}: Popup;
 * <li>{@code connectDecisionNeeded}: Läuft ein Ablauf des Verbindens, stellt dessen Dialog die Frage, sonst öffnet {@link Ui}
 * sie (schließbar), und die Antwort beginnt einen neuen Ablauf mit Ladeansicht;
 * <li>{@code skipped} und {@code locked}: Fußzeile mit der lesbaren Bezeichnung statt des Pfades; bei {@code locked} dazu
 * der Link "gesperrte Einträge" (kein Dialog, auch in Ansichten ohne Fußzeile nicht: Dort zeigt ihn die Leiste unter dem
 * Inhalt).
 * </ul>
 * Dazu merkt sie sich die letzte Störung des Abgleichs (SYS-006) als Ursache für Statuszeile und Info-Fenster und meldet
 * über {@code onSyncChanged}, wenn sich ändern kann, was das Info-Fenster zeigt: bei {@code skipped}, {@code reconnected},
 * und, wenn man sie mit {@link #watch} an die Verbindung hängt, bei jeder Änderung von Zustand, Vollständigkeit der
 * Verbindung und letztem Abgleich (der Versionsspeicher meldet {@code skipped} nur einmal je Eintrag und Programmlauf, auch
 * über ein Trennen und Verbinden hinweg). Verlässt der Zustand {@code ENTSCHEIDUNG}, schließt sie die offene Frage im
 * Betrieb.
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

		/** Schließt die offene Frage ohne Antwort, weil sie sich erledigt hat; ohne offene Frage nichts. */
		void closeDecision();

		/**
		 * Zeigt einen Hinweis nur in der Fußzeile; ist gerade keine zu sehen (Hauptmenü, Ladeansichten), nichts. Für Texte,
		 * die sich häufen können und keinen Dialog wert sind.
		 */
		void footer(Severity severity, String text);

		/** Nimmt den Hinweis {@code text} aus der Fußzeile, weil er nicht mehr gilt; steht dort ein anderer, bleibt der. */
		void withdrawFooter(String text);
	}

	/** Die Frage beim Verbinden, die der Versionsspeicher gestellt hat und die noch offen ist. */
	private record PendingDecision(String dir, boolean setAsideAllowed) {
	}

	private final SyncListener live;
	private final Function<UnitKey, String> labels;
	private final Ui ui;
	private final ConnectFlow flow;
	private final Runnable onSyncChanged;
	private final BiConsumer<UnitKey, UnitState.Status> onLocked;
	private String lastFailure;
	private PendingDecision pendingDecision;

	/**
	 * @param live            der Listener von {@code LiveUpdates}
	 * @param labels          die lesbare Bezeichnung einer Einheit ({@code ConflictLabels}); darf {@code null} liefern
	 * @param flow            der Ablauf des Verbindens
	 * @param onSyncChanged   was geschieht, wenn sich Zustand, Vollständigkeit, letzter Abgleich oder die Liste der
	 *                        übersprungenen Einträge geändert haben können (Hinweis in der Fußzeile, Info-Fenster)
	 * @param onLocked        was geschieht, wenn eine Einheit gesperrt gemeldet wurde: merkt sie sich und frischt den Link
	 *                        "gesperrte Einträge" auf
	 */
	public SharedFolderEvents(SyncListener live, Function<UnitKey, String> labels, Ui ui, ConnectFlow flow,
			Runnable onSyncChanged, BiConsumer<UnitKey, UnitState.Status> onLocked) {
		this.live = live;
		this.labels = labels;
		this.ui = ui;
		this.flow = flow;
		this.onSyncChanged = onSyncChanged;
		this.onLocked = onLocked;
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
	 * endet, die Verbindung wechselt.
	 * <p>
	 * Die gemerkte Ursache vergisst sie, wenn der Abgleich wieder läuft oder endet ({@code AKTUELL}, {@code AUSSTEHEND},
	 * {@code AUS}), und nimmt ihren Text dann aus der Fußzeile; nicht bei {@code OFFLINE}: Der Speicher meldet denselben Text nicht noch einmal, wenn auf FEHLER ein
	 * Ausfall und wieder FEHLER folgt. Die wartende Entscheidung vergisst sie, sobald der Zustand {@code ENTSCHEIDUNG}
	 * verlässt. Steht der Zustand beim Einhängen schon auf {@code ENTSCHEIDUNG}, ohne dass die Frage ankam (der Rückruf kam
	 * vor dem Einhängen), gilt sie mit Beiseitelegen als wartend: {@code decideConnect} prüft die Schutzregel selbst und
	 * fragt bei Bedarf erneut, nur mit "Mitnehmen".
	 */
	public void watch(ConnectionControl sync) {
		if (sync.syncState().get() == SyncState.ENTSCHEIDUNG && pendingDecision == null) {
			pendingDecision = new PendingDecision(sync.sharedDir(), true);
		}
		sync.syncState().addListener((observable, oldState, newState) -> {
			if (newState == SyncState.AKTUELL || newState == SyncState.AUSSTEHEND || newState == SyncState.AUS) {
				// Die Störung ist vorbei: Ihr Text soll nicht in der Fußzeile stehen bleiben, wenn Hinweis und Ursache weg sind
				forgetFailure();
			}
			if (newState != SyncState.ENTSCHEIDUNG) {
				boolean questionMayBeOpen = pendingDecision != null || oldState == SyncState.ENTSCHEIDUNG;
				pendingDecision = null;
				// Die Frage im Betrieb hat sich erledigt (anderswo entschieden, getrennt); die der Ladeansicht gehört dem Ablauf
				if (questionMayBeOpen && !flow.running()) {
					ui.closeDecision();
				}
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
			// Der Text kann nach dem Wechsel des Zustands ankommen; die Statuszeile braucht ihn dann nachträglich
			syncChanged();
			// Nur in der Fußzeile: LiveUpdates öffnete in Ansichten ohne Fußzeile (Ladeansicht, Hauptmenü) einen modalen
			// Dialog, und verschiedene Ursachen stapelten sich. Die Ursache steht auch in Statuszeile und Info-Fenster.
			ui.footer(Severity.ERROR, message);
			return;
		}
		live.error(message);
	}

	// --- Was hier behandelt wird

	@Override
	public void unreachable(String path) {
		// Im Ablauf des Verbindens zeigt die Ladeansicht den Hinweis schon (Entscheidung 15): kein Popup dazu
		if (!flow.running() && !ui.unreachableOpen()) {
			ui.showUnreachable(StoreMessages.unreachable(path));
		}
	}

	@Override
	public void reconnected() {
		forgetFailure();
		ui.footer(Severity.INFO, SharedFolderTexts.RECONNECTED);
		syncChanged();
	}

	/** Vergisst die gemerkte Störung und nimmt ihren Text aus der Fußzeile; ohne Störung nichts. */
	private void forgetFailure() {
		if (lastFailure != null) {
			ui.withdrawFooter(lastFailure);
			lastFailure = null;
		}
	}

	@Override
	public void duplicateMachine(String message) {
		ui.showDuplicateMachine(message);
	}

	@Override
	public void connectDecisionNeeded(String dir, boolean setAsideAllowed) {
		pendingDecision = new PendingDecision(dir, setAsideAllowed);
		openDecision(pendingDecision);
	}

	/**
	 * Öffnet die Frage beim Verbinden noch einmal, wenn der Versionsspeicher noch auf die Antwort wartet (der
	 * Zustand {@code ENTSCHEIDUNG} dauert an): für den Hinweis in der Fußzeile, nachdem man die Frage geschlossen hat. Läuft
	 * ein Ablauf, fragt dessen Dialog. Ohne wartende Entscheidung tut es nichts.
	 */
	public void askDecisionAgain() {
		if (pendingDecision != null) {
			openDecision(pendingDecision);
		}
	}

	/** Ob der Versionsspeicher auf eine Entscheidung wartet, die {@link #askDecisionAgain()} erneut stellen kann. */
	public boolean decisionPending() {
		return pendingDecision != null;
	}

	private void openDecision(PendingDecision decision) {
		if (flow.running()) {
			// Der Ablauf des Knopfes (oder des Starts) fragt selbst; ein zweiter Dialog neben seinem wäre doppelt
			flow.decisionNeeded(decision.setAsideAllowed());
			return;
		}
		ui.askDecision(decision.dir(), decision.setAsideAllowed(),
				mode -> answer(decision.dir(), decision.setAsideAllowed(), mode), flow::cancelDecision);
	}

	/**
	 * Die Antwort der Frage im Betrieb: Der Ablauf beginnt mit der Ladeansicht. Hat {@code LeaveGuard} verweigert, ist die
	 * Frage zu, aber unbeantwortet; sie kommt wieder, damit die Entscheidung nicht verloren geht.
	 */
	private void answer(String dir, boolean setAsideAllowed, ConnectMode mode) {
		if (!flow.decide(mode) && !flow.running()) {
			openDecision(new PendingDecision(dir, setAsideAllowed));
		}
	}

	@Override
	public void skipped(UnitKey unit, boolean subtree, String cause) {
		ui.footer(Severity.WARNING, SharedFolderTexts.skippedFooter(label(unit), subtree));
		syncChanged();
	}

	@Override
	public void locked(UnitKey unit, UnitState.Status status) {
		String text = StoreMessages.locked(label(unit), status);
		if (text == null) {
			return;
		}
		// nur in der Fußzeile: Der Link "gesperrte Einträge" führt zur Liste, ein Dialog dazu käme in jeder Ansicht ohne Fußzeile
		ui.footer(Severity.ERROR, text);
		try {
			onLocked.accept(unit, status);
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Die gesperrten Einträge ließen sich nicht anzeigen", e);
		}
	}

	/** Die Bezeichnung der Einheit; ihr Pfad, wenn sie sich nicht benennen lässt. */
	private String label(UnitKey unit) {
		return LockedUnits.label(labels, unit);
	}

	private void syncChanged() {
		try {
			onSyncChanged.run();
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Die Anzeige des Abgleichs ließ sich nicht auffrischen", e);
		}
	}
}
