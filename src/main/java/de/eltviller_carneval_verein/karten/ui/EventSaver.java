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
		StatusMessage.getInstance().showSaveResult(result.saved(), result.validation());
		return result.saved();
	}

	/**
	 * Speichert eine Struktur-Änderung (Löschen von Vorstellung, Tisch oder Sitz, Wiederherstellen aus einem Backup)
	 * ohne Prüfung und ohne Meldung in der Fußzeile, siehe Klassenbeschreibung. Fehler des Repositories werden nicht
	 * abgefangen.
	 */
	public static void saveStructureChange(Event event) {
		saveStructureChange(event, JsonEventRepository.getInstance());
	}

	/** Wie {@link #saveStructureChange(Event)} (paketsichtbar für Tests). */
	static void saveStructureChange(Event event, EventRepository repository) {
		repository.saveEvent(event);
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
