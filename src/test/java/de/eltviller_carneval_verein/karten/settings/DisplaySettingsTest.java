package de.eltviller_carneval_verein.karten.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
		assertTrue(display.isSeatPatterns());
		assertTrue(display.isSelectionHint());
		assertEquals("ABOVE", display.getSelectionHintPlacement());
		assertTrue(display.isSelectionHintAvoidEdges());
		assertEquals("AUTO", display.getSummaryPlacement());
		assertTrue(display.getTicketGrouping().isEmpty());
		assertEquals("VALUE", display.getTicketGroupOrder());
		assertTrue(display.isTicketGroupingNested());
	}

	@Test
	void ticketGroupingNestedDefaultsToTrueAndSurvivesSaveAndReload(@TempDir File dir) throws IOException {
		write(dir, "{\"display\": {\"restoreAtStart\": true}}");
		assertTrue(new SettingsStore(dir).current().getDisplay().isTicketGroupingNested(), "fehlendes Feld: verschachtelt");

		SettingsStore store = new SettingsStore(dir);
		store.current().getDisplay().setTicketGroupingNested(false);
		store.save();

		assertFalse(new SettingsStore(dir).current().getDisplay().isTicketGroupingNested());
	}

	@Test
	void settingsWithoutTheTicketGroupingAreUngrouped(@TempDir File dir) throws IOException {
		// KARV-193: Die Gruppierung kam nach der Platzierung; ein Block ohne sie lässt die Kartentabelle ungruppiert
		write(dir, "{\"display\": {\"restoreAtStart\": true, \"summaryPlacement\": \"LEFT\"}}");

		DisplaySettings display = new SettingsStore(dir).current().getDisplay();

		assertTrue(display.getTicketGrouping().isEmpty());
		assertEquals("VALUE", display.getTicketGroupOrder());
	}

	@Test
	void ticketGroupingNullGetsTheDefaults(@TempDir File dir) throws IOException {
		write(dir, "{\"display\": {\"ticketGrouping\": null, \"ticketGroupOrder\": null}}");
		DisplaySettings display = new SettingsStore(dir).current().getDisplay();
		assertTrue(display.getTicketGrouping().isEmpty());
		assertEquals("VALUE", display.getTicketGroupOrder());

		DisplaySettings bean = new DisplaySettings();
		bean.setTicketGrouping(List.of("LAST_NAME"));
		bean.setTicketGrouping(null);
		assertTrue(bean.getTicketGrouping().isEmpty());
		bean.setTicketGroupOrder("SIZE");
		bean.setTicketGroupOrder(null);
		assertEquals(DisplaySettings.DEFAULT_TICKET_GROUP_ORDER, bean.getTicketGroupOrder());
		assertEquals("VALUE", DisplaySettings.DEFAULT_TICKET_GROUP_ORDER);
	}

	@Test
	void ticketGroupingSurvivesSaveAndReload(@TempDir File dir) {
		SettingsStore store = new SettingsStore(dir);
		store.current().getDisplay().setTicketGrouping(List.of("PRESENTATION", "LAST_NAME"));
		store.current().getDisplay().setTicketGroupOrder("SIZE");
		store.save();

		DisplaySettings reloaded = new SettingsStore(dir).current().getDisplay();

		assertEquals(List.of("PRESENTATION", "LAST_NAME"), reloaded.getTicketGrouping());
		assertEquals("SIZE", reloaded.getTicketGroupOrder());
	}

	@Test
	void settingsWithoutAvoidEdgesLetTheHintAvoidEdges(@TempDir File dir) throws IOException {
		// KARV-197: Der Haken kam nach der Lage; ein Block ohne ihn weicht am Rand aus
		write(dir, "{\"display\": {\"restoreAtStart\": true, \"selectionHintPlacement\": \"BELOW\"}}");

		assertTrue(new SettingsStore(dir).current().getDisplay().isSelectionHintAvoidEdges());
	}

	@Test
	void settingsWithoutTheHintPlacementPutTheHintAbove(@TempDir File dir) throws IOException {
		// KARV-197: Die Lage kam nach dem Schalter; ein Block ohne sie (1.1.0, Testbuilds von 1.1.1) zeigt ihn oben
		write(dir, "{\"display\": {\"restoreAtStart\": true, \"selectionHint\": false}}");

		DisplaySettings display = new SettingsStore(dir).current().getDisplay();

		assertEquals("ABOVE", display.getSelectionHintPlacement());
		assertFalse(display.isSelectionHint());
	}

	@Test
	void settingsFrom110WithoutSelectionHintShowTheHint(@TempDir File dir) throws IOException {
		// KARV-194: Der Schalter kam mit 1.1.1; ein Block ohne ihn zeigt den Hinweis am Mauszeiger
		write(dir, "{\"display\": {\"restoreAtStart\": true, \"rows\": false, \"seatPatterns\": false}}");

		assertTrue(new SettingsStore(dir).current().getDisplay().isSelectionHint());
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
		assertTrue(display.get("seatPatterns").asBoolean());
		assertTrue(display.get("selectionHint").asBoolean());
		assertEquals("ABOVE", display.get("selectionHintPlacement").asText());
		assertTrue(display.get("selectionHintAvoidEdges").asBoolean());
		assertEquals("AUTO", display.get("summaryPlacement").asText());
	}

	@Test
	void settingsFrom110WithoutSeatPatternsShowThePatterns(@TempDir File dir) throws IOException {
		// KARV-26: Der Schalter kam nach KARV-76; ein Block ohne ihn zeigt die barrierefreie Darstellung
		write(dir, "{\"display\": {\"restoreAtStart\": true, \"rows\": false, \"summaryPlacement\": \"LEFT\"}}");

		assertTrue(new SettingsStore(dir).current().getDisplay().isSeatPatterns());
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
		display.setSelectionHintPlacement(null);
		assertEquals("ABOVE", display.getSelectionHintPlacement());
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
		display.setSeatPatterns(false);
		display.setSelectionHint(false);
		display.setSelectionHintPlacement("BELOW");
		display.setSelectionHintAvoidEdges(false);
		display.setSummaryPlacement("LEFT");
		store.save();

		DisplaySettings reloaded = new SettingsStore(dir).current().getDisplay();

		assertFalse(reloaded.isRestoreAtStart());
		assertFalse(reloaded.isRows());
		assertFalse(reloaded.isTables());
		assertFalse(reloaded.isSeatNumbers());
		assertFalse(reloaded.isHallObjects());
		assertFalse(reloaded.isSeatPatterns());
		assertFalse(reloaded.isSelectionHint());
		assertEquals("BELOW", reloaded.getSelectionHintPlacement());
		assertFalse(reloaded.isSelectionHintAvoidEdges());
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
