package de.eltviller_carneval_verein.karten.ui.shared;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.value.ChangeListener;

/**
 * Erkennt das Ende des ersten Laufs einer Verbindung am Fortschritt des Vollabgleichs: Der Fortschritt wechselt von einem
 * Wert auf {@code null}. Ein {@code null} vor dem ersten Wert zählt nicht (der Lauf hat noch nicht begonnen); ein Ausfall
 * des Ordners behält den Wert und beendet nichts. Ohne JavaFX-Knoten; die Rückrufe kommen auf dem Thread, der den Fortschritt
 * ändert.
 */
public final class FirstRunWatch {

	private final ReadOnlyObjectProperty<Double> progress;
	private final ChangeListener<Double> listener = (observable, oldValue, newValue) -> changed(newValue);

	private boolean begun;
	private boolean ended;
	private Runnable onEnded;

	/** Beobachtet den Fortschritt ab jetzt; ist schon ein Wert da, gilt der Lauf als begonnen. */
	public FirstRunWatch(ReadOnlyObjectProperty<Double> progress) {
		this.progress = progress;
		this.begun = progress.get() != null;
		progress.addListener(listener);
	}

	/** Ob der Fortschritt nach dem Erzeugen von einem Wert auf {@code null} gewechselt ist. */
	public synchronized boolean ended() {
		return ended;
	}

	/**
	 * Merkt den Rückruf für das Ende des Laufs; er läuft höchstens einmal. Ist der Lauf schon zu Ende, läuft er sofort.
	 */
	public void onEnded(Runnable callback) {
		boolean runNow;
		synchronized (this) {
			onEnded = callback;
			runNow = ended;
			if (runNow) {
				onEnded = null;
			}
		}
		if (runNow) {
			callback.run();
		}
	}

	/** Entfernt den Listener; danach ändert sich nichts mehr und kein Rückruf kommt. */
	public void dispose() {
		progress.removeListener(listener);
		synchronized (this) {
			onEnded = null;
		}
	}

	private void changed(Double value) {
		Runnable callback;
		synchronized (this) {
			if (ended) {
				return;
			}
			if (value != null) {
				begun = true;
				return;
			}
			if (!begun) {
				return;
			}
			ended = true;
			callback = onEnded;
			onEnded = null;
		}
		progress.removeListener(listener);
		if (callback != null) {
			callback.run();
		}
	}
}
