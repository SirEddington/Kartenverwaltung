package de.eltviller_carneval_verein.karten.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import de.eltviller_carneval_verein.karten.store.CloudFolders.CloudTarget;

/**
 * Prüft mit Zeitlimit, ob der gemeinsame Ordner erreichbar ist. Ein hängendes Netzlaufwerk blockiert Dateizugriffe oft
 * eine halbe Minute; die Prüfung läuft deshalb auf einem eigenen Daemon-Thread, und wer fragt, wartet höchstens bis zum
 * Zeitlimit. Läuft es ab, gilt der Ordner als nicht erreichbar. Ohne JavaFX.
 * <p>
 * <b>Cloud-Ordner</b> (KARV-127): Ein Ordner von Nextcloud oder OneDrive ({@link CloudFolders}) liegt lokal und ist
 * deshalb immer da. Er gilt erst als erreichbar, wenn auch der Server seines Clients antwortet ({@link ServerProbe}, ein
 * zweiter Versuch gegen kurze Aussetzer), solange die Prüfung eingeschaltet ist ({@link #setCloudCheck}, in der
 * Anwendung die Einstellung "Verbindung zum Cloud-Server prüfen"). Ausgeschaltet zählt wie bisher nur der Ordner.
 */
public final class Reachability {

	/** Zeitlimit der Prüfung in der Anwendung. */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

	/** Zeitlimit eines Verbindungsaufbaus zum Server; zwei Versuche passen in {@link #DEFAULT_TIMEOUT}. */
	private static final Duration SERVER_TIMEOUT = Duration.ofSeconds(2);

	private static final CloudFolders SYSTEM_CLOUDS = CloudFolders.system();
	private static final ServerProbe SYSTEM_SERVER = ServerProbe.tcp(SERVER_TIMEOUT);

	private static volatile boolean cloudCheck = true;

	private Reachability() {
	}

	/** Schaltet die Prüfung des Cloud-Servers ein oder aus (Standard: ein); gilt ab der nächsten Prüfung. */
	public static void setCloudCheck(boolean enabled) {
		cloudCheck = enabled;
	}

	/**
	 * Fragt {@code probe} für {@code target} auf einem Daemon-Thread ab. Läuft das Zeitlimit ab, wirft die Probe oder wird
	 * der wartende Thread unterbrochen, ist das Ergebnis {@code false}. Ein hängender Prüf-Thread wird unterbrochen und
	 * läuft sonst von selbst aus; er hält die Anwendung beim Beenden nicht auf.
	 */
	public static boolean isReachable(Path target, Predicate<Path> probe, Duration timeout) {
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		Thread thread = new Thread(() -> {
			try {
				result.complete(probe.test(target));
			} catch (RuntimeException e) {
				result.complete(Boolean.FALSE);
			}
		}, "store-reachability");
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

	/**
	 * Ob {@code target} ein vorhandener Ordner ist und, falls er zu einem Cloud-Client gehört und {@code checkServer} gilt,
	 * dessen Server antwortet (höchstens zwei Versuche); alles zusammen innerhalb von {@code timeout}.
	 */
	public static boolean isReachable(Path target, CloudFolders clouds, ServerProbe server, boolean checkServer,
			Duration timeout) {
		return isReachable(target, path -> {
			if (!Files.isDirectory(path)) {
				return false;
			}
			if (!checkServer) {
				return true;
			}
			return clouds.find(path.toString()).map(cloud -> answersTwice(server, cloud)).orElse(true);
		}, timeout);
	}

	/** Ein zweiter Versuch gegen kurze Aussetzer (etwa ein verlorenes Paket beim WLAN-Wechsel). */
	private static boolean answersTwice(ServerProbe server, CloudTarget target) {
		return server.answers(target.host(), target.port()) || server.answers(target.host(), target.port());
	}

	/**
	 * Ob {@code target} erreichbar ist: mit der Erkennung der Cloud-Clients dieses Rechners, dem Schalter aus
	 * {@link #setCloudCheck} und {@link #DEFAULT_TIMEOUT}.
	 */
	public static boolean isReachable(Path target) {
		return isReachable(target, SYSTEM_CLOUDS, SYSTEM_SERVER, cloudCheck, DEFAULT_TIMEOUT);
	}
}
