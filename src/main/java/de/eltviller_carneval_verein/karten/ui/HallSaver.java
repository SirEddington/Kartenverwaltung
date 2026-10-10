package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.HallRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;

/**
 * Zentraler Weg zum Speichern einer Halle über den Speichern-Button und beim Verlassen eines Screens (Gegenstück
 * zum {@link EventSaver}). Für Hallen gibt es noch keine fachlichen Prüfungen (KARV-56); sobald es sie gibt,
 * gehören sie hierher, damit alle Speicherwege sie durchlaufen.
 */
public final class HallSaver {

	private HallSaver() {
	}

	/**
	 * Speichert die Halle und meldet "Gespeichert" in der Fußzeile.
	 *
	 * @return {@code true}, wenn gespeichert wurde
	 */
	public static boolean save(Hall hall) {
		boolean saved = trySave(hall, JsonHallRepository.getInstance());
		if (saved) {
			StatusMessage.getInstance().showSaved();
		}
		return saved;
	}

	/** Die Entscheidung ohne Oberfläche (paketsichtbar für Tests). Fehler des Repositories werden nicht abgefangen. */
	static boolean trySave(Hall hall, HallRepository repository) {
		repository.saveHall(hall);
		return true;
	}
}
