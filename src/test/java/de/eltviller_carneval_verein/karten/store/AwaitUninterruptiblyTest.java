package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Das Warten von {@link SyncService} auf das Beiseitelegen bzw. auf laufende Speichervorgänge: Eine Unterbrechung beendet es
 * nicht, sie wird gemeldet, und der Merker ist danach gelöscht. Erst der Aufrufer stellt sie wieder her (beim Beiseitelegen
 * nach dem Takt); bis dahin läuft kein Dateizugriff mit gesetztem Merker.
 */
class AwaitUninterruptiblyTest {

	private final Object monitor = new Object();

	@AfterEach
	void clearInterrupt() {
		Thread.interrupted();
	}

	@Test
	void withoutInterruptNothingIsReported() {
		boolean interrupted;
		synchronized (monitor) {
			interrupted = SyncService.awaitUninterruptibly(monitor, () -> true);
		}

		assertFalse(interrupted);
	}

	/**
	 * Wird ein wartender Thread zugleich geweckt und unterbrochen, darf {@code wait()} normal zurückkehren und den Merker
	 * stehen lassen (JLS 17.2.4). Das sieht so aus wie ein Merker, der schon vor dem Warten stand: Beides zählt als
	 * Unterbrechung und ist danach gelöscht (CI unter Linux, PR #29: "rename" lief mit gesetzter Unterbrechung).
	 */
	@Test
	void interruptStillSetWhenTheConditionHoldsIsReportedAndCleared() {
		Thread.currentThread().interrupt();
		boolean interrupted;
		synchronized (monitor) {
			interrupted = SyncService.awaitUninterruptibly(monitor, () -> true);
		}

		assertTrue(interrupted);
		assertFalse(Thread.currentThread().isInterrupted(), "der Merker ist gelöscht");
	}

	@Test
	void interruptWhileWaitingIsReportedAndCleared() throws Exception {
		AtomicBoolean done = new AtomicBoolean();
		AtomicBoolean reported = new AtomicBoolean();
		AtomicBoolean flagAfter = new AtomicBoolean(true);
		Thread waiter = new Thread(() -> {
			synchronized (monitor) {
				reported.set(SyncService.awaitUninterruptibly(monitor, done::get));
			}
			flagAfter.set(Thread.currentThread().isInterrupted());
		});
		waiter.start();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline && waiter.getState() != Thread.State.WAITING) {
			Thread.onSpinWait();
		}
		assertEquals(Thread.State.WAITING, waiter.getState());

		waiter.interrupt();
		synchronized (monitor) {
			done.set(true);
			monitor.notifyAll();
		}
		waiter.join(10_000);

		assertFalse(waiter.isAlive());
		assertTrue(reported.get());
		assertFalse(flagAfter.get(), "der Merker ist gelöscht");
	}
}
