package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.function.Consumer;

/**
 * Bündelt viele Anforderungen zu einer einzigen Ausführung: Solange eine bereits eingeplante Ausführung noch nicht
 * gelaufen ist, plant {@link #request()} keine weitere ein. Eine {@code ObservableSet} meldet etwa jede Änderung einzeln;
 * bei hundert gewählten Sitzen soll der Plan trotzdem nur einmal neu gezeichnet werden.
 */
final class CoalescedAction {

	private final Consumer<Runnable> scheduler;
	private final Runnable action;
	private boolean scheduled = false;

	/**
	 * @param scheduler plant eine Ausführung für später ein (im Kundenfenster {@code Platform::runLater})
	 * @param action    die gebündelte Aktion
	 */
	CoalescedAction(Consumer<Runnable> scheduler, Runnable action) {
		this.scheduler = scheduler;
		this.action = action;
	}

	/** Fordert die Aktion an; sie läuft einmal, egal wie oft vorher angefordert wurde. */
	void request() {
		if (scheduled) {
			return;
		}
		scheduled = true;
		scheduler.accept(() -> {
			scheduled = false;
			action.run();
		});
	}
}
