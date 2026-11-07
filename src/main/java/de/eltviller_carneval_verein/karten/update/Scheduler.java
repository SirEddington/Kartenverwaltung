package de.eltviller_carneval_verein.karten.update;

import java.time.Duration;

/** Der Takt der Update-Prüfung; in Tests gefälscht (KARV-131). */
public interface Scheduler {

	/** Führt {@code task} nach {@code initialDelay} aus und danach jeweils {@code delay} nach dem Ende des vorigen Laufs. */
	void scheduleWithFixedDelay(Runnable task, Duration initialDelay, Duration delay);

	/** Führt {@code task} sobald wie möglich aus. */
	void execute(Runnable task);

	/** Beendet den Takt. */
	void close();
}
