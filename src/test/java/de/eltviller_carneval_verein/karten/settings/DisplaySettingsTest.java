package de.eltviller_carneval_verein.karten.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/** Der Block {@code display} in {@code settings.json} (KARV-76): Menü "Anzeige" und ob es beim Start wieder gilt. */
class DisplaySettingsTest {

	private static void write(File dir, String text) throws IOException {
		Files.writeString(new File(dir, "settings.json").toPath(), text, StandardCharsets.UTF_8);
	}

	private static void assertDefaults(DisplaySettings display) {
		assertTrue(display.isRestoreAtStart());
		assertTrue(display.isRows());
		assertTrue(display.isTables());
		assertTrue(display.isSeatNumbers());
		assertTrue(display.isHallObjects());
		assertEquals("AUTO", display.getSummaryPlacement());
	}

	@Test
	void defaultsRestoreAndShowEverything() {
		assertDefaults(new AppSettings().getDisplay());
	}

	@Test
	void defaultSettingsSerializeTheDisplayBlock() throws IOException {
		JsonNode display = JsonMapperFactory.create().readTree(JsonMapperFactory.create().writeValueAsString(new AppSettings())).get("display");

		assertTrue(display.get("restoreAtStart").asBoolean());
		assertTrue(display.get("rows").asBoolean());
		assertTrue(display.get("tables").asBoolean());
		assertTrue(display.get("seatNumbers").asBoolean());
		assertTrue(display.get("hallObjects").asBoolean());
		assertEquals("AUTO", display.get("summaryPlacement").asText());
	}

	@Test
	void settingsFrom110WithoutTheBlockGetTheDefaults(@TempDir File dir) throws IOException {
		write(dir, "{\"sharedDir\": null, \"saveBookingsImmediately\": true}");

		assertDefaults(new SettingsStore(dir).current().getDisplay());
	}

	@Test
	void missingFieldsInTheBlockGetTheirDefaults(@TempDir File dir) throws IOException {
		write(dir, "{\"display\": {\"rows\": false}}");

		DisplaySettings display = new SettingsStore(dir).current().getDisplay();

		assertFalse(display.isRows());
		assertTrue(display.isRestoreAtStart());
		assertTrue(display.isTables());
		assertEquals("AUTO", display.getSummaryPlacement());
	}

	@Test
	void nullBlockOrPlacementGetTheDefaults(@TempDir File dir) throws IOException {
		write(dir, "{\"display\": null}");
		assertDefaults(new SettingsStore(dir).current().getDisplay());

		DisplaySettings display = new DisplaySettings();
		display.setSummaryPlacement(null);
		assertEquals("AUTO", display.getSummaryPlacement());
		AppSettings settings = new AppSettings();
		settings.setDisplay(null);
		assertNotNull(settings.getDisplay());
	}

	@Test
	void unknownFieldsAndPlacementsFromANewerVersionDoNotBreakTheFile(@TempDir File dir) throws IOException {
		write(dir, "{\"display\": {\"rows\": false, \"summaryPlacement\": \"CORNER\", \"zukunft\": 1}}");

		SettingsStore store = new SettingsStore(dir);

		assertTrue(store.getAndClearLoadWarnings().isEmpty(), "die Datei gilt nicht als defekt");
		assertFalse(store.current().getDisplay().isRows());
		// Unbekannte Platzierungen löst erst die Oberfläche auf (DisplayPreferences); hier bleibt der Text
		assertEquals("CORNER", store.current().getDisplay().getSummaryPlacement());
	}

	@Test
	void blockSurvivesSaveAndReload(@TempDir File dir) {
		SettingsStore store = new SettingsStore(dir);
		DisplaySettings display = store.current().getDisplay();
		display.setRestoreAtStart(false);
		display.setRows(false);
		display.setTables(false);
		display.setSeatNumbers(false);
		display.setHallObjects(false);
		display.setSummaryPlacement("LEFT");
		store.save();

		DisplaySettings reloaded = new SettingsStore(dir).current().getDisplay();

		assertFalse(reloaded.isRestoreAtStart());
		assertFalse(reloaded.isRows());
		assertFalse(reloaded.isTables());
		assertFalse(reloaded.isSeatNumbers());
		assertFalse(reloaded.isHallObjects());
		assertEquals("LEFT", reloaded.getSummaryPlacement());
	}

	@Test
	void saveOnlyOfTheMenuKeepsAnUnsavedRestoreSwitch(@TempDir File dir) throws IOException {
		SettingsStore store = new SettingsStore(dir);
		store.current().getDisplay().setRestoreAtStart(false);

		store.saveOnly(settings -> settings.getDisplay().setRows(false));

		JsonNode file = JsonMapperFactory.create().readTree(new File(dir, "settings.json")).get("display");
		assertFalse(file.get("rows").asBoolean());
		assertTrue(file.get("restoreAtStart").asBoolean(), "der ungespeicherte Schalter wird nicht mitgeschrieben");
		assertFalse(store.current().getDisplay().isRows());
		assertFalse(store.current().getDisplay().isRestoreAtStart());
	}
}
