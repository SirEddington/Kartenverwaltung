package de.eltviller_carneval_verein.karten.ui.settings;

import java.util.Locale;

/**
 * Die Suche im Einstellungsbildschirm: entscheidet, welche Abschnitte und Zeilen zum Suchtext passen.
 * <p>
 * Der Vergleich ignoriert Groß-/Kleinschreibung und umgebende Leerzeichen. Passt die Überschrift eines
 * Abschnitts, bleiben alle seine Zeilen sichtbar.
 */
final class SettingsFilter {

	private SettingsFilter() {
	}

	/** Ob die Zeile bei diesem Suchtext sichtbar ist (leerer Suchtext zeigt alles). */
	static boolean rowVisible(String query, SettingsSection section, SettingRow row) {
		String needle = normalize(query);
		if (needle.isEmpty()) {
			return true;
		}
		return contains(section.title(), needle) || contains(row.label(), needle) || contains(row.hint(), needle);
	}

	/** Ob der Abschnitt sichtbar ist: seine Überschrift oder mindestens eine seiner Zeilen passt. */
	static boolean sectionVisible(String query, SettingsSection section) {
		if (normalize(query).isEmpty() || contains(section.title(), normalize(query))) {
			return true;
		}
		return section.rows().stream().anyMatch(row -> rowVisible(query, section, row));
	}

	private static String normalize(String text) {
		return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
	}

	private static boolean contains(String text, String needle) {
		return normalize(text).contains(needle);
	}
}
