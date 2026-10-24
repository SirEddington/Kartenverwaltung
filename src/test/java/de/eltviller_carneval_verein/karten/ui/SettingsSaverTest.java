package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.mirror.MirrorSetup;
import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.validation.SettingsIssue;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

class SettingsSaverTest {

	private static final Path LOCAL_ROOT = Path.of("lokal", "daten").toAbsolutePath();

	/** Ein Hook, der aufschreibt, was in welcher Reihenfolge lief. */
	private static final class RecordingHook implements SettingsSaver.MirrorHook {

		final List<String> calls = new ArrayList<>();
		ValidationResult prepareResult = ValidationResult.ok();
		IOException prepareFailure;
		RuntimeException prepareRuntimeFailure;

		@Override
		public ValidationResult prepare(AppSettings settings) throws IOException {
			calls.add("prepare");
			if (prepareFailure != null) {
				throw prepareFailure;
			}
			if (prepareRuntimeFailure != null) {
				throw prepareRuntimeFailure;
			}
			return prepareResult;
		}

		@Override
		public void activate(AppSettings settings) {
			calls.add("activate");
		}
	}

	private static AppSettings settingsWithMirror(String mirrorDir) {
		AppSettings settings = new AppSettings();
		settings.setMirrorDir(mirrorDir);
		return settings;
	}

	@Test
	void validationErrorBlocksSavingAndTheHook() {
		RecordingHook hook = new RecordingHook();
		List<String> calls = hook.calls;

		SettingsSaver.Outcome outcome = SettingsSaver.trySave(settingsWithMirror(LOCAL_ROOT.toString()), LOCAL_ROOT, hook,
				() -> calls.add("save"));

		assertFalse(outcome.saved());
		assertNull(outcome.error());
		assertFalse(outcome.validation().isValid());
		assertEquals(SettingsIssue.MIRROR_DIR_NESTED.toIssue(LOCAL_ROOT.toString()).code(),
				outcome.validation().mostSevere().orElseThrow().code());
		assertTrue(calls.isEmpty(), "Weder der Hook noch das Speichern dürfen laufen: " + calls);
	}

	@Test
	void invalidPrepareResultBlocksSaving() {
		RecordingHook hook = new RecordingHook();
		hook.prepareResult = ValidationResult.of(SettingsIssue.MIRROR_DIR_UNREACHABLE.toIssue("X:\\weg"));
		List<String> calls = hook.calls;

		SettingsSaver.Outcome outcome = SettingsSaver.trySave(settingsWithMirror("X:\\weg"), LOCAL_ROOT, hook,
				() -> calls.add("save"));

		assertFalse(outcome.saved());
		assertNull(outcome.error());
		assertFalse(outcome.validation().isValid());
		assertEquals(List.of("prepare"), calls);
	}

	@Test
	void cancelIsSilent() {
		RecordingHook hook = new RecordingHook();
		hook.prepareRuntimeFailure = new MirrorSetup.CancelledException();
		List<String> calls = hook.calls;

		SettingsSaver.Outcome outcome = SettingsSaver.trySave(settingsWithMirror("X:\\spiegel"), LOCAL_ROOT, hook,
				() -> calls.add("save"));

		assertFalse(outcome.saved());
		assertTrue(outcome.validation().isEmpty());
		assertNull(outcome.error());
		assertEquals(List.of("prepare"), calls, "Gespeichert und aktiviert wird nach einem Abbruch nichts");
	}

	@Test
	void copyFailureReportsSys003AndSavesNothing() {
		RecordingHook hook = new RecordingHook();
		hook.prepareFailure = new IOException("Platte voll");
		List<String> calls = hook.calls;

		SettingsSaver.Outcome outcome = SettingsSaver.trySave(settingsWithMirror("  X:\\spiegel  "), LOCAL_ROOT, hook,
				() -> calls.add("save"));

		assertFalse(outcome.saved());
		assertTrue(outcome.error().startsWith("Nicht gespeichert: SYS-003: "), outcome.error());
		assertTrue(outcome.error().contains("Platte voll"), outcome.error());
		assertTrue(outcome.error().contains("X:\\spiegel"), "Der Pfad steht ohne Leerzeichen am Rand im Text: " + outcome.error());
		assertFalse(outcome.error().contains("  X:"), outcome.error());
		assertEquals(List.of("prepare"), calls);
	}

	@Test
	void successRunsPrepareThenSaveThenActivate() {
		RecordingHook hook = new RecordingHook();
		List<String> calls = hook.calls;

		SettingsSaver.Outcome outcome = SettingsSaver.trySave(settingsWithMirror("X:\\spiegel"), LOCAL_ROOT, hook,
				() -> calls.add("save"));

		assertTrue(outcome.saved());
		assertNull(outcome.error());
		assertTrue(outcome.validation().isEmpty());
		assertEquals(List.of("prepare", "save", "activate"), calls);
	}

	@Test
	void saveFailureKeepsTheOldMessageFormatAndSkipsActivate() {
		RecordingHook hook = new RecordingHook();
		List<String> calls = hook.calls;

		SettingsSaver.Outcome outcome = SettingsSaver.trySave(settingsWithMirror(null), LOCAL_ROOT, hook, () -> {
			calls.add("save");
			throw new RuntimeException("Fehler beim Speichern der Datei: settings.json");
		});

		assertFalse(outcome.saved());
		assertEquals("Nicht gespeichert: Fehler beim Speichern der Datei: settings.json", outcome.error());
		assertEquals(List.of("prepare", "save"), calls, "Nach einem Speicherfehler wird nicht aktiviert");
	}

	@Test
	void saveFailureWithoutMessageStillSaysNotSaved() {
		SettingsSaver.Outcome outcome = SettingsSaver.trySave(new AppSettings(), LOCAL_ROOT, new RecordingHook(), () -> {
			throw new IllegalStateException();
		});

		assertFalse(outcome.saved());
		assertEquals("Nicht gespeichert", outcome.error());
	}
}
