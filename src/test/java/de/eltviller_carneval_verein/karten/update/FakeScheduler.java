package de.eltviller_carneval_verein.karten.update;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/** Ein Scheduler für Tests: merkt sich die Takt-Aufgabe und sammelt einzelne Aufgaben, bis der Test sie ausführt. */
final class FakeScheduler implements Scheduler {

	Runnable tick;
	Duration initialDelay;
	Duration delay;
	final List<Runnable> queued = new ArrayList<>();
	boolean closed;

	@Override
	public void scheduleWithFixedDelay(Runnable task, Duration initialDelay, Duration delay) {
		this.tick = task;
		this.initialDelay = initialDelay;
		this.delay = delay;
	}

	@Override
	public void execute(Runnable task) {
		if (closed) {
			throw new RejectedExecutionException("geschlossen");
		}
		queued.add(task);
	}

	/** Führt alle eingereihten Aufgaben aus, auch solche, die dabei neu dazukommen. */
	void runQueued() {
		while (!queued.isEmpty()) {
			queued.remove(0).run();
		}
	}

	@Override
	public void close() {
		closed = true;
	}
}
