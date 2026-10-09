package de.eltviller_carneval_verein.karten.validation;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

/**
 * Plausibilitätsprüfungen für ein Event (Kürzel EVT, siehe {@link EventIssue}) und Einstieg für die
 * Prüfung des ganzen Baums Event, Vorstellung, Tisch, Sitz. Aufbau wie bei {@link SeatValidator}.
 */
public final class EventValidator {

	private final Event event;
	private final ValidationResult result = ValidationResult.ok();

	private EventValidator(Event event) {
		this.event = event;
	}

	public static ValidationResult validate(Event event) {
		return new EventValidator(event).run();
	}

	private ValidationResult run() {
		validatePresentationNamesAreUnique();
		validatePresentations();
		return result;
	}

	/**
	 * Vergleich unter Geschwistern: eine Meldung je mehrfach vorkommendem Namen (Groß-/Kleinschreibung
	 * und Leerzeichen am Rand zählen nicht). Gleiche Namen machen die Beschreibung von Tischen und
	 * Sitzen in den Meldungen mehrdeutig.
	 */
	private void validatePresentationNamesAreUnique() {
		// Schlüssel: normalisierter Name, Wert: Anzahl und erster Name in Originalschreibweise
		Map<String, int[]> counts = new TreeMap<>();
		Map<String, String> firstNames = new TreeMap<>();
		for (Presentation presentation : event.getPresentations()) {
			String name = presentation.getName();
			if (name == null || name.isBlank()) {
				continue;
			}
			String key = name.trim().toLowerCase(Locale.GERMAN);
			counts.computeIfAbsent(key, k -> new int[1])[0]++;
			firstNames.putIfAbsent(key, name.trim());
		}
		counts.forEach((key, count) -> {
			if (count[0] > 1) {
				result.add(EventIssue.PRESENTATION_NAME_DUPLICATE.toIssue(firstNames.get(key)));
			}
		});
	}

	private void validatePresentations() {
		for (Presentation presentation : event.getPresentations()) {
			result.addAll(PresentationValidator.validate(presentation));
		}
	}
}
