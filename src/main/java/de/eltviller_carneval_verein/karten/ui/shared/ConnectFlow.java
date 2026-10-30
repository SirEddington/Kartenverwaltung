package de.eltviller_carneval_verein.karten.ui.shared;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.store.ConnectOutcome;
import de.eltviller_carneval_verein.karten.store.ConnectOutcome.Kind;
import de.eltviller_carneval_verein.karten.store.ConnectionControl;
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
		 * Unter dem Fortschritt die Ursache zeigen und den Knopf "Trotzdem öffnen" (Antwort:
		 * {@link ConnectFlow#openAnyway()}).
		 *
		 * @param setAsidePath der Ordner, in den schon beiseitegelegt wurde, sonst {@code null}
		 */
		void showHint(String message, Path setAsidePath);

		/** Die Ursache wieder ausblenden: der Abgleich läuft wieder. */
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
	private int generation;
	private boolean running;
	private boolean closed;
	private boolean awaitingDecision;
	private boolean hintVisible;
	private String dir;
	private FirstRunWatch watch;
	private boolean listeningToState;

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
		this.stateListener = (observable, oldState, newState) -> fx.execute(() -> stateChanged(newState));
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
		connect(id, dirText, mode);
	}

	/**
	 * Die Antwort auf die Frage ("Mitnehmen" bzw. "Beiseitelegen"), vom Knopf oder im Modus ASK im Betrieb: erst die Frage
	 * nach ungespeicherten Änderungen, dann {@code decideConnect}. Gehört zum laufenden Ablauf, wenn dieser auf die Antwort
	 * wartet; sonst beginnt ein neuer Ablauf mit der Ladeansicht.
	 */
	public void decide(ConnectMode mode) {
		if (closed || (running && !awaitingDecision)) {
			return;
		}
		boolean fresh = !running;
		if (!leaveGuard.getAsBoolean()) {
			return;
		}
		// Während der Rückfrage kann sich der Ablauf geändert haben (Schließen, Abbrechen).
		if (closed || running == fresh || (!fresh && !awaitingDecision)) {
			return;
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
				sync.decideConnect(mode).whenComplete((outcome, error) -> fx.execute(() -> handle(id, outcome, error)));
			} catch (RuntimeException e) {
				fx.execute(() -> handle(id, null, e));
			}
		});
	}

	/**
	 * "Abbrechen" in der Frage: trennen (im Hintergrund), {@code sharedDir} löschen, zurück in die Einstellungen.
	 */
	public void cancelDecision() {
		if (closed) {
			return;
		}
		// Ein neuer Ablauf macht die Rückrufe des alten unwirksam, auch das ABGEBROCHEN, das das Trennen auslöst.
		end(generation);
		int id = begin();
		background.execute(() -> {
			try {
				sync.disconnect();
			} finally {
				fx.execute(() -> {
					if (isCurrent(id)) {
						saveSharedDir.accept(null);
						end(id);
						screen.cancelled();
					}
				});
			}
		});
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
		dir = typed;
		saveSharedDir.accept(typed);
		screen.showProgress();
		connect(id, typed, ConnectMode.ASK);
	}

	private void connect(int id, String target, ConnectMode mode) {
		background.execute(() -> {
			try {
				sync.connect(target, mode).whenComplete((outcome, error) -> fx.execute(() -> handle(id, outcome, error)));
			} catch (RuntimeException e) {
				fx.execute(() -> handle(id, null, e));
			}
		});
	}

	/** Das Ergebnis von {@code connect} oder {@code decideConnect}; ein Fehler gilt wie {@link Kind#FEHLER}. */
	private void handle(int id, ConnectOutcome outcome, Throwable error) {
		if (!isCurrent(id)) {
			return;
		}
		if (error != null || outcome == null) {
			showHint(id, error == null ? null : messageOf(error), null);
			return;
		}
		switch (outcome.kind()) {
		case ENTSCHEIDUNG -> {
			awaitingDecision = true;
			screen.askDecision(dir != null ? dir : sync.sharedDir(), outcome.setAsideAllowed());
		}
		case AKTUELL, AUSSTEHEND -> finish(id);
		case OFFLINE, FEHLER -> showHint(id, outcome.message(), outcome.setAsidePath());
		case ABGEBROCHEN -> {
			end(id);
			screen.cancelled();
		}
		}
	}

	/** Der erste Lauf ist noch nicht zu Ende: Hinweis zeigen und auf das Ende des Laufs oder das Ende der Störung warten. */
	private void showHint(int id, String message, Path setAsidePath) {
		hintVisible = true;
		if (watch == null) {
			watch = new FirstRunWatch(sync.fullSyncProgress());
			watch.onEnded(() -> fx.execute(() -> {
				if (isCurrent(id)) {
					finish(id);
				}
			}));
		}
		if (!listeningToState) {
			listeningToState = true;
			sync.syncState().addListener(stateListener);
		}
		screen.showHint(message, setAsidePath);
	}

	private void stateChanged(SyncState state) {
		if (!running || closed || !hintVisible) {
			return;
		}
		if (state == SyncState.AKTUELL || state == SyncState.AUSSTEHEND) {
			hintVisible = false;
			screen.hideHint();
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
		hintVisible = false;
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
		}
		if (listeningToState) {
			listeningToState = false;
			sync.syncState().removeListener(stateListener);
		}
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
