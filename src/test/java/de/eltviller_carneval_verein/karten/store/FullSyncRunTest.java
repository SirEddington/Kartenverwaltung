package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullSyncRunTest {

	private static final BooleanSupplier NEVER = () -> false;
	private static final BooleanSupplier ALWAYS = () -> true;

	@TempDir
	Path dir;

	private VersionStore local;
	private VersionStore shared;
	private FullSyncRun run;

	/** Zeichnet die Listen auf, mit denen {@code sync} aufgerufen wird; wirft auf Wunsch einmal. */
	private static final class Recorder implements FullSyncRun.Work {

		final List<List<UnitKey>> calls = new ArrayList<>();
		IOException failOnce;

		@Override
		public void sync(List<UnitKey> units) throws IOException {
			if (failOnce != null) {
				IOException e = failOnce;
				failOnce = null;
				throw e;
			}
			calls.add(List.copyOf(units));
		}
	}

	@BeforeEach
	void setUp() {
		FileLayer files = new NioFileLayer();
		local = new VersionStore(dir.resolve("local").resolve("data"), files);
		shared = new VersionStore(dir.resolve("shared").resolve("data"), files);
		run = new FullSyncRun(local, shared);
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
		seats(local, 1, 1000);
		seats(shared, 1001, 1200);
		Recorder work = new Recorder();
		UnitKey p1 = presentation("e1", "p1");
		run.begin();

		assertFalse(run.step(() -> true, work)); // Kopf des Events
		assertFalse(run.inProgress(p1));
		assertFalse(run.step(ALWAYS, work));
		assertEquals(2, work.calls.size());
		assertEquals(1 + FullSyncRun.BOOKING_BLOCK, work.calls.get(1).size());
		assertEquals(p1, work.calls.get(1).get(0));
		assertEquals(booking("e1", "p1", seat(1)), work.calls.get(1).get(1));
		assertEquals(booking("e1", "p1", seat(500)), work.calls.get(1).get(500));
		assertTrue(run.inProgress(p1));
		assertTrue(run.inProgress(booking("e1", "p1", seat(7))));
		assertFalse(run.inProgress(booking("e1", "p2", seat(7))));
		assertFalse(run.inProgress(UnitKey.event("e1")));

		assertFalse(run.step(ALWAYS, work));
		assertEquals(FullSyncRun.BOOKING_BLOCK, work.calls.get(2).size());
		assertEquals(booking("e1", "p1", seat(501)), work.calls.get(2).get(0));
		assertEquals(booking("e1", "p1", seat(1000)), work.calls.get(2).get(499));
		assertTrue(run.inProgress(p1));

		assertTrue(run.step(ALWAYS, work));
		assertEquals(200, work.calls.get(3).size());
		assertEquals(booking("e1", "p1", seat(1001)), work.calls.get(3).get(0));
		assertEquals(booking("e1", "p1", seat(1200)), work.calls.get(3).get(199));
		assertFalse(run.inProgress(p1));
		assertTrue(run.finished());
	}

	@Test
	void bookingAddedAfterThePointerIsInTheNextBlock() throws IOException {
		seats(local, 1, 600);
		Recorder work = new Recorder();
		run.begin();
		run.step(ALWAYS, work); // Kopf
		run.step(ALWAYS, work); // Vorstellung + s0001..s0500
		assertEquals(seat(500), work.calls.get(1).get(500).bookingDir());

		seats(shared, 601, 601); // erst nach dem ersten Block entstanden
		folder(local, "events/e1/presentations/p1/bookings/s0000"); // vor dem Zeiger: wartet auf den nächsten Lauf

		assertTrue(run.step(ALWAYS, work));
		List<UnitKey> last = work.calls.get(2);
		assertEquals(101, last.size());
		assertEquals(booking("e1", "p1", seat(501)), last.get(0));
		assertEquals(booking("e1", "p1", seat(601)), last.get(100));
		assertFalse(last.contains(booking("e1", "p1", "s0000")));
	}

	@Test
	void stepStopsWhenTimeIsUpButDoesAtLeastOneBlock() throws IOException {
		folder(local, "halls/h1");
		seats(local, 1, 600);
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
		// Halle, Kopf, Vorstellung + 500, 100
		assertEquals(4, steps);
		assertEquals(4, work.calls.size());
	}

	@Test
	void failedBlockIsRepeated() throws IOException {
		seats(local, 1, 600);
		Recorder work = new Recorder();
		run.begin();
		run.step(ALWAYS, work); // Kopf
		work.failOnce = new IOException("Platte weg");

		assertThrows(IOException.class, () -> run.step(NEVER, work));
		assertFalse(run.inProgress(presentation("e1", "p1")));

		run.step(ALWAYS, work);
		assertEquals(2, work.calls.size());
		assertEquals(1 + FullSyncRun.BOOKING_BLOCK, work.calls.get(1).size());
		assertTrue(run.inProgress(presentation("e1", "p1")));

		work.failOnce = new IOException("Platte weg");
		assertThrows(IOException.class, () -> run.step(NEVER, work));
		assertTrue(run.inProgress(presentation("e1", "p1")));
		assertTrue(run.step(NEVER, work));
		assertEquals(3, work.calls.size());
		assertEquals(booking("e1", "p1", seat(501)), work.calls.get(2).get(0));
		assertEquals(100, work.calls.get(2).size());
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
