package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.store.ConnectOutcome;
import de.eltviller_carneval_verein.karten.store.ConnectOutcome.Kind;
import de.eltviller_carneval_verein.karten.store.ConnectionControl;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncState;

class ConnectFlowTest {

	@TempDir
	Path root;

	private final FakeConnection sync = new FakeConnection();
	/** Die Aufrufe der Oberfläche in der Reihenfolge, in der sie kamen. */
	private final List<String> screenLog = new ArrayList<>();
	/** Was {@code saveSharedDir} bekam; {@code "null"} für {@code null}. */
	private final List<String> saves = new ArrayList<>();
	/** Die Aufrufe der Verbindung, die schon liefen, als {@code saveSharedDir} aufgerufen wurde. */
	private final List<List<String>> callsAtSave = new ArrayList<>();
	private boolean guardAllows = true;
	private int guardAsked;
	private Path shared;
	private Path appDataDir;
	private RuntimeException saveFailure;
	private ConnectFlow flow;

	@BeforeEach
	void setUp() throws IOException {
		shared = Files.createDirectories(root.resolve("gemeinsam"));
		Path appData = Files.createDirectories(root.resolve("appdata"));
		flow = new ConnectFlow(sync, Runnable::run, Runnable::run, new RecordingScreen(), () -> {
			guardAsked++;
			return guardAllows;
		}, dir -> {
			saves.add(String.valueOf(dir));
			callsAtSave.add(sync.calls());
			if (saveFailure != null) {
				throw saveFailure;
			}
		}, appData, path -> true);
		appDataDir = appData;
	}

	/**
	 * Eine Verbindung, die alles an {@link #sync} weitergibt, außer dem Aufruf {@code method}: der wirft {@code failure}.
	 */
	private ConnectionControl syncFailingOn(String method, RuntimeException failure) {
		return (ConnectionControl) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { ConnectionControl.class }, (proxy, called, args) -> {
					if (called.getName().equals(method)) {
						throw failure;
					}
					try {
						return called.invoke(sync, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	private ConnectFlow flowOver(ConnectionControl connection) {
		return new ConnectFlow(connection, Runnable::run, Runnable::run, new RecordingScreen(), () -> true, dir -> {
		}, appDataDir, path -> true);
	}

	private String dir() {
		return shared.toString();
	}

	private static ConnectOutcome outcome(Kind kind) {
		return new ConnectOutcome(kind, false, null, null);
	}

	@Test
	void sharedDirIsSavedBeforeConnect() {
		flow.connectByButton("  " + dir() + "  ");

		assertEquals(List.of(dir()), saves);
		assertEquals(List.of(), callsAtSave.get(0), "beim Speichern lief noch kein connect");
		assertEquals(List.of("connect:" + dir() + ":ASK"), sync.calls());
		assertEquals(List.of("showProgress"), screenLog);
		assertTrue(flow.running());
	}

	@Test
	void failedCheckDoesNotConnect() {
		Path missing = root.resolve("gibt-es-nicht");

		flow.connectByButton(missing.toString());

		assertEquals(List.of(), sync.calls());
		assertEquals(List.of(), saves);
		assertEquals(1, screenLog.size());
		assertTrue(screenLog.get(0).startsWith("checkFailed:Den Ordner " + missing), screenLog.get(0));
		assertFalse(flow.running(), "nach einer fehlgeschlagenen Prüfung kann man es noch einmal versuchen");
	}

	@Test
	void leaveGuardRefusalStopsBeforeAnything() {
		guardAllows = false;

		flow.connectByButton(dir());

		assertEquals(1, guardAsked);
		assertEquals(List.of(), sync.calls());
		assertEquals(List.of(), saves);
		assertEquals(List.of(), screenLog);
		assertFalse(flow.running());
	}

	@Test
	void currentOutcomeFinishes() {
		flow.connectByButton(dir());

		sync.outcome(outcome(Kind.AKTUELL));

		assertEquals(List.of("showProgress", "finished"), screenLog);
		assertFalse(flow.running());
	}

	@Test
	void pendingOutcomeFinishes() {
		flow.connectByButton(dir());

		sync.outcome(outcome(Kind.AUSSTEHEND));

		assertEquals(List.of("showProgress", "finished"), screenLog);
	}

	@Test
	void decisionAsksAndMergeContinues() {
		flow.connectByButton(dir());

		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, true, null, null));
		assertEquals(List.of("showProgress", "askDecision:" + dir() + ":true"), screenLog);
		assertTrue(flow.running());

		flow.decide(ConnectMode.MERGE);
		assertEquals(List.of("connect:" + dir() + ":ASK", "decide:MERGE"), sync.calls());

		sync.outcome(outcome(Kind.AKTUELL));
		assertEquals(List.of("showProgress", "askDecision:" + dir() + ":true", "finished"), screenLog);
	}

	@Test
	void refusedSetAsideAsksAgainWithMergeOnly() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, true, null, null));

		flow.decide(ConnectMode.SET_ASIDE);
		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, false, null, null));

		assertEquals(List.of("connect:" + dir() + ":ASK", "decide:SET_ASIDE"), sync.calls());
		assertEquals(List.of("showProgress", "askDecision:" + dir() + ":true", "askDecision:" + dir() + ":false"),
				screenLog);
		assertTrue(flow.running());
	}

	@Test
	void cancelInDecisionDisconnectsAndClearsSharedDir() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, true, null, null));

		flow.cancelDecision();

		assertEquals(List.of("connect:" + dir() + ":ASK", "disconnect"), sync.calls());
		assertEquals(List.of(dir(), "null"), saves);
		assertEquals(List.of("showProgress", "askDecision:" + dir() + ":true", "cancelled"), screenLog);
		assertFalse(flow.running());
	}

	@Test
	void decideAsksLeaveGuardFirst() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, true, null, null));
		guardAllows = false;
		guardAsked = 0;

		flow.decide(ConnectMode.MERGE);

		assertEquals(1, guardAsked);
		assertEquals(List.of("connect:" + dir() + ":ASK"), sync.calls());
		assertTrue(flow.running(), "die Frage bleibt offen, man kann noch antworten");

		guardAllows = true;
		flow.decide(ConnectMode.MERGE);
		assertEquals(List.of("connect:" + dir() + ":ASK", "decide:MERGE"), sync.calls());
	}

	@Test
	void refusedLeaveGuardKeepsTheQuestionOpen() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, true, null, null));
		guardAllows = false;

		// Die Frage wurde schon geschlossen, bevor die Antwort ankommt; verweigert LeaveGuard, muss sie wieder erscheinen
		assertFalse(flow.decide(ConnectMode.MERGE));

		assertEquals(List.of("showProgress", "askDecision:" + dir() + ":true", "askDecision:" + dir() + ":true"), screenLog);
		assertEquals(List.of("connect:" + dir() + ":ASK"), sync.calls());
		assertTrue(flow.running());

		guardAllows = true;
		assertTrue(flow.decide(ConnectMode.MERGE));
		assertEquals(List.of("connect:" + dir() + ":ASK", "decide:MERGE"), sync.calls());
		assertEquals(2, screenLog.stream().filter(entry -> entry.startsWith("askDecision")).count(),
				"nach der Antwort keine weitere Frage");
	}

	@Test
	void refusedLeaveGuardWithoutRunningFlowReportsItAndStartsNothing() {
		sync.sharedDir(dir());
		guardAllows = false;

		assertFalse(flow.decide(ConnectMode.MERGE), "wer gefragt hat, kann die Frage selbst wieder öffnen");

		assertEquals(List.of(), sync.calls());
		assertEquals(List.of(), screenLog);
		assertFalse(flow.running());
	}

	@Test
	void decideReportsWhetherItActed() {
		sync.sharedDir(dir());
		assertTrue(flow.decide(ConnectMode.MERGE));
		assertFalse(flow.decide(ConnectMode.MERGE), "ein laufender Ablauf ohne wartende Frage nimmt keine zweite Antwort");
	}

	@Test
	void decideWithoutRunningFlowStartsOneWithProgress() {
		sync.sharedDir(dir());

		flow.decide(ConnectMode.MERGE);
		assertEquals(List.of("decide:MERGE"), sync.calls());
		assertEquals(List.of("showProgress"), screenLog);
		assertTrue(flow.running());

		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, false, null, null));
		assertEquals(List.of("showProgress", "askDecision:" + dir() + ":false"), screenLog);

		flow.decide(ConnectMode.MERGE);
		sync.outcome(outcome(Kind.AKTUELL));
		assertEquals(List.of("showProgress", "askDecision:" + dir() + ":false", "finished"), screenLog,
				"die erneute Antwort gehört zum laufenden Ablauf, keine zweite Ladeansicht");
	}

	@Test
	void secondAnswerWhileDecidingIsIgnored() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, true, null, null));

		flow.decide(ConnectMode.MERGE);
		flow.decide(ConnectMode.SET_ASIDE);

		assertEquals(List.of("connect:" + dir() + ":ASK", "decide:MERGE"), sync.calls());
	}

	@Test
	void offlineShowsHintAndFinishesAtRunEnd() {
		flow.connectByButton(dir());

		sync.outcome(new ConnectOutcome(Kind.OFFLINE, false, null, "Ordner nicht erreichbar"));
		assertEquals(List.of("showProgress", "showHint:Ordner nicht erreichbar:null"), screenLog);

		sync.progress(0.3);
		sync.state(SyncState.AKTUELL);
		assertEquals(List.of("showProgress", "showHint:Ordner nicht erreichbar:null", "hideHint"), screenLog);
		assertTrue(flow.running(), "der Lauf ist noch nicht zu Ende");

		sync.progress(null);
		assertEquals(List.of("showProgress", "showHint:Ordner nicht erreichbar:null", "hideHint", "finished"),
				screenLog);
		assertFalse(flow.running());
	}

	@Test
	void failureThenRunEndWithIncompleteConnectionStillFinishes() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.FEHLER, false, null, "Fehler beim Abgleich"));
		sync.progress(0.2);

		sync.complete(false);
		sync.progress(null);

		assertEquals(List.of("showProgress", "showHint:Fehler beim Abgleich:null", "finished"), screenLog);
	}

	@Test
	void hintStaysWhileTheStateStaysOffline() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.OFFLINE, false, null, "weg"));

		sync.state(SyncState.OFFLINE);
		sync.state(SyncState.FEHLER);
		sync.progress(0.1);
		sync.progress(0.2);

		assertEquals(List.of("showProgress", "showHint:weg:null"), screenLog);
	}

	@Test
	void openAnywayFinishesAtOnce() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.OFFLINE, false, null, "weg"));

		flow.openAnyway();

		assertEquals(List.of("showProgress", "showHint:weg:null", "finished"), screenLog);
		assertFalse(flow.running());

		// der spätere Lauf-Ende-Wechsel löst kein zweites finished() aus
		sync.progress(0.5);
		sync.progress(null);
		flow.openAnyway();
		assertEquals(3, screenLog.size());
	}

	@Test
	void hintNamesSetAsideFolder() {
		Path aside = root.resolve("data-beiseite-2026");
		flow.connectByButton(dir());

		sync.outcome(new ConnectOutcome(Kind.FEHLER, false, aside, "kaputt"));

		assertEquals(List.of("showProgress", "showHint:kaputt:" + aside), screenLog);
	}

	@Test
	void abortedOutcomeCancels() {
		flow.connectByButton(dir());

		sync.outcome(outcome(Kind.ABGEBROCHEN));

		assertEquals(List.of("showProgress", "cancelled"), screenLog);
		assertFalse(flow.running());
		assertEquals(List.of(dir()), saves, "die Einstellung bleibt, getrennt wurde schon anderswo");
	}

	@Test
	void secondStartWhileRunningIsIgnored() {
		flow.connectByButton(dir());
		flow.connectByButton(dir());
		flow.connectAtStart(dir(), ConnectMode.MERGE);

		assertEquals(List.of("connect:" + dir() + ":ASK"), sync.calls());
		assertEquals(List.of(dir()), saves);
		assertEquals(1, guardAsked);
	}

	@Test
	void lateOutcomeAfterCancelDoesNothing() {
		flow.connectByButton(dir());

		flow.cancel();
		sync.outcome(outcome(Kind.AKTUELL));

		assertEquals(List.of("showProgress"), screenLog);
		assertFalse(flow.running());
	}

	@Test
	void cancelRemovesTheListeners() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.OFFLINE, false, null, "weg"));

		flow.cancel();
		sync.progress(0.4);
		sync.state(SyncState.AKTUELL);
		sync.progress(null);

		assertEquals(List.of("showProgress", "showHint:weg:null"), screenLog);
	}

	@Test
	void nothingStartsAfterCancel() {
		flow.cancel();

		flow.connectByButton(dir());
		flow.connectAtStart(dir(), ConnectMode.MERGE);
		flow.decide(ConnectMode.MERGE);

		assertEquals(List.of(), sync.calls());
		assertEquals(List.of(), screenLog);
	}

	@Test
	void connectAtStartNeitherChecksNorSaves() {
		flow.connectAtStart("gibt/es/nicht", ConnectMode.MERGE);

		assertEquals(List.of("connect:gibt/es/nicht:MERGE"), sync.calls());
		assertEquals(List.of(), saves);
		assertEquals(0, guardAsked);
		assertEquals(List.of("showProgress"), screenLog);

		sync.outcome(outcome(Kind.AKTUELL));
		assertEquals(List.of("showProgress", "finished"), screenLog);
	}

	@Test
	void exceptionallyCompletedFutureCountsAsFailure() {
		flow.connectAtStart(dir(), ConnectMode.MERGE);
		sync.fail(new IllegalStateException("kaputt"));

		assertEquals(List.of("showProgress", "showHint:kaputt:null"), screenLog);
		assertTrue(flow.running(), "wie FEHLER: die Ladeansicht endet mit dem Lauf oder mit Trotzdem öffnen");

		flow.openAnyway();
		assertEquals("finished", screenLog.get(screenLog.size() - 1));
	}

	@Test
	void connectThrowingCountsAsFailure() {
		ConnectFlow throwing = flowOver(syncFailingOn("connect", new IllegalStateException("nicht möglich")));

		throwing.connectAtStart(dir(), ConnectMode.MERGE);

		assertEquals(List.of("showProgress", "showHint:nicht möglich:null"), screenLog);
	}

	@Test
	void failingSaveBeforeConnectReportsAndEndsTheFlow() {
		saveFailure = new IllegalStateException("Datei gesperrt");

		flow.connectByButton(dir());

		assertEquals(List.of("checkFailed:Datei gesperrt"), screenLog);
		assertEquals(List.of(), sync.calls(), "ohne gespeicherte Einstellung wird nicht verbunden");
		assertFalse(flow.running());
	}

	@Test
	void failingSaveOnCancelStillCancels() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, true, null, null));
		saveFailure = new IllegalStateException("Datei gesperrt");

		flow.cancelDecision();

		assertEquals("cancelled", screenLog.get(screenLog.size() - 1));
		assertEquals(List.of("connect:" + dir() + ":ASK", "disconnect"), sync.calls());
		assertFalse(flow.running());
	}

	@Test
	void failingDisconnectStillCancels() {
		ConnectFlow failing = flowOver(syncFailingOn("disconnect", new IllegalStateException("kaputt")));

		failing.cancelDecision();

		assertEquals(List.of("cancelled"), screenLog);
		assertFalse(failing.running());
	}

	@Test
	void decisionAfterOfflineHintCanBeAnswered() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.OFFLINE, false, null, "weg"));

		flow.decisionNeeded(true);
		assertEquals(List.of("showProgress", "showHint:weg:null", "hideHint", "askDecision:" + dir() + ":true"),
				screenLog);

		flow.decide(ConnectMode.MERGE);
		assertEquals(List.of("connect:" + dir() + ":ASK", "decide:MERGE"), sync.calls());
		sync.outcome(outcome(Kind.AKTUELL));

		assertEquals("finished", screenLog.get(screenLog.size() - 1));
		assertFalse(flow.running());
	}

	@Test
	void decisionNeededIsAskedOnlyOncePerQuestion() {
		flow.connectByButton(dir());

		flow.decisionNeeded(true);
		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, true, null, null));
		assertEquals(List.of("showProgress", "askDecision:" + dir() + ":true"), screenLog);

		flow.decide(ConnectMode.SET_ASIDE);
		flow.decisionNeeded(false);
		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, false, null, null));
		assertEquals(List.of("showProgress", "askDecision:" + dir() + ":true", "askDecision:" + dir() + ":false"),
				screenLog);
	}

	@Test
	void decisionNeededWithoutRunningFlowDoesNothing() {
		flow.decisionNeeded(true);

		assertEquals(List.of(), screenLog);
		assertFalse(flow.running());
	}

	@Test
	void secondOutageShowsTheHintAgain() {
		Path aside = root.resolve("data-beiseite-2026");
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.FEHLER, false, aside, "kaputt"));
		sync.state(SyncState.AKTUELL);

		sync.state(SyncState.OFFLINE);

		assertEquals(List.of("showProgress", "showHint:kaputt:" + aside, "hideHint", "showHint:kaputt:" + aside),
				screenLog);
		assertTrue(flow.running());
	}

	@Test
	void startWithAskWhileOfflineFinishesAtOnce() {
		flow.connectAtStart(dir(), ConnectMode.ASK);

		sync.outcome(new ConnectOutcome(Kind.OFFLINE, false, null, "weg"));

		assertEquals(List.of("showProgress", "finished"), screenLog);
		assertFalse(flow.running());
	}

	@Test
	void startWithAskAfterFailureShowsTheHint() {
		flow.connectAtStart(dir(), ConnectMode.ASK);

		sync.outcome(new ConnectOutcome(Kind.FEHLER, false, null, "kaputt"));

		assertEquals(List.of("showProgress", "showHint:kaputt:null"), screenLog);
	}

	@Test
	void startWithMergeWhileOfflineWaits() {
		flow.connectAtStart(dir(), ConnectMode.MERGE);

		sync.outcome(new ConnectOutcome(Kind.OFFLINE, false, null, "weg"));

		assertEquals(List.of("showProgress", "showHint:weg:null"), screenLog);
		assertTrue(flow.running());
	}

	@Test
	void buttonWhileOfflineStillWaits() {
		flow.connectByButton(dir());

		sync.outcome(new ConnectOutcome(Kind.OFFLINE, false, null, "weg"));

		assertEquals(List.of("showProgress", "showHint:weg:null"), screenLog);
	}

	@Test
	void decideRefusedWhileOfflineShowsTheUnreachableText() {
		sync.sharedDir(dir()).state(SyncState.OFFLINE);
		ConnectFlow failing = flowOver(syncFailingOn("decideConnect", new IllegalStateException("Keine Verbindung wartet")));

		failing.decide(ConnectMode.MERGE);

		assertEquals(List.of("showProgress", "showHint:" + StoreMessages.unreachable(dir()) + ":null"), screenLog);
	}

	@Test
	void decideRefusedWhileOnlineShowsTheDeveloperMessage() {
		sync.sharedDir(dir()).state(SyncState.AKTUELL);
		ConnectFlow failing = flowOver(syncFailingOn("decideConnect", new IllegalStateException("Keine Verbindung wartet")));

		failing.decide(ConnectMode.MERGE);

		assertEquals(List.of("showProgress", "showHint:Keine Verbindung wartet:null"), screenLog);
	}

	@Test
	void progressBeforeTheFailureStillEndsTheRun() {
		flow.connectByButton(dir());
		sync.progress(0.2);

		sync.outcome(new ConnectOutcome(Kind.FEHLER, false, null, "kaputt"));
		assertEquals(List.of("showProgress", "showHint:kaputt:null"), screenLog);

		sync.progress(null);
		assertEquals(List.of("showProgress", "showHint:kaputt:null", "finished"), screenLog);
	}

	@Test
	void cancelDecisionWithoutRunningFlowStillDisconnects() {
		flow.cancelDecision();

		assertEquals(List.of("disconnect"), sync.calls());
		assertEquals(List.of("null"), saves);
		assertEquals(List.of("cancelled"), screenLog);
	}

	@Test
	void abortedOutcomeAfterCancelDecisionDoesNotCancelTwice() {
		flow.connectByButton(dir());
		sync.outcome(new ConnectOutcome(Kind.ENTSCHEIDUNG, true, null, null));
		flow.decide(ConnectMode.MERGE);

		flow.cancelDecision();
		sync.outcome(outcome(Kind.ABGEBROCHEN));

		assertEquals(1, screenLog.stream().filter("cancelled"::equals).count());
	}

	@Test
	void flowCanRunAgainAfterItEnded() {
		flow.connectByButton(dir());
		sync.outcome(outcome(Kind.AKTUELL));

		flow.connectByButton(dir());
		sync.outcome(outcome(Kind.AKTUELL));

		assertEquals(2, sync.calls().stream().filter(call -> call.startsWith("connect:")).count());
		assertEquals(2, screenLog.stream().filter("finished"::equals).count());
	}

	private final class RecordingScreen implements ConnectFlow.Screen {

		@Override
		public void showProgress() {
			screenLog.add("showProgress");
		}

		@Override
		public void checkFailed(String text) {
			screenLog.add("checkFailed:" + text);
		}

		@Override
		public void askDecision(String dir, boolean setAsideAllowed) {
			screenLog.add("askDecision:" + dir + ":" + setAsideAllowed);
		}

		@Override
		public void showHint(String message, Path setAsidePath) {
			screenLog.add("showHint:" + message + ":" + setAsidePath);
		}

		@Override
		public void hideHint() {
			screenLog.add("hideHint");
		}

		@Override
		public void finished() {
			screenLog.add("finished");
		}

		@Override
		public void cancelled() {
			screenLog.add("cancelled");
		}
	}
}
