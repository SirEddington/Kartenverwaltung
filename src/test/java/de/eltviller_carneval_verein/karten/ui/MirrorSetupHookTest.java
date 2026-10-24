package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MirrorSetupHookTest {

	@Test
	void needsConfigureOnlyWhenTheTrimmedFolderDiffers() {
		assertFalse(MirrorSetupHook.needsConfigure("X:\\spiegel", "X:\\spiegel"), "Gleicher Ordner");
		assertFalse(MirrorSetupHook.needsConfigure("  X:\\spiegel  ", "X:\\spiegel"), "Leerzeichen am Rand zählen nicht");
		assertTrue(MirrorSetupHook.needsConfigure("X:\\anders", "X:\\spiegel"), "Anderer Ordner");
		assertFalse(MirrorSetupHook.needsConfigure("   ", null), "Leer und aus");
		assertFalse(MirrorSetupHook.needsConfigure(null, null), "Null und aus");
		assertTrue(MirrorSetupHook.needsConfigure("X:\\spiegel", null), "Neuer Ordner bei ausgeschalteter Spiegelung");
		assertTrue(MirrorSetupHook.needsConfigure("", "X:\\spiegel"), "Leeren schaltet die Spiegelung ab");
		assertTrue(MirrorSetupHook.needsConfigure(null, "X:\\spiegel"), "Null schaltet die Spiegelung ab");
	}
}
