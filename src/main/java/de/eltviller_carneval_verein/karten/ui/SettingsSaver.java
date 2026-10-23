package de.eltviller_carneval_verein.karten.ui;

import java.util.Optional;

import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.validation.Severity;

/**
 * Zentraler Weg zum Speichern der Einstellungen über den Speichern-Button und beim Verlassen des Einstellungs-Screens
 * (Gegenstück zum {@link HallSaver}): Das Ergebnis erscheint in der Fußzeile ("Gespeichert" bzw. "Nicht gespeichert:
 * ..."). Einen Validator gibt es noch nicht, weil das Gerüst keine Einstellungen mit fachlichen Regeln enthält; ein
 * Folgeticket ergänzt ihn hier, damit alle Speicherwege ihn durchlaufen.
 */
public final class SettingsSaver {

	private SettingsSaver() {
	}

	/**
	 * Speichert die Einstellungen und meldet das Ergebnis in der Fußzeile. Ein Fehler beim Schreiben der Datei wird
	 * als Fehlermeldung angezeigt statt weitergereicht.
	 *
	 * @return {@code true}, wenn gespeichert wurde
	 */
	public static boolean save() {
		Optional<String> error = trySave(SettingsStore.getInstance()::save);
		if (error.isEmpty()) {
			StatusMessage.getInstance().showSaved();
			return true;
		}
		StatusMessage.getInstance().show(Severity.ERROR, error.get());
		return false;
	}

	/**
	 * Die Entscheidung ohne Oberfläche (paketsichtbar für Tests).
	 *
	 * @return leer bei Erfolg, sonst der Text für die Fußzeile
	 */
	static Optional<String> trySave(Runnable save) {
		try {
			save.run();
			return Optional.empty();
		} catch (RuntimeException e) {
			String message = e.getMessage();
			return Optional.of(message == null || message.isBlank() ? "Nicht gespeichert" : "Nicht gespeichert: " + message);
		}
	}
}
