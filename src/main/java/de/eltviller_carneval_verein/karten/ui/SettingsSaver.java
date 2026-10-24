package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;
import java.nio.file.Path;

import de.eltviller_carneval_verein.karten.AppPaths;
import de.eltviller_carneval_verein.karten.mirror.MirrorMessages;
import de.eltviller_carneval_verein.karten.mirror.MirrorSetup;
import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.validation.SettingsValidator;
import de.eltviller_carneval_verein.karten.validation.Severity;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

/**
 * Zentraler Weg zum Speichern der Einstellungen über den Speichern-Button und beim Verlassen des Einstellungs-Screens
 * (Gegenstück zum {@link HallSaver}): Das Ergebnis erscheint in der Fußzeile ("Gespeichert" bzw. "Nicht gespeichert:
 * ..."). Der Ablauf ist: prüfen, vorbereiten, speichern, aktivieren. Vor dem Schreiben läuft der
 * {@link SettingsValidator} (SET-001); wurde ein neuer Spiegelordner gewählt, richtet der {@link MirrorHook} ihn
 * vorher ein (Prüfen mit SET-002/SET-003, Vergleichen, Daten übertragen) und erst nach dem erfolgreichen Schreiben
 * von {@code settings.json} schaltet er die Spiegelung um. Beanstandungen (SET-xxx) erscheinen als "Nicht
 * gespeichert: SET-...", ein gescheitertes Übertragen als "Nicht gespeichert: SYS-003: ..."; in beiden Fällen
 * bleibt {@code settings.json} unberührt. Bricht der Nutzer die Rückfrage beim Einrichten ab, wird nichts
 * geändert und auch nichts gemeldet.
 */
public final class SettingsSaver {

	/**
	 * Der Teil des Speicherns, der den Spiegelordner betrifft, getrennt von der Oberfläche, damit die Reihenfolge
	 * ohne JavaFX prüfbar ist.
	 */
	interface MirrorHook {

		/**
		 * Richtet einen neuen Spiegelordner ein, bevor gespeichert wird; tut nichts, wenn er sich nicht ändert.
		 *
		 * @return die Beanstandungen (SET-002/003); ein ungültiges Ergebnis verhindert das Speichern
		 * @throws IOException                    wenn das Übertragen scheitert (SYS-003)
		 * @throws MirrorSetup.CancelledException wenn der Nutzer das Einrichten abbricht
		 */
		ValidationResult prepare(AppSettings settings) throws IOException;

		/** Schaltet die Spiegelung auf den gespeicherten Ordner um, nachdem {@code settings.json} geschrieben ist. */
		void activate(AppSettings settings);
	}

	/**
	 * Ergebnis eines Speicherversuchs.
	 *
	 * @param saved      ob {@code settings.json} geschrieben wurde
	 * @param validation Beanstandungen der Prüfungen; leer nach einem Abbruch
	 * @param error      Text für die Fußzeile, wenn das Übertragen oder Schreiben scheiterte; sonst {@code null}
	 */
	record Outcome(boolean saved, ValidationResult validation, String error) {
	}

	private SettingsSaver() {
	}

	/**
	 * Speichert die Einstellungen und meldet das Ergebnis in der Fußzeile. Ein Fehler beim Schreiben der Datei wird
	 * als Fehlermeldung angezeigt statt weitergereicht. Bricht der Nutzer das Einrichten des Spiegelordners ab,
	 * erscheint nichts.
	 *
	 * @return {@code true}, wenn gespeichert wurde
	 */
	public static boolean save() {
		SettingsStore store = SettingsStore.getInstance();
		Outcome outcome = trySave(store.current(), AppPaths.appDataDir().toPath(), new MirrorSetupHook(), store::save);
		if (outcome.error() != null) {
			StatusMessage.getInstance().show(Severity.ERROR, outcome.error());
			return false;
		}
		if (!outcome.saved() && outcome.validation().isEmpty()) {
			// Abbruch durch den Nutzer: nichts melden
			return false;
		}
		StatusMessage.getInstance().showSaveResult(outcome.saved(), outcome.validation());
		return outcome.saved();
	}

	/**
	 * Die Entscheidung ohne Oberfläche (paketsichtbar für Tests). Reihenfolge: Prüfen, Spiegelordner einrichten,
	 * Speichern, Spiegelung umschalten; jeder Schritt läuft nur, wenn der vorige gelang.
	 *
	 * @param localRoot der Standardordner der Anwendung (für SET-001)
	 */
	static Outcome trySave(AppSettings settings, Path localRoot, MirrorHook mirror, Runnable save) {
		ValidationResult validation = SettingsValidator.validate(settings, localRoot);
		if (!validation.isValid()) {
			return new Outcome(false, validation, null);
		}

		try {
			ValidationResult prepared = mirror.prepare(settings);
			validation.addAll(prepared);
			if (!prepared.isValid()) {
				return new Outcome(false, validation, null);
			}
		} catch (MirrorSetup.CancelledException e) {
			return new Outcome(false, ValidationResult.ok(), null);
		} catch (IOException e) {
			String path = settings.getMirrorDir() == null ? "" : settings.getMirrorDir().trim();
			return new Outcome(false, validation, "Nicht gespeichert: " + MirrorMessages.syncFailed(path, e.getMessage()));
		}

		try {
			save.run();
		} catch (RuntimeException e) {
			String message = e.getMessage();
			String error = message == null || message.isBlank() ? "Nicht gespeichert" : "Nicht gespeichert: " + message;
			return new Outcome(false, validation, error);
		}
		mirror.activate(settings);
		return new Outcome(true, validation, null);
	}
}
