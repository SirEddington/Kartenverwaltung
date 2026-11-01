package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.store.SyncState;

class SyncHintTest {

	private static final Duration NONE = Duration.ZERO;

	private static SyncHint hint(SyncState state, Duration pendingFor, boolean complete, Double progress,
			boolean firstRunEnded, int skipped) {
		return SyncHint.of(state, pendingFor, complete, progress, firstRunEnded, false, skipped).orElseThrow();
	}

	@Test
	void catchingUpShowsRankSeven() {
		assertHint(7, "Verkäufe anderer Rechner kommen noch an",
				SyncHint.of(SyncState.AKTUELL, NONE, true, null, false, true, 0).orElseThrow());
	}

	@Test
	void pendingWinsOverCatchingUpAndCatchingUpOverSkipped() {
		assertHint(6, "Hochladen verzögert: Verkäufe vorläufig",
				SyncHint.of(SyncState.AUSSTEHEND, Duration.ofSeconds(31), true, null, false, true, 2).orElseThrow());
		assertHint(7, "Verkäufe anderer Rechner kommen noch an",
				SyncHint.of(SyncState.AKTUELL, NONE, true, null, false, true, 2).orElseThrow());
	}

	@Test
	void incompleteFirstRunAndOfflineWinOverCatchingUp() {
		assertHint(4, "Noch nicht vollständig abgeglichen (10 %)",
				SyncHint.of(SyncState.AKTUELL, NONE, false, 0.1, false, true, 0).orElseThrow());
		assertHint(2, "Offline: Verkäufe vorläufig",
				SyncHint.of(SyncState.OFFLINE, NONE, true, null, false, true, 0).orElseThrow());
		assertEquals(Optional.empty(), SyncHint.of(SyncState.AUS, NONE, true, null, false, true, 0));
	}

	private static void assertHint(int rank, String text, SyncHint actual) {
		assertEquals(rank, actual.rank());
		assertEquals(text, actual.text());
	}

	@Test
	void decisionIsRankOne() {
		assertHint(1, "Gemeinsamer Ordner: Entscheidung nötig",
				hint(SyncState.ENTSCHEIDUNG, NONE, true, null, false, 0));
	}

	@Test
	void offlineIsRankTwo() {
		assertHint(2, "Offline: Verkäufe vorläufig", hint(SyncState.OFFLINE, NONE, true, null, false, 0));
	}

	@Test
	void failureIsRankThree() {
		assertHint(3, "Abgleich gestört: Verkäufe vorläufig", hint(SyncState.FEHLER, NONE, true, null, false, 0));
	}

	@Test
	void incompleteFirstRunShowsPercentWhileRunning() {
		assertHint(4, "Noch nicht vollständig abgeglichen (42 %)", hint(SyncState.AKTUELL, NONE, false, 0.429, false, 0));
		// auch ein späterer Lauf nach einem beendeten (nach 10 Minuten) zeigt wieder den Fortschritt
		assertHint(4, "Noch nicht vollständig abgeglichen (0 %)", hint(SyncState.AKTUELL, NONE, false, 0.0, true, 2));
		assertHint(4, "Noch nicht vollständig abgeglichen (100 %)", hint(SyncState.AUSSTEHEND, NONE, false, 1.0, false, 0));
	}

	@Test
	void runEndedWithSkippedEntriesShowsRankFive() {
		assertHint(5, "Noch nicht vollständig abgeglichen: Einträge übersprungen",
				hint(SyncState.AKTUELL, NONE, false, null, true, 3));
	}

	@Test
	void pendingShowsOnlyAfterThirtySeconds() {
		assertEquals(Optional.empty(), SyncHint.of(SyncState.AUSSTEHEND, Duration.ofSeconds(29), true, null, false, false, 0));
		assertHint(6, "Hochladen verzögert: Verkäufe vorläufig",
				hint(SyncState.AUSSTEHEND, Duration.ofSeconds(30), true, null, false, 0));
		assertHint(6, "Hochladen verzögert: Verkäufe vorläufig",
				hint(SyncState.AUSSTEHEND, Duration.ofMinutes(5), true, null, false, 0));
	}

	@Test
	void skippedEntriesAloneShowRankEightWithPlural() {
		assertHint(8, "1 Eintrag nicht abgeglichen", hint(SyncState.AKTUELL, NONE, true, null, false, 1));
		assertHint(8, "3 Einträge nicht abgeglichen", hint(SyncState.AKTUELL, NONE, true, null, false, 3));
	}

	@Test
	void nothingWhenOffOrCurrentOrPendingBriefly() {
		assertEquals(Optional.empty(), SyncHint.of(SyncState.AUS, NONE, true, null, false, false, 0));
		assertEquals(Optional.empty(), SyncHint.of(SyncState.AKTUELL, NONE, true, null, false, false, 0));
		assertEquals(Optional.empty(), SyncHint.of(SyncState.AUSSTEHEND, Duration.ofSeconds(10), true, null, false, false, 0));
		assertEquals(Optional.empty(), SyncHint.of(null, NONE, true, null, false, false, 0));
	}

	@Test
	void singleModeShowsNothingEvenWithStaleValues() {
		assertEquals(Optional.empty(), SyncHint.of(SyncState.AUS, NONE, false, 0.5, false, true, 2));
	}

	@Test
	void offlineWinsOverIncompleteFirstRun() {
		assertHint(2, "Offline: Verkäufe vorläufig", hint(SyncState.OFFLINE, NONE, false, 0.3, false, 4));
		assertHint(3, "Abgleich gestört: Verkäufe vorläufig", hint(SyncState.FEHLER, NONE, false, null, true, 4));
		assertHint(1, "Gemeinsamer Ordner: Entscheidung nötig", hint(SyncState.ENTSCHEIDUNG, NONE, false, 0.3, false, 4));
	}

	@Test
	void noSkippedHintBetweenConnectAndRunStart() {
		// complete false, der Lauf hat noch nicht begonnen (progress null), keiner ging zu Ende: Rang 4 ohne Prozent
		assertHint(4, "Noch nicht vollständig abgeglichen", hint(SyncState.AKTUELL, NONE, false, null, false, 0));
		assertHint(4, "Noch nicht vollständig abgeglichen", hint(SyncState.AUSSTEHEND, NONE, false, null, false, 5));
	}

	@Test
	void incompleteFirstRunWinsOverPendingAndSkipped() {
		assertHint(4, "Noch nicht vollständig abgeglichen (10 %)",
				hint(SyncState.AUSSTEHEND, Duration.ofMinutes(2), false, 0.1, false, 2));
		assertHint(5, "Noch nicht vollständig abgeglichen: Einträge übersprungen",
				hint(SyncState.AUSSTEHEND, Duration.ofMinutes(2), false, null, true, 2));
	}

	@Test
	void pendingWinsOverSkipped() {
		assertHint(6, "Hochladen verzögert: Verkäufe vorläufig",
				hint(SyncState.AUSSTEHEND, Duration.ofSeconds(31), true, null, false, 2));
	}
}
