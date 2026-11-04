package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.store.SkippedUnit;
import de.eltviller_carneval_verein.karten.store.SyncState;
import de.eltviller_carneval_verein.karten.store.UnitKey;

class SyncStatusTest {

	/** Eine Uhr, die der Test vorstellt. */
	private static final class TestClock extends Clock {
		private Instant now = Instant.parse("2026-10-31T09:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final FakeConnection sync = new FakeConnection();
	private final TestClock clock = new TestClock();
	private int changes;
	private final SyncStatus status = new SyncStatus(sync, clock, () -> changes++);

	private int rank() {
		return status.hint().map(SyncHint::rank).orElse(0);
	}

	@Test
	void pendingCountsFromTheChangeToPending() {
		sync.state(SyncState.AKTUELL);
		assertEquals(0, rank());

		sync.state(SyncState.AUSSTEHEND);
		clock.advance(Duration.ofSeconds(29));
		assertEquals(0, rank());

		clock.advance(Duration.ofSeconds(1));
		assertEquals(6, rank());
	}

	@Test
	void catchingUpShowsItsHintWhileCurrent() {
		sync.state(SyncState.AKTUELL).catchingUp(true);
		assertEquals(7, rank());

		sync.catchingUp(false);
		assertEquals(0, rank());
	}

	@Test
	void pendingRestartsAfterBeingCurrentInBetween() {
		sync.state(SyncState.AUSSTEHEND);
		clock.advance(Duration.ofSeconds(40));
		assertEquals(6, rank());

		sync.state(SyncState.AKTUELL);
		sync.state(SyncState.AUSSTEHEND);
		assertEquals(0, rank());
		clock.advance(Duration.ofSeconds(30));
		assertEquals(6, rank());
	}

	@Test
	void pendingFromTheStartIsCountedFromTheFirstLook() {
		sync.state(SyncState.AUSSTEHEND);
		SyncStatus late = new SyncStatus(sync, clock, () -> {
		});

		assertEquals(Optional.empty(), late.hint());
		clock.advance(Duration.ofSeconds(30));
		assertEquals(6, late.hint().orElseThrow().rank());
		late.dispose();
	}

	@Test
	void skippedEntriesAreReadOnEveryCall() {
		sync.state(SyncState.AKTUELL);
		assertEquals(0, rank());

		sync.skipped(List.of(new SkippedUnit(UnitKey.event("e1"), true, "x")));
		assertEquals("1 Eintrag nicht abgeglichen", status.hint().orElseThrow().text());

		sync.skipped(List.of());
		assertEquals(0, rank());
	}

	@Test
	void runEndedIsKnownToEveryReaderAndReported() {
		sync.state(SyncState.AKTUELL).complete(false);
		sync.skipped(List.of(new SkippedUnit(UnitKey.event("e1"), false, "x")));
		assertEquals(4, rank(), "vor dem Beginn des Laufs ohne Prozent");
		assertEquals("Noch nicht vollständig abgeglichen", status.hint().orElseThrow().text());

		sync.progress(0.5);
		assertEquals("Noch nicht vollständig abgeglichen (50 %)", status.hint().orElseThrow().text());
		assertEquals(0, changes);

		sync.progress(null);
		assertEquals(1, changes, "das Ende des Laufs wird gemeldet");
		// eine später angelegte Anzeige liest dasselbe, sie startet keinen eigenen Watch
		assertEquals(5, rank());
		assertEquals(5, status.hint().orElseThrow().rank());
	}

	@Test
	void newConnectionStartsANewWatch() {
		sync.state(SyncState.AKTUELL).complete(false);
		sync.progress(0.5);
		sync.progress(null);
		assertEquals(5, rank());

		sync.complete(true);
		assertEquals(0, rank());

		// getrennt und neu verbunden: der Lauf von vorhin zählt nicht mehr
		sync.complete(false);
		assertEquals(4, rank());
		sync.progress(0.1);
		assertEquals(4, rank());
		sync.progress(null);
		assertEquals(5, rank());
	}

	@Test
	void startingWithAnIncompleteMarkerWatchesAtOnce() {
		FakeConnection other = new FakeConnection().state(SyncState.AKTUELL).marker("D:\\x", false).progress(0.2);
		SyncStatus restarted = new SyncStatus(other, clock, () -> {
		});

		assertEquals(4, restarted.hint().orElseThrow().rank());
		other.progress(null);
		assertEquals(5, restarted.hint().orElseThrow().rank());
		restarted.dispose();
	}

	@Test
	void onlyTheLatestTrackedViewIsRefreshed() {
		int[] first = { 0 };
		int[] second = { 0 };
		status.refreshTracked();
		status.track(() -> first[0]++);
		status.refreshTracked();
		assertEquals(1, first[0]);

		// eine neue Ansicht ersetzt die alte: Die alte wird nie wieder aufgefrischt
		status.track(() -> second[0]++);
		status.refreshTracked();
		status.refreshTracked();
		assertEquals(1, first[0]);
		assertEquals(2, second[0]);
	}

	@Test
	void aFailingTrackedViewDoesNotBreakTheTick() {
		status.track(() -> {
			throw new IllegalStateException("kaputt");
		});

		status.refreshTracked();
		status.track(null);
		status.refreshTracked();
	}

	@Test
	void disposeForgetsTheTrackedView() {
		int[] calls = { 0 };
		status.track(() -> calls[0]++);

		status.dispose();
		status.refreshTracked();

		assertEquals(0, calls[0]);
	}

	@Test
	void disposeRemovesTheListeners() {
		sync.state(SyncState.AKTUELL).complete(false);
		sync.progress(0.5);
		status.dispose();

		sync.progress(null);
		assertEquals(0, changes);
	}
}
