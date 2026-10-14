package de.eltviller_carneval_verein.karten.ui.backup;

import java.util.List;

/**
 * Ergebnis eines Wiederherstellens.
 *
 * @param success {@code true}, wenn der Stand zurückgeholt und gespeichert wurde
 * @param message bei Erfolg eine kurze Bestätigung, sonst der Grund des Scheiterns (mit Dateiname der Sicherung)
 * @param notes   Hinweise zum Erfolg, z. B. entfernte Hallen; leer, wenn es keine gibt
 */
public record RestoreResult(boolean success, String message, List<String> notes) {

	static RestoreResult success(String message, List<String> notes) {
		return new RestoreResult(true, message, List.copyOf(notes));
	}

	static RestoreResult failure(String message) {
		return new RestoreResult(false, message, List.of());
	}
}
