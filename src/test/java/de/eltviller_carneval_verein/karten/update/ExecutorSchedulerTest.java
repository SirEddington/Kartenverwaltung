package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ExecutorSchedulerTest {

	private static Thread threadOf(ExecutorScheduler s) throws Exception {
		CompletableFuture<Thread> t = new CompletableFuture<>();
		s.execute(() -> t.complete(Thread.currentThread()));
		try {
			return t.get(5, TimeUnit.SECONDS);
		} finally {
			s.close();
		}
	}

	@Test
	void defaultThreadIsUpdatePruefungDaemon() throws Exception {
		Thread t = threadOf(new ExecutorScheduler());
		assertEquals("update-pruefung", t.getName());
		assertTrue(t.isDaemon());
	}

	@Test
	void threadNameIsConfigurable() throws Exception {
		Thread t = threadOf(new ExecutorScheduler("update-ordner"));
		assertEquals("update-ordner", t.getName());
		assertTrue(t.isDaemon());
	}
}
