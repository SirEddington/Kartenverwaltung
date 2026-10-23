package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SettingsSaverTest {

	@Test
	void successGivesNoError() {
		AtomicInteger calls = new AtomicInteger();

		Optional<String> error = SettingsSaver.trySave(calls::incrementAndGet);

		assertTrue(error.isEmpty());
		assertEquals(1, calls.get(), "Das Speichern muss genau einmal laufen");
	}

	@Test
	void failureGivesTheMessageWithPrefix() {
		Optional<String> error = SettingsSaver.trySave(() -> {
			throw new RuntimeException("Fehler beim Speichern der Datei: settings.json");
		});

		assertEquals("Nicht gespeichert: Fehler beim Speichern der Datei: settings.json", error.orElseThrow());
	}

	@Test
	void failureWithoutMessageStillSaysNotSaved() {
		Optional<String> error = SettingsSaver.trySave(() -> {
			throw new IllegalStateException();
		});

		assertTrue(error.orElseThrow().startsWith("Nicht gespeichert"));
	}
}
