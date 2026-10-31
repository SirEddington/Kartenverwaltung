package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncListener;
import de.eltviller_carneval_verein.karten.store.SyncState;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.validation.Severity;

class SharedFolderEventsTest {

	private static final String DIR = "D:\\Nextcloud\\Karten";
	private static final UnitKey UNIT = UnitKey.presentation("e1", "p1");

	private final List<String> live = new ArrayList<>();
	private final List<String> ui = new ArrayList<>();
	private final List<String> screen = new ArrayList<>();
	private final FakeConnection connection = new FakeConnection();
	private boolean unreachableOpen;
	private boolean guardAllows = true;
	private Function<UnitKey, String> labels = unit -> "Gala › Samstag";
	private int syncChanged;
	/** Die Einheiten, die der Empfänger der Sperren bekam (mit Zustand). */
	private final List<String> lockedReported = new ArrayList<>();
	/** Die Antworten der zuletzt gezeigten Frage. */
	private Consumer<ConnectMode> shownDecide;
	private Runnable shownCancel;

	private final ConnectFlow flow = new ConnectFlow(connection, Runnable::run, Runnable::run, new RecordingScreen(),
			() -> guardAllows, dir -> {
			}, Path.of("appdata"), path -> true);

	private final SharedFolderEvents events = new SharedFolderEvents(new RecordingLive(), unit -> labels.apply(unit),
			new RecordingUi(), flow, () -> syncChanged++, (unit, status) -> lockedReported.add(unit.relativePath() + ":" + status));

	@Test
	void unitsChangedAndErrorsReachLiveUpdates() {
		UnitKey unit = UnitKey.event("e1");
		VersionId version = VersionId.of("00000000001-abcdefgh-0001");
		events.unitsChanged(Set.of(unit));
		events.conflictsChanged();
		events.error("SYS-012: Datei");
		events.lost(unit, List.of(version), version);
		events.pending(unit, version, "Kasse 2");

		assertEquals(List.of("unitsChanged:" + Set.of(unit), "conflictsChanged", "error:SYS-012: Datei",
				"lost:" + unit, "pending:Kasse 2"), live);
		assertEquals(List.of(), ui);
	}

	@Test
	void unreachablePopupIsShownOnceWhileOpen() {
		events.unreachable(DIR);
		unreachableOpen = true;
		events.unreachable(DIR);

		assertEquals(List.of("showUnreachable:" + StoreMessages.unreachable(DIR)), ui);

		unreachableOpen = false;
		events.unreachable(DIR);

		assertEquals(2, ui.size(), "ist das Popup geschlossen, erscheint es beim nächsten Ausfall wieder");
	}

	@Test
	void reconnectedShowsAnInfoInTheFooter() {
		events.reconnected();

		assertEquals(List.of("footer:INFO:Wieder verbunden. Vorläufige Verkäufe werden hochgeladen."), ui);
		assertEquals(1, syncChanged);
	}

	@Test
	void duplicateMachineOpensAPopup() {
		events.duplicateMachine("SYS-011: Neue Kennung");

		assertEquals(List.of("showDuplicateMachine:SYS-011: Neue Kennung"), ui);
	}

	@Test
	void skippedUsesLabelAndSubtreeSuffix() {
		events.skipped(UNIT, false, "IOException: Zugriff verweigert");
		events.skipped(UNIT, true, "IOException: Zugriff verweigert");

		assertEquals(List.of("footer:WARNING:Nicht abgeglichen: Gala › Samstag",
				"footer:WARNING:Nicht abgeglichen: Gala › Samstag mit allem darunter"), ui);
		assertEquals(2, syncChanged, "Info-Fenster und Hinweis erfahren davon");
	}

	@Test
	void skippedFallsBackToPathWithoutLabel() {
		labels = unit -> null;

		events.skipped(UNIT, false, "x");

		assertEquals(List.of("footer:WARNING:Nicht abgeglichen: " + UNIT.relativePath()), ui);
	}

	@Test
	void skippedFallsBackToPathWhenTheLabelCannotBeDerived() {
		labels = unit -> {
			throw new IllegalStateException("kein Verlauf");
		};

		events.skipped(UNIT, true, "x");

		assertEquals(List.of("footer:WARNING:Nicht abgeglichen: " + UNIT.relativePath() + " mit allem darunter"), ui);
		assertEquals(1, syncChanged);
	}

	@Test
	void lockedUsesLabelInsteadOfPath() {
		events.locked(UNIT, UnitState.Status.LOCKED_DAMAGED);
		events.locked(UNIT, UnitState.Status.LOCKED_FORMAT);

		assertEquals(List.of("footer:ERROR:" + StoreMessages.damaged("Gala › Samstag"),
				"footer:ERROR:" + StoreMessages.newerFormat("Gala › Samstag")), ui);
		assertFalse(ui.get(0).contains(UNIT.relativePath()));
	}

	@Test
	void lockedShowsOnlyTheFooterAndTellsTheLinkWhichUnit() {
		events.locked(UNIT, UnitState.Status.LOCKED_DAMAGED);
		events.locked(UNIT, UnitState.Status.LOCKED_FORMAT);

		assertTrue(ui.stream().allMatch(entry -> entry.startsWith("footer:")),
				"kein Dialog: Mit dem Link in der Fußzeile braucht es keinen");
		assertEquals(List.of(UNIT.relativePath() + ":LOCKED_DAMAGED", UNIT.relativePath() + ":LOCKED_FORMAT"),
				lockedReported);
	}

	@Test
	void lockedWithoutLabelShowsThePath() {
		labels = unit -> null;

		events.locked(UNIT, UnitState.Status.LOCKED_DAMAGED);

		assertEquals(List.of("footer:ERROR:" + StoreMessages.damaged(UNIT.relativePath())), ui);
	}

	@Test
	void lockedWithAnOrdinaryStatusShowsNothing() {
		events.locked(UNIT, UnitState.Status.OK);

		assertEquals(List.of(), ui);
		assertEquals(List.of(), lockedReported);
	}

	@Test
	void failingReceiversDoNotBreakTheService() {
		SharedFolderEvents failing = new SharedFolderEvents(new RecordingLive(), unit -> null, new RecordingUi(), flow,
				() -> {
					throw new IllegalStateException("Info-Fenster");
				}, (unit, status) -> {
					throw new IllegalStateException("Link");
				});

		failing.skipped(UNIT, false, "x");
		failing.locked(UNIT, UnitState.Status.LOCKED_FORMAT);
		failing.reconnected();

		assertEquals(3, ui.size());
	}

	@Test
	void decisionDuringButtonFlowGoesToItsDialog() {
		flow.connectAtStart(DIR, ConnectMode.ASK);

		events.connectDecisionNeeded(DIR, true);

		assertEquals(List.of("showProgress", "askDecision:" + DIR + ":true"), screen);
		assertEquals(List.of(), ui, "kein zweiter Dialog");
	}

	@Test
	void decisionWithoutFlowOpensTheQuestionAndTheAnswerStartsAFlow() {
		connection.sharedDir(DIR);

		events.connectDecisionNeeded(DIR, true);

		assertEquals(List.of("askDecision:" + DIR + ":true"), ui);
		assertEquals(List.of(), screen);

		shownDecide.accept(ConnectMode.SET_ASIDE);

		assertEquals(List.of("decide:SET_ASIDE"), connection.calls());
		assertEquals(List.of("showProgress"), screen, "Ladeansicht bis zum Ergebnis");
		assertTrue(flow.running());
	}

	@Test
	void cancellingTheQuestionWithoutFlowDisconnects() {
		connection.sharedDir(DIR);
		events.connectDecisionNeeded(DIR, false);

		shownCancel.run();

		assertEquals(List.of("disconnect"), connection.calls());
	}

	@Test
	void refusedLeaveGuardOpensTheQuestionAgain() {
		connection.sharedDir(DIR);
		events.connectDecisionNeeded(DIR, true);
		guardAllows = false;

		shownDecide.accept(ConnectMode.MERGE);

		assertEquals(List.of("askDecision:" + DIR + ":true", "askDecision:" + DIR + ":true"), ui);
		assertEquals(List.of(), connection.calls());
		assertFalse(flow.running());
	}

	@Test
	void lastFailureIsTheLastSyncFailedText() {
		assertNull(events.lastFailure());

		String first = StoreMessages.syncFailed(DIR, "IOException: eins");
		String second = StoreMessages.syncFailed(DIR, "IOException: zwei");
		events.error(first);
		events.error(second);
		events.error(StoreMessages.contentMismatch("x.json"));

		assertEquals(second, events.lastFailure(), "SYS-012 ist keine Störung des Abgleichs");
		assertEquals(List.of("error:" + first, "error:" + second, "error:" + StoreMessages.contentMismatch("x.json")), live);
	}

	@Test
	void lastFailureEndsWithTheReturn() {
		events.error(StoreMessages.syncFailed(DIR, "IOException: eins"));

		events.reconnected();

		assertNull(events.lastFailure());
	}

	@Test
	void errorSetsCauseAndNotifies() {
		events.error(StoreMessages.syncFailed(DIR, "IOException: eins"));

		assertEquals(1, syncChanged, "die Statuszeile erfährt die Ursache, auch wenn sie nach dem Zustand kommt");
		events.error(StoreMessages.contentMismatch("x.json"));
		assertEquals(1, syncChanged, "SYS-012 ändert die Ursache nicht");
	}

	@Test
	void causeSurvivesAnOutage() {
		events.watch(connection);
		String failure = StoreMessages.syncFailed(DIR, "IOException: eins");
		connection.state(SyncState.FEHLER);
		events.error(failure);

		connection.state(SyncState.OFFLINE);
		assertEquals(failure, events.lastFailure());
		connection.state(SyncState.FEHLER);
		assertEquals(failure, events.lastFailure(), "der Speicher meldet denselben Text nicht noch einmal");

		connection.state(SyncState.AUSSTEHEND);
		assertNull(events.lastFailure());
	}

	@Test
	void causeEndsWhenTheConnectionEnds() {
		events.watch(connection);
		events.error(StoreMessages.syncFailed(DIR, "IOException: eins"));

		connection.state(SyncState.ENTSCHEIDUNG);
		assertEquals(StoreMessages.syncFailed(DIR, "IOException: eins"), events.lastFailure());
		connection.state(SyncState.AUS);

		assertNull(events.lastFailure());
	}

	@Test
	void decisionIsRememberedUntilStateLeavesEntscheidung() {
		events.watch(connection);
		connection.state(SyncState.ENTSCHEIDUNG);
		connection.sharedDir(DIR);
		events.connectDecisionNeeded(DIR, false);
		ui.clear();

		events.askDecisionAgain();
		assertEquals(List.of("askDecision:" + DIR + ":false"), ui);

		connection.state(SyncState.AKTUELL);
		ui.clear();
		events.askDecisionAgain();
		assertEquals(List.of(), ui, "die Entscheidung ist gefallen, es gibt nichts mehr zu fragen");
	}

	@Test
	void askDecisionAgainReopensTheQuestion() {
		connection.sharedDir(DIR);
		events.connectDecisionNeeded(DIR, true);
		shownDecide = null;
		ui.clear();

		events.askDecisionAgain();

		assertEquals(List.of("askDecision:" + DIR + ":true"), ui);
		shownDecide.accept(ConnectMode.MERGE);
		assertEquals(List.of("decide:MERGE"), connection.calls(), "die Antwort geht wie bei der ersten Frage an den Ablauf");
	}

	@Test
	void askDecisionAgainDuringAFlowGoesToItsDialog() {
		connection.sharedDir(DIR);
		events.connectDecisionNeeded(DIR, true);
		ui.clear();
		flow.connectAtStart(DIR, ConnectMode.ASK);
		screen.clear();

		events.askDecisionAgain();

		assertEquals(List.of("askDecision:" + DIR + ":true"), screen);
		assertEquals(List.of(), ui);
	}

	@Test
	void askDecisionAgainWithoutRememberedDecisionDoesNothing() {
		events.askDecisionAgain();

		assertEquals(List.of(), ui);
		assertEquals(List.of(), screen);
	}

	@Test
	void decisionStateAtInstallIsOfferedWithSetAsideAllowed() {
		connection.sharedDir(DIR);
		connection.state(SyncState.ENTSCHEIDUNG);

		events.watch(connection);
		events.askDecisionAgain();

		assertEquals(List.of("askDecision:" + DIR + ":true"), ui);
	}

	@Test
	void rememberedDecisionIsNotOverwrittenAtInstall() {
		connection.sharedDir(DIR);
		connection.state(SyncState.ENTSCHEIDUNG);
		events.connectDecisionNeeded(DIR, false);
		ui.clear();

		events.watch(connection);
		events.askDecisionAgain();

		assertEquals(List.of("askDecision:" + DIR + ":false"), ui);
	}

	@Test
	void syncChangesAreReportedWhenTheConnectionChanges() {
		events.watch(connection);

		connection.state(SyncState.OFFLINE);
		connection.state(SyncState.AKTUELL);
		connection.complete(false);
		connection.lastSync(java.time.Instant.parse("2026-10-31T10:00:00Z"));

		assertEquals(4, syncChanged);
	}

	@Test
	void lastFailureEndsWhenTheStateRecovers() {
		events.watch(connection);
		connection.state(SyncState.FEHLER);
		events.error(StoreMessages.syncFailed(DIR, "IOException: eins"));
		assertEquals(StoreMessages.syncFailed(DIR, "IOException: eins"), events.lastFailure());

		connection.state(SyncState.AKTUELL);

		assertNull(events.lastFailure());
	}

	/** Was {@code LiveUpdates.listener()} bekäme. */
	private final class RecordingLive implements SyncListener {

		@Override
		public void unitsChanged(Set<UnitKey> units) {
			live.add("unitsChanged:" + units);
		}

		@Override
		public void conflictsChanged() {
			live.add("conflictsChanged");
		}

		@Override
		public void error(String message) {
			live.add("error:" + message);
		}

		@Override
		public void lost(UnitKey unit, List<VersionId> mine, VersionId winner) {
			live.add("lost:" + unit);
		}

		@Override
		public void pending(UnitKey unit, VersionId version, String machineName) {
			live.add("pending:" + machineName);
		}
	}

	private final class RecordingUi implements SharedFolderEvents.Ui {

		@Override
		public boolean unreachableOpen() {
			return unreachableOpen;
		}

		@Override
		public void showUnreachable(String text) {
			ui.add("showUnreachable:" + text);
		}

		@Override
		public void showDuplicateMachine(String text) {
			ui.add("showDuplicateMachine:" + text);
		}

		@Override
		public void askDecision(String dir, boolean setAsideAllowed, Consumer<ConnectMode> decide, Runnable cancel) {
			ui.add("askDecision:" + dir + ":" + setAsideAllowed);
			shownDecide = decide;
			shownCancel = cancel;
		}

		@Override
		public void footer(Severity severity, String text) {
			ui.add("footer:" + severity + ":" + text);
		}
	}

	private final class RecordingScreen implements ConnectFlow.Screen {

		@Override
		public void showProgress() {
			screen.add("showProgress");
		}

		@Override
		public void checkFailed(String text) {
			screen.add("checkFailed:" + text);
		}

		@Override
		public void askDecision(String dir, boolean setAsideAllowed) {
			screen.add("askDecision:" + dir + ":" + setAsideAllowed);
		}

		@Override
		public void showHint(String message, Path setAsidePath) {
			screen.add("showHint:" + message);
		}

		@Override
		public void hideHint() {
			screen.add("hideHint");
		}

		@Override
		public void finished() {
			screen.add("finished");
		}

		@Override
		public void cancelled() {
			screen.add("cancelled");
		}
	}
}
