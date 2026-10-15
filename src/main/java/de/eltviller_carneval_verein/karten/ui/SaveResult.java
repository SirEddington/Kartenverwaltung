package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.validation.ValidationResult;

/**
 * Ergebnis eines Speicherversuchs über {@link EventSaver} oder {@link HallSaver}: wurde gespeichert, und was hat die
 * Prüfung ergeben (Fehler verhindern das Speichern, Warnungen nicht).
 */
record SaveResult(boolean saved, ValidationResult validation) {
}
