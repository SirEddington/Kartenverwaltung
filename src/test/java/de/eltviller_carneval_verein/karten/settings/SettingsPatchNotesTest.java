package de.eltviller_carneval_verein.karten.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/** KARV-125: die gesehene Patchnotes-Version und ob {@code settings.json} beim Start schon da war. */
class SettingsPatchNotesTest {

	@TempDir
	File dir;

	private void write(String json) throws IOException {
		Files.writeString(new File(dir, "settings.json").toPath(), json, StandardCharsets.UTF_8);
	}

	@Test
	void patchNotesSeenIsNullByDefault() {
		assertNull(new SettingsStore(dir).current().getPatchNotesSeen());
	}

	@Test
	void patchNotesSeenSurvivesRoundTrip() {
		new SettingsStore(dir).saveOnly(s -> s.setPatchNotesSeen("1.1.0"));
		assertEquals("1.1.0", new SettingsStore(dir).current().getPatchNotesSeen());
	}

	@Test
	void existedAtLoadWithoutFile() {
		assertFalse(new SettingsStore(dir).existedAtLoad());
	}

	@Test
	void existedAtLoadWithFile() throws IOException {
		write("{\"sharedDir\":null}");
		assertTrue(new SettingsStore(dir).existedAtLoad());
	}

	@Test
	void existedAtLoadWithBrokenFile() throws IOException {
		write("[]");
		assertTrue(new SettingsStore(dir).existedAtLoad());
	}

	@Test
	void existedAtLoadDoesNotChangeAfterSaving() {
		SettingsStore store = new SettingsStore(dir);
		store.save();
		store.saveOnly(s -> s.setPatchNotesSeen("1.1.0"));
		assertFalse(store.existedAtLoad());
	}

	@Test
	void rememberingKeepsOtherFields() throws IOException {
		write("{\"sharedDir\":\"D:\\\\Ordner\",\"saveBookingsImmediately\":false,\"checkCloudServer\":false,"
				+ "\"recentOperators\":[\"Anna\"],\"future\":1}");
		new SettingsStore(dir).saveOnly(s -> s.setPatchNotesSeen("1.1.0"));

		AppSettings reloaded = new SettingsStore(dir).current();
		assertEquals("D:\\Ordner", reloaded.getSharedDir());
		assertFalse(reloaded.isSaveBookingsImmediately());
		assertFalse(reloaded.isCheckCloudServer());
		assertEquals(List.of("Anna"), reloaded.getRecentOperators());
		assertEquals("1.1.0", reloaded.getPatchNotesSeen());
		JsonNode file = JsonMapperFactory.create().readTree(new File(dir, "settings.json"));
		assertEquals("1.1.0", file.get("patchNotesSeen").asText());
	}
}
