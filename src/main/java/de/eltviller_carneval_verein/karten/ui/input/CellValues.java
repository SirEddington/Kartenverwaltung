package de.eltviller_carneval_verein.karten.ui.input;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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

	/**
	 * Der Wert, den eine Zelle nach einer nicht lesbaren Eingabe stattdessen übernimmt: nur bei einem leeren Feld
	 * ({@code current == null}) und gesetztem {@code fallback} (z. B. der heutige Tag bei einem Datum), sonst leer, dann
	 * bleibt der bisherige Wert.
	 */
	static <V> Optional<V> replacementAfterFailure(V current, Supplier<V> fallback) {
		if (current != null || fallback == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(fallback.get());
	}

	private static boolean isBlankText(Object value) {
		return value == null || value instanceof String text && text.isEmpty();
	}
}
