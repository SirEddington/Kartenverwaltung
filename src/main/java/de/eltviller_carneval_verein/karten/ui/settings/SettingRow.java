package de.eltviller_carneval_verein.karten.ui.settings;

import javafx.scene.Node;

/**
 * Eine Zeile im Einstellungsbildschirm: Beschriftung, Erklärung und das Bedienelement.
 *
 * @param label   Beschriftung der Einstellung
 * @param hint    kurze Erklärung unter der Beschriftung (darf {@code null} sein)
 * @param control das Bedienelement, mit dem die Einstellung geändert wird
 */
public record SettingRow(String label, String hint, Node control) {
}
