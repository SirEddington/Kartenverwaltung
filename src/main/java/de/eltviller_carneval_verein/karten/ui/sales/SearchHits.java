package de.eltviller_carneval_verein.karten.ui.sales;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Die Trefferzahl neben dem Suchfeld der Verkaufsansicht und ihr Tooltip (KARV-192). Gezählt wird, was die Ansicht als
 * Treffer zeigt ({@link SalesViewState#searchHits()}); der Tooltip schlüsselt das nach Name und, bei "Alle
 * Vorstellungen", nach Vorstellung auf. Ohne JavaFX.
 */
final class SearchHits {

	/** Wie viele Namen der Tooltip höchstens nennt; die übrigen fasst eine Zeile "… und k weitere" zusammen. */
	static final int MAX_NAMES = 15;

	static final String NO_NAME = "(ohne Namen)";
	static final String NO_HITS = "Keine Treffer";

	private SearchHits() {
	}

	/** "0 Treffer", "1 Treffer", "12 Treffer". */
	static String countText(int count) {
		return count + " Treffer";
	}

	/**
	 * Der Tooltip zur Trefferzahl: je Name eine Zeile mit der Anzahl, alphabetisch, "(ohne Namen)" zuletzt, höchstens
	 * {@link #MAX_NAMES}; mit {@code byPresentation} darunter je Vorstellung mit Treffern eine Zeile in der Reihenfolge von
	 * {@code presentations}. Ohne Treffer "Keine Treffer".
	 */
	static String tooltipText(List<Booking> hits, List<Presentation> presentations, boolean byPresentation) {
		if (hits.isEmpty()) {
			return NO_HITS;
		}
		List<String> lines = new ArrayList<>();
		lines.add("Nach Name:");
		List<Map.Entry<String, Integer>> names = countByName(hits);
		names.stream().limit(MAX_NAMES).forEach(entry -> lines.add(entry.getKey() + ": " + entry.getValue()));
		if (names.size() > MAX_NAMES) {
			lines.add("… und " + (names.size() - MAX_NAMES) + " weitere");
		}
		if (byPresentation) {
			List<String> perPresentation = new ArrayList<>();
			for (Presentation presentation : presentations) {
				long count = hits.stream().filter(booking -> booking.getParent() == presentation).count();
				if (count > 0) {
					perPresentation.add(EntityLabels.nameOrPlaceholder(presentation.getName()) + ": " + count);
				}
			}
			if (!perPresentation.isEmpty()) {
				lines.add("");
				lines.add("Nach Vorstellung:");
				lines.addAll(perPresentation);
			}
		}
		return String.join("\n", lines);
	}

	/**
	 * Treffer je Name, sortiert. Gleich sind Namen, die nach dem Trimmen ohne Groß- und Kleinschreibung übereinstimmen;
	 * es gilt die Schreibweise des ersten Treffers.
	 */
	private static List<Map.Entry<String, Integer>> countByName(List<Booking> hits) {
		Map<String, List<Booking>> byName = hits.stream()
				.collect(Collectors.groupingBy(booking -> nameOf(booking).toLowerCase(Locale.GERMAN), LinkedHashMap::new, Collectors.toList()));
		Collator collator = Collator.getInstance(Locale.GERMAN);
		Comparator<String> order = Comparator.comparing((String name) -> name.equals(NO_NAME)).thenComparing(collator::compare);
		return byName.values().stream().map(group -> Map.entry(nameOf(group.get(0)), group.size())).sorted(Map.Entry.comparingByKey(order))
				.toList();
	}

	/** "Müller, Hans", nur "Müller" oder "Hans"; ohne beides "(ohne Namen)". */
	static String nameOf(Booking booking) {
		String name = Stream.of(booking.getLastName(), booking.getFirstName()).filter(part -> part != null && !part.isBlank())
				.map(String::trim).collect(Collectors.joining(", "));
		return name.isEmpty() ? NO_NAME : name;
	}
}
