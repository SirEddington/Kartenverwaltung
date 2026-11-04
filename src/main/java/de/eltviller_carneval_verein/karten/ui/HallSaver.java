package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.HallRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.validation.HallValidator;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

/**
 * Zentraler Weg zum Speichern einer Halle über den Speichern-Button und beim Verlassen eines Screens (Gegenstück
 * zum {@link EventSaver}): Erst wird die Halle mit {@link HallValidator} geprüft. Bei Fehlern wird
 * <strong>nicht</strong> gespeichert und alle Fehler erscheinen in der Fußzeile; Warnungen blockieren nicht. Neue
 * fachliche Prüfungen für Hallen (KARV-56) gehören in die Validatoren, damit alle Speicherwege sie durchlaufen.
 */
public final class HallSaver {

	private HallSaver() {
	}

	/**
	 * Prüft und speichert die Halle und meldet das Ergebnis in der Fußzeile ("Gespeichert",
	 * "Gespeichert mit Warnungen: ..." bzw. "Nicht gespeichert: ...").
	 *
	 * @return {@code true}, wenn gespeichert wurde
	 */
	public static boolean save(Hall hall) {
		SaveResult result = trySave(hall, StoreHallRepository.getInstance());
		StatusMessage.getInstance().showSaveResult(result.saved(), result.validation());
		return result.saved();
	}

	/**
	 * Speichert eine Struktur-Änderung (Löschen eines Hallenobjekts, Wiederherstellen aus einem Backup) ohne
	 * Meldung in der Fußzeile (Gegenstück zu {@link EventSaver#saveStructureChange(de.eltviller_carneval_verein.karten.model.Event)}).
	 * Fehler des Repositories werden nicht abgefangen.
	 */
	public static void saveStructureChange(Hall hall) {
		saveStructureChange(hall, StoreHallRepository.getInstance());
	}

	/** Wie {@link #saveStructureChange(Hall)} (paketsichtbar für Tests). */
	static void saveStructureChange(Hall hall, HallRepository repository) {
		repository.saveHall(hall);
	}

	/** Die Entscheidung ohne Oberfläche (paketsichtbar für Tests). Fehler des Repositories werden nicht abgefangen. */
	static SaveResult trySave(Hall hall, HallRepository repository) {
		ValidationResult validation = HallValidator.validate(hall);
		if (!validation.isValid()) {
			return new SaveResult(false, validation);
		}
		repository.saveHall(hall);
		return new SaveResult(true, validation);
	}
}
