package de.eltviller_carneval_verein.karten.ui.settings;

import java.util.List;

import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.settings.DisplaySettings;

/** Der Abschnitt "Anzeige" (KARV-76): ob die Auswahl im Menü "Anzeige" beim nächsten Start wieder gilt ({@code DisplayPreferences}). */
final class DisplaySection {

	static final String TITLE = "Anzeige";
	static final String RESTORE = "Anzeige beim Start wiederherstellen";
	static final String RESTORE_HINT = "Die Auswahl im Menü „Anzeige“ (Reihen, Tische, Sitznummern, Hallenobjekte und die"
			+ " Kaufzusammenfassung im Kundenfenster) gilt beim nächsten Start wieder. Sie ist für Saalansicht, Kundenfenster"
			+ " und Hallen-Editor dieselbe. Ausgeschaltet zeigt jeder Start alles an.";

	private DisplaySection() {
	}

	/** @param settings die bearbeiteten Einstellungen */
	static SettingsSection build(AppSettings settings) {
		DisplaySettings display = settings.getDisplay();
		return new SettingsSection(TITLE, List.of(new SettingRow(RESTORE, RESTORE_HINT,
				SettingControls.checkBox(display.isRestoreAtStart(), display::setRestoreAtStart))));
	}
}
