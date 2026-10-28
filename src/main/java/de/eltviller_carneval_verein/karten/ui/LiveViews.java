package de.eltviller_carneval_verein.karten.ui;

import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * Hilfen für Ansichten, die fremde Änderungen anzeigen: Sie melden sich bei den Repositories an, solange sie zu sehen
 * sind, und wieder ab, sobald sie es nicht mehr sind. So hält kein Repository eine verworfene Ansicht am Leben.
 */
public final class LiveViews {

	private LiveViews() {
	}

	/**
	 * Ruft {@code register} auf, sobald die Szene von {@code anchor} in einem gezeigten Fenster steht, und {@code unregister},
	 * sobald das nicht mehr gilt (Ansicht aus der Szene genommen, Szene ersetzt, Fenster geschlossen). Beides wechselt sich
	 * ab, jeweils höchstens einmal je Wechsel; steht {@code anchor} schon in einem gezeigten Fenster, läuft {@code register}
	 * sofort. Alles auf dem FX-Thread.
	 */
	public static void whileShown(Node anchor, Runnable register, Runnable unregister) {
		new Watch(anchor, register, unregister).update();
	}

	/** Beobachtet die Kette Knoten, Szene, Fenster, gezeigt; hängt sich um, wenn ein Glied wechselt. */
	private static final class Watch {

		private final Node anchor;
		private final Runnable register;
		private final Runnable unregister;
		private final InvalidationListener changed = observable -> update();
		private Scene scene;
		private Window window;
		private boolean registered;

		Watch(Node anchor, Runnable register, Runnable unregister) {
			this.anchor = anchor;
			this.register = register;
			this.unregister = unregister;
			anchor.sceneProperty().addListener(changed);
		}

		void update() {
			Scene currentScene = anchor.getScene();
			if (currentScene != scene) {
				if (scene != null) {
					scene.windowProperty().removeListener(changed);
				}
				scene = currentScene;
				if (scene != null) {
					scene.windowProperty().addListener(changed);
				}
			}
			Window currentWindow = scene == null ? null : scene.getWindow();
			if (currentWindow != window) {
				if (window != null) {
					window.showingProperty().removeListener(changed);
				}
				window = currentWindow;
				if (window != null) {
					window.showingProperty().addListener(changed);
				}
			}
			boolean shown = window != null && window.isShowing();
			if (shown != registered) {
				registered = shown;
				(shown ? register : unregister).run();
			}
		}
	}
}
