package de.eltviller_carneval_verein.karten.ui.shared;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.store.ConnectOutcome;
import de.eltviller_carneval_verein.karten.store.ConnectOutcome.Kind;
import de.eltviller_carneval_verein.karten.store.ConnectionControl;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncState;
import javafx.beans.value.ChangeListener;

/**
 * Der Ablauf des Verbindens mit dem gemeinsamen Ordner, ohne JavaFX: Prüfungen, Einstellung schreiben, {@code connect},
 * Frage bei {@link Kind#ENTSCHEIDUNG}, Hinweis bei {@link Kind#OFFLINE} und {@link Kind#FEHLER}, Ende des ersten Laufs.
 * Die Oberfläche setzt {@link Screen} um und lässt {@link Screen#finished()} neu laden und das Hauptmenü öffnen.
 * <p>
 * Threads: Die öffentlichen Methoden und alle {@link Screen}-Rückrufe laufen auf dem FX-Thread ({@code fx}).
 * {@code connect}, {@code decideConnect}, {@code disconnect} und die Prüfungen (die Erreichbarkeit kann bis zu 5 s dauern)
 * laufen auf {@code background}; Ergebnisse des Versionsspeichers und Änderungen seiner Eigenschaften kehren über
 * {@code fx} zurück. Es läuft höchstens ein Ablauf; {@link Screen#finished()} kommt je Ablauf höchstens einmal.
 */
public final class ConnectFlow {

	private static final Logger LOGGER = Logger.getLogger(ConnectFlow.class.getName());

	/** Was die Oberfläche beim Verbinden zeigt. */
	public interface Screen {

		/** Die Ladeansicht "Verbinde …" mit dem Fortschritt aus {@code fullSyncProgress()} zeigen. */
		void showProgress();

		/** Eine Prüfung ist fehlgeschlagen: den Fehlertext am Einstellungsabschnitt zeigen. Es wurde nichts verbunden. */
		void checkFailed(String text);

		/**
		 * Die Frage "Mitnehmen" / "Beiseitelegen" / "Abbrechen" über der Ladeansicht zeigen. Die Antwort kommt über
		 * {@link ConnectFlow#decide} bzw. {@link ConnectFlow#cancelDecision()}.
		 *
		 * @param setAsideAllowed ob "Beiseitelegen" zur Wahl steht; sonst nur "Mitnehmen", mit Erklärung
		 */
		void askDecision(String dir, boolean setAsideAllowed);

		/**
		 * Unter dem Fortschritt die Ursache zeigen. Der Knopf "Trotzdem öffnen" (Antwort:
		 * {@link ConnectFlow#openAnyway()}) steht vom ersten Aufruf an bis zum Ende des Ablaufs zur Verfügung; er hängt nicht
		 * an der Ursache. Der Aufruf kommt wieder, wenn der Abgleich nach {@link #hideHint()} erneut ausfällt.
		 *
		 * @param setAsidePath der Ordner, in den schon beiseitegelegt wurde, sonst {@code null}
		 */
		void showHint(String message, Path setAsidePath);

		/** Nur den Text der Ursache ausblenden, weil der Abgleich wieder läuft; "Trotzdem öffnen" bleibt. */
		void hideHint();

		/** Der erste Lauf ist zu Ende (oder "Trotzdem öffnen"): neu laden, dann Hauptmenü. */
		void finished();

		/** Abgebrochen (Abbrechen in der Frage, oder parallel getrennt): zurück in die Einstellungen. */
		void cancelled();
	}

	private final ConnectionControl sync;
	private final Executor background;
	private final Executor fx;
	private final Screen screen;
	private final BooleanSupplier leaveGuard;
	private final Consumer<String> saveSharedDir;
	private final Path appDataDir;
	private final Predicate<Path> reachable;

	private final ChangeListener<SyncState> stateListener;

	/** Nummer des laufenden Ablaufs; Rückrufe eines früheren Ablaufs (oder nach {@link #cancel()}) tun nichts. */
	private volatile int generation;
	private boolean running;
	private boolean closed;
	private boolean awaitingDecision;
	/** Ob die offene Frage "Beiseitelegen" anbot. */
	private boolean askedAllowed;
	private boolean hintVisible;
	/** Text, Beiseite-Ordner und Art ({@code OFFLINE}/{@code FEHLER}) des letzten Hinweises, falls er nach einer Pause wieder erscheint. */
	private String lastMessage;
	private Path lastSetAsidePath;
	private SyncState lastOutage;
	private String dir;
	/** Wartet auf das Ende des ersten Laufs; solange er besteht, hört der Ablauf auch auf den Zustand. */
	private FirstRunWatch watch;

	/**
	 * @param sync          die Verbindung zum Versionsspeicher
	 * @param background    führt Aufrufe aus, die blockieren können
	 * @param fx            führt Aufrufe auf dem FX-Thread aus
	 * @param screen        die Oberfläche
	 * @param leaveGuard    fragt nach ungespeicherten Änderungen; {@code false} bricht ab
	 * @param saveSharedDir schreibt die Einstellung {@code sharedDir} per Teil-Speichern ({@code null} löscht sie); wird auf
	 *                      {@code fx} aufgerufen
	 * @param appDataDir    der eigene Datenordner, für die Prüfungen
	 * @param reachable     die Erreichbarkeitsprüfung des Dienstes, für die Prüfungen
	 */
	public ConnectFlow(ConnectionControl sync, Executor background, Executor fx, Screen screen,
			BooleanSupplier leaveGuard, Consumer<String> saveSharedDir, Path appDataDir, Predicate<Path> reachable) {
		this.sync = sync;
		this.background = background;
		this.fx = fx;
		this.screen = screen;
		this.leaveGuard = leaveGuard;
		this.saveSharedDir = saveSharedDir;
		this.appDataDir = appDataDir;
		this.reachable = reachable;
		this.stateListener = (observable, oldState, newState) -> {
			// Der Ablauf wird beim Ereignis festgehalten: Ein Ereignis, das noch in der Warteschlange steht, wenn der Ablauf
			// endet, darf nicht im nächsten Ablauf wirken.
			int id = generation;
			fx.execute(() -> stateChanged(id, newState));
		};
	}

	/** Ob ein Ablauf unterwegs ist. */
	public boolean running() {
		return running;
	}

	/**
	 * Der Knopf "Verbinden": ungespeicherte Änderungen klären, den Ordner prüfen, {@code sharedDir} schreiben, dann
	 * {@code connect(dir, ASK)}. Tut nichts, wenn schon ein Ablauf läuft.
	 */
	public void connectByButton(String dirText) {
		if (closed || running) {
			return;
		}
		int id = begin();
		if (!leaveGuard.getAsBoolean()) {
			end(id);
			return;
		}
		if (!isCurrent(id)) {
			return;
		}
		String typed = dirText == null ? "" : dirText.strip();
		background.execute(() -> {
			Optional<String> error = check(typed);
			fx.execute(() -> checked(id, typed, error));
		});
	}

	/**
	 * Verbinden beim Start: ohne Prüfungen und ohne die Einstellung zu schreiben (sie steht schon); {@code startup} hat der
	 * Aufrufer schon erledigt. Tut nichts, wenn schon ein Ablauf läuft.
	 */
	public void connectAtStart(String dirText, ConnectMode mode) {
		if (closed || running) {
			return;
		}
		int id = begin();
		dir = dirText;
		screen.showProgress();
		connect(id, dirText, mode, true);
	}

	/**
	 * Die Antwort auf die Frage ("Mitnehmen" bzw. "Beiseitelegen"), vom Knopf oder im Modus ASK im Betrieb: erst die Frage
	 * nach ungespeicherten Änderungen, dann {@code decideConnect}. Gehört zum laufenden Ablauf, wenn dieser auf die Antwort
	 * wartet; sonst beginnt ein neuer Ablauf mit der Ladeansicht.
	 * <p>
	 * Die Frage ist schon geschlossen, wenn die Antwort ankommt. Verweigert {@code LeaveGuard}, bleibt der Ablauf, der auf
	 * die Antwort wartet, nicht ohne Frage stehen: Sie wird erneut gezeigt. Ohne laufenden Ablauf tut das der Aufrufer
	 * (Rückgabe {@code false}), denn dann gehört die Frage nicht zu einem Ablauf.
	 *
	 * @return ob die Antwort angenommen wurde ({@code decideConnect} läuft); sonst {@code false}
	 */
	public boolean decide(ConnectMode mode) {
		if (closed || (running && !awaitingDecision)) {
			return false;
		}
		boolean fresh = !running;
		if (!leaveGuard.getAsBoolean()) {
			// Während der Rückfrage kann sich der Ablauf geändert haben; nur die noch offene Frage kommt wieder
			if (!fresh && !closed && running && awaitingDecision) {
				screen.askDecision(currentDir(), askedAllowed);
			}
			return false;
		}
		// Während der Rückfrage kann sich der Ablauf geändert haben (Schließen, Abbrechen).
		if (closed || running == fresh || (!fresh && !awaitingDecision)) {
			return false;
		}
		int id;
		if (fresh) {
			id = begin();
			screen.showProgress();
		} else {
			id = generation;
			awaitingDecision = false;
		}
		background.execute(() -> {
			try {
				sync.decideConnect(mode).whenComplete((outcome, error) -> fx.execute(() -> handle(id, outcome, error, false)));
			} catch (IllegalStateException e) {
				// Keine Verbindung wartet mehr auf die Entscheidung. Ist der Ordner nicht erreichbar, ist das der Grund:
				// dann den Text von SYS-005 zeigen statt der Entwicklermeldung.
				fx.execute(() -> {
					if (!isCurrent(id)) {
						return;
					}
					if (sync.syncState().get() == SyncState.OFFLINE) {
						showHint(id, StoreMessages.unreachable(currentDir()), null, SyncState.OFFLINE);
					} else {
						handle(id, null, e, false);
					}
				});
			} catch (RuntimeException e) {
				fx.execute(() -> handle(id, null, e, false));
			}
		});
		return true;
	}

	/**
	 * "Abbrechen" in der Frage: {@code sharedDir} löschen, dann trennen (im Hintergrund), zurück in die Einstellungen.
	 */
	public void cancelDecision() {
		if (closed) {
			return;
		}
		// Ein neuer Ablauf macht die Rückrufe des alten unwirksam, auch das ABGEBROCHEN, das das Trennen auslöst.
		end(generation);
		int id = begin();
		// Zuerst die Einstellung: Schließt die App zwischen beiden Schritten, bliebe nach umgekehrter Reihenfolge der Ordner
		// eingetragen, und der nächste Start verbände die abgebrochene Verbindung wieder.
		try {
			saveSharedDir.accept(null);
		} catch (RuntimeException e) {
			LOGGER.log(Level.WARNING, "Die Einstellung des gemeinsamen Ordners ließ sich nicht löschen", e);
		}
		background.execute(() -> {
			try {
				sync.disconnect();
			} catch (RuntimeException e) {
				LOGGER.log(Level.WARNING, "Trennen vom gemeinsamen Ordner ist fehlgeschlagen", e);
			}
			fx.execute(() -> {
				if (isCurrent(id)) {
					end(id);
					screen.cancelled();
				}
			});
		});
	}

	/**
	 * Der Versionsspeicher meldet über {@code connectDecisionNeeded}, dass er auf eine Entscheidung wartet (auf {@code fx}
	 * aufrufen). Läuft ein Ablauf, der noch keine Frage gestellt hat, wird ein Hinweis ausgeblendet und die Frage gestellt:
	 * Kommt die Entscheidung nach {@link Kind#OFFLINE} oder {@link Kind#FEHLER}, ist das Ergebnis von {@code connect} schon
	 * abgegeben und kommt nicht noch einmal. Dieselbe Frage wird nicht zweimal gestellt (der Rückruf und das Ergebnis
	 * {@link Kind#ENTSCHEIDUNG} melden sie beide). Ohne laufenden Ablauf tut es nichts; dann antwortet der Aufrufer mit
	 * {@link #decide}, das einen neuen Ablauf beginnt.
	 */
	public void decisionNeeded(boolean setAsideAllowed) {
		if (closed || !running) {
			return;
		}
		ask(generation, setAsideAllowed);
	}

	/** "Trotzdem öffnen": der Ablauf endet sofort, die Oberfläche lädt neu und öffnet das Hauptmenü. */
	public void openAnyway() {
		if (closed || !running) {
			return;
		}
		finish(generation);
	}

	/** Die App wird geschlossen: spätere Ergebnisse und der laufende Ablauf werden ignoriert, Listener entfernt. */
	public void cancel() {
		closed = true;
		end(generation);
	}

	// --- Ablauf

	private Optional<String> check(String typed) {
		try {
			return ConnectChecks.check(typed, appDataDir, reachable);
		} catch (RuntimeException e) {
			return Optional.of(messageOf(e));
		}
	}

	private void checked(int id, String typed, Optional<String> error) {
		if (!isCurrent(id)) {
			return;
		}
		if (error.isPresent()) {
			end(id);
			screen.checkFailed(error.get());
			return;
		}
		// Absolut gespeichert: Ein relativer Pfad gälte beim nächsten Start vom damaligen Arbeitsordner aus
		String absolute = Path.of(typed).toAbsolutePath().normalize().toString();
		dir = absolute;
		try {
			saveSharedDir.accept(absolute);
		} catch (RuntimeException e) {
			// Ohne gespeicherte Einstellung wird nicht verbunden: der nächste Start fände den Ordner sonst nicht
			end(id);
			screen.checkFailed(messageOf(e));
			return;
		}
		screen.showProgress();
		connect(id, absolute, ConnectMode.ASK, false);
	}

	/**
	 * @param atStart ob das Verbinden beim Start geschieht: Mit {@link ConnectMode#ASK} läuft der Start dann offline weiter
	 *                (SYS-005 kommt vom Versionsspeicher), statt auf den Ordner zu warten
	 */
	private void connect(int id, String target, ConnectMode mode, boolean atStart) {
		boolean offlineFinishes = atStart && mode == ConnectMode.ASK;
		background.execute(() -> {
			try {
				sync.connect(target, mode)
						.whenComplete((outcome, error) -> fx.execute(() -> handle(id, outcome, error, offlineFinishes)));
			} catch (RuntimeException e) {
				fx.execute(() -> handle(id, null, e, false));
			}
		});
	}

	/** Das Ergebnis von {@code connect} oder {@code decideConnect}; ein Fehler gilt wie {@link Kind#FEHLER}. */
	private void handle(int id, ConnectOutcome outcome, Throwable error, boolean offlineFinishes) {
		if (!isCurrent(id)) {
			return;
		}
		if (error != null || outcome == null) {
			showHint(id, error == null ? null : messageOf(error), null, SyncState.FEHLER);
			return;
		}
		switch (outcome.kind()) {
		case ENTSCHEIDUNG -> ask(id, outcome.setAsideAllowed());
		case AKTUELL, AUSSTEHEND -> finish(id);
		case OFFLINE -> {
			if (offlineFinishes) {
				finish(id);
			} else {
				showHint(id, outcome.message(), outcome.setAsidePath(), SyncState.OFFLINE);
			}
		}
		case FEHLER -> showHint(id, outcome.message(), outcome.setAsidePath(), SyncState.FEHLER);
		case ABGEBROCHEN -> {
			end(id);
			screen.cancelled();
		}
		}
	}

	/** Die Frage stellen (und einen Hinweis ausblenden); dieselbe Frage, die schon offen ist, nicht noch einmal. */
	private void ask(int id, boolean setAsideAllowed) {
		if (!isCurrent(id) || (awaitingDecision && askedAllowed == setAsideAllowed)) {
			return;
		}
		if (hintVisible) {
			hintVisible = false;
			screen.hideHint();
		}
		awaitingDecision = true;
		askedAllowed = setAsideAllowed;
		screen.askDecision(currentDir(), setAsideAllowed);
	}

	/**
	 * Der erste Lauf ist noch nicht zu Ende: Hinweis zeigen und auf das Ende des Laufs oder das Ende der Störung warten.
	 * <p>
	 * Warum der Watch erst hier beginnt und nicht mit dem Ablauf: Der Versionsspeicher setzt AKTUELL/AUSSTEHEND fest, bevor
	 * er es meldet, und das erste Ergebnis gilt (first-wins). Ein in einem Takt festgelegtes Ergebnis wird vor den
	 * Fortschrittsereignissen des nächsten Takts zugestellt, weil der Arbeiter ein einzelner Thread ist und der Fortschritt
	 * unter {@code workLock} eingereiht wird. Beim Trennen und Neu-Verbinden erscheint aber {@code null} im Fortschritt
	 * <em>vor</em> ABGEBROCHEN; ein Watch seit Beginn des Ablaufs hielte das für das Laufende. {@code fx} muss dieselbe
	 * FIFO-Warteschlange sein wie die {@code ui} des Versionsspeichers ({@code Platform::runLater}), sonst gilt die
	 * Reihenfolge nicht.
	 */
	private void showHint(int id, String message, Path setAsidePath, SyncState outage) {
		hintVisible = true;
		lastMessage = message;
		lastSetAsidePath = setAsidePath;
		lastOutage = outage;
		// beim ersten Hinweis des Ablaufs: auf das Laufende und auf den Zustand hören (end() nimmt beides wieder weg)
		if (watch == null) {
			watch = new FirstRunWatch(sync.fullSyncProgress());
			watch.onEnded(() -> fx.execute(() -> {
				if (isCurrent(id)) {
					finish(id);
				}
			}));
			sync.syncState().addListener(stateListener);
		}
		screen.showHint(message, setAsidePath);
	}

	/**
	 * Der Abgleich läuft wieder (Hinweis ausblenden) oder fällt erneut aus (Hinweis wieder zeigen). "Trotzdem öffnen" bleibt
	 * vom ersten Hinweis an bis zum Ende des Ablaufs; ausgeblendet wird nur die Ursache. Während eine Frage offen ist, bleibt
	 * der Hinweis aus.
	 *
	 * @param id der Ablauf, in dem das Ereignis entstand; ein Ereignis eines früheren Ablaufs tut nichts
	 */
	private void stateChanged(int id, SyncState state) {
		if (!isCurrent(id) || awaitingDecision) {
			return;
		}
		if (hintVisible && (state == SyncState.AKTUELL || state == SyncState.AUSSTEHEND)) {
			hintVisible = false;
			screen.hideHint();
		} else if (!hintVisible && watch != null && (state == SyncState.OFFLINE || state == SyncState.FEHLER)) {
			// Ein Ausfall anderer Art als der vorige hat eine andere Ursache: OFFLINE nennt SYS-005, FEHLER keine (den Text von
			// SYS-006 bekommt nur die Fußzeile)
			String message = state == lastOutage ? lastMessage
					: state == SyncState.OFFLINE ? StoreMessages.unreachable(currentDir()) : null;
			showHint(id, message, lastSetAsidePath, state);
		}
	}

	private void finish(int id) {
		if (!isCurrent(id)) {
			return;
		}
		end(id);
		screen.finished();
	}

	// --- Zustand

	/** Beginnt einen Ablauf und liefert seine Nummer. */
	private int begin() {
		running = true;
		awaitingDecision = false;
		askedAllowed = false;
		hintVisible = false;
		lastMessage = null;
		lastSetAsidePath = null;
		lastOutage = null;
		dir = null;
		return ++generation;
	}

	/** Ob dies der laufende Ablauf ist. */
	private boolean isCurrent(int id) {
		return running && !closed && id == generation;
	}

	/** Beendet den Ablauf, wenn er der laufende ist: Listener entfernen, spätere Rückrufe werden unwirksam. */
	private void end(int id) {
		if (id != generation) {
			return;
		}
		generation++;
		running = false;
		awaitingDecision = false;
		hintVisible = false;
		if (watch != null) {
			watch.dispose();
			watch = null;
			sync.syncState().removeListener(stateListener);
		}
	}

	/** Der Ordner des Ablaufs; ohne (Antwort im Betrieb) der des Versionsspeichers. */
	private String currentDir() {
		return dir != null ? dir : sync.sharedDir();
	}

	private static String messageOf(Throwable error) {
		Throwable cause = error;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}
}
