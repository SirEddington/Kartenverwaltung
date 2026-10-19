package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

/**
 * Plausibilitätsprüfungen für ein Event (Kürzel EVT, siehe {@link EventIssue}) und Einstieg für die
 * Prüfung des ganzen Baums Event, Vorstellung, Buchung. Tische und Sitze gehören zur Bestuhlung der Halle und werden
 * dort geprüft ({@link HallValidator}, {@link SeatingPlanValidator}). Aufbau wie bei {@link SeatValidator}.
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
		validateName();
		validatePresentationNamesAreUnique();
		validatePresentations();
		return result;
	}

	/** Der Eventname darf nicht leer sein (auch nicht nur Leerzeichen): Er gibt dem Event seine Anzeige und Datei-Zuordnung. */
	private void validateName() {
		String name = event.getName();
		if (name == null || name.isBlank()) {
			result.add(EventIssue.NAME_EMPTY.toIssue());
		}
	}

	/**
	 * Vergleich unter Geschwistern: eine Meldung je mehrfach vorkommendem Namen (Groß-/Kleinschreibung
	 * und Leerzeichen am Rand zählen nicht). Gleiche Namen machen die Beschreibung der Buchungen in den
	 * Meldungen mehrdeutig ("Prunksitzung, Tisch A3, Sitz 2").
	 */
	private void validatePresentationNamesAreUnique() {
		for (String name : DuplicateNames.of(event.getPresentations().stream().map(Presentation::getName))) {
			result.add(EventIssue.PRESENTATION_NAME_DUPLICATE.toIssue(name));
		}
	}

	private void validatePresentations() {
		for (Presentation presentation : event.getPresentations()) {
			result.addAll(PresentationValidator.validate(presentation));
		}
	}
}
