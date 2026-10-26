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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class UploaderTest {

	private static final Instant AT = Instant.parse("2026-10-26T10:00:00Z");
	private static final Instant LATER = Instant.parse("2026-10-26T11:00:00Z");
	private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
	private static final String ME = "maschine-1";
	private static final String OTHER = "maschine-2";

	private static final UnitKey UNIT = UnitKey.event("e1");
	private static final UnitKey UNIT2 = UnitKey.event("e2");

	private static final VersionId V1 = VersionId.create(1_000_000L, "aaaaaaaa", 0);
	private static final VersionId V2 = VersionId.create(2_000_000L, "aaaaaaaa", 1);
	private static final VersionId V3 = VersionId.create(3_000_000L, "aaaaaaaa", 2);
	private static final VersionId FOREIGN = VersionId.create(1_500_000L, "bbbbbbbb", 0);

	@TempDir
	Path dir;

	private FaultyFileLayer sharedLayer;
	private VersionStore shared;
	private VersionStore local;

	@BeforeEach
	void setUp() {
		sharedLayer = new FaultyFileLayer(new NioFileLayer());
		shared = new VersionStore(dir.resolve("shared"), sharedLayer);
		local = new VersionStore(dir.resolve("local"), new NioFileLayer());
	}

	private static Version version(String machine, VersionId id, VersionId... parents) {
		return new Version(1, id, List.of(parents), machine, "Kasse", "Anna", AT, "save-" + id, false,
				new ObjectMapper().createObjectNode().put("name", "Sitzung"));
	}

	private static UploadJob.Item item(UnitKey unit, Version version) throws IOException {
		return new UploadJob.Item(unit, version, StoreCodec.write(version));
	}

	private static UploadJob job(UploadJob.Item... items) {
		return new UploadJob("save-1", List.of(items));
	}

	private static byte[] claimBytes(Version version, byte[] versionBytes, Instant at) throws IOException {
		return StoreCodec.write(new Claim(version.id(), StoreCodec.sha256(versionBytes), version.machine(), at));
	}

	/** Ein fremder Rechner hat den Platz hinter dem Vorgänger der Version schon belegt. */
	private void foreignClaim(UnitKey unit, Version version) throws IOException {
		byte[] bytes = StoreCodec.write(version);
		assertTrue(shared.writeVersion(unit, version.id(), bytes));
		assertTrue(shared.createClaim(unit, version.claimName(), claimBytes(version, bytes, AT)));
	}

	private Uploader uploader(Arbiter arbiter) {
		return new Uploader(shared, new FeedWriter(shared, ME, CLOCK), arbiter);
	}

	private Uploader sharedUploader() {
		return uploader(new SharedArbiter(shared, local, CLOCK));
	}

	private List<String> createdFiles() {
		return sharedLayer.calls().stream().filter(c -> c.startsWith("createNew ")).map(c -> c.substring("createNew ".length()))
				.map(c -> c.replace('\\', '/')).toList();
	}

	private boolean sharedHas(String rel) throws IOException {
		return shared.readRelative(rel).isPresent();
	}

	@Test
	void uploadOrderIsVersionFeedClaim() throws IOException {
		Version a = version(ME, V1);
		Version b = version(ME, V2);

		List<LostVersion> lost = sharedUploader().run(job(item(UNIT, a), item(UNIT2, b)));

		assertTrue(lost.isEmpty());
		List<String> created = createdFiles();
		assertEquals(5, created.size(), created.toString());
		assertTrue(created.get(0).endsWith("events/e1/" + V1 + ".json"), created.toString());
		assertTrue(created.get(1).endsWith("events/e2/" + V2 + ".json"), created.toString());
		assertTrue(created.get(2).endsWith("feeds/" + ME + "/1.json"), created.toString());
		assertTrue(created.get(3).endsWith("events/e1/claims/root.json"), created.toString());
		assertTrue(created.get(4).endsWith("events/e2/claims/root.json"), created.toString());
		Feed feed = StoreCodec.readFeed(shared.readRelative("feeds/" + ME + "/1.json").orElseThrow());
		assertEquals(List.of(new FeedEntry("events/e1", V1, "root"), new FeedEntry("events/e2", V2, "root")), feed.entries());
	}

	@Test
	void winCopiesClaimToLocal() throws IOException {
		Version a = version(ME, V1);
		UploadJob job = job(item(UNIT, a));

		List<LostVersion> lost = sharedUploader().run(job);

		assertTrue(lost.isEmpty());
		assertTrue(job.isDone());
		byte[] sharedClaim = shared.readClaimBytes(UNIT, "root").orElseThrow();
		assertArrayEquals(sharedClaim, local.readClaimBytes(UNIT, "root").orElseThrow());
		Claim claim = StoreCodec.readClaim(sharedClaim);
		assertEquals(V1, claim.version());
		assertEquals(StoreCodec.sha256(StoreCodec.write(a)), claim.sha256());
		assertEquals(ME, claim.machine());
		assertEquals(AT, claim.at());
	}

	@Test
	void lossCopiesWinnerToLocalAndReports() throws IOException {
		Version foreign = version(OTHER, FOREIGN);
		foreignClaim(UNIT, foreign);
		Version mine = version(ME, V1);

		List<LostVersion> lost = sharedUploader().run(job(item(UNIT, mine)));

		assertEquals(List.of(new LostVersion(UNIT, List.of(V1), FOREIGN)), lost);
		assertArrayEquals(shared.readClaimBytes(UNIT, "root").orElseThrow(), local.readClaimBytes(UNIT, "root").orElseThrow());
		assertEquals(FOREIGN, StoreCodec.readClaim(local.readClaimBytes(UNIT, "root").orElseThrow()).version());
		assertArrayEquals(StoreCodec.write(foreign), local.readVersionBytes(UNIT, FOREIGN).orElseThrow());
		// Die eigene Version liegt trotzdem im gemeinsamen Ordner, aber ohne Anspruch
		assertTrue(shared.readVersionBytes(UNIT, V1).isPresent());
	}

	@Test
	void lossWithMissingWinnerFileStillCopiesClaim() throws IOException {
		Version foreign = version(OTHER, FOREIGN);
		byte[] foreignBytes = StoreCodec.write(foreign);
		// Anspruch ist da, die Versionsdatei des Gewinners (noch) nicht
		assertTrue(shared.createClaim(UNIT, "root", claimBytes(foreign, foreignBytes, AT)));

		List<LostVersion> lost = sharedUploader().run(job(item(UNIT, version(ME, V1))));

		assertEquals(List.of(new LostVersion(UNIT, List.of(V1), FOREIGN)), lost);
		assertTrue(local.readClaimBytes(UNIT, "root").isPresent());
		assertFalse(local.readVersionBytes(UNIT, FOREIGN).isPresent());
	}

	@Test
	void resumesAtFailedStep() throws IOException {
		boolean[] failClaims = { true };
		sharedLayer.failWhen(p -> failClaims[0] && p.toString().replace('\\', '/').contains("/claims/"), "createNew",
				new IOException("Störung"));
		UploadJob job = job(item(UNIT, version(ME, V1)));
		Uploader uploader = sharedUploader();

		assertThrows(IOException.class, () -> uploader.run(job));

		assertFalse(job.isDone());
		assertTrue(sharedHas("feeds/" + ME + "/1.json"));
		assertFalse(sharedHas("events/e1/claims/root.json"));
		failClaims[0] = false;
		int createsBefore = createdFiles().size();

		List<LostVersion> lost = uploader.run(job);

		assertTrue(lost.isEmpty());
		assertTrue(job.isDone());
		assertFalse(sharedHas("feeds/" + ME + "/2.json"), "keine zweite Listendatei");
		List<String> created = createdFiles().subList(createsBefore, createdFiles().size());
		assertEquals(1, created.size(), created.toString());
		assertTrue(created.get(0).endsWith("events/e1/claims/root.json"));
		assertEquals(V1, StoreCodec.readClaim(shared.readClaimBytes(UNIT, "root").orElseThrow()).version());
	}

	@Test
	void resumeKeepsLossesFoundBeforeTheFailure() throws IOException {
		foreignClaim(UNIT, version(OTHER, FOREIGN));
		boolean[] failClaims = { false };
		sharedLayer.failWhen(p -> failClaims[0] && p.toString().replace('\\', '/').contains("events/e2/claims/"),
				"createNew", new IOException("Störung"));
		UploadJob job = job(item(UNIT, version(ME, V1)), item(UNIT2, version(ME, V2)));
		Uploader uploader = sharedUploader();
		failClaims[0] = true;

		assertThrows(IOException.class, () -> uploader.run(job));
		failClaims[0] = false;
		List<LostVersion> lost = uploader.run(job);

		assertEquals(List.of(new LostVersion(UNIT, List.of(V1), FOREIGN)), lost);
		assertTrue(job.isDone());
		assertTrue(uploader.run(job).isEmpty(), "ein fertiger Auftrag meldet nichts mehr");
	}

	@Test
	void crashAfterVersionRestartsWithNewFeedEntry() throws IOException {
		Version a = version(ME, V1);
		sharedLayer.failWhen(p -> p.toString().replace('\\', '/').contains("/claims/"), "createNew", new IOException("Absturz"));
		assertThrows(IOException.class, () -> sharedUploader().run(job(item(UNIT, a))));
		assertTrue(sharedHas("feeds/" + ME + "/1.json"));

		// Neustart: neuer Auftrag, neuer Listenschreiber, derselbe Anspruchsfehler ist behoben
		setUpNewLayerOnSameFolders();
		List<LostVersion> lost = sharedUploader().run(job(item(UNIT, a)));

		assertTrue(lost.isEmpty());
		assertTrue(sharedHas("feeds/" + ME + "/2.json"), "neue Listendatei");
		assertFalse(sharedHas("feeds/" + ME + "/3.json"));
		assertEquals(V1, StoreCodec.readClaim(shared.readClaimBytes(UNIT, "root").orElseThrow()).version());
		Feed feed = StoreCodec.readFeed(shared.readRelative("feeds/" + ME + "/2.json").orElseThrow());
		assertEquals(List.of(new FeedEntry("events/e1", V1, "root")), feed.entries());
		assertArrayEquals(StoreCodec.write(a), shared.readVersionBytes(UNIT, V1).orElseThrow());
	}

	private void setUpNewLayerOnSameFolders() {
		sharedLayer = new FaultyFileLayer(new NioFileLayer());
		shared = new VersionStore(dir.resolve("shared"), sharedLayer);
	}

	@Test
	void existingVersionWithOtherBytesIsMismatch() throws IOException {
		byte[] other = "anderer Inhalt".getBytes(StandardCharsets.UTF_8);
		assertTrue(shared.writeVersion(UNIT, V1, other));
		UploadJob job = job(item(UNIT, version(ME, V1)));
		Uploader uploader = sharedUploader();

		ContentMismatchException e = assertThrows(ContentMismatchException.class, () -> uploader.run(job));

		assertEquals("events/e1/" + V1 + ".json", e.relativePath());
		assertFalse(job.isDone());
		assertArrayEquals(other, shared.readVersionBytes(UNIT, V1).orElseThrow());
		assertFalse(sharedHas("feeds/" + ME + "/1.json"));
		assertFalse(sharedHas("events/e1/claims/root.json"));
	}

	@Test
	void existingOwnClaimCountsAsWon() throws IOException {
		Version a = version(ME, V1);
		byte[] bytes = StoreCodec.write(a);
		// Absturz zwischen Anspruch und lokaler Kopie: der Anspruch liegt schon da, mit anderem Zeitpunkt
		assertTrue(shared.writeVersion(UNIT, V1, bytes));
		byte[] existing = claimBytes(a, bytes, LATER);
		assertTrue(shared.createClaim(UNIT, "root", existing));

		List<LostVersion> lost = sharedUploader().run(job(item(UNIT, a)));

		assertTrue(lost.isEmpty());
		assertArrayEquals(existing, shared.readClaimBytes(UNIT, "root").orElseThrow());
		assertArrayEquals(existing, local.readClaimBytes(UNIT, "root").orElseThrow());
	}

	@Test
	void existingClaimOfOwnVersionWithOtherHashIsLost() throws IOException {
		Version a = version(ME, V1);
		byte[] bytes = StoreCodec.write(a);
		assertTrue(shared.writeVersion(UNIT, V1, bytes));
		assertTrue(shared.createClaim(UNIT, "root",
				StoreCodec.write(new Claim(V1, StoreCodec.sha256("anders".getBytes(StandardCharsets.UTF_8)), ME, AT))));

		ClaimOutcome outcome = new SharedArbiter(shared, local, CLOCK).claim(UNIT, a, bytes);

		assertFalse(outcome.won());
	}

	@Test
	void versionOnLostParentIsNotClaimed() throws IOException {
		foreignClaim(UNIT, version(OTHER, FOREIGN));
		Version first = version(ME, V1);
		Version second = version(ME, V2, V1);

		List<LostVersion> lost = sharedUploader().run(job(item(UNIT, first), item(UNIT, second)));

		assertEquals(List.of(new LostVersion(UNIT, List.of(V1, V2), FOREIGN)), lost);
		assertFalse(sharedHas("events/e1/claims/" + V1 + ".json"));
		assertFalse(local.readClaimBytes(UNIT, V1.value()).isPresent());
		assertTrue(sharedHas("events/e1/" + V2 + ".json"), "die Versionsdatei wird hochgeladen");
	}

	@Test
	void versionOnParentLostEarlierIsNotClaimed() throws IOException {
		foreignClaim(UNIT, version(OTHER, FOREIGN));
		Version first = version(ME, V1);
		Version second = version(ME, V2, V1);
		assertEquals(List.of(new LostVersion(UNIT, List.of(V1), FOREIGN)), sharedUploader().run(job(item(UNIT, first))));

		List<LostVersion> lost = sharedUploader().run(job(item(UNIT, second)));

		assertEquals(List.of(new LostVersion(UNIT, List.of(V2), FOREIGN)), lost);
		assertFalse(sharedHas("events/e1/claims/" + V1 + ".json"));
		assertEquals(FOREIGN, StoreCodec.readClaim(local.readClaimBytes(UNIT, "root").orElseThrow()).version());
	}

	@Test
	void versionOnWonParentIsClaimed() throws IOException {
		Version first = version(ME, V1);
		Version second = version(ME, V2, V1);
		Version third = version(ME, V3, V2);

		List<LostVersion> lost = sharedUploader().run(job(item(UNIT, first), item(UNIT, second), item(UNIT, third)));

		assertTrue(lost.isEmpty());
		assertEquals(V2, StoreCodec.readClaim(shared.readClaimBytes(UNIT, V1.value()).orElseThrow()).version());
		assertEquals(V3, StoreCodec.readClaim(shared.readClaimBytes(UNIT, V2.value()).orElseThrow()).version());
		assertTrue(local.readClaimBytes(UNIT, V2.value()).isPresent());
	}

	@Test
	void claimWithUnclaimedParentThrows() throws IOException {
		Version first = version(ME, V1);
		Version second = version(ME, V2, V1);
		assertTrue(shared.writeVersion(UNIT, V1, StoreCodec.write(first)));
		// Der Anspruch hinter root fehlt: der Zustand ist uneindeutig
		SharedArbiter arbiter = new SharedArbiter(shared, local, CLOCK);

		assertThrows(IOException.class, () -> arbiter.claim(UNIT, second, StoreCodec.write(second)));

		assertFalse(sharedHas("events/e1/claims/" + V1 + ".json"));
	}

	@Test
	void localArbiterAlwaysWinsOnFreeName() throws IOException {
		LocalArbiter arbiter = new LocalArbiter(local, CLOCK);
		Version first = version(ME, V1);
		byte[] firstBytes = StoreCodec.write(first);

		ClaimOutcome won = arbiter.claim(UNIT, first, firstBytes);

		assertTrue(won.won());
		assertEquals(new Claim(V1, StoreCodec.sha256(firstBytes), ME, AT), won.claim());
		assertEquals(won.claim(), StoreCodec.readClaim(local.readClaimBytes(UNIT, "root").orElseThrow()));
		// Ein zweiter Anspruch auf denselben Platz verliert, auch ohne Prüfung des Vorgängers
		Version second = version(ME, V2);
		ClaimOutcome lost = arbiter.claim(UNIT, second, StoreCodec.write(second));
		assertFalse(lost.won());
		assertEquals(V1, lost.claim().version());
		// Wiederholung desselben Anspruchs gilt als gewonnen
		assertTrue(arbiter.claim(UNIT, first, firstBytes).won());
		// Der Vorgänger wird nicht geprüft: V3 hinter V2 (ohne Anspruch dafür) gewinnt
		Version third = version(ME, V3, V2);
		assertTrue(arbiter.claim(UNIT, third, StoreCodec.write(third)).won());
		Optional<byte[]> claim = local.readClaimBytes(UNIT, V2.value());
		assertEquals(V3, StoreCodec.readClaim(claim.orElseThrow()).version());
	}

	@Test
	void localArbiterWorksThroughUploader() throws IOException {
		Uploader uploader = uploader(new LocalArbiter(local, CLOCK));

		List<LostVersion> lost = uploader.run(job(item(UNIT, version(ME, V1))));

		assertTrue(lost.isEmpty());
		assertTrue(local.readClaimBytes(UNIT, "root").isPresent());
	}
}
