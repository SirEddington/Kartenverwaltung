package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

	private static final Instant NOW = Instant.parse("2026-11-07T13:00:00Z");

	private FakeScheduler scheduler;
	private FakeUpdateSource source;
	private UpdateChecker checker;
	private final List<UpdateState> seen = new ArrayList<>();

	private static UpdateInfo info(String version, String revision) {
		return new UpdateInfo(AppVersion.parse(version, revision).orElseThrow(), "EcvKarten_x", false);
	}

	@BeforeEach
	void setUp() {
		Map<String, String> props = Map.of("app.version", "1.1.0", "app.revision", "1", "app.fsname", "ecv-karten",
				"app.repositoryUrl", "http://localhost:8765/upd", "os.name", "Linux");
		Installation inst = Installation.from(props::get, Map.<String, String>of()::get).orElseThrow();
		scheduler = new FakeScheduler();
		source = new FakeUpdateSource(info("1.1.0", "2"));
		checker = new UpdateChecker(inst, source, scheduler, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));
		checker.setListener(seen::add);
	}

	@Test
	void startCleansUpThenSchedules10sAnd4h() {
		checker.start();
		scheduler.runQueued();
		assertEquals(1, source.cleanups.get());
		assertEquals(Duration.ofSeconds(10), scheduler.initialDelay);
		assertEquals(Duration.ofHours(4), scheduler.delay);
		assertEquals(0, source.fetches.get());
	}

	@Test
	void newerVersionIsAvailable() {
		checker.start();
		scheduler.tick.run();
		UpdateState.Available a = assertInstanceOf(UpdateState.Available.class, checker.state());
		assertEquals("1.1.0 (Rev. 2)", a.neu().version().display());
		assertEquals(NOW, a.geprueft());
	}

	@Test
	void sameOrOlderIsUpToDate() {
		source.info = info("1.1.0", "1");
		checker.start();
		scheduler.tick.run();
		assertEquals(new UpdateState.UpToDate(NOW), checker.state());
		source.info = info("1.0.1", "0");
		scheduler.tick.run();
		assertInstanceOf(UpdateState.UpToDate.class, checker.state());
	}

	@Test
	void checkingCarriesPrevious() {
		checker.start();
		scheduler.tick.run();
		UpdateState available = checker.state();
		scheduler.tick.run();
		assertTrue(seen.contains(new UpdateState.Checking(available)));
	}

	@Test
	void failureKeepsKnownUpdate() {
		checker.start();
		scheduler.tick.run();
		UpdateState.Available available = (UpdateState.Available) checker.state();
		source.failure = new UnknownHostException("github.com");
		scheduler.tick.run();
		assertEquals(new UpdateState.CheckFailed(Grund.NETWORK, NOW, Optional.of(available)), checker.state());
	}

	@Test
	void checkNowWhileRunningIsIgnored() {
		checker.start();
		source.duringFetch = () -> checker.checkNow();
		scheduler.tick.run();
		source.duringFetch = () -> {
		};
		scheduler.runQueued();
		assertEquals(1, source.fetches.get());
	}

	@Test
	void tickWhileRunningIsSkipped() {
		checker.start();
		checker.checkNow();
		source.duringFetch = () -> scheduler.tick.run();
		scheduler.runQueued();
		assertEquals(1, source.fetches.get());
	}

	@Test
	void lockIsFreeAfterCheck() {
		checker.start();
		checker.checkNow();
		scheduler.runQueued();
		checker.checkNow();
		scheduler.runQueued();
		assertEquals(2, source.fetches.get());
	}

	@Test
	void checkNowAfterCloseLeavesLockFree() {
		checker.start();
		scheduler.closed = true;
		checker.checkNow();
		scheduler.closed = false;
		checker.checkNow();
		scheduler.runQueued();
		assertEquals(1, source.fetches.get());
	}

	@Test
	void listenerExceptionDoesNotStopTicks() {
		checker.setListener(state -> {
			throw new IllegalStateException("Listener kaputt");
		});
		checker.start();
		scheduler.tick.run();
		scheduler.tick.run();
		assertEquals(2, source.fetches.get());
		assertInstanceOf(UpdateState.Available.class, checker.state());
	}

	@Test
	void cleanupExceptionIsLoggedAndFirstCheckStillRuns() {
		source.cleanupFailure = new IllegalStateException("gesperrt");
		checker.start();
		scheduler.runQueued();
		scheduler.tick.run();
		assertInstanceOf(UpdateState.Available.class, checker.state());
	}

	@Test
	void closeDiscardsResultsAndClosesScheduler() {
		checker.start();
		checker.close();
		assertTrue(scheduler.closed);
		scheduler.tick.run();
		assertEquals(new UpdateState.NotChecked(), checker.state());
	}

	@Test
	void lastSuccessAfterAvailable() {
		checker.start();
		scheduler.tick.run();
		assertEquals(Optional.of(new UpdateSuccess(info("1.1.0", "2"), NOW)), checker.lastSuccess());
	}

	@Test
	void lastSuccessAfterUpToDate() {
		source.info = info("1.1.0", "1");
		checker.start();
		scheduler.tick.run();
		assertEquals(Optional.of(new UpdateSuccess(info("1.1.0", "1"), NOW)), checker.lastSuccess());
	}

	@Test
	void lastSuccessSurvivesCheckFailed() {
		source.info = info("1.1.0", "1");
		checker.start();
		scheduler.tick.run();
		source.failure = new UnknownHostException("github.com");
		scheduler.tick.run();
		assertInstanceOf(UpdateState.CheckFailed.class, checker.state());
		assertEquals(Optional.of(new UpdateSuccess(info("1.1.0", "1"), NOW)), checker.lastSuccess());
	}

	@Test
	void lastSuccessSetBeforeListener() {
		List<Optional<UpdateSuccess>> atListener = new ArrayList<>();
		checker.setListener(state -> {
			if (state instanceof UpdateState.Available) {
				atListener.add(checker.lastSuccess());
			}
		});
		checker.start();
		scheduler.tick.run();
		assertEquals(List.of(Optional.of(new UpdateSuccess(info("1.1.0", "2"), NOW))), atListener);
	}

	@Test
	void noLastSuccessAfterClose() {
		checker.start();
		checker.close();
		scheduler.tick.run();
		assertEquals(Optional.empty(), checker.lastSuccess());
	}

	@Test
	void noLastSuccessBeforeFirstCheck() {
		assertEquals(Optional.empty(), checker.lastSuccess());
	}

	@Test
	void unexpectedRuntimeExceptionBecomesUnknown() {
		source.failure = new IllegalStateException("unerwartet");
		checker.start();
		scheduler.tick.run();
		assertEquals(Grund.UNKNOWN, assertInstanceOf(UpdateState.CheckFailed.class, checker.state()).grund());
	}
}
