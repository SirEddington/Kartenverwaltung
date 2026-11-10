package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.settings.DisplaySettings;

class DisplayPreferencesTest {

	/** Was geschrieben wurde: jede Änderung angewendet auf eine eigene Kopie der Datei. */
	private final DisplaySettings file = new DisplaySettings();
	private final List<Consumer<DisplaySettings>> writes = new ArrayList<>();

	private DisplayPreferences load(DisplaySettings saved) {
		return new DisplayPreferences(saved, change -> {
			writes.add(change);
			change.accept(file);
		});
	}

	private static DisplaySettings saved(boolean restore) {
		DisplaySettings saved = new DisplaySettings();
		saved.setRestoreAtStart(restore);
		saved.setRows(false);
		saved.setSeatNumbers(false);
		saved.setSummaryPlacement("LEFT");
		return saved;
	}

	@Test
	void withRestoreTheSavedSelectionApplies() {
		DisplayPreferences prefs = load(saved(true));

		assertEquals(new PlanLabels(false, true, false, true), prefs.planLabels().snapshot());
		assertEquals(SummaryPlacement.LEFT, prefs.summaryPlacementProperty().get());
	}

	@Test
	void withoutRestoreEverythingIsShownAndPlacedAutomatically() {
		DisplayPreferences prefs = load(saved(false));

		assertEquals(PlanLabels.ALL, prefs.planLabels().snapshot());
		assertEquals(SummaryPlacement.AUTO, prefs.summaryPlacementProperty().get());
	}

	@Test
	void anUnknownPlacementFromANewerVersionBecomesAutomatic() {
		DisplaySettings saved = saved(true);
		saved.setSummaryPlacement("CORNER");

		assertEquals(SummaryPlacement.AUTO, load(saved).summaryPlacementProperty().get());
	}

	@Test
	void loadingWritesNothing() {
		load(saved(true));

		assertTrue(writes.isEmpty());
	}

	@Test
	void everyChangeWritesTheWholeSelection() {
		DisplayPreferences prefs = load(saved(true));

		prefs.planLabels().tablesProperty().set(false);
		prefs.summaryPlacementProperty().set(SummaryPlacement.BOTTOM);

		assertEquals(2, writes.size());
		assertFalse(file.isRows());
		assertFalse(file.isTables());
		assertFalse(file.isSeatNumbers());
		assertTrue(file.isHallObjects());
		assertEquals("BOTTOM", file.getSummaryPlacement());
		assertTrue(file.isRestoreAtStart(), "der Schalter gehört den Einstellungen, das Menü schreibt ihn nicht");
	}

	@Test
	void changesAreWrittenEvenWithoutRestore() {
		DisplayPreferences prefs = load(saved(false));

		prefs.planLabels().hallObjectsProperty().set(false);

		assertEquals(1, writes.size());
		assertFalse(file.isHallObjects());
		assertTrue(file.isRows(), "geschrieben wird, was gerade gilt, nicht der alte gespeicherte Stand");
	}

	@Test
	void aFailedWriteKeepsTheSelectionForTheSession() {
		DisplayPreferences prefs = new DisplayPreferences(saved(true), change -> {
			throw new IllegalStateException("Datenträger voll");
		});

		prefs.planLabels().rowsProperty().set(true);

		assertTrue(prefs.planLabels().rowsProperty().get());
	}

	@Test
	void theUnsavedVariantStartsWithEverythingShown() {
		DisplayPreferences prefs = DisplayPreferences.unsaved();

		prefs.planLabels().rowsProperty().set(false);

		assertFalse(prefs.planLabels().snapshot().rows());
		assertEquals(SummaryPlacement.AUTO, prefs.summaryPlacementProperty().get());
	}
}
