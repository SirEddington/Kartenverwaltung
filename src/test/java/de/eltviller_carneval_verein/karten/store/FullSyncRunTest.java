package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullSyncRunTest {

	/** Eine Frist, die nie erreicht wird. */
	private static final Instant NEVER = Instant.MAX;
	/** Eine Frist, die immer schon vorbei ist: Ein Aufruf von {@code step} erledigt genau einen Block. */
	private static final Instant ALWAYS = Instant.MIN;

	@TempDir
	Path dir;

	private final TestClock clock = new TestClock();
	private VersionStore local;
	private VersionStore shared;
	private FullSyncRun run;

	/**
	 * Zeichnet die Listen auf, mit denen {@code sync} aufgerufen wird; wirft auf Wunsch einmal und stellt auf Wunsch die
	 * Uhr je Aufruf vor (die Dauer eines Blocks).
	 */
	private final class Recorder implements FullSyncRun.Work {

		final List<List<UnitKey>> calls = new ArrayList<>();
		IOException failOnce;
		Duration cost = Duration.ZERO;

		@Override
		public void sync(List<UnitKey> units) throws IOException {
			if (failOnce != null) {
				IOException e = failOnce;
				failOnce = null;
				throw e;
			}
			calls.add(List.copyOf(units));
			clock.advance(cost);
		}
	}

	@BeforeEach
	void setUp() {
		FileLayer files = new NioFileLayer();
		local = new VersionStore(dir.resolve("local").resolve("data"), files);
		shared = new VersionStore(dir.resolve("shared").resolve("data"), files);
		run = new FullSyncRun(local, shared, clock);
	}

	private static void folder(VersionStore store, String relativePath) throws IOException {
		Files.createDirectories(store.resolve(relativePath));
	}

	private static UnitKey presentation(String event, String presentation) {
		return UnitKey.presentation(event, presentation);
	}

	private static UnitKey booking(String event, String presentation, String seat) {
		return UnitKey.booking(event, presentation, seat);
	}

	private static String seat(int n) {
		return String.format("s%04d", n);
	}

	private void seats(VersionStore store, int from, int toInclusive) throws IOException {
		for (int n = from; n <= toInclusive; n++) {
			folder(store, "events/e1/presentations/p1/bookings/" + seat(n));
		}
	}

	@Test
	void sectionsAreHallBundlesThenHeadAndPresentationsPerEvent() throws IOException {
		int halls = FullSyncRun.HALL_BUNDLE + 1;
		for (int n = 1; n <= halls; n++) {
			folder(local, String.format("halls/h%03d", n));
		}
		folder(local, "events/e2/presentations/p1");
		folder(local, "events/e1/presentations/pb");
		folder(local, "events/e1/presentations/pa/bookings/s1");
		Recorder work = new Recorder();

		run.begin();
		assertTrue(run.step(NEVER, work));

		List<UnitKey> firstBundle = new ArrayList<>();
		for (int n = 1; n <= FullSyncRun.HALL_BUNDLE; n++) {
			firstBundle.add(UnitKey.hall(String.format("h%03d", n)));
		}
		List<List<UnitKey>> expected = List.of(
				firstBundle,
				List.of(UnitKey.hall(String.format("h%03d", halls))),
				List.of(UnitKey.event("e1")),
				List.of(presentation("e1", "pa"), booking("e1", "pa", "s1")),
				List.of(presentation("e1", "pb")),
				List.of(UnitKey.event("e2")),
				List.of(presentation("e2", "p1")));
		assertEquals(expected, work.calls);
		assertTrue(run.finished());
		assertEquals(1.0, run.progress());
	}

	@Test
	void unionOfBothSidesIsCovered() throws IOException {
		folder(shared, "events/e1/presentations/p1/bookings/s2");
		folder(local, "events/e1/presentations/p1/bookings/s1");
		folder(local, "halls/h1");
		folder(shared, "events/e0");
		Recorder work = new Recorder();

		run.begin();
		assertTrue(run.step(NEVER, work));

		assertEquals(List.of(
				List.of(UnitKey.hall("h1")),
				List.of(UnitKey.event("e0")),
				List.of(UnitKey.event("e1")),
				List.of(presentation("e1", "p1"), booking("e1", "p1", "s1"), booking("e1", "p1", "s2"))),
				work.calls);
	}

	@Test
	void largePresentationIsSplitIntoBlocksWithThePointer() throws IOException {
		int block = FullSyncRun.BOOKING_BLOCK;
		int tail = block / 2;
		seats(local, 1, 2 * block);
		seats(shared, 2 * block + 1, 2 * block + tail);
		Recorder work = new Recorder();
		UnitKey p1 = presentation("e1", "p1");
		run.begin();

		assertFalse(run.step(ALWAYS, work)); // Kopf des Events
		assertFalse(run.inProgress(p1));
		assertFalse(run.step(ALWAYS, work));
		assertEquals(2, work.calls.size());
		assertEquals(1 + block, work.calls.get(1).size());
		assertEquals(p1, work.calls.get(1).get(0));
		assertEquals(booking("e1", "p1", seat(1)), work.calls.get(1).get(1));
		assertEquals(booking("e1", "p1", seat(block)), work.calls.get(1).get(block));
		assertTrue(run.inProgress(p1));
		assertTrue(run.inProgress(booking("e1", "p1", seat(7))));
		assertFalse(run.inProgress(booking("e1", "p2", seat(7))));
		assertFalse(run.inProgress(UnitKey.event("e1")));

		assertFalse(run.step(ALWAYS, work));
		assertEquals(block, work.calls.get(2).size());
		assertEquals(booking("e1", "p1", seat(block + 1)), work.calls.get(2).get(0));
		assertEquals(booking("e1", "p1", seat(2 * block)), work.calls.get(2).get(block - 1));
		assertTrue(run.inProgress(p1));

		assertTrue(run.step(ALWAYS, work));
		assertEquals(tail, work.calls.get(3).size());
		assertEquals(booking("e1", "p1", seat(2 * block + 1)), work.calls.get(3).get(0));
		assertEquals(booking("e1", "p1", seat(2 * block + tail)), work.calls.get(3).get(tail - 1));
		assertFalse(run.inProgress(p1));
		assertTrue(run.finished());
	}

	@Test
	void bookingAddedAfterThePointerIsInTheNextBlock() throws IOException {
		int block = FullSyncRun.BOOKING_BLOCK;
		seats(local, 1, block + 20);
		Recorder work = new Recorder();
		run.begin();
		run.step(ALWAYS, work); // Kopf
		run.step(ALWAYS, work); // Vorstellung + die ersten Buchungen eines Blocks
		assertEquals(seat(block), work.calls.get(1).get(block).bookingDir());

		seats(shared, block + 21, block + 21); // erst nach dem ersten Block entstanden
		folder(local, "events/e1/presentations/p1/bookings/s0000"); // vor dem Zeiger: wartet auf den nächsten Lauf

		assertTrue(run.step(ALWAYS, work));
		List<UnitKey> last = work.calls.get(2);
		assertEquals(21, last.size());
		assertEquals(booking("e1", "p1", seat(block + 1)), last.get(0));
		assertEquals(booking("e1", "p1", seat(block + 21)), last.get(20));
		assertFalse(last.contains(booking("e1", "p1", "s0000")));
	}

	@Test
	void stepStopsWhenTimeIsUpButDoesAtLeastOneBlock() throws IOException {
		folder(local, "halls/h1");
		seats(local, 1, FullSyncRun.BOOKING_BLOCK + 20);
		Recorder work = new Recorder();
		run.begin();

		int steps = 0;
		boolean done = false;
		while (!done) {
			int before = work.calls.size();
			done = run.step(ALWAYS, work);
			steps++;
			assertEquals(before + 1, work.calls.size(), "je Aufruf genau ein Block");
			assertTrue(steps < 10);
		}
		// Halle, Kopf, Vorstellung + ein Block, der Rest
		assertEquals(4, steps);
		assertEquals(4, work.calls.size());
	}

	/** Fünf Events: Abschnitte mit je einer Einheit, deren Dauer der Test über {@link Recorder#cost} vorgibt. */
	private void fiveEventHeads() throws IOException {
		for (int n = 1; n <= 5; n++) {
			folder(local, "events/e" + n);
		}
	}

	@Test
	void furtherBlockStartsOnlyIfItFitsTheBudget() throws IOException {
		fiveEventHeads();
		Recorder work = new Recorder();
		work.cost = Duration.ofMillis(800);
		Instant deadline = clock.instant().plusSeconds(2);
		run.begin();

		assertFalse(run.step(deadline, work));

		// 0,8 s, dann 1,6 s; ein dritter Block endete bei 2,4 s und passt nicht mehr
		assertEquals(2, work.calls.size());
		assertEquals(Duration.ofMillis(1600), Duration.between(deadline.minusSeconds(2), clock.instant()));
	}

	@Test
	void blockEndingExactlyAtTheDeadlineStillFits() throws IOException {
		fiveEventHeads();
		Recorder work = new Recorder();
		work.cost = Duration.ofSeconds(1);
		run.begin();

		assertFalse(run.step(clock.instant().plusSeconds(2), work));

		assertEquals(2, work.calls.size());
	}

	@Test
	void blockLongerThanTheBudgetRunsAloneAndTheRunAdvances() throws IOException {
		fiveEventHeads();
		Recorder work = new Recorder();
		work.cost = Duration.ofSeconds(3);
		run.begin();

		int steps = 0;
		boolean done = false;
		while (!done) {
			int before = work.calls.size();
			done = run.step(clock.instant().plusSeconds(2), work);
			steps++;
			assertEquals(before + 1, work.calls.size(), "je Aufruf genau ein Block");
			assertTrue(steps < 10);
		}
		assertEquals(5, steps);
	}

	@Test
	void blocksOfALargePresentationFollowTheSameRule() throws IOException {
		int block = FullSyncRun.BOOKING_BLOCK;
		seats(local, 1, 3 * block);
		Recorder work = new Recorder();
		work.cost = Duration.ofMillis(900);
		run.begin();
		run.step(ALWAYS, work); // Kopf

		// Vorstellung + erster Block (0,9 s); der zweite endete bei 1,8 s und passt; der dritte nicht
		assertFalse(run.step(clock.instant().plusSeconds(2), work));
		assertEquals(3, work.calls.size());
		assertTrue(run.inProgress(presentation("e1", "p1")));
	}

	@Test
	void failedBlockIsRepeated() throws IOException {
		int block = FullSyncRun.BOOKING_BLOCK;
		seats(local, 1, block + 20);
		Recorder work = new Recorder();
		run.begin();
		run.step(ALWAYS, work); // Kopf
		work.failOnce = new IOException("Platte weg");

		assertThrows(IOException.class, () -> run.step(NEVER, work));
		assertFalse(run.inProgress(presentation("e1", "p1")));

		run.step(ALWAYS, work);
		assertEquals(2, work.calls.size());
		assertEquals(1 + block, work.calls.get(1).size());
		assertTrue(run.inProgress(presentation("e1", "p1")));

		work.failOnce = new IOException("Platte weg");
		assertThrows(IOException.class, () -> run.step(NEVER, work));
		assertTrue(run.inProgress(presentation("e1", "p1")));
		assertTrue(run.step(NEVER, work));
		assertEquals(3, work.calls.size());
		assertEquals(booking("e1", "p1", seat(block + 1)), work.calls.get(2).get(0));
		assertEquals(20, work.calls.get(2).size());
	}

	@Test
	void progressCountsSections() throws IOException {
		folder(local, "halls/h1");
		folder(local, "events/e1/presentations/p1");
		folder(local, "events/e1/presentations/p2");
		Recorder work = new Recorder();
		run.begin();

		// Halle, Kopf, p1, p2
		assertEquals(0.0, run.progress());
		assertFalse(run.finished());
		run.step(ALWAYS, work);
		assertEquals(0.25, run.progress());
		run.step(ALWAYS, work);
		assertEquals(0.5, run.progress());
		run.step(ALWAYS, work);
		assertEquals(0.75, run.progress());
		assertTrue(run.step(ALWAYS, work));
		assertEquals(1.0, run.progress());
		assertTrue(run.finished());
	}
}
