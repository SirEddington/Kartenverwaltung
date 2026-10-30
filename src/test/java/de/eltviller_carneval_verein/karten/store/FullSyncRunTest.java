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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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

	/** Eine übersprungene Einheit, wie {@link FullSyncRun.Work#skipped} sie meldet. */
	private record Skip(UnitKey unit, boolean subtree) {
	}

	/**
	 * Zeichnet die Listen auf, mit denen {@code sync} aufgerufen wird, und die übersprungenen Einheiten; wirft auf Wunsch
	 * einmal oder bei jedem Aufruf mit einer bestimmten Einheit und stellt auf Wunsch die Uhr je Aufruf vor (die Dauer eines
	 * Blocks). Gelingt ein Aufruf nicht, steht er nicht in {@link #calls}.
	 */
	private final class Recorder implements FullSyncRun.Work {

		final List<List<UnitKey>> calls = new ArrayList<>();
		final List<Skip> skips = new ArrayList<>();
		IOException failOnce;
		/** Jeder Aufruf, der diese Einheit enthält, scheitert; {@code null}: keiner. */
		UnitKey broken;
		/** Jeder Aufruf scheitert. */
		boolean failAlways;
		boolean reachable = true;
		Duration cost = Duration.ZERO;

		@Override
		public void sync(List<UnitKey> units) throws IOException {
			if (failOnce != null) {
				IOException e = failOnce;
				failOnce = null;
				throw e;
			}
			if (failAlways || broken != null && units.contains(broken)) {
				throw new IOException("unlesbar: " + units);
			}
			calls.add(List.copyOf(units));
			clock.advance(cost);
		}

		@Override
		public boolean reachable() {
			return reachable;
		}

		@Override
		public void skipped(UnitKey unit, boolean subtree, Exception cause) {
			skips.add(new Skip(unit, subtree));
		}
	}

	@BeforeEach
	void setUp() {
		FileLayer files = new NioFileLayer();
		local = new VersionStore(dir.resolve("local").resolve("data"), files);
		shared = new VersionStore(dir.resolve("shared").resolve("data"), files);
		run = new FullSyncRun(local, shared, clock);
	}

	/** Lässt jedes Auflisten unter dem Ordner im gemeinsamen Bestand scheitern, solange {@code broken} gilt. */
	private void breakSharedListing(String relativePath, AtomicBoolean broken) {
		FaultyFileLayer faulty = new FaultyFileLayer(new NioFileLayer());
		Path folder = dir.resolve("shared").resolve("data").resolve(relativePath);
		faulty.failWhen(p -> broken.get() && p.equals(folder), "listNames", new IOException("unlesbar: " + folder));
		shared = new VersionStore(dir.resolve("shared").resolve("data"), faulty);
		run = new FullSyncRun(local, shared, clock);
	}

	/**
	 * Ruft {@code step} auf, bis der Abschnitt nicht mehr scheitert, im Abstand von {@code gap} auf der Uhr; liefert die
	 * Zahl der gescheiterten Aufrufe.
	 */
	private int stepUntilItPasses(Recorder work, Duration gap) throws IOException {
		for (int failures = 0; failures < 100; failures++) {
			try {
				run.step(ALWAYS, work);
				return failures;
			} catch (IOException e) {
				clock.advance(gap);
			}
		}
		throw new AssertionError("der Abschnitt scheitert weiter");
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
		// der gescheiterte Block kann schon einen Teil abgeglichen haben
		assertTrue(run.inProgress(presentation("e1", "p1")));

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
	void failedBlockKeepsItsPresentationInProgressUntilABlockSucceeds() throws IOException {
		seats(local, 1, 3);
		folder(local, "events/e1/presentations/p2");
		Recorder work = new Recorder();
		run.begin();
		run.step(ALWAYS, work); // Kopf
		work.failOnce = new IOException("Platte weg");

		// der einzige Block der Vorstellung scheitert: Ein Teil kann schon abgeglichen und gemeldet sein, der Rest noch nicht
		assertThrows(IOException.class, () -> run.step(ALWAYS, work));
		assertTrue(run.inProgress(presentation("e1", "p1")));
		assertTrue(run.inProgress(booking("e1", "p1", seat(2))));
		assertFalse(run.inProgress(presentation("e1", "p2")));
		assertFalse(run.inProgress(UnitKey.event("e1")));

		run.step(ALWAYS, work);
		assertEquals(List.of(presentation("e1", "p1"), booking("e1", "p1", seat(1)), booking("e1", "p1", seat(2)),
				booking("e1", "p1", seat(3))), work.calls.get(1));
		assertFalse(run.inProgress(presentation("e1", "p1")));
		assertFalse(run.inProgress(presentation("e1", "p2")), "der nächste Abschnitt hat noch keinen Block begonnen");

		// ein neuer Lauf beginnt ohne Altlast
		work.failOnce = new IOException("Platte weg");
		assertThrows(IOException.class, () -> run.step(ALWAYS, work));
		assertTrue(run.inProgress(presentation("e1", "p2")));
		run.begin();
		assertFalse(run.inProgress(presentation("e1", "p2")));
	}

	// --- ein Block, der dauerhaft scheitert (KARV-106)

	@Test
	void blockFailingLongEnoughIsWorkedUnitByUnitAndOnlyTheFailingUnitIsSkipped() throws IOException {
		seats(local, 1, 3);
		folder(local, "events/e2");
		Recorder work = new Recorder();
		run.begin();
		run.step(ALWAYS, work); // Kopf
		work.broken = booking("e1", "p1", seat(2));
		Duration gap = FullSyncRun.SKIP_AFTER.dividedBy(FullSyncRun.FAILURES_BEFORE_SKIP);

		int failures = stepUntilItPasses(work, gap);

		// erst nach genug Fehlversuchen und genug Zeit seit dem ersten
		assertEquals(FullSyncRun.FAILURES_BEFORE_SKIP, failures);
		assertEquals(List.of(new Skip(booking("e1", "p1", seat(2)), false)), work.skips);
		assertEquals(List.of(List.of(UnitKey.event("e1")), List.of(presentation("e1", "p1")),
				List.of(booking("e1", "p1", seat(1))), List.of(booking("e1", "p1", seat(3)))), work.calls);
		// die Vorstellung der übersprungenen Buchung bleibt bis zum Ende des Laufs zurückgestellt, die übrigen nicht
		assertTrue(run.inProgress(booking("e1", "p1", seat(1))));
		assertFalse(run.inProgress(UnitKey.event("e2")));
		assertEquals(Set.of(presentation("e1", "p1")), run.skippedTrees());
		// die folgenden Abschnitte kommen dran, wieder als ganze Blöcke, und der Lauf endet
		assertTrue(run.step(NEVER, work));
		assertEquals(List.of(UnitKey.event("e2")), work.calls.get(4));
		assertEquals(1, work.skips.size());
	}

	/** Zwei Hallen in einem Bündel, der Lauf ist begonnen, und jeder Block mit h2 scheitert. */
	private Recorder hallsWithBrokenH2() throws IOException {
		folder(local, "halls/h1");
		folder(local, "halls/h2");
		Recorder work = new Recorder();
		run.begin();
		work.broken = UnitKey.hall("h2");
		return work;
	}

	/** {@code count} Aufrufe von {@code step}, die alle scheitern müssen, im Abstand {@code gap} auf der Uhr. */
	private void failSteps(Recorder work, int count, Duration gap) {
		for (int i = 0; i < count; i++) {
			assertThrows(IOException.class, () -> run.step(ALWAYS, work));
			clock.advance(gap);
		}
	}

	@Test
	void manyQuickFailuresDoNotSkip() throws IOException {
		Recorder work = hallsWithBrokenH2();
		Instant start = clock.instant();

		failSteps(work, 10 * FullSyncRun.FAILURES_BEFORE_SKIP, Duration.ofSeconds(1));

		// viele Fehlversuche, aber noch nicht lange genug: eine kurze Sperre überspringt nichts
		assertTrue(Duration.between(start, clock.instant()).compareTo(FullSyncRun.SKIP_AFTER) < 0);
		assertEquals(List.of(), work.skips);
		// die Sperre ist vorbei: Der Block gelingt als Ganzes
		work.broken = null;
		assertTrue(run.step(ALWAYS, work));
		assertEquals(List.of(List.of(UnitKey.hall("h1"), UnitKey.hall("h2"))), work.calls);
	}

	@Test
	void failuresWhileUnreachableDoNotCount() throws IOException {
		Recorder work = hallsWithBrokenH2();
		work.reachable = false;
		failSteps(work, 2 * FullSyncRun.FAILURES_BEFORE_SKIP, FullSyncRun.SKIP_AFTER);

		// erreichbar: Die Fehlversuche beginnen erst jetzt zu zählen
		work.reachable = true;
		int failures = stepUntilItPasses(work, FullSyncRun.SKIP_AFTER);

		assertEquals(FullSyncRun.FAILURES_BEFORE_SKIP, failures);
		assertEquals(List.of(new Skip(UnitKey.hall("h2"), false)), work.skips);
		assertEquals(Set.of(), run.skippedTrees(), "Hallen haben keine Geschwister");
	}

	@Test
	void outageRestartsTheCount() throws IOException {
		Recorder work = hallsWithBrokenH2();
		failSteps(work, 1, Duration.ofSeconds(10));
		work.reachable = false;
		failSteps(work, 1, Duration.ofMinutes(10));

		// zurück, und kurz danach scheitert es noch ein paar Mal: Die Auszeit zählt nicht zur Frist
		work.reachable = true;
		failSteps(work, FullSyncRun.FAILURES_BEFORE_SKIP + 1, Duration.ofSeconds(2));

		assertEquals(List.of(), work.skips);
	}

	@Test
	void blockWhoseUnitsAllFailAloneIsNotSkipped() throws IOException {
		folder(local, "events/e1");
		Recorder work = hallsWithBrokenH2();
		// etwa die Platte voll: Jede Einheit des Bündels scheitert, auch einzeln
		work.failAlways = true;

		failSteps(work, 3 * FullSyncRun.FAILURES_BEFORE_SKIP, FullSyncRun.SKIP_AFTER);

		assertEquals(List.of(), work.skips, "der Fehler liegt nicht an den Einheiten");
		work.failAlways = false;
		work.broken = null;
		assertTrue(run.step(NEVER, work));
		assertEquals(List.of(List.of(UnitKey.hall("h1")), List.of(UnitKey.hall("h2")), List.of(UnitKey.event("e1"))),
				work.calls);
	}

	@Test
	void unitFailingWhileUnreachableIsNeverSkipped() throws IOException {
		Recorder work = hallsWithBrokenH2();
		failSteps(work, FullSyncRun.FAILURES_BEFORE_SKIP, FullSyncRun.SKIP_AFTER);

		// jetzt einzeln, aber der Ordner ist weg: nichts wird übersprungen, der Abschnitt bleibt stehen
		work.reachable = false;
		failSteps(work, 1, Duration.ZERO);
		assertEquals(List.of(), work.skips);
		assertFalse(run.finished());

		// zurück: Die Fehlversuche zählen von vorn, der Block gelingt wieder als Ganzes
		work.reachable = true;
		work.broken = null;
		assertTrue(run.step(ALWAYS, work));
		assertEquals(List.of(), work.skips);
		assertEquals(List.of(UnitKey.hall("h1"), UnitKey.hall("h2")), work.calls.get(work.calls.size() - 1));
	}

	@Test
	void bookingsThatCannotBeListedSkipThePresentationWithEverythingBelow() throws IOException {
		seats(shared, 1, 2);
		folder(local, "events/e1/presentations/p2");
		AtomicBoolean broken = new AtomicBoolean(true);
		breakSharedListing("events/e1/presentations/p1/bookings", broken);
		Recorder work = new Recorder();
		run.begin();
		run.step(ALWAYS, work); // Kopf

		int failures = stepUntilItPasses(work, FullSyncRun.SKIP_AFTER);

		assertEquals(FullSyncRun.FAILURES_BEFORE_SKIP, failures);
		assertEquals(List.of(new Skip(presentation("e1", "p1"), true)), work.skips);
		assertTrue(run.step(NEVER, work));
		assertEquals(List.of(List.of(UnitKey.event("e1")), List.of(presentation("e1", "p2"))), work.calls);
	}

	@Test
	void presentationsThatCannotBeListedAtTheBeginningAreListedLater() throws IOException {
		seats(local, 1, 1);
		folder(local, "events/e2");
		AtomicBoolean broken = new AtomicBoolean(true);
		breakSharedListing("events/e1/presentations", broken);
		Recorder work = new Recorder();

		// der Beginn scheitert nicht: Das Event kommt mit seinem Kopf, seine Vorstellungen später
		run.begin();
		run.step(ALWAYS, work);
		assertEquals(List.of(List.of(UnitKey.event("e1"))), work.calls);
		assertThrows(IOException.class, () -> run.step(ALWAYS, work));

		broken.set(false);
		assertTrue(run.step(NEVER, work));
		assertEquals(List.of(List.of(UnitKey.event("e1")),
				List.of(presentation("e1", "p1"), booking("e1", "p1", seat(1))), List.of(UnitKey.event("e2"))),
				work.calls);
		assertEquals(List.of(), work.skips);
	}

	@Test
	void presentationsThatStayUnlistableSkipTheEventWithEverythingBelow() throws IOException {
		seats(local, 1, 1);
		folder(local, "events/e2");
		AtomicBoolean broken = new AtomicBoolean(true);
		breakSharedListing("events/e1/presentations", broken);
		Recorder work = new Recorder();
		run.begin();
		run.step(ALWAYS, work); // Kopf

		int failures = stepUntilItPasses(work, FullSyncRun.SKIP_AFTER);

		assertEquals(FullSyncRun.FAILURES_BEFORE_SKIP, failures);
		assertEquals(List.of(new Skip(UnitKey.event("e1"), true)), work.skips);
		assertTrue(run.step(NEVER, work));
		assertEquals(List.of(List.of(UnitKey.event("e1")), List.of(UnitKey.event("e2"))), work.calls);
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
