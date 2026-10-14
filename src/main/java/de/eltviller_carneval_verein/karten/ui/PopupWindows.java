package de.eltviller_carneval_verein.karten.ui;

import java.util.List;

import javafx.stage.PopupWindow;
import javafx.stage.Window;

/**
 * Popups (Dialoge wie der Sitz-Dialog, Menüs, Tooltips) gehören zum Fenster, nicht zur Scene oder zum Controller: Wird
 * der Inhalt ausgetauscht, bleiben sie sonst über der neuen Ansicht stehen.
 */
public final class PopupWindows {

	private PopupWindows() {
	}

	/** Schließt alle Popups, die direkt zum Fenster gehören; {@code null} (noch kein Fenster) tut nichts. */
	public static void hideOwnedBy(Window owner) {
		if (owner == null) {
			return;
		}
		for (Window window : List.copyOf(Window.getWindows())) {
			if (window instanceof PopupWindow popup && popup.getOwnerWindow() == owner) {
				popup.hide();
			}
		}
	}
}
