package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class FeedReaderTest {

	private static final Instant START = Instant.parse("2026-10-26T10:00:00Z");
	private static final String ME = "mmmmmmmmmmmmmmmmmmmmmmmmmm";
	private static final String PEER_A = "aaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String PEER_B = "bbbbbbbbbbbbbbbbbbbbbbbbbb";
	private static final String SHARED_KEY = "\\\\server\\karten";

	private static final UnitKey UNIT = UnitKey.event("e1");
	private static final UnitKey UNIT2 = UnitKey.event("e2");

	private static final VersionId V1 = VersionId.create(1_000_000L, PEER_A, 0);
	private static final VersionId V2 = VersionId.create(2_000_000L, PEER_A, 1);
	private static final VersionId FOREIGN = VersionId.create(1_500_000L, PEER_B, 0);

	/** Eine Uhr, die der Test vorstellt. */
	private static final class TestClock extends Clock {
		private Instant now = START;

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@TempDir
	Path dir;

	private TestClock clock;
	private FaultyFileLayer sharedLayer;
	private VersionStore shared;
	private VersionStore local;
	private SyncPositions positions;
	private FeedReader reader;

	@BeforeEach
	void setUp() {
		clock = new TestClock();
		sharedLayer = new FaultyFileLayer(new NioFileLayer());
		shared = new VersionStore(dir.resolve("shared"), sharedLayer);
		local = new VersionStore(dir.resolve("local"), new NioFileLayer());
		positions = new SyncPositions(dir.resolve("sync-state.json"), SHARED_KEY);
		reader = new FeedReader(shared, local, new Ownership(ME, List.of()), positions, clock);
	}

	/** Die frühere ID dieses Rechners (vor SYS-011); sie galt bis 1 500 000 ms, also für {@link #V1}, nicht für {@link #V2}. */
	private static final String PREVIOUS = "pppppppppppppppppppppppppp";

	/** Ein Leser für diesen Rechner mit der früheren ID {@link #PREVIOUS}. */
	private FeedReader readerWithPreviousId() {
		Ownership own = new Ownership(ME, List.of(new Ownership.Previous(PREVIOUS, Instant.ofEpochMilli(1_500_000L))));
		return new FeedReader(shared, local, own, positions, clock);
	}

	private static Version version(String machine, String machineName, VersionId id, VersionId... parents) {
		return new Version(1, id, List.of(parents), machine, machineName, "Anna", START, "save-" + id, false,
				new ObjectMapper().createObjectNode().put("name", "Sitzung"));
	}

	/** Legt die Versionsdatei im gemeinsamen Ordner ab und liefert ihre Bytes. */
	private byte[] putVersion(UnitKey unit, Version version) throws IOException {
		byte[] bytes = StoreCodec.write(version);
		assertTrue(shared.writeVersion(unit, version.id(), bytes));
		return bytes;
	}

	private byte[] putClaim(UnitKey unit, Version version, byte[] versionBytes) throws IOException {
		byte[] bytes = StoreCodec.write(new Claim(version.id(), StoreCodec.sha256(versionBytes), version.machine(), START));
		assertTrue(shared.createClaim(unit, version.claimName(), bytes));
		return bytes;
	}

	private void putFeed(String machine, long seq, FeedEntry... entries) throws IOException {
		Feed feed = new Feed(StoreCodec.FORMAT, machine, seq, START, List.of(entries));
		assertTrue(shared.createRelative("feeds/" + machine + "/" + seq + ".json", StoreCodec.write(feed)));
	}

	private void putRaw(String rel, String content) throws IOException {
		assertTrue(shared.createRelative(rel, content.getBytes(StandardCharsets.UTF_8)));
	}

	private static FeedEntry entry(UnitKey unit, Version version) {
		return new FeedEntry(unit.relativePath(), version.id(), version.claimName());
	}

	/** Version, Liste und Anspruch eines Verkaufs in der richtigen Reihenfolge. */
	private void sell(String machine, long seq, UnitKey unit, Version version) throws IOException {
		byte[] bytes = putVersion(unit, version);
		putFeed(machine, seq, entry(unit, version));
		putClaim(unit, version, bytes);
	}

	private SyncPositions reloaded() {
		return new SyncPositions(dir.resolve("sync-state.json"), SHARED_KEY);
	}

	@Test
	void newFeedCopiesVersionAndClaim() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		byte[] versionBytes = putVersion(UNIT, version);
		putFeed(PEER_A, 1, entry(UNIT, version));
		byte[] claimBytes = putClaim(UNIT, version, versionBytes);

		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT), poll.changed());
		assertTrue(poll.pending().isEmpty());
		assertFalse(poll.fullSyncNeeded());
		assertArrayEquals(versionBytes, local.readVersionBytes(UNIT, V1).orElseThrow());
		assertArrayEquals(claimBytes, local.readClaimBytes(UNIT, "root").orElseThrow());
		assertEquals(1L, positions.position(PEER_A));
		assertEquals(1L, reloaded().position(PEER_A), "die Position ist gespeichert");

		// nichts Neues: nichts zu melden
		FeedPoll again = reader.poll();
		assertTrue(again.changed().isEmpty());
		assertTrue(again.pending().isEmpty());
		assertFalse(again.fullSyncNeeded());
	}

	@Test
	void feedsOfOneMachineAreReadInOrder() throws IOException {
		positions.set(PEER_A, 0);
		Version first = version(PEER_A, "Kasse 2", V1);
		Version second = version(PEER_A, "Kasse 2", V2, V1);
		sell(PEER_A, 1, UNIT, first);
		sell(PEER_A, 2, UNIT, second);

		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT), poll.changed());
		assertEquals(2L, positions.position(PEER_A));
		assertTrue(local.readClaimBytes(UNIT, "root").isPresent());
		assertTrue(local.readClaimBytes(UNIT, V1.value()).isPresent());
	}

	@Test
	void ownFeedIsIgnored() throws IOException {
		positions.set(ME, 0);
		Version version = version(ME, "Kasse 1", VersionId.create(1_000_000L, ME, 0));
		sell(ME, 1, UNIT, version);

		FeedPoll poll = reader.poll();

		assertTrue(poll.changed().isEmpty());
		assertTrue(local.readVersionBytes(UNIT, version.id()).isEmpty());
		assertEquals(0L, positions.position(ME));
		assertTrue(sharedLayer.calls().stream().noneMatch(c -> c.startsWith("read ") && c.contains(ME)),
				"die eigene Liste wird nicht einmal gelesen");
	}

	@Test
	void machinesWithoutValidIdAreIgnored() throws IOException {
		positions.set("kein-rechner", 0);
		positions.set("AAAAAAAAAAAAAAAAAAAAAAAAAA", 0);

		FeedPoll poll = reader.poll();

		assertTrue(poll.changed().isEmpty());
		assertTrue(sharedLayer.calls().stream().noneMatch(c -> c.startsWith("read ")));
	}

	@Test
	void missingVersionWaitsThenRequestsFullSync() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		putFeed(PEER_A, 1, entry(UNIT, version));
		// die Liste steht schon da, ihre Nachfolgerin auch, die Versionsdatei noch nicht
		Version later = version(PEER_A, "Kasse 2", V2, V1);
		sell(PEER_A, 2, UNIT, later);

		FeedPoll first = reader.poll();
		assertFalse(first.fullSyncNeeded());
		assertTrue(first.changed().isEmpty());
		assertEquals(0L, positions.position(PEER_A), "die Position bleibt stehen");
		assertTrue(local.readVersionBytes(UNIT, V2).isEmpty(), "spätere Listen warten mit");

		clock.advance(Duration.ofMinutes(4));
		FeedPoll second = reader.poll();
		assertFalse(second.fullSyncNeeded());
		assertEquals(0L, positions.position(PEER_A));

		clock.advance(Duration.ofMinutes(1).plusSeconds(1));
		FeedPoll third = reader.poll();
		assertTrue(third.fullSyncNeeded());
		assertEquals(2L, positions.position(PEER_A), "die Liste ist übersprungen, die folgende gelesen");
		assertEquals(Set.of(UNIT), third.changed());
		assertEquals(2L, reloaded().position(PEER_A));

		// der Wunsch nach Vollabgleich wird nur einmal gemeldet
		assertFalse(reader.poll().fullSyncNeeded());
	}

	@Test
	void missingVersionThatArrivesInTimeIsFetched() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		putFeed(PEER_A, 1, entry(UNIT, version));
		assertTrue(reader.poll().changed().isEmpty());
		clock.advance(Duration.ofMinutes(2));

		byte[] bytes = putVersion(UNIT, version);
		putClaim(UNIT, version, bytes);
		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT), poll.changed());
		assertFalse(poll.fullSyncNeeded());
		assertEquals(1L, positions.position(PEER_A));
	}

	@Test
	void missingClaimIsWatchedAndSighted() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		putVersion(UNIT, version);
		putFeed(PEER_A, 1, entry(UNIT, version));

		FeedPoll poll = reader.poll();

		assertEquals(List.of(new PendingSighting(UNIT, V1, "Kasse 2")), poll.pending());
		assertEquals(Set.of(UNIT), poll.changed(), "die neue Versionsdatei wird gemeldet, die Einheit bleibt ungültig");
		assertFalse(poll.fullSyncNeeded());
		assertTrue(local.readVersionBytes(UNIT, V1).isPresent(), "die Versionsdatei liegt schon lokal");
		assertTrue(local.readClaimBytes(UNIT, "root").isEmpty());
		assertEquals(1L, positions.position(PEER_A));

		// die Beobachtung wird nicht bei jedem Durchlauf neu gemeldet
		assertTrue(reader.poll().pending().isEmpty());
		assertTrue(reader.poll().pending().isEmpty());
	}

	@Test
	void sightingWithoutMachineNameHasNull() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, null, V1);
		putVersion(UNIT, version);
		putFeed(PEER_A, 1, entry(UNIT, version));

		assertEquals(List.of(new PendingSighting(UNIT, V1, null)), reader.poll().pending());
	}

	@Test
	void watchedClaimAppearsLater() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		byte[] bytes = putVersion(UNIT, version);
		putFeed(PEER_A, 1, entry(UNIT, version));
		assertEquals(1, reader.poll().pending().size());
		clock.advance(Duration.ofMinutes(2));
		assertTrue(reader.poll().changed().isEmpty());

		byte[] claimBytes = putClaim(UNIT, version, bytes);
		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT), poll.changed());
		assertTrue(poll.pending().isEmpty());
		assertArrayEquals(claimBytes, local.readClaimBytes(UNIT, "root").orElseThrow());

		// danach ist die Beobachtung beendet
		assertTrue(reader.poll().changed().isEmpty());
	}

	@Test
	void watchedClaimMayPointToAnotherVersion() throws IOException {
		positions.set(PEER_A, 0);
		Version mine = version(PEER_A, "Kasse 2", V1);
		putVersion(UNIT, mine);
		putFeed(PEER_A, 1, entry(UNIT, mine));
		assertEquals(1, reader.poll().pending().size());

		Version winner = version(PEER_B, "Kasse 3", FOREIGN);
		byte[] winnerBytes = putVersion(UNIT, winner);
		byte[] claimBytes = putClaim(UNIT, winner, winnerBytes);
		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT), poll.changed());
		assertArrayEquals(claimBytes, local.readClaimBytes(UNIT, "root").orElseThrow());
		assertEquals(FOREIGN, StoreCodec.readClaim(local.readClaimBytes(UNIT, "root").orElseThrow()).version());
	}

	@Test
	void watchExpiresAfterFiveMinutes() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		byte[] bytes = putVersion(UNIT, version);
		putFeed(PEER_A, 1, entry(UNIT, version));
		assertEquals(1, reader.poll().pending().size());

		clock.advance(Duration.ofMinutes(5).plusSeconds(1));
		FeedPoll expired = reader.poll();
		assertTrue(expired.changed().isEmpty());
		assertTrue(expired.pending().isEmpty());
		assertFalse(expired.fullSyncNeeded(), "das Ablaufen ist keine Störung");

		// ein später erscheinender Anspruch wird nicht mehr beobachtet (der Vollabgleich holt ihn)
		putClaim(UNIT, version, bytes);
		FeedPoll after = reader.poll();
		assertTrue(after.changed().isEmpty());
		assertTrue(local.readClaimBytes(UNIT, "root").isEmpty());
	}

	@Test
	void watchIsStillActiveJustBeforeTheLimit() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		byte[] bytes = putVersion(UNIT, version);
		putFeed(PEER_A, 1, entry(UNIT, version));
		reader.poll();

		clock.advance(Duration.ofMinutes(5).minusSeconds(1));
		putClaim(UNIT, version, bytes);

		assertEquals(Set.of(UNIT), reader.poll().changed());
	}

	@Test
	void claimThatIsAlreadyLocalIsNotReportedAgain() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		byte[] bytes = putVersion(UNIT, version);
		putFeed(PEER_A, 1, entry(UNIT, version));
		byte[] claimBytes = putClaim(UNIT, version, bytes);
		// Version und Anspruch liegen schon lokal (etwa vom Vollabgleich): es gibt nichts zu melden
		assertTrue(local.writeVersion(UNIT, V1, bytes));
		assertTrue(local.createClaim(UNIT, "root", claimBytes));

		FeedPoll poll = reader.poll();

		assertTrue(poll.changed().isEmpty());
		assertEquals(1L, positions.position(PEER_A));
	}

	@Test
	void discoverFindsNewMachine() throws IOException {
		Version version = version(PEER_A, "Kasse 2", V1);
		sell(PEER_A, 1, UNIT, version);
		// Fremdes im Ordner: eigener Ordner, ungültige Namen, eine Datei
		putRaw("feeds/" + ME + "/1.json", "{}");
		putRaw("feeds/kein-rechner/1.json", "{}");
		putRaw("feeds/notiz.txt", "x");

		assertTrue(reader.poll().changed().isEmpty(), "ohne Erkennung bleibt der Rechner unbekannt");

		reader.discoverMachines();
		assertEquals(Set.of(PEER_A), positions.machines());
		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT), poll.changed());
		assertEquals(1L, positions.position(PEER_A));
	}

	@Test
	void discoverKeepsKnownPositions() throws IOException {
		positions.set(PEER_A, 5);
		sell(PEER_A, 6, UNIT, version(PEER_A, "Kasse 2", V1));

		reader.discoverMachines();

		assertEquals(5L, positions.position(PEER_A));
		assertEquals(Set.of(UNIT), reader.poll().changed());
	}

	@Test
	void discoveredMachinesAreSavedWithThePolls() throws IOException {
		putFeed(PEER_B, 1);

		reader.discoverMachines();
		reader.poll();

		assertEquals(1L, reloaded().position(PEER_B));
	}

	@Test
	void peersAreReadInSortedOrder() throws IOException {
		positions.set(PEER_B, 0);
		positions.set(PEER_A, 0);
		sell(PEER_B, 1, UNIT2, version(PEER_B, "Kasse 3", VersionId.create(1_000_000L, PEER_B, 0)));
		sell(PEER_A, 1, UNIT, version(PEER_A, "Kasse 2", V1));

		reader.poll();

		List<String> reads = sharedLayer.calls().stream().filter(c -> c.startsWith("read ") && c.contains("feeds"))
				.map(c -> c.replace('\\', '/')).toList();
		int a = indexContaining(reads, "feeds/" + PEER_A + "/1.json");
		int b = indexContaining(reads, "feeds/" + PEER_B + "/1.json");
		assertTrue(a >= 0 && b > a, reads.toString());
	}

	private static int indexContaining(List<String> list, String part) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).contains(part)) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void largeBacklogIsProcessedInChunks() throws IOException {
		positions.set(PEER_A, 0);
		for (int i = 1; i <= 500; i++) {
			UnitKey unit = UnitKey.event("e" + i);
			sell(PEER_A, i, unit, version(PEER_A, "Kasse 2", VersionId.create(1_000_000L + i, PEER_A, 0)));
		}

		FeedPoll first = reader.poll();
		assertEquals(200, first.changed().size());
		assertEquals(200L, positions.position(PEER_A));
		assertEquals(200L, reloaded().position(PEER_A));

		FeedPoll second = reader.poll();
		assertEquals(200, second.changed().size());
		assertEquals(400L, positions.position(PEER_A));

		FeedPoll third = reader.poll();
		assertEquals(100, third.changed().size());
		assertEquals(500L, positions.position(PEER_A));
		assertEquals(500L, reloaded().position(PEER_A));

		assertTrue(reader.poll().changed().isEmpty());
	}

	@Test
	void limitOfFeedsCountsAllMachinesTogether() throws IOException {
		positions.set(PEER_A, 0);
		positions.set(PEER_B, 0);
		for (int i = 1; i <= 150; i++) {
			putFeed(PEER_A, i);
			putFeed(PEER_B, i);
		}

		reader.poll();

		assertEquals(150L, positions.position(PEER_A));
		assertEquals(50L, positions.position(PEER_B));
	}

	@Test
	void unreadableFeedIsSkippedAndRequestsFullSync() throws IOException {
		positions.set(PEER_A, 0);
		putRaw("feeds/" + PEER_A + "/1.json", "{ kaputt");
		sell(PEER_A, 2, UNIT, version(PEER_A, "Kasse 2", V1));

		FeedPoll poll = reader.poll();

		assertTrue(poll.fullSyncNeeded());
		assertEquals(Set.of(UNIT), poll.changed());
		assertEquals(2L, positions.position(PEER_A));
	}

	@Test
	void feedWithUnknownFormatIsSkipped() throws IOException {
		positions.set(PEER_A, 0);
		putRaw("feeds/" + PEER_A + "/1.json",
				"{\"format\":2,\"machine\":\"" + PEER_A + "\",\"seq\":1,\"at\":\"2026-10-26T10:00:00Z\",\"entries\":[]}");

		FeedPoll poll = reader.poll();

		assertTrue(poll.fullSyncNeeded());
		assertEquals(1L, positions.position(PEER_A));
	}

	@Test
	void feedThatDoesNotMatchItsPathIsSkipped() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		putVersion(UNIT, version);
		// Inhalt gehört zu Nummer 7 und zu einem anderen Rechner
		Feed wrongSeq = new Feed(StoreCodec.FORMAT, PEER_A, 7, START, List.of(entry(UNIT, version)));
		assertTrue(shared.createRelative("feeds/" + PEER_A + "/1.json", StoreCodec.write(wrongSeq)));
		Feed wrongMachine = new Feed(StoreCodec.FORMAT, PEER_B, 2, START, List.of(entry(UNIT, version)));
		assertTrue(shared.createRelative("feeds/" + PEER_A + "/2.json", StoreCodec.write(wrongMachine)));

		FeedPoll poll = reader.poll();

		assertTrue(poll.fullSyncNeeded());
		assertTrue(poll.changed().isEmpty());
		assertTrue(poll.pending().isEmpty());
		assertEquals(2L, positions.position(PEER_A));
		assertTrue(local.readVersionBytes(UNIT, V1).isEmpty());
	}

	@Test
	void invalidEntryIsSkippedAndRequestsFullSync() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		byte[] bytes = putVersion(UNIT, version);
		Feed feed = new Feed(StoreCodec.FORMAT, PEER_A, 1, START,
				List.of(new FeedEntry("events/../x", V2, "root"), new FeedEntry(UNIT.relativePath(), V1, "kein-name"),
						entry(UNIT, version)));
		assertTrue(shared.createRelative("feeds/" + PEER_A + "/1.json", StoreCodec.write(feed)));
		putClaim(UNIT, version, bytes);

		FeedPoll poll = reader.poll();

		assertTrue(poll.fullSyncNeeded());
		assertEquals(Set.of(UNIT), poll.changed());
		assertEquals(1L, positions.position(PEER_A));
	}

	@Test
	void jumpToLatestSetsPositionsToTheHighestNumber() throws IOException {
		positions.set(PEER_A, 1);
		putFeed(PEER_A, 1);
		putFeed(PEER_A, 2);
		putFeed(PEER_A, 12);
		putRaw("feeds/" + PEER_A + "/013.json", "{}");
		putRaw("feeds/" + PEER_A + "/notiz.txt", "x");
		putFeed(PEER_B, 4);
		putRaw("feeds/" + ME + "/9.json", "{}");
		putRaw("feeds/kein-rechner/9.json", "{}");

		reader.jumpToLatest();

		assertEquals(12L, positions.position(PEER_A));
		assertEquals(4L, positions.position(PEER_B));
		assertEquals(Set.of(PEER_A, PEER_B), positions.machines());
		SyncPositions saved = reloaded();
		assertFalse(saved.wasBroken());
		assertEquals(12L, saved.position(PEER_A));
		assertEquals(4L, saved.position(PEER_B));
		assertTrue(reader.poll().changed().isEmpty());
	}

	@Test
	void jumpToLatestForgetsWaitingFeeds() throws IOException {
		positions.set(PEER_A, 0);
		Version missing = version(PEER_A, "Kasse 2", V1);
		putFeed(PEER_A, 1, entry(UNIT, missing));
		putFeed(PEER_A, 2);
		reader.poll();
		assertEquals(0L, positions.position(PEER_A));

		reader.jumpToLatest();
		clock.advance(Duration.ofMinutes(10));
		FeedPoll poll = reader.poll();

		assertTrue(poll.changed().isEmpty());
		assertFalse(poll.fullSyncNeeded(), "die Wartezeit der übersprungenen Liste läuft nicht weiter");
		assertEquals(2L, positions.position(PEER_A));
	}

	@Test
	void watchSurvivesJumpToLatest() throws IOException {
		positions.set(PEER_A, 0);
		Version version = version(PEER_A, "Kasse 2", V1);
		byte[] bytes = putVersion(UNIT, version);
		putFeed(PEER_A, 1, entry(UNIT, version));
		assertEquals(1, reader.poll().pending().size());

		// Vollabgleich: der Anspruch fehlt noch, erscheint aber kurz danach
		reader.jumpToLatest();
		clock.advance(Duration.ofMinutes(1));
		byte[] claimBytes = putClaim(UNIT, version, bytes);
		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT), poll.changed());
		assertArrayEquals(claimBytes, local.readClaimBytes(UNIT, "root").orElseThrow());
	}

	@Test
	void versionCopiedAfterItsClaimReportsTheUnit() throws IOException {
		positions.set(PEER_A, 0);
		positions.set(PEER_B, 0);
		Version mine = version(PEER_A, "Kasse 2", V1);
		putVersion(UNIT, mine);
		putFeed(PEER_A, 1, entry(UNIT, mine));
		reader.poll();

		// Der Anspruch des Gewinners erscheint vor seiner Versionsdatei und kommt über die Beobachtung lokal an
		Version winner = version(PEER_B, "Kasse 3", FOREIGN);
		byte[] winnerBytes = StoreCodec.write(winner);
		putClaim(UNIT, winner, winnerBytes);
		assertEquals(Set.of(UNIT), reader.poll().changed());
		assertTrue(local.readVersionBytes(UNIT, FOREIGN).isEmpty());

		// Dann kommt seine Liste mit der Versionsdatei: die Einheit ist erst jetzt gültig und muss gemeldet werden
		assertTrue(shared.writeVersion(UNIT, FOREIGN, winnerBytes));
		putFeed(PEER_B, 1, entry(UNIT, winner));
		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT), poll.changed());
		assertTrue(local.readVersionBytes(UNIT, FOREIGN).isPresent());
	}

	@Test
	void resultsOfAFailedPollSurviveJumpToLatest() throws IOException {
		positions.set(PEER_A, 0);
		positions.set(PEER_B, 0);
		sell(PEER_A, 1, UNIT, version(PEER_A, "Kasse 2", V1));
		sell(PEER_B, 1, UNIT2, version(PEER_B, "Kasse 3", FOREIGN));
		AtomicBoolean broken = new AtomicBoolean(true);
		sharedLayer.failWhen(p -> broken.get() && p.toString().contains(PEER_B) && p.toString().endsWith("1.json"), "read",
				new IOException("Störung"));
		assertThrows(IOException.class, () -> reader.poll());
		assertTrue(local.readClaimBytes(UNIT, "root").isPresent(), "der Anspruch von A liegt schon lokal");

		// Der Vollabgleich nach dem Wiederverbinden meldet UNIT nicht mehr, denn der Anspruch ist schon da
		broken.set(false);
		reader.jumpToLatest();
		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT), poll.changed());
		assertEquals(1L, positions.position(PEER_B));
	}

	@Test
	void resultsOfAFailedPollAreDeliveredByTheNextOne() throws IOException {
		positions.set(PEER_A, 0);
		positions.set(PEER_B, 0);
		sell(PEER_A, 1, UNIT, version(PEER_A, "Kasse 2", V1));
		sell(PEER_B, 1, UNIT2, version(PEER_B, "Kasse 3", FOREIGN));
		AtomicBoolean broken = new AtomicBoolean(true);
		sharedLayer.failWhen(p -> broken.get() && p.toString().contains(PEER_B) && p.toString().endsWith("1.json"), "read",
				new IOException("Störung"));

		assertThrows(IOException.class, () -> reader.poll());
		broken.set(false);
		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT, UNIT2), poll.changed());
		assertEquals(1L, reloaded().position(PEER_A));
		assertEquals(1L, reloaded().position(PEER_B));
	}

	/** Eine eigene Version mit lokalem Anspruch auf {@code root}, wie nach einem eigenen Gewinn. */
	private Version ownRootClaim() throws IOException {
		Version mine = version(ME, "Ich", VersionId.create(500_000L, ME, 0));
		byte[] bytes = StoreCodec.write(mine);
		local.writeVersion(UNIT, mine.id(), bytes);
		local.createClaim(UNIT, "root", StoreCodec.write(new Claim(mine.id(), StoreCodec.sha256(bytes), ME, START)));
		return mine;
	}

	@Test
	void listOfAPreviousIdIsReadButOwnVersionsInItAreNoSightings() throws IOException {
		reader = readerWithPreviousId();
		positions.set(PREVIOUS, 0);
		// eigene Version von vor der Neuvergabe: Liste geschrieben, Anspruch noch nicht (Abbruch dazwischen)
		Version own = version(PREVIOUS, "Kasse 1", VersionId.create(1_000_000L, PREVIOUS, 0));
		local.writeVersion(UNIT, own.id(), putVersion(UNIT, own));
		putFeed(PREVIOUS, 1, entry(UNIT, own));
		// die Kopie, die die alte ID weiter trägt, speichert danach, ebenfalls noch ohne Anspruch
		Version copy = version(PREVIOUS, "Kasse 1", VersionId.create(2_000_000L, PREVIOUS, 0));
		putVersion(UNIT2, copy);
		putFeed(PREVIOUS, 2, entry(UNIT2, copy));

		FeedPoll poll = reader.poll();

		assertEquals(2L, positions.position(PREVIOUS), "die Listen der früheren ID werden gelesen");
		assertEquals(List.of(new PendingSighting(UNIT2, copy.id(), "Kasse 1")), poll.pending());
		assertTrue(poll.contested().isEmpty());
	}

	@Test
	void ownClaimOfAPreviousIdMeetingAForeignEntryIsContested() throws IOException {
		reader = readerWithPreviousId();
		positions.set(PEER_A, 0);
		Version mine = version(PREVIOUS, "Ich", VersionId.create(500_000L, PREVIOUS, 0));
		byte[] bytes = StoreCodec.write(mine);
		local.writeVersion(UNIT, mine.id(), bytes);
		local.createClaim(UNIT, "root", StoreCodec.write(new Claim(mine.id(), StoreCodec.sha256(bytes), PREVIOUS, START)));
		Version theirs = version(PEER_A, "Kasse 2", V1);
		putVersion(UNIT, theirs);
		putFeed(PEER_A, 1, entry(UNIT, theirs));

		FeedPoll poll = reader.poll();

		assertEquals(Set.of(new FeedPoll.Contested(UNIT, "root")), poll.contested());
	}

	@Test
	void sharedClaimDifferingFromTheLocalOneIsContested() throws IOException {
		positions.set(PEER_A, 0);
		Version mine = ownRootClaim();
		byte[] localClaim = local.readClaimBytes(UNIT, "root").orElseThrow();
		// lange nach der eigenen Beobachtung: der Sync-Client hat den Anspruch von A übernommen, A's Liste kommt an
		sell(PEER_A, 1, UNIT, version(PEER_A, "Kasse 2", V1));

		FeedPoll poll = reader.poll();

		assertEquals(Set.of(new FeedPoll.Contested(UNIT, "root")), poll.contested());
		// der Leser ersetzt nichts: das entscheidet der Aufrufer
		assertArrayEquals(localClaim, local.readClaimBytes(UNIT, "root").orElseThrow());
		assertEquals(mine.id(), StoreCodec.readClaim(localClaim).version());
	}

	@Test
	void ownClaimMeetingAForeignEntryIsContestedEvenBeforeTheClientResolves() throws IOException {
		positions.set(PEER_A, 0);
		ownRootClaim();
		// Version und Liste von A sind da, der Anspruch im gemeinsamen Ordner noch nicht
		Version theirs = version(PEER_A, "Kasse 2", V1);
		putVersion(UNIT, theirs);
		putFeed(PEER_A, 1, entry(UNIT, theirs));

		FeedPoll poll = reader.poll();

		assertEquals(Set.of(new FeedPoll.Contested(UNIT, "root")), poll.contested());
		assertEquals(1, poll.pending().size());
	}

	@Test
	void matchingClaimIsNotContested() throws IOException {
		positions.set(PEER_A, 0);
		Version theirs = version(PEER_A, "Kasse 2", V1);
		byte[] bytes = putVersion(UNIT, theirs);
		putFeed(PEER_A, 1, entry(UNIT, theirs));
		byte[] claim = putClaim(UNIT, theirs, bytes);
		local.createClaim(UNIT, "root", claim);

		assertTrue(reader.poll().contested().isEmpty());
	}

	@Test
	void copiedVersionNotMatchingTheClaimIsReportedDamaged() throws IOException {
		positions.set(PEER_A, 0);
		Version theirs = version(PEER_A, "Kasse 2", V1);
		byte[] good = StoreCodec.write(theirs);
		// die gemeinsame Kopie ist beschädigt, der Anspruch nennt den Prüfwert der guten
		assertTrue(shared.writeVersion(UNIT, V1, "kaputt".getBytes(StandardCharsets.UTF_8)));
		putFeed(PEER_A, 1, entry(UNIT, theirs));
		assertTrue(shared.createClaim(UNIT, "root",
				StoreCodec.write(new Claim(V1, StoreCodec.sha256(good), PEER_A, START))));

		FeedPoll poll = reader.poll();

		assertEquals(Set.of(UNIT), poll.damaged());
	}

	@Test
	void latestNumbersAreTheHighestFeedsOfThePeers() throws IOException {
		putFeed(PEER_A, 1);
		putFeed(PEER_A, 3);
		putFeed(PEER_B, 2);
		putRaw("feeds/" + ME + "/9.json", "{}");

		assertEquals(Map.of(PEER_A, 3L, PEER_B, 2L), reader.latestNumbers());
	}

	@Test
	void jumpToKeepsFeedsWrittenAfterTheSnapshot() throws IOException {
		putFeed(PEER_A, 1);
		Map<String, Long> latest = reader.latestNumbers();
		// zwischen Schnappschuss und Sprung (während des Vollabgleichs) schreibt der andere Rechner weiter
		Version later = version(PEER_A, "Kasse 2", V2);
		sell(PEER_A, 2, UNIT, later);

		reader.jumpTo(latest);

		assertEquals(1L, positions.position(PEER_A));
		assertEquals(1L, reloaded().position(PEER_A));
		assertEquals(Set.of(UNIT), reader.poll().changed());
		assertTrue(local.readVersionBytes(UNIT, V2).isPresent());
	}
}
