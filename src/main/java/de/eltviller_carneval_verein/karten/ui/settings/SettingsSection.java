package de.eltviller_carneval_verein.karten.ui.settings;

import java.util.List;

/**
 * Ein Abschnitt im Einstellungsbildschirm: eine Überschrift mit den zugehörigen Zeilen.
 *
 * @param title Überschrift des Abschnitts
 * @param rows  die Einstellungen dieses Abschnitts
 */
public record SettingsSection(String title, List<SettingRow> rows) {
}
