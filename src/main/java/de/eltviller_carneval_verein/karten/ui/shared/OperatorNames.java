package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Die Regeln für die Namen der Bediener, ohne Oberfläche: was als Name gilt und wie die Liste der zuletzt verwendeten
 * Namen fortgeschrieben wird ({@code AppSettings.recentOperators}).
 */
public final class OperatorNames {

	/** So viele zuletzt verwendete Namen bleiben erhalten. */
	public static final int MAX_RECENT = 8;

	private OperatorNames() {
	}

	/**
	 * Der Name ohne Leerraum am Rand; ein leerer oder fehlender Name ist keiner.
	 *
	 * @param input die Eingabe, auch {@code null}
	 */
	public static Optional<String> valid(String input) {
		if (input == null) {
			return Optional.empty();
		}
		String trimmed = input.strip();
		return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
	}

	/**
	 * Schreibt die Liste fort: {@code name} (ohne Leerraum am Rand) steht danach an erster Stelle, ein früherer Eintrag
	 * desselben Namens (Groß-/Kleinschreibung zählt nicht) entfällt, es bleiben höchstens {@link #MAX_RECENT}. Die neue
	 * Schreibweise gilt. Ist {@code name} kein gültiger Name, bleibt die Liste, wie sie ist. Die übergebene Liste wird nicht
	 * verändert; das Ergebnis ist eine neue, veränderbare Liste.
	 *
	 * @param recent die bisherigen Namen, der jüngste zuerst; {@code null} gilt als leer
	 * @param name   der Name, der gerade verwendet wird
	 */
	public static List<String> remember(List<String> recent, String name) {
		List<String> result = new ArrayList<>();
		Optional<String> valid = valid(name);
		valid.ifPresent(result::add);
		if (recent != null) {
			for (String known : recent) {
				if (known != null && !known.isBlank() && valid.map(v -> !v.equalsIgnoreCase(known.strip())).orElse(true)) {
					result.add(known);
				}
			}
		}
		return result.size() > MAX_RECENT ? new ArrayList<>(result.subList(0, MAX_RECENT)) : result;
	}
}
