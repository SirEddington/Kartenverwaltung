package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.update.FolderDownloaderTest.FakeTasks;
import de.eltviller_carneval_verein.karten.update.StationRegistryTest.MutableClock;

class FolderWatchTest {

	private static final Instant NOW = Instant.parse("2026-11-10T13:00:00Z");
	private static final String FAMILY = "EcvKartenFx132_0cs1gtds5d9x0";
	private static final String FS = "ecv-karten-fx132";
	private static final AppVersion OWN = new AppVersion("1.1.0", 1);

	@TempDir
	Path tmp;

	private Path folder;
	private Path updates;
	private MutableClock clock;
	private FakeSharedFolder access;
	private FakeScheduler scheduler;
	private FakeTasks tasks;
	private final AtomicInteger checkNows = new AtomicInteger();
	private Optional<UpdateSuccess> lastSuccess = Optional.empty();
	private Supplier<UpdateState> checkState = () -> new UpdateState.NotChecked();
	private final List<FolderState> published = new ArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		folder = Files.createDirectories(tmp.resolve("gemeinsam"));
		updates = folder.resolve("updates");
		clock = new MutableClock(NOW);
		access = new FakeSharedFolder(folder);
		scheduler = new FakeScheduler();
		tasks = new FakeTasks();
	}

	private static Optional<Installation> windows() {
		return Installation.from(Map.of("app.version", "1.1.0", "app.revision", "1", "app.fsname", FS, "app.repositoryUrl",
				"http://127.0.0.1:9/src/", "os.name", "Windows 11", "app.windows.userModelID", FAMILY + "!App")::get,
				Map.of("PACKAGE_LOCALAPPDATA", "C:\\Users\\x\\AppData\\Local\\Packages\\" + FAMILY + "\\LocalCache\\Local")::get);
	}

	private static Optional<Installation> linux() {
		return Installation.from(Map.of("app.version", "1.1.0", "app.revision", "1", "app.fsname", FS, "app.repositoryUrl",
				"http://127.0.0.1:9/src/", "os.name", "Linux")::get, Map.<String, String>of()::get);
	}

	private FolderWatch watch(Optional<Installation> inst, Optional<AppVersion> own) {
		OwnFiles ownFiles = new OwnFiles();
		boolean paket = inst.map(i -> i.plattform() == Plattform.WINDOWS_PAKET).orElse(false);
		Optional<FolderUpdates> fu = paket ? Optional.of(new FolderUpdates(clock, ownFiles, access::isOwnTempTag))
				: Optional.empty();
		Optional<FolderDownloader> dl = paket
				? Optional.of(new FolderDownloader(inst.get(), access, ownFiles, clock, new java.util.Random(1), tasks,
						Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(30), 250L * 1024 * 1024))
				: Optional.empty();
		FolderWatch w = new FolderWatch(inst, own, FS, access, new StationRegistry(access, ownFiles, clock), fu, dl,
				() -> lastSuccess, () -> checkState.get(), checkNows::incrementAndGet, scheduler, Runnable::run, clock);
		w.setListener(published::add);
		w.start();
		return w;
	}

	private static Optional<UpdateSuccess> success(AppVersion v, Instant at) {
		return Optional.of(new UpdateSuccess(new UpdateInfo(v, FAMILY, false), at));
	}

	private void installer(String name, String quad) throws IOException {
		Files.createDirectories(updates);
		TestMsix.standard(updates.resolve(name), quad);
	}

	private void foreignStation(String id, String version) throws IOException {
		Path k = Files.createDirectories(updates.resolve("kassen"));
		Files.writeString(k.resolve(id + ".json"), "{\"format\":1,\"machineId\":\"" + id + "\",\"name\":\"Kasse 2\",\"fsname\":\""
				+ FS + "\",\"version\":\"" + version + "\",\"revision\":0,\"plattform\":\"WINDOWS_PAKET\",\"gestartet\":\""
				+ NOW + "\"}");
	}

	@Test
	void startSchedules30sThen10min() {
		watch(Optional.empty(), Optional.of(OWN));
		assertEquals(Duration.ofSeconds(30), scheduler.initialDelay);
		assertEquals(Duration.ofMinutes(10), scheduler.delay);
	}

	@Test
	void tickWithMissingFolderDoesNothing() {
		access.folder = tmp.resolve("getrennt");
		FolderWatch w = watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		assertFalse(Files.exists(tmp.resolve("getrennt")));
		assertEquals(FolderState.empty(), w.state());
	}

	@Test
	void noFolderConfiguredGivesEmptyState() {
		access.folder = null;
		FolderWatch w = watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		assertEquals(FolderState.empty(), w.state());
	}

	@Test
	void writesOwnEntryWithoutInstallation() {
		FolderWatch w = watch(Optional.empty(), Optional.of(OWN));
		scheduler.tick.run();
		assertTrue(Files.exists(updates.resolve("kassen").resolve(access.machineId + ".json")));
		assertEquals(1, w.state().stations().size());
		assertEquals("SONSTIGE", w.state().stations().get(0).plattform());
	}

	@Test
	void noEntryWithoutVersion() {
		watch(Optional.empty(), Optional.empty()).hashCode();
		scheduler.tick.run();
		assertFalse(Files.exists(updates.resolve("kassen")));
	}

	@Test
	void newerStationReported() throws IOException {
		foreignStation("bbbb2222", "1.1.1");
		FolderWatch w = watch(Optional.empty(), Optional.of(OWN));
		scheduler.tick.run();
		assertEquals(Optional.of("bbbb2222"), w.state().newerStation().map(StationEntry::machineId));
	}

	@Test
	void offerUsesPresenceNotReachability() throws IOException {
		access.reachable = false;
		installer("a.msix", "1.1.0.2");
		FolderWatch w = watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		assertEquals(Optional.of(new AppVersion("1.1.0", 2)), w.state().offer().map(FolderOffer::version));
	}

	@Test
	void withdrawnReported() throws IOException {
		installer("a.msix", "1.1.0.3");
		clock.now = NOW.plusSeconds(60);
		FolderWatch w = watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		lastSuccess = success(new AppVersion("1.1.0", 2), NOW.plusSeconds(120));
		clock.now = NOW.plusSeconds(120);
		scheduler.tick.run();
		assertEquals(Optional.empty(), w.state().offer());
		assertEquals(Optional.of(new AppVersion("1.1.0", 3)), w.state().withdrawn());
	}

	@Test
	void noDownloadWhenUnreachable() {
		access.reachable = false;
		lastSuccess = success(new AppVersion("1.1.0", 2), NOW);
		watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		tasks.drain();
		assertFalse(Files.exists(updates.resolve(UpdatesDir.claimName("1.1.0.2", access.machineId))));
	}

	@Test
	void downloadOfferedOnAvailable() {
		lastSuccess = success(new AppVersion("1.1.0", 2), NOW);
		watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		assertEquals(1, tasks.now.size());
	}

	@Test
	void downloadOfferedOnUpToDateSameVersion() {
		lastSuccess = success(OWN, NOW);
		checkState = () -> new UpdateState.UpToDate(NOW);
		watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		assertEquals(1, tasks.now.size());
	}

	@Test
	void downloadStillOfferedAfterCheckFailed() {
		lastSuccess = success(OWN, NOW);
		checkState = () -> new UpdateState.CheckFailed(Grund.NETWORK, NOW, Optional.empty());
		watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		assertEquals(1, tasks.now.size());
	}

	@Test
	void noDownloadWithoutSuccess() {
		watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		assertTrue(tasks.now.isEmpty());
	}

	@Test
	void unclearTriggersCheckNowOnce() throws IOException {
		installer("a.msix", "1.1.0.3");
		lastSuccess = success(new AppVersion("1.1.0", 2), NOW.minusSeconds(60));
		FolderWatch w = watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		scheduler.tick.run();
		w.rescanNow();
		scheduler.runQueued();
		assertEquals(1, checkNows.get());
		assertEquals(Optional.empty(), w.state().offer());
		assertEquals(Optional.empty(), w.state().withdrawn());
	}

	@Test
	void unclearOfflineThenFolder() throws IOException {
		installer("a.msix", "1.1.0.3");
		lastSuccess = success(new AppVersion("1.1.0", 2), NOW.minusSeconds(60));
		FolderWatch w = watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		clock.now = NOW.plusSeconds(10);
		checkState = () -> new UpdateState.CheckFailed(Grund.NETWORK, NOW.plusSeconds(10), Optional.empty());
		scheduler.tick.run();
		assertEquals(Optional.of(new AppVersion("1.1.0", 3)), w.state().offer().map(FolderOffer::version));
		assertEquals(1, checkNows.get());
	}

	@Test
	void linuxGetsWarningOnly() throws IOException {
		installer("a.msix", "1.1.0.2");
		foreignStation("bbbb2222", "1.1.1");
		lastSuccess = success(new AppVersion("1.1.0", 2), NOW);
		FolderWatch w = watch(linux(), Optional.of(OWN));
		scheduler.tick.run();
		assertEquals(Optional.empty(), w.state().offer());
		assertTrue(w.state().newerStation().isPresent());
		assertTrue(tasks.now.isEmpty());
	}

	@Test
	void otherFamilyGivesReinstallNeeded() throws IOException {
		Files.createDirectories(updates);
		TestMsix.write(updates.resolve("b.msix"), TestMsix.manifestWithName("Andere", "1.2.0.0"), TestMsix.standardExtras());
		FolderWatch w = watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		assertEquals(Optional.of(new AppVersion("1.2.0", 0)), w.state().reinstallNeeded());
	}

	@Test
	void listenerGetsEveryState() {
		watch(Optional.empty(), Optional.of(OWN));
		scheduler.tick.run();
		scheduler.tick.run();
		assertEquals(2, published.size());
	}

	@Test
	void loadingShownWhileWaiting() {
		lastSuccess = success(new AppVersion("1.1.0", 2), NOW);
		FolderWatch w = watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		tasks.drain();
		scheduler.runQueued();
		assertEquals(Optional.of(new AppVersion("1.1.0", 2)), w.state().loading());
	}

	@Test
	void closeStopsDownloaderAndScheduler() {
		FolderWatch w = watch(windows(), Optional.of(OWN));
		w.close();
		assertTrue(tasks.shut);
		assertTrue(scheduler.closed);
	}

	@Test
	void exceptionInStepKeepsTicking() throws IOException {
		installer("a.msix", "1.1.0.2");
		checkState = () -> {
			throw new IllegalStateException("kaputt");
		};
		FolderWatch w = watch(windows(), Optional.of(OWN));
		scheduler.tick.run();
		checkState = () -> new UpdateState.NotChecked();
		scheduler.tick.run();
		assertEquals(Optional.of(new AppVersion("1.1.0", 2)), w.state().offer().map(FolderOffer::version));
	}
}
