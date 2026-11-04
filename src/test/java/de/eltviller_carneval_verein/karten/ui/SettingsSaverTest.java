package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SettingsSaverTest {

	@Test
	void successSavesOnce() {
		List<String> calls = new ArrayList<>();

		String error = SettingsSaver.trySave(() -> calls.add("save"));

		assertNull(error);
		assertEquals(List.of("save"), calls);
	}

	@Test
	void saveFailureKeepsTheOldMessageFormat() {
		String error = SettingsSaver.trySave(() -> {
			throw new RuntimeException("Fehler beim Speichern der Datei: settings.json");
		});

		assertEquals("Nicht gespeichert: Fehler beim Speichern der Datei: settings.json", error);
	}

	@Test
	void saveFailureWithoutMessageStillSaysNotSaved() {
		String error = SettingsSaver.trySave(() -> {
			throw new IllegalStateException();
		});

		assertEquals("Nicht gespeichert", error);
	}
}
