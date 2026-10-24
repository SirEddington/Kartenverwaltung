package de.eltviller_carneval_verein.karten.ui.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SettingsFilterTest {

	private static final SettingsSection SAVING = new SettingsSection("Speichern",
			List.of(new SettingRow("Backup-Aufbewahrung", "Wie lange Sicherungen bleiben", null)));
	private static final SettingsSection DISPLAY = new SettingsSection("Anzeige",
			List.of(new SettingRow("Fenstergröße merken", "Größe und Position beim Start wiederherstellen", null),
						new SettingRow("Sprache", "Oberfläche", null)));

	private static SettingRow rowOf(SettingsSection section) {
		return section.rows().get(0);
	}

	@Test
	void blankQueryShowsEverything() {
		for (String query : new String[] { null, "", "   " }) {
			assertTrue(SettingsFilter.rowVisible(query, SAVING, rowOf(SAVING)));
			assertTrue(SettingsFilter.rowVisible(query, DISPLAY, rowOf(DISPLAY)));
			assertTrue(SettingsFilter.sectionVisible(query, SAVING));
			assertTrue(SettingsFilter.sectionVisible(query, DISPLAY));
		}
	}

	@Test
	void matchesLabelHintAndTitleIgnoringCase() {
		for (String query : new String[] { "backup", "SICHERUNGEN", "speichern" }) {
			assertTrue(SettingsFilter.rowVisible(query, SAVING, rowOf(SAVING)), query);
		}
	}

	@Test
	void titleMatchShowsAllRowsOfTheSection() {
		SettingRow first = DISPLAY.rows().get(0);
		SettingRow second = DISPLAY.rows().get(1);

		// Die zweite Zeile passt für sich nicht zum Suchtext, bleibt aber über die Überschrift sichtbar
		assertFalse(SettingsFilter.rowVisible("anzeige", new SettingsSection("Sonstiges", List.of(second)), second));
		assertTrue(SettingsFilter.rowVisible("anzeige", DISPLAY, first));
		assertTrue(SettingsFilter.rowVisible("anzeige", DISPLAY, second));
		assertFalse(SettingsFilter.rowVisible("anzeige", SAVING, rowOf(SAVING)));
	}

	@Test
	void missingLabelAndHintCountAsEmptyText() {
		SettingRow empty = new SettingRow(null, null, null);
		SettingsSection section = new SettingsSection("Anzeige", List.of(empty));

		assertTrue(SettingsFilter.rowVisible(null, section, empty));
		assertFalse(SettingsFilter.rowVisible("sprache", new SettingsSection("Sonstiges", List.of(empty)), empty));
		assertFalse(SettingsFilter.sectionVisible("sprache", new SettingsSection("Sonstiges", List.of(empty))));
		assertTrue(SettingsFilter.rowVisible("anzeige", section, empty));
	}

	@Test
	void nonMatchingRowsAndSectionsAreHidden() {
		assertFalse(SettingsFilter.sectionVisible("fenster", SAVING));
		assertTrue(SettingsFilter.sectionVisible("fenster", DISPLAY));
	}

	@Test
	void surroundingWhitespaceAndUmlautsAreHandled() {
		assertTrue(SettingsFilter.rowVisible("  FENSTERGRÖ ", DISPLAY, rowOf(DISPLAY)));
	}
}
