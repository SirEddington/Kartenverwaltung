package de.eltviller_carneval_verein.karten.mirror;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Prüft mit Zeitlimit, ob ein Spiegelordner erreichbar ist. Ein hängendes Netzlaufwerk blockiert Dateizugriffe oft
 * eine halbe Minute; die Prüfung läuft deshalb auf einem eigenen Daemon-Thread, und wer fragt, wartet höchstens
 * bis zum Zeitlimit. Läuft es ab, gilt der Ordner als nicht erreichbar. Ohne JavaFX.
 */
public final class MirrorReachability {

	/** Zeitlimit der Prüfung in der Anwendung. */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

	private MirrorReachability() {
	}

	/**
	 * Fragt {@code probe} für {@code target} auf einem Daemon-Thread ab. Läuft das Zeitlimit ab, wirft die Probe
	 * oder wird der wartende Thread unterbrochen, ist das Ergebnis {@code false}. Ein hängender Prüf-Thread wird
	 * unterbrochen und läuft sonst von selbst aus; er hält die Anwendung beim Beenden nicht auf.
	 */
	public static boolean isReachable(Path target, Predicate<Path> probe, Duration timeout) {
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		Thread thread = new Thread(() -> {
			try {
				result.complete(probe.test(target));
			} catch (RuntimeException e) {
				result.complete(Boolean.FALSE);
			}
		}, "mirror-reachability");
		thread.setDaemon(true);
		thread.start();
		try {
			return Boolean.TRUE.equals(result.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
		} catch (TimeoutException e) {
			thread.interrupt();
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			thread.interrupt();
			return false;
		} catch (ExecutionException e) {
			return false;
		}
	}

	/** Ob {@code target} ein vorhandener Ordner ist; mit {@link #DEFAULT_TIMEOUT}. */
	public static boolean isReachable(Path target) {
		return isReachable(target, Files::isDirectory, DEFAULT_TIMEOUT);
	}
}
