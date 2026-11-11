package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.update.StationRegistryTest.MutableClock;

class FolderDownloaderTest {

	private static final Instant NOW = Instant.parse("2026-11-10T13:00:00Z");
	private static final String FAMILY = "EcvKartenFx132_0cs1gtds5d9x0";
	private static final String PUBLISHER = "CN=Kartenverwaltung - Eltviller Carneval Verein";
	private static final String QUAD = "1.1.0.2";
	private static final AppVersion V = new AppVersion("1.1.0", 2);
	private static final AppVersion OWN = new AppVersion("1.1.0", 1);
	private static final String MSIX_PATH = "/rel/ecv-karten-fx132-1.1.0-2.x64.msix";
	private static final String MSIX_NAME = "ecv-karten-fx132-1.1.0-2.x64.msix";

	@TempDir
	Path tmp;

	private Path folder;
	private Path updates;
	private MiniHttpServer server;
	private MutableClock clock;
	private FakeSharedFolder access;
	private OwnFiles own;
	private FakeTasks tasks;
	private FolderDownloader dl;
	private byte[] msixBytes;
	private int changes;
	private int rechecks;

	/** Führt Aufgaben auf Abruf aus; verzögerte erst mit {@link #fireLater()}. */
	static final class FakeTasks implements FolderDownloader.Tasks {
		final List<Runnable> now = new ArrayList<>();
		final List<Runnable> later = new ArrayList<>();
		Duration lastDelay;
		boolean shut;

		@Override
		public void run(Runnable task) {
			now.add(task);
		}

		@Override
		public void runLater(Runnable task, Duration delay) {
			later.add(task);
			lastDelay = delay;
		}

		@Override
		public void shutdown() {
			shut = true;
		}

		void drain() {
			while (!now.isEmpty()) {
				now.remove(0).run();
			}
		}

		void fireLater() {
			List<Runnable> copy = new ArrayList<>(later);
			later.clear();
			copy.forEach(Runnable::run);
			drain();
		}
	}

	private static String appInstaller(String quad, String msixUri) {
		return "<?xml version=\"1.0\" encoding=\"utf-8\"?><AppInstaller xmlns=\"http://schemas.microsoft.com/appx/appinstaller/2018\""
				+ " Version=\"" + quad + "\" Uri=\"http://x/ecv-karten-fx132.appinstaller\"><MainPackage Name=\"EcvKartenFx132\""
				+ " Publisher=\"" + PUBLISHER + "\" Version=\"" + quad + "\" ProcessorArchitecture=\"x64\" Uri=\"" + msixUri
				+ "\"/></AppInstaller>";
	}

	@BeforeEach
	void setUp() throws IOException {
		folder = Files.createDirectories(tmp.resolve("gemeinsam"));
		updates = Files.createDirectories(folder.resolve("updates"));
		server = new MiniHttpServer();
		msixBytes = Files.readAllBytes(TestMsix.standard(tmp.resolve("src.msix"), QUAD));
		server.route("/src/ecv-karten-fx132.appinstaller", 200, appInstaller(QUAD, server.base(MSIX_PATH).toString()));
		server.route(MSIX_PATH, msixBytes);
		clock = new MutableClock(NOW);
		access = new FakeSharedFolder(folder);
		own = new OwnFiles();
		tasks = new FakeTasks();
		dl = newDownloader(250L * 1024 * 1024);
	}

	@AfterEach
	void tearDown() throws IOException {
		server.close();
	}

	private FolderDownloader newDownloader(long maxBytes) {
		Map<String, String> props = Map.of("app.version", "1.1.0", "app.revision", "1", "app.fsname", "ecv-karten-fx132",
				"app.repositoryUrl", server.base("/src/").toString(), "os.name", "Windows 11",
				"app.windows.userModelID", FAMILY + "!EcvKartenFx132");
		Map<String, String> env = Map.of("PACKAGE_LOCALAPPDATA",
				"C:\\Users\\x\\AppData\\Local\\Packages\\" + FAMILY + "\\LocalCache\\Local");
		Installation inst = Installation.from(props::get, env::get).orElseThrow();
		FolderDownloader d = new FolderDownloader(inst, access, own, new LoadClaims(clock), clock, new java.util.Random(7),
				tasks, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(30), maxBytes);
		d.setOnChange(() -> changes++);
		d.setRecheck(() -> rechecks++);
		return d;
	}

	/** Review: Ein Rückzug in begin (ohne eigene Ladedatei) meldet keine Änderung, sonst kreiseln Takt und Download. */
	@Test
	void withdrawInBeginReportsNoChange() throws IOException {
		foreignClaim("bbbb2222", NOW, null);
		int before = changes;
		offer();
		tasks.drain();
		assertEquals(before, changes);
		access.reachable = false;
		Files.delete(updates.resolve(UpdatesDir.claimName(QUAD, "bbbb2222")));
		clock.now = NOW.plus(Duration.ofMinutes(11));
		offer();
		tasks.drain();
		assertEquals(before, changes, "auch nicht bei unerreichbarem Ordner");
	}

	/** Review: Nach einem Rückzug erst beim nächsten regulären Takt wieder, außer eine jüngere Prüfung liegt vor. */
	@Test
	void noReofferBeforeNextTickAfterWithdraw() throws IOException {
		access.reachable = false;
		offer();
		tasks.drain();
		access.reachable = true;
		offer();
		assertTrue(tasks.now.isEmpty(), "gleich danach kein neuer Versuch");
		clock.now = NOW.plus(Duration.ofMinutes(9));
		offer();
		assertTrue(tasks.now.isEmpty());
		dl.offer(folder, new UpdateSuccess(new UpdateInfo(V, FAMILY, false), NOW.plus(Duration.ofMinutes(9))), OWN,
				Optional.empty());
		assertEquals(1, tasks.now.size(), "eine jüngere Prüfung hebt die Pause auf");
	}

	@Test
	void reofferAfterTenMinutes() {
		access.reachable = false;
		offer();
		tasks.drain();
		access.reachable = true;
		clock.now = NOW.plus(Duration.ofMinutes(10));
		offer();
		assertEquals(1, tasks.now.size());
	}

	/** Review: Die eigene „fertig“-Ladedatei eines früheren Laufs bleibt 3 h stehen (Spec), eine unfertige nicht. */
	@Test
	void cleanupKeepsOwnDoneClaimFromEarlierRun() throws IOException {
		foreignClaim(access.machineId, NOW.minus(Duration.ofMinutes(5)), NOW);
		dl.cleanupClaims(folder);
		assertEquals(1, names().size(), "fertig und frisch: bleibt");
		clock.now = NOW.plus(Duration.ofMinutes(181));
		dl.cleanupClaims(folder);
		assertEquals(List.of(), names());
	}

	private static UpdateSuccess source(AppVersion v, String family) {
		return new UpdateSuccess(new UpdateInfo(v, family, false), NOW);
	}

	private void offer() {
		dl.offer(folder, source(V, FAMILY), OWN, Optional.empty());
	}

	private Path ownClaim() {
		return updates.resolve(UpdatesDir.claimName(QUAD, access.machineId));
	}

	private List<String> names() throws IOException {
		try (Stream<Path> s = Files.list(updates)) {
			return s.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	private void foreignClaim(String machine, Instant angelegt, Instant fertig) throws IOException {
		Files.write(updates.resolve(UpdatesDir.claimName(QUAD, machine)), LoadClaims.json(machine, "Kasse", angelegt, fertig));
	}

	private int msixRequests() {
		return server.requests(MSIX_PATH).size();
	}

	/** Ein ganzer Durchlauf: Ladedatei, Wartezeit, Download. */
	private void runAll() {
		offer();
		tasks.drain();
		tasks.fireLater();
	}

	@Test
	void downloadsAndPlaces() throws IOException {
		runAll();
		Path target = updates.resolve(MSIX_NAME);
		assertArrayEquals(msixBytes, Files.readAllBytes(target));
		assertArrayEquals(FolderUpdates.descriptionJson(QUAD, FAMILY, FolderUpdates.sha256Hex(target), msixBytes.length),
				Files.readAllBytes(updates.resolve(MSIX_NAME + ".json")));
		assertTrue(new LoadClaims(clock).read(updates, QUAD).get(0).done());
		assertEquals(List.of(MSIX_NAME, MSIX_NAME + ".json", UpdatesDir.claimName(QUAD, access.machineId)), names());
		assertEquals(Optional.empty(), dl.loading());
		assertTrue(changes > 0);
	}

	@Test
	void waitIsTwoToFiveMinutesAndLoadingIsShown() {
		offer();
		tasks.drain();
		assertTrue(Files.exists(ownClaim()));
		assertTrue(tasks.lastDelay.compareTo(Duration.ofMinutes(2)) >= 0 && tasks.lastDelay.compareTo(Duration.ofMinutes(5)) <= 0,
				tasks.lastDelay.toString());
		assertEquals(Optional.of(V), dl.loading());
	}

	@Test
	void waitDoesNotBlockCaller() {
		offer();
		assertFalse(Files.exists(ownClaim()));
		assertEquals(0, msixRequests());
	}

	@Test
	void followsRedirectToOtherHost() throws IOException {
		server.route("/rel/umweg.msix", new MiniHttpServer.Route(302,
				Map.of("Location", server.viaLocalhost("/real/" + MSIX_NAME).toString()), new byte[0], 0));
		server.route("/real/" + MSIX_NAME, msixBytes);
		server.route("/src/ecv-karten-fx132.appinstaller", 200, appInstaller(QUAD, server.base("/rel/umweg.msix").toString()));
		runAll();
		assertEquals(1, server.requests("/real/" + MSIX_NAME).size());
		assertFalse(Files.exists(updates.resolve(MSIX_NAME)), "Name kommt aus der Uri der .appinstaller");
		assertArrayEquals(msixBytes, Files.readAllBytes(updates.resolve("umweg.msix")));
	}

	@Test
	void waitsWhenForeignFreshClaim() throws IOException {
		foreignClaim("bbbb2222", NOW, null);
		offer();
		tasks.drain();
		assertFalse(Files.exists(ownClaim()));
		assertTrue(tasks.later.isEmpty());
	}

	@Test
	void smallestIdWins() throws IOException {
		access.machineId = "bbbb2222";
		offer();
		tasks.drain();
		assertTrue(Files.exists(ownClaim()));
		foreignClaim("aaaa1111", NOW, null);
		tasks.fireLater();
		assertFalse(Files.exists(ownClaim()));
		assertEquals(0, msixRequests());
		assertEquals(Optional.empty(), dl.loading());
	}

	@Test
	void freshDoneClaimBlocksRegardlessOfId() throws IOException {
		offer();
		tasks.drain();
		foreignClaim("zzzz9999", NOW, NOW);
		tasks.fireLater();
		assertFalse(Files.exists(ownClaim()));
		assertEquals(0, msixRequests());
	}

	@Test
	void noDownloadWhenValidInstallerAtLeastV() {
		dl.offer(folder, source(V, FAMILY), OWN, Optional.of(V));
		tasks.drain();
		assertFalse(Files.exists(ownClaim()));
	}

	@Test
	void downloadWhenSourceEqualsOwn() throws IOException {
		dl.offer(folder, source(V, FAMILY), V, Optional.empty());
		tasks.drain();
		tasks.fireLater();
		assertTrue(Files.exists(updates.resolve(MSIX_NAME)));
	}

	@Test
	void noDownloadWhenSourceOlderThanOwn() {
		dl.offer(folder, source(new AppVersion("1.1.0", 0), FAMILY), OWN, Optional.empty());
		tasks.drain();
		assertFalse(Files.exists(ownClaim()));
	}

	@Test
	void noDownloadForOtherFamily() {
		dl.offer(folder, source(V, "Andere_x"), OWN, Optional.empty());
		tasks.drain();
		assertFalse(Files.exists(ownClaim()));
	}

	@Test
	void noDownloadWhenUnreachable() {
		access.reachable = false;
		offer();
		tasks.drain();
		assertFalse(Files.exists(ownClaim()));
	}

	@Test
	void noDownloadWithoutSpace() throws IOException {
		dl.setFreeSpace(p -> 1000);
		runAll();
		assertEquals(List.of(), names());
	}

	/** Handtest KARV-132: Die Quelle meldet während der Wartezeit eine neuere Version; das ist kein Fehler. */
	@Test
	void sourceChangedDuringWaitIsNoFailure() throws IOException {
		server.route("/src/ecv-karten-fx132.appinstaller", 200, appInstaller("1.1.0.3", server.base(MSIX_PATH).toString()));
		runAll();
		assertEquals(List.of(), names());
		assertEquals(1, rechecks, "eine neue Update-Prüfung wird angestoßen");
		offer();
		assertTrue(tasks.now.isEmpty(), "dasselbe alte Ergebnis wird nicht gleich wieder angeboten");
		dl.offer(folder, new UpdateSuccess(new UpdateInfo(new AppVersion("1.1.0", 3), FAMILY, false), NOW.plusSeconds(1)),
				OWN, Optional.empty());
		assertEquals(1, tasks.now.size(), "keine Sperre: mit dem neuen Ergebnis gleich ein neuer Versuch");
	}

	@Test
	void versionMismatchInAppInstallerAborts() throws IOException {
		server.route("/src/ecv-karten-fx132.appinstaller", 200, appInstaller("1.1.0.3", server.base(MSIX_PATH).toString()));
		runAll();
		assertEquals(0, msixRequests());
		assertEquals(List.of(), names());
	}

	@Test
	void unsafeTargetNameRejected() throws IOException {
		server.route("/rel/..%5Cboese.msix", msixBytes);
		server.route("/src/ecv-karten-fx132.appinstaller", 200, appInstaller(QUAD, server.base("/rel/..%5Cboese.msix").toString()));
		runAll();
		assertEquals(List.of(), names());
		assertFalse(Files.exists(folder.resolve("boese.msix")));
	}

	@Test
	void validTargetPresentDoesNothing() throws IOException {
		Files.write(updates.resolve(MSIX_NAME), msixBytes);
		runAll();
		assertEquals(0, msixRequests());
		assertEquals(List.of(MSIX_NAME), names());
	}

	@Test
	void growingTargetIsNotReplaced() throws IOException {
		Path target = TestMsix.standard(updates.resolve(MSIX_NAME), QUAD);
		TestMsix.flipPayloadByte(target);
		runAll();
		assertEquals(0, msixRequests());
		assertFalse(Files.exists(ownClaim()));
		assertTrue(Files.exists(target));
	}

	@Test
	void invalidStableTargetIsReplaced() throws IOException {
		Path target = TestMsix.standard(updates.resolve(MSIX_NAME), QUAD);
		TestMsix.flipPayloadByte(target);
		Files.setLastModifiedTime(target, FileTime.from(NOW.minus(Duration.ofDays(1))));
		runAll();
		assertEquals(0, msixRequests(), "erst nach 1 h unverändert");
		clock.now = NOW.plus(Duration.ofMinutes(61));
		runAll();
		assertArrayEquals(msixBytes, Files.readAllBytes(target));
	}

	@Test
	void shortBodyIsRejected() throws IOException {
		server.route(MSIX_PATH, new MiniHttpServer.Route(200, Map.of(), msixBytes, 0, msixBytes.length + 500));
		runAll();
		assertEquals(List.of(), names());
	}

	@Test
	void oversizedLengthRejectedUpFront() throws IOException {
		dl = newDownloader(1000);
		runAll();
		assertEquals(List.of(), names());
	}

	@Test
	void oversizedWithoutLengthIsCut() throws IOException {
		server.route(MSIX_PATH, new MiniHttpServer.Route(200, Map.of(), msixBytes, 0, MiniHttpServer.NO_LENGTH));
		dl = newDownloader(1000);
		runAll();
		assertEquals(List.of(), names());
	}

	@Test
	void withoutLengthWithinLimitIsAccepted() throws IOException {
		server.route(MSIX_PATH, new MiniHttpServer.Route(200, Map.of(), msixBytes, 0, MiniHttpServer.NO_LENGTH));
		runAll();
		assertArrayEquals(msixBytes, Files.readAllBytes(updates.resolve(MSIX_NAME)));
	}

	@Test
	void manifestMismatchRejected() throws IOException {
		byte[] other = Files.readAllBytes(TestMsix.write(tmp.resolve("o.msix"), TestMsix.manifestWithName("Andere", QUAD),
				TestMsix.standardExtras()));
		server.route(MSIX_PATH, other);
		runAll();
		assertEquals(List.of(), names());
	}

	@Test
	void lostRenameWritesDescriptionForSameHash() throws IOException {
		dl.setBeforeMove(target -> Files.write(target, msixBytes));
		runAll();
		assertTrue(Files.exists(updates.resolve(MSIX_NAME + ".json")));
		assertEquals(List.of(MSIX_NAME, MSIX_NAME + ".json", UpdatesDir.claimName(QUAD, access.machineId)), names());
	}

	@Test
	void lostRenameWithOtherContentWritesNoDescription() throws IOException {
		dl.setBeforeMove(target -> Files.write(target, "anders".getBytes(StandardCharsets.UTF_8)));
		runAll();
		assertFalse(Files.exists(updates.resolve(MSIX_NAME + ".json")));
		assertFalse(Files.exists(ownClaim()));
		assertEquals(List.of(MSIX_NAME), names());
	}

	@Test
	void folderChangeAborts() throws IOException {
		dl.setBeforeMove(target -> access.folder = tmp.resolve("anders"));
		runAll();
		assertFalse(Files.exists(updates.resolve(MSIX_NAME)));
		assertFalse(Files.exists(updates.resolve(MSIX_NAME + ".json")));
	}

	@Test
	void stopDeletesUnfinishedClaim() {
		offer();
		tasks.drain();
		assertTrue(Files.exists(ownClaim()));
		dl.stop();
		assertFalse(Files.exists(ownClaim()));
		assertTrue(tasks.shut);
		tasks.fireLater();
		assertEquals(0, msixRequests());
	}

	@Test
	void stopKeepsDoneClaim() {
		runAll();
		dl.stop();
		assertTrue(Files.exists(ownClaim()));
	}

	@Test
	void retryNotBeforeOneHourAfterFailure() {
		dl.setFreeSpace(p -> 1000);
		runAll();
		offer();
		assertTrue(tasks.now.isEmpty(), "gesperrt nach Fehler");
		clock.now = NOW.plus(Duration.ofMinutes(61));
		offer();
		assertEquals(1, tasks.now.size());
	}

	@Test
	void retryNowLiftsTheBlock() {
		dl.setFreeSpace(p -> 1000);
		runAll();
		dl.retryNow();
		offer();
		assertEquals(1, tasks.now.size());
	}

	@Test
	void secondOfferWhileRunningIsIgnored() {
		offer();
		offer();
		assertEquals(1, tasks.now.size());
	}

	@Test
	void cleanupForeignClaimAfterOneDay() throws IOException {
		foreignClaim("bbbb2222", NOW.minus(Duration.ofHours(25)), null);
		dl.cleanupClaims(folder);
		assertEquals(List.of(), names());
	}

	@Test
	void cleanupKeepsFreshForeignClaim() throws IOException {
		foreignClaim("bbbb2222", NOW, null);
		dl.cleanupClaims(folder);
		assertEquals(1, names().size());
	}

	@Test
	void cleanupOwnClaimFromEarlierRunOrId() throws IOException {
		foreignClaim(access.machineId, NOW, null);
		access.ownTags.add("alte-id-1");
		foreignClaim("alte-id-1", NOW, null);
		dl.cleanupClaims(folder);
		assertEquals(List.of(), names());
	}

	@Test
	void cleanupKeepsOwnDoneClaimThreeHours() throws IOException {
		runAll();
		clock.now = NOW.plus(Duration.ofMinutes(170));
		dl.cleanupClaims(folder);
		assertTrue(Files.exists(ownClaim()));
		clock.now = NOW.plus(Duration.ofMinutes(190));
		dl.cleanupClaims(folder);
		assertFalse(Files.exists(ownClaim()));
	}

	@Test
	void cleanupDuringWaitKeepsOwnClaim() throws IOException {
		offer();
		tasks.drain();
		dl.cleanupClaims(folder);
		assertTrue(Files.exists(ownClaim()));
		assertTrue(own.contains(ownClaim()));
	}
}
