package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.repository.EventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.validation.EventValidator;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

/**
 * Zentraler Weg zum Speichern eines Events über die Speichern-Buttons: Erst wird das ganze Event mit
 * {@link EventValidator} geprüft. Bei Fehlern wird <strong>nicht</strong> gespeichert und alle Fehler
 * erscheinen in der Fußzeile; Warnungen blockieren nicht, werden aber nach dem Speichern angezeigt.
 *
 * <p>
 * Nicht über diesen Weg laufen Struktur-Änderungen wie das Löschen von Vorstellung, Tisch oder Sitz:
 * Dort ist das Modell vor dem Speichern schon verändert, und Löschen kann die Daten nicht verschlechtern.
 */
public final class EventSaver {

	/** Ergebnis eines Speicherversuchs: wurde gespeichert, und was hat die Prüfung ergeben. */
	record SaveResult(boolean saved, ValidationResult validation) {
	}

	private EventSaver() {
	}

	/**
	 * Prüft und speichert das Event und meldet das Ergebnis in der Fußzeile ("Gespeichert",
	 * "Gespeichert mit Warnungen: ..." bzw. "Nicht gespeichert: ...").
	 *
	 * @return {@code true}, wenn gespeichert wurde
	 */
	public static boolean save(Event event) {
		SaveResult result = trySave(event, JsonEventRepository.getInstance());
		StatusMessage messages = StatusMessage.getInstance();
		if (!result.saved()) {
			messages.show(result.validation(), "Nicht gespeichert: ");
		} else if (result.validation().isEmpty()) {
			messages.showSaved();
		} else {
			messages.show(result.validation(), "Gespeichert mit Warnungen: ");
		}
		return result.saved();
	}

	/** Die Entscheidung ohne Oberfläche (paketsichtbar für Tests). Fehler des Repositories werden nicht abgefangen. */
	static SaveResult trySave(Event event, EventRepository repository) {
		ValidationResult validation = EventValidator.validate(event);
		if (!validation.isValid()) {
			return new SaveResult(false, validation);
		}
		repository.saveEvent(event);
		return new SaveResult(true, validation);
	}
}
