package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

/**
 * Plausibilitätsprüfungen für ein Event (Kürzel EVT) und Einstieg für die Prüfung des ganzen Baums
 * Event, Vorstellung, Tisch, Sitz. Eigene Regeln gibt es noch nicht, der Validator reicht die
 * Prüfung an die Vorstellungen weiter. Aufbau wie bei {@link SeatValidator}.
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
		validatePresentations();
		return result;
	}

	private void validatePresentations() {
		for (Presentation presentation : event.getPresentations()) {
			result.addAll(PresentationValidator.validate(presentation));
		}
	}
}
