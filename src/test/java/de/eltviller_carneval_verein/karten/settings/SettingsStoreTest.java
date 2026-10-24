package de.eltviller_carneval_verein.karten.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

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
	void mirrorDirSurvivesSaveAndReload(@TempDir File dir) {
		SettingsStore store = new SettingsStore(dir);
		store.current().setMirrorDir("E:\\ECV");

		store.save();

		assertEquals("E:\\ECV", new SettingsStore(dir).current().getMirrorDir());
	}

	@Test
	void emptySettingsSerializeToAnEmptyObject() throws IOException {
		String json = JsonMapperFactory.create().writeValueAsString(new AppSettings());

		assertTrue(JsonMapperFactory.create().readTree(json).isObject());
		assertEquals(0, JsonMapperFactory.create().readTree(json).size());
	}
}
