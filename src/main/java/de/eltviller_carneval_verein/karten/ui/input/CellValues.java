package de.eltviller_carneval_verein.karten.ui.input;

import java.util.Objects;

/** Vergleiche für Eingaben in Tabellenzellen, ohne JavaFX (einzeln testbar). */
final class CellValues {

	private CellValues() {
	}

	/** Ob die Eingabe den Wert der Zelle nicht ändert; ein fehlender Text und ein leerer gelten als gleich. */
	static <V> boolean isUnchanged(V current, V entered) {
		if (Objects.equals(current, entered)) {
			return true;
		}
		return isBlankText(current) && isBlankText(entered);
	}

	private static boolean isBlankText(Object value) {
		return value == null || value instanceof String text && text.isEmpty();
	}
}
