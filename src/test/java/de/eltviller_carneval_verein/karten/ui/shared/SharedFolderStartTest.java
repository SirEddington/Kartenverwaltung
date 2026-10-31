package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderStart.Plan;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderStart.StartResult;

/**
 * Die Starttabelle (KARV-103, Abschnitt E): je Zeile ein Test für {@link SharedFolderStart#plan}, dazu der Ablauf von
 * {@link SharedFolderStart#run} mit dem Test-Doppel.
 */
class SharedFolderStartTest {

	private static final String X = "\\\\server\\karten";
	private static final String Y = "\\\\anderer\\ordner";

	@Test
	void emptySettingsWithoutMarkerStartsLocally() {
		Plan plan = SharedFolderStart.plan(null, Optional.empty(), true);

		assertNull(plan.startupDir());
		assertNull(plan.connectDir());
		assertNull(plan.mode());
		assertFalse(plan.clearMarker());
	}

	@Test
	void blankSettingsCountAsEmpty() {
		Plan plan = SharedFolderStart.plan("   ", Optional.empty(), true);

		assertNull(plan.startupDir());
		assertNull(plan.connectDir());
		assertFalse(plan.clearMarker());
	}

	@Test
	void emptySettingsWithMarkerStartsLocallyAndClearsMarker() {
		Plan plan = SharedFolderStart.plan("", Optional.of(X), true);

		assertNull(plan.startupDir());
		assertNull(plan.connectDir());
		assertTrue(plan.clearMarker());
	}

	@Test
	void emptySettingsWithIncompleteMarkerClearsMarkerToo() {
		Plan plan = SharedFolderStart.plan(null, Optional.of(X), false);

		assertNull(plan.startupDir());
		assertNull(plan.connectDir());
		assertTrue(plan.clearMarker());
	}

	@Test
	void matchingCompleteMarkerStartsWithTheFolder() {
		Plan plan = SharedFolderStart.plan(X, Optional.of(X), true);

		assertEquals(X, plan.startupDir());
		assertNull(plan.connectDir());
		assertNull(plan.mode());
		assertFalse(plan.clearMarker());
	}

	@Test
	void incompleteMarkerContinuesWithMergeAfterLocalStartup() {
		Plan plan = SharedFolderStart.plan(X, Optional.of(X), false);

		assertNull(plan.startupDir());
		assertEquals(X, plan.connectDir());
		assertEquals(ConnectMode.MERGE, plan.mode());
		assertFalse(plan.clearMarker());
	}

	@Test
	void missingMarkerAsksAfterLocalStartup() {
		Plan plan = SharedFolderStart.plan(X, Optional.empty(), true);

		assertNull(plan.startupDir());
		assertEquals(X, plan.connectDir());
		assertEquals(ConnectMode.ASK, plan.mode());
		assertFalse(plan.clearMarker());
	}

	@Test
	void otherFolderInMarkerAsksAfterLocalStartup() {
		Plan plan = SharedFolderStart.plan(X, Optional.of(Y), true);

		assertNull(plan.startupDir());
		assertEquals(X, plan.connectDir());
		assertEquals(ConnectMode.ASK, plan.mode());
		assertFalse(plan.clearMarker());
	}

	@Test
	void otherFolderWithIncompleteMarkerStillAsks() {
		Plan plan = SharedFolderStart.plan(X, Optional.of(Y), false);

		assertEquals(ConnectMode.ASK, plan.mode());
		assertEquals(X, plan.connectDir());
	}

	@Test
	void trailingWhitespaceInSettingsMatchesMarker() {
		Plan plan = SharedFolderStart.plan("  " + X + " ", Optional.of(X), true);

		assertEquals(X, plan.startupDir());
		assertNull(plan.connectDir());
	}

	@Test
	void trailingWhitespaceInMarkerMatchesSettings() {
		Plan plan = SharedFolderStart.plan(X, Optional.of(X + "  "), false);

		assertEquals(ConnectMode.MERGE, plan.mode());
		assertEquals(X, plan.connectDir());
	}

	@Test
	void startupAlwaysRunsBeforeConnect() {
		FakeConnection sync = new FakeConnection().marker(Y, true);

		StartResult result = SharedFolderStart.run(sync, X, p -> {
		});

		assertEquals(List.of("startup:null"), sync.calls(), "verbunden wird erst danach, vom Aufrufer, mit ConnectFlow");
		assertEquals(X, result.plan().connectDir());
		assertEquals(ConnectMode.ASK, result.plan().mode());
	}

	@Test
	void runStartsWithTheFolderWhenMarkerIsComplete() {
		FakeConnection sync = new FakeConnection().marker(X, true);

		StartResult result = SharedFolderStart.run(sync, X, p -> {
		});

		assertEquals(List.of("startup:" + X), sync.calls());
		assertTrue(result.reachable());
		assertNull(result.plan().connectDir());
	}

	@Test
	void runReportsAnUnreachableFolder() {
		FakeConnection sync = new FakeConnection().marker(X, true).startupResult(false);

		StartResult result = SharedFolderStart.run(sync, X, p -> {
		});

		assertFalse(result.reachable());
	}

	@Test
	void runClearsTheMarkerAfterStartupWhenSettingsAreEmpty() {
		FakeConnection sync = new FakeConnection().marker(X, true);

		StartResult result = SharedFolderStart.run(sync, "", p -> {
		});

		assertEquals(List.of("startup:null", "disconnect"), sync.calls());
		assertTrue(result.plan().clearMarker());
	}

	@Test
	void runWithoutAnyFolderOnlyStartsLocally() {
		FakeConnection sync = new FakeConnection();

		StartResult result = SharedFolderStart.run(sync, null, p -> {
		});

		assertEquals(List.of("startup:null"), sync.calls());
		assertTrue(result.reachable());
		assertNull(result.plan().connectDir());
	}
}
