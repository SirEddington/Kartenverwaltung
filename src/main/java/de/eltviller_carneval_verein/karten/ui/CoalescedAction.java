package de.eltviller_carneval_verein.karten.ui;

import java.util.function.Consumer;

/**
 * Bündelt viele Anforderungen zu einer einzigen Ausführung: Solange eine bereits eingeplante Ausführung noch nicht
 * gelaufen ist, plant {@link #request()} keine weitere ein. Eine {@code ObservableSet} meldet etwa jede Änderung einzeln;
 * bei hundert gewählten Sitzen soll der Plan trotzdem nur einmal neu gezeichnet werden.
 */
public final class CoalescedAction {

	private final Consumer<Runnable> scheduler;
	private final Runnable action;
	private boolean scheduled = false;

	/**
	 * @param scheduler plant eine Ausführung für später ein (in den Ansichten {@code Platform::runLater})
	 * @param action    die gebündelte Aktion
	 */
	public CoalescedAction(Consumer<Runnable> scheduler, Runnable action) {
		this.scheduler = scheduler;
		this.action = action;
	}

	/** Fordert die Aktion an; sie läuft einmal, egal wie oft vorher angefordert wurde. */
	public void request() {
		if (scheduled) {
			return;
		}
		scheduled = true;
		scheduler.accept(this::flush);
	}

	/**
	 * Führt eine angeforderte, noch nicht gelaufene Aktion sofort aus; ohne Anforderung geschieht nichts. So lässt sie sich
	 * vorziehen, etwa vor das nächste Bild einer Ansicht. Die schon eingeplante Ausführung tut danach nichts mehr, es sei
	 * denn, inzwischen wurde neu angefordert.
	 */
	public void flush() {
		if (!scheduled) {
			return;
		}
		scheduled = false;
		action.run();
	}
}
