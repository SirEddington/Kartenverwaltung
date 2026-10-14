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

	/**
	 * Speichert eine Struktur-Änderung (Löschen eines Hallenobjekts, Wiederherstellen aus einem Backup) ohne
	 * Meldung in der Fußzeile (Gegenstück zu {@link EventSaver#saveStructureChange(de.eltviller_carneval_verein.karten.model.Event)}).
	 * Fehler des Repositories werden nicht abgefangen.
	 */
	public static void saveStructureChange(Hall hall) {
		saveStructureChange(hall, JsonHallRepository.getInstance());
	}

	/**
	 * Speichert eine aus einem Backup wiederhergestellte Halle: wie {@link #saveStructureChange(Hall)}, aber der
	 * aktuelle Stand wird nur überschrieben, wenn er vorher gesichert werden konnte (sonst wirft es, die Datei
	 * bleibt unberührt).
	 */
	public static void saveRestoredState(Hall hall) {
		JsonHallRepository.getInstance().saveHallRequiringBackup(hall);
	}

	/** Wie {@link #saveStructureChange(Hall)} (paketsichtbar für Tests). */
	static void saveStructureChange(Hall hall, HallRepository repository) {
		repository.saveHall(hall);
	}

	/** Die Entscheidung ohne Oberfläche (paketsichtbar für Tests). Fehler des Repositories werden nicht abgefangen. */
	static boolean trySave(Hall hall, HallRepository repository) {
		repository.saveHall(hall);
		return true;
	}
}
