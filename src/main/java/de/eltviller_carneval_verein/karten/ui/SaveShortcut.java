package de.eltviller_carneval_verein.karten.ui;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * Strg+S wirkt wie ein Klick auf "Speichern". Das Kürzel tut nur etwas, solange der Knopf aktiv ist (also im
 * Bearbeitungsmodus).
 *
 * <p>
 * Es setzt zuerst den Fokus auf den Knopf. So läuft derselbe Weg wie beim Mausklick: Eine offene Zelle oder ein Spinner
 * verliert den Fokus und übernimmt den getippten Wert, ein nicht lesbarer Wert sperrt das Speichern einmal
 * ({@code SpinnerInput.blockSaveIfScreenInputRejected}). Danach geht der Fokus zurück, wohin er gehörte, soweit das
 * Ziel noch im Fenster steht, damit man weitertippen kann.
 */
public final class SaveShortcut {

	private static final KeyCombination COMBINATION = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);

	private SaveShortcut() {
	}

	/** Hängt Strg+S an die Szene, in der der Knopf steht (auch wenn er erst später in eine Szene kommt). */
	public static void install(Button saveButton) {
		saveButton.setTooltip(new Tooltip("Speichern (Strg+S)"));
		ChangeListener<Scene> onScene = (obs, oldScene, newScene) -> {
			if (oldScene != null) {
				oldScene.getAccelerators().remove(COMBINATION);
			}
			if (newScene != null) {
				newScene.getAccelerators().put(COMBINATION, () -> trigger(saveButton));
			}
		};
		saveButton.sceneProperty().addListener(onScene);
		if (saveButton.getScene() != null) {
			onScene.changed(saveButton.sceneProperty(), null, saveButton.getScene());
		}
	}

	static void trigger(Button saveButton) {
		if (saveButton.isDisabled()) {
			return;
		}
		Scene scene = saveButton.getScene();
		Node previousFocus = scene == null ? null : scene.getFocusOwner();
		saveButton.requestFocus();
		saveButton.fire();
		if (previousFocus != null && previousFocus != saveButton && previousFocus.getScene() == scene) {
			previousFocus.requestFocus();
		}
	}
}
