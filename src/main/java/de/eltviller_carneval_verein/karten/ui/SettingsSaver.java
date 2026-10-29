package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.validation.Severity;

/**
 * Zentraler Weg zum Speichern der Einstellungen über den Speichern-Button und beim Verlassen des Einstellungs-Screens
 * (Gegenstück zum {@link HallSaver}): Das Ergebnis erscheint in der Fußzeile ("Gespeichert" bzw. "Nicht gespeichert:
 * ..."). Ein Fehler beim Schreiben von {@code settings.json} wird als Fehlermeldung angezeigt statt weitergereicht.
 * Prüfungen der Einstellungen gibt es bisher nicht; Folgetickets ergänzen sie hier vor dem Schreiben.
 */
public final class SettingsSaver {

	private SettingsSaver() {
	}

	/**
	 * Speichert die Einstellungen und meldet das Ergebnis in der Fußzeile.
	 *
	 * @return {@code true}, wenn gespeichert wurde
	 */
	public static boolean save() {
		String error = trySave(SettingsStore.getInstance()::save);
		if (error != null) {
			StatusMessage.getInstance().show(Severity.ERROR, error);
			return false;
		}
		StatusMessage.getInstance().showSaved();
		return true;
	}

	/**
	 * Die Entscheidung ohne Oberfläche (paketsichtbar für Tests).
	 *
	 * @return der Text für die Fußzeile, wenn das Schreiben scheiterte; sonst {@code null}
	 */
	static String trySave(Runnable save) {
		try {
			save.run();
		} catch (RuntimeException e) {
			String message = e.getMessage();
			return message == null || message.isBlank() ? "Nicht gespeichert" : "Nicht gespeichert: " + message;
		}
		return null;
	}
}
