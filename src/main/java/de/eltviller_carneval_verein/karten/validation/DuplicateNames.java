package de.eltviller_carneval_verein.karten.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Vergleich unter Geschwistern für die Prüfer: findet Namen, die mehrfach vorkommen. Groß-/Kleinschreibung und Leerzeichen
 * am Rand zählen nicht, leere Namen werden übersprungen.
 */
final class DuplicateNames {

	private DuplicateNames() {
	}

	/** Die erste Schreibweise (ohne Rand) jedes mehrfach vorkommenden Namens, in der Reihenfolge des ersten Auftretens. */
	static List<String> of(Stream<String> names) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<String, String> firstNames = new LinkedHashMap<>();
		names.filter(name -> name != null && !name.isBlank()).forEach(name -> {
			String key = name.trim().toLowerCase(Locale.GERMAN);
			counts.merge(key, 1, Integer::sum);
			firstNames.putIfAbsent(key, name.trim());
		});
		List<String> duplicates = new ArrayList<>();
		counts.forEach((key, count) -> {
			if (count > 1) {
				duplicates.add(firstNames.get(key));
			}
		});
		return duplicates;
	}
}
