package de.eltviller_carneval_verein.karten.mirror;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class MirrorReachabilityTest {

	private static final Path TARGET = Path.of("irrelevant");

	@Test
	void probeThatBlocksCountsAsUnreachable() {
		long start = System.nanoTime();
		boolean reachable = MirrorReachability.isReachable(TARGET, path -> {
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return true;
		}, Duration.ofMillis(100));
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

		assertFalse(reachable);
		assertTrue(elapsedMillis < 1000, "Rückkehr nach " + elapsedMillis + " ms");
	}

	@Test
	void probeThatThrowsCountsAsUnreachable() {
		assertFalse(MirrorReachability.isReachable(TARGET, path -> {
			throw new IllegalStateException("Laufwerk weg");
		}, Duration.ofSeconds(1)));
	}

	@Test
	void probeResultIsReturned() {
		assertTrue(MirrorReachability.isReachable(TARGET, path -> true, Duration.ofSeconds(1)));
		assertFalse(MirrorReachability.isReachable(TARGET, path -> false, Duration.ofSeconds(1)));
	}

	@Test
	void probeReceivesTheTarget() {
		assertTrue(MirrorReachability.isReachable(TARGET, TARGET::equals, Duration.ofSeconds(1)));
	}
}
