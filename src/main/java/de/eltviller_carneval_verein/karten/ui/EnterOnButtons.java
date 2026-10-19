package de.eltviller_carneval_verein.karten.ui;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Enter löst auf einem Knopf mit Fokus nichts aus. JavaFX drückt einen fokussierten Knopf sonst auch bei Enter (wie bei
 * der Leertaste): Nach dem Klick auf "Bearbeiten" behält der Knopf den Fokus, und ein Enter, das in keiner Zelle und
 * keinem Textfeld landet, schaltete den Modus um. Klick, Leertaste und Strg+S (siehe {@link SaveShortcut}) bleiben.
 *
 * <p>
 * Der Filter hängt an den Szenen des Hauptfensters ({@code MainApp.createScene}). Dialoge sind eigene Fenster und bleiben
 * unberührt: Dort ist Enter auf dem fokussierten Knopf gewollt, etwa auf "Abbrechen" im Dialog "Belegte Sitze".
 * Menüknöpfe, Checkboxen und Radiobuttons sind keine {@link Button}.
 */
public final class EnterOnButtons {

	private EnterOnButtons() {
	}

	public static void install(Scene scene) {
		scene.addEventFilter(KeyEvent.ANY, event -> {
			if (swallows(event)) {
				event.consume();
			}
		});
	}

	/** {@code true} für Enter, das auf einem einfachen Knopf landen würde. */
	static boolean swallows(KeyEvent event) {
		return event.getCode() == KeyCode.ENTER && event.getTarget() instanceof Button;
	}
}
