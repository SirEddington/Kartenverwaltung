package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.store.CloudFolders.CloudTarget;

class ReachabilityTest {

	private static final Path TARGET = Path.of("irrelevant");

	/** Eine Prüfung, die hängt: zwei Sekunden, dann doch erreichbar. */
	private static boolean hangThenAnswer() {
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return true;
	}

	/** Prüft, dass {@code check} unerreichbar meldet und vor 1 s zurückkehrt. */
	private static void assertUnreachableQuickly(BooleanSupplier check) {
		long start = System.nanoTime();
		boolean reachable = check.getAsBoolean();
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

		assertFalse(reachable);
		assertTrue(elapsedMillis < 1000, "Rückkehr nach " + elapsedMillis + " ms");
	}

	@Test
	void probeThatBlocksCountsAsUnreachable() {
		assertUnreachableQuickly(
				() -> Reachability.isReachable(TARGET, path -> hangThenAnswer(), Duration.ofMillis(100)));
	}

	@Test
	void probeThatThrowsCountsAsUnreachable() {
		assertFalse(Reachability.isReachable(TARGET, path -> {
			throw new IllegalStateException("Laufwerk weg");
		}, Duration.ofSeconds(1)));
	}

	@Test
	void probeResultIsReturned() {
		assertTrue(Reachability.isReachable(TARGET, TARGET::equals, Duration.ofSeconds(1)));
		assertFalse(Reachability.isReachable(TARGET, path -> false, Duration.ofSeconds(1)));
	}

	@Test
	void defaultChecksForAnExistingFolder(@TempDir Path dir) {
		assertEquals(Duration.ofSeconds(5), Reachability.DEFAULT_TIMEOUT);
		assertTrue(Reachability.isReachable(dir));
		assertFalse(Reachability.isReachable(dir.resolve("fehlt")));
	}

	// --- KARV-127: Cloud-Ordner zählen erst, wenn ihr Server antwortet

	private static final CloudTarget SERVER = new CloudTarget("cloud.example.de", 443);

	/** Jeder Ordner unter {@code dir} gehört zum Nextcloud-Konto mit {@link #SERVER}. */
	private static CloudFolders cloudAt(Path dir) {
		String cfg = "[Accounts]\n0\\url=https://cloud.example.de\n0\\Folders\\1\\localPath="
				+ dir.toString().replace('\\', '/') + "/\n";
		return new CloudFolders(() -> Optional.of(cfg), name -> null);
	}

	private static final CloudFolders NO_CLOUD = new CloudFolders(Optional::empty, name -> null);

	/** Antwortet nach der Reihe mit {@code answers} (danach wie zuletzt) und zählt die Fragen. */
	private static final class ScriptedServer implements ServerProbe {

		private final boolean[] answers;
		private final List<CloudTarget> asked = new ArrayList<>();

		ScriptedServer(boolean... answers) {
			this.answers = answers;
		}

		@Override
		public boolean answers(String host, int port) {
			asked.add(new CloudTarget(host, port));
			return answers[Math.min(asked.size(), answers.length) - 1];
		}
	}

	@Test
	void cloudFolderIsReachableWhenItsServerAnswers(@TempDir Path dir) {
		ScriptedServer server = new ScriptedServer(true);

		assertTrue(Reachability.isReachable(dir, cloudAt(dir), server, true, Duration.ofSeconds(5)));
		assertEquals(List.of(SERVER), server.asked);
	}

	@Test
	void cloudFolderWithoutServerIsUnreachableAlthoughItExists(@TempDir Path dir) {
		ScriptedServer server = new ScriptedServer(false);

		assertFalse(Reachability.isReachable(dir, cloudAt(dir), server, true, Duration.ofSeconds(5)));
		assertEquals(List.of(SERVER, SERVER), server.asked, "ein zweiter Versuch gegen kurze Aussetzer");
	}

	@Test
	void secondAttemptCanReachTheServer(@TempDir Path dir) {
		assertTrue(Reachability.isReachable(dir, cloudAt(dir), new ScriptedServer(false, true), true, Duration.ofSeconds(5)));
	}

	@Test
	void switchedOffCloudCheckOnlyLooksForTheFolder(@TempDir Path dir) {
		ScriptedServer server = new ScriptedServer(false);

		assertTrue(Reachability.isReachable(dir, cloudAt(dir), server, false, Duration.ofSeconds(5)));
		assertTrue(server.asked.isEmpty());
	}

	@Test
	void folderOfNoCloudClientNeedsNoServer(@TempDir Path dir) {
		ScriptedServer server = new ScriptedServer(false);

		assertTrue(Reachability.isReachable(dir, NO_CLOUD, server, true, Duration.ofSeconds(5)));
		assertTrue(server.asked.isEmpty());
	}

	@Test
	void missingCloudFolderIsUnreachableWithoutAskingTheServer(@TempDir Path dir) {
		ScriptedServer server = new ScriptedServer(true);

		assertFalse(Reachability.isReachable(dir.resolve("fehlt"), cloudAt(dir), server, true, Duration.ofSeconds(5)));
		assertTrue(server.asked.isEmpty());
	}

	@Test
	void hangingServerCheckEndsAtTheTimeout(@TempDir Path dir) {
		ServerProbe hanging = (host, port) -> hangThenAnswer();

		assertUnreachableQuickly(() -> Reachability.isReachable(dir, cloudAt(dir), hanging, true, Duration.ofMillis(100)));
	}
}
