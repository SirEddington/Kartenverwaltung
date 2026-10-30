package de.eltviller_carneval_verein.karten.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.tracking.StateRestorer;

class SettingsStoreTest {

	private static void write(File dir, String text) throws IOException {
		Files.writeString(new File(dir, "settings.json").toPath(), text, StandardCharsets.UTF_8);
	}

	private static File[] backups(File dir) {
		return dir.listFiles((d, name) -> name.startsWith("settings.json.defekt-") && name.endsWith(".bak"));
	}

	/** Prüft den gemeinsamen Ablauf einer defekten Datei: Standardwerte, eine Warnung, Datei beiseitegelegt. */
	private static void assertBrokenFileHandled(File dir, String content) throws IOException {
		write(dir, content);

		SettingsStore store = new SettingsStore(dir);

		assertNotNull(store.current());
		List<String> warnings = store.getAndClearLoadWarnings();
		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).contains("settings.json"));
		assertFalse(new File(dir, "settings.json").exists());
		assertEquals(1, backups(dir).length);
		// Die Warnung nennt die beiseitegelegte Datei und ist einzeilig (keine rohe Jackson-Meldung mit Quelltext)
		assertTrue(warnings.get(0).contains("'" + backups(dir)[0].getName() + "'"));
		assertFalse(warnings.get(0).contains("\n"));
		assertFalse(warnings.get(0).contains("\r"));
	}

	@Test
	void missingFileGivesDefaults(@TempDir File dir) {
		SettingsStore store = new SettingsStore(dir);

		assertNotNull(store.current());
		assertTrue(store.getAndClearLoadWarnings().isEmpty());
		assertFalse(new File(dir, "settings.json").exists());
	}

	@Test
	void saveWritesAFileThatReloads(@TempDir File dir) throws IOException {
		SettingsStore store = new SettingsStore(dir);

		store.save();

		String[] names = dir.list();
		assertEquals(1, names.length);
		assertEquals("settings.json", names[0]);
		assertTrue(JsonMapperFactory.create().readTree(new File(dir, "settings.json")).isObject());
		assertTrue(new SettingsStore(dir).getAndClearLoadWarnings().isEmpty());
	}

	@Test
	void unknownFieldsAreIgnored(@TempDir File dir) throws IOException {
		write(dir, "{\"zukunft\": true}");

		SettingsStore store = new SettingsStore(dir);

		assertNotNull(store.current());
		assertTrue(store.getAndClearLoadWarnings().isEmpty());
		assertTrue(new File(dir, "settings.json").exists());
	}

	@Test
	void emptyFileCountsAsBroken(@TempDir File dir) throws IOException {
		assertBrokenFileHandled(dir, "");
	}

	@Test
	void jsonArrayCountsAsBroken(@TempDir File dir) throws IOException {
		assertBrokenFileHandled(dir, "[]");
	}

	@Test
	void brokenJsonCountsAsBroken(@TempDir File dir) throws IOException {
		assertBrokenFileHandled(dir, "{kaputt");
	}

	@Test
	void brokenFileWarnsOnlyOnce(@TempDir File dir) throws IOException {
		write(dir, "{kaputt");
		SettingsStore first = new SettingsStore(dir);

		assertEquals(1, first.getAndClearLoadWarnings().size());
		assertTrue(first.getAndClearLoadWarnings().isEmpty());
		assertTrue(new SettingsStore(dir).getAndClearLoadWarnings().isEmpty());
	}

	@Test
	void replaceCurrentSwapsWithoutWriting(@TempDir File dir) {
		SettingsStore store = new SettingsStore(dir);
		AppSettings other = new AppSettings();

		store.replaceCurrent(other);

		assertSame(other, store.current());
		assertFalse(new File(dir, "settings.json").exists());
	}

	@Test
	void saveFailureNamesTheFile() throws IOException {
		File notADirectory = File.createTempFile("settings-store-test", ".tmp");
		try {
			SettingsStore store = new SettingsStore(notADirectory);
			List<AppSettings> notified = new ArrayList<>();
			store.addListener(notified::add);

			assertNotNull(store.current());
			RuntimeException failure = assertThrows(RuntimeException.class, store::save);

			assertTrue(failure.getMessage().contains("settings.json"));
			assertTrue(notified.isEmpty());
		} finally {
			notADirectory.delete();
		}
	}

	@Test
	void listenersAreCalledAfterSaveAndTheirFailuresAreIgnored(@TempDir File dir) {
		SettingsStore store = new SettingsStore(dir);
		List<AppSettings> notified = new ArrayList<>();
		store.addListener(settings -> {
			throw new IllegalStateException("Absichtlicher Fehler im Test");
		});
		store.addListener(notified::add);

		store.save();

		assertEquals(1, notified.size());
		assertSame(store.current(), notified.get(0));
	}

	@Test
	void mirrorDirFromAnOlderVersionIsIgnored(@TempDir File dir) throws IOException {
		write(dir, "{\"mirrorDir\":\"E:\\\\ECV\"}");

		SettingsStore store = new SettingsStore(dir);
		store.save();

		assertTrue(store.getAndClearLoadWarnings().isEmpty());
		assertFalse(JsonMapperFactory.create().readTree(new File(dir, "settings.json")).has("mirrorDir"));
	}

	@Test
	void defaultSettingsSerializeToTheDefaultFields() throws IOException {
		JsonNode json = JsonMapperFactory.create().readTree(JsonMapperFactory.create().writeValueAsString(new AppSettings()));

		assertTrue(json.isObject());
		assertTrue(json.get("sharedDir").isNull());
		assertTrue(json.get("saveBookingsImmediately").asBoolean());
		assertEquals(0, json.get("recentOperators").size());
	}

	@Test
	void defaultsWhenFieldsAreMissing(@TempDir File dir) throws IOException {
		write(dir, "{}");

		AppSettings settings = new SettingsStore(dir).current();

		assertNull(settings.getSharedDir());
		assertTrue(settings.isSaveBookingsImmediately());
		assertNotNull(settings.getRecentOperators());
		assertTrue(settings.getRecentOperators().isEmpty());
	}

	@Test
	void fieldsSurviveSaveAndReload(@TempDir File dir) {
		SettingsStore store = new SettingsStore(dir);
		store.current().setSharedDir("E:\\Nextcloud\\ECV");
		store.current().setSaveBookingsImmediately(false);
		store.current().setRecentOperators(List.of("Anna", "Bert"));
		store.save();

		AppSettings reloaded = new SettingsStore(dir).current();

		assertEquals("E:\\Nextcloud\\ECV", reloaded.getSharedDir());
		assertFalse(reloaded.isSaveBookingsImmediately());
		assertEquals(List.of("Anna", "Bert"), reloaded.getRecentOperators());
	}

	@Test
	void nullRecentOperatorsBecomeAnEmptyList() {
		AppSettings settings = new AppSettings();

		settings.setRecentOperators(null);

		assertNotNull(settings.getRecentOperators());
		assertTrue(settings.getRecentOperators().isEmpty());
	}

	@Test
	void saveOnlyWritesOnlyTheChangedField(@TempDir File dir) throws IOException {
		SettingsStore store = new SettingsStore(dir);
		store.current().setSaveBookingsImmediately(false);

		store.saveOnly(settings -> settings.setSharedDir("X"));

		JsonNode file = JsonMapperFactory.create().readTree(new File(dir, "settings.json"));
		assertEquals("X", file.get("sharedDir").asText());
		assertTrue(file.get("saveBookingsImmediately").asBoolean());
		assertEquals(1, dir.list().length, "keine temporäre Datei zurückgeblieben");
	}

	@Test
	void saveOnlyKeepsOtherUnsavedChangesInCurrent(@TempDir File dir) {
		SettingsStore store = new SettingsStore(dir);
		store.current().setSaveBookingsImmediately(false);
		List<AppSettings> partial = new ArrayList<>();
		List<AppSettings> full = new ArrayList<>();
		store.addPartialListener(partial::add);
		store.addListener(full::add);

		store.saveOnly(settings -> settings.setSharedDir("X"));

		assertEquals("X", store.current().getSharedDir());
		assertFalse(store.current().isSaveBookingsImmediately());
		assertEquals(1, partial.size());
		assertEquals("X", partial.get(0).getSharedDir());
		assertTrue(partial.get(0).isSaveBookingsImmediately(), "die Kopie trägt die ungespeicherte Änderung nicht");
		assertNotSame(store.current(), partial.get(0));
		assertTrue(full.isEmpty(), "Teil-Speichern ruft die Listener des vollen Speicherns nicht");
	}

	@Test
	void saveUpdatesTheSavedCopy(@TempDir File dir) throws IOException {
		SettingsStore store = new SettingsStore(dir);
		store.current().setSaveBookingsImmediately(false);
		store.save();

		store.saveOnly(settings -> settings.setSharedDir("X"));

		JsonNode file = JsonMapperFactory.create().readTree(new File(dir, "settings.json"));
		assertEquals("X", file.get("sharedDir").asText());
		assertFalse(file.get("saveBookingsImmediately").asBoolean());
	}

	@Test
	void saveOnlyFailureLeavesCurrentAndListenersUntouched() throws IOException {
		File notADirectory = File.createTempFile("settings-store-test", ".tmp");
		try {
			SettingsStore store = new SettingsStore(notADirectory);
			List<AppSettings> partial = new ArrayList<>();
			store.addPartialListener(partial::add);

			RuntimeException failure = assertThrows(RuntimeException.class, () -> store.saveOnly(settings -> settings.setSharedDir("X")));

			assertTrue(failure.getMessage().contains("settings.json"));
			assertNull(store.current().getSharedDir());
			assertTrue(partial.isEmpty());
		} finally {
			notADirectory.delete();
		}
	}

	/** Der Ablauf aus {@code MainApp}: Tracker, Restorer ("Nicht speichern") und der Listener des Teil-Speicherns. */
	@Test
	void discardAfterSaveOnlyKeepsSharedDir(@TempDir File dir) {
		SettingsStore store = new SettingsStore(dir);
		ChangeTracker<AppSettings> tracker = new ChangeTracker<>(settings -> "settings");
		StateRestorer<AppSettings> restorer = new StateRestorer<>(AppSettings.class, tracker, store::replaceCurrent);
		store.addListener(tracker::markSaved);
		store.addPartialListener(saved -> {
			AppSettings current = store.current();
			if (tracker.baseline(current) != null) {
				tracker.rebase(current, tracker.snapshotOf(saved));
			}
		});
		tracker.track(store.current());
		store.current().setSaveBookingsImmediately(false);
		tracker.check(store.current());
		assertTrue(tracker.isDirty(store.current()));

		store.saveOnly(settings -> settings.setSharedDir("X"));

		assertTrue(tracker.isDirty(store.current()), "die ungespeicherte Checkbox bleibt geändert");
		restorer.restoreBaseline(store.current());

		assertEquals("X", store.current().getSharedDir());
		assertTrue(store.current().isSaveBookingsImmediately());
		assertFalse(tracker.isDirty(store.current()));
		assertFalse(tracker.anyDirty());
	}

	@Test
	void partialListenerFailureIsIgnored(@TempDir File dir) {
		SettingsStore store = new SettingsStore(dir);
		List<AppSettings> partial = new ArrayList<>();
		store.addPartialListener(settings -> {
			throw new IllegalStateException("Absichtlicher Fehler im Test");
		});
		store.addPartialListener(partial::add);

		store.saveOnly(settings -> settings.setSharedDir("X"));

		assertEquals(1, partial.size());
		assertEquals("X", store.current().getSharedDir());
	}
}
