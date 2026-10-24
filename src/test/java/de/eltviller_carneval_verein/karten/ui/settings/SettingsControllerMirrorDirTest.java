package de.eltviller_carneval_verein.karten.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;

class SettingsControllerMirrorDirTest {

	@Test
	void choosingOrRemovingAFolderMarksTheSettingsDirtyImmediately() {
		AppSettings settings = new AppSettings();
		try {
			ChangeTracker.settings().track(settings);
			assertFalse(ChangeTracker.settings().isDirty(settings));

			SettingsController.applyMirrorDir(settings, "E:\\ECV");
			assertEquals("E:\\ECV", settings.getMirrorDir());
			assertTrue(ChangeTracker.settings().isDirty(settings));

			SettingsController.applyMirrorDir(settings, null);
			assertEquals(null, settings.getMirrorDir());
			assertFalse(ChangeTracker.settings().isDirty(settings));
		} finally {
			ChangeTracker.settings().reset();
		}
	}
}
