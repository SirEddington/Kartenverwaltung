package de.eltviller_carneval_verein.karten.update;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Der Takt über einen {@link ScheduledExecutorService} mit einem Daemon-Thread „update-pruefung“ (KARV-131). */
public final class ExecutorScheduler implements Scheduler {

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread t = new Thread(task, "update-pruefung");
		t.setDaemon(true);
		return t;
	});

	@Override
	public void scheduleWithFixedDelay(Runnable task, Duration initialDelay, Duration delay) {
		executor.scheduleWithFixedDelay(task, initialDelay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
	}

	@Override
	public void execute(Runnable task) {
		executor.execute(task);
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}
}
