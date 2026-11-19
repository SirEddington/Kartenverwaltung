package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.settings.DisplaySettings;
import de.eltviller_carneval_verein.karten.ui.hall.PlanLabels;
import de.eltviller_carneval_verein.karten.ui.sales.GroupOrder;
import de.eltviller_carneval_verein.karten.ui.sales.SelectionHintPlacement;
import de.eltviller_carneval_verein.karten.ui.sales.SummaryPlacement;
import de.eltviller_carneval_verein.karten.ui.sales.TicketColumn;

class DisplayPreferencesTest {

	/** Was geschrieben wurde: jede Änderung angewendet auf eigene Einstellungen. */
	private final AppSettings written = new AppSettings();
	private final DisplaySettings file = written.getDisplay();
	private int writes;

	private DisplayPreferences load(DisplaySettings saved) {
		return new DisplayPreferences(saved, change -> {
			writes++;
			change.accept(written);
		});
	}

	private static DisplaySettings saved(boolean restore) {
		DisplaySettings saved = new DisplaySettings();
		saved.setRestoreAtStart(restore);
		saved.setRows(false);
		saved.setSeatNumbers(false);
		saved.setSeatPatterns(false);
		saved.setSelectionHint(false);
		saved.setSelectionHintPlacement("BELOW");
		saved.setSelectionHintAvoidEdges(false);
		saved.setSummaryPlacement("LEFT");
		return saved;
	}

	@Test
	void withRestoreTheSavedSelectionApplies() {
		DisplayPreferences prefs = load(saved(true));

		assertEquals(new PlanLabels(false, true, false, true), prefs.planLabels().snapshot());
		assertFalse(prefs.seatPatternsProperty().get());
		assertFalse(prefs.selectionHintProperty().get());
		assertEquals(SelectionHintPlacement.BELOW, prefs.selectionHintPlacementProperty().get());
		assertFalse(prefs.selectionHintAvoidEdgesProperty().get());
		assertEquals(SummaryPlacement.LEFT, prefs.summaryPlacementProperty().get());
	}

	@Test
	void ticketGroupingAppliesAtStartWithRestore() {
		DisplaySettings saved = saved(true);
		saved.setTicketGrouping(List.of("PRESENTATION", "LAST_NAME"));
		saved.setTicketGroupOrder("SIZE");

		DisplayPreferences prefs = load(saved);

		assertEquals(List.of(TicketColumn.PRESENTATION, TicketColumn.LAST_NAME), prefs.ticketGroupingProperty().get());
		assertEquals(GroupOrder.SIZE, prefs.ticketGroupOrderProperty().get());
		assertEquals(0, writes, "der Start schreibt nichts");
	}

	@Test
	void withoutRestoreTheTicketTableIsUngrouped() {
		DisplaySettings saved = saved(false);
		saved.setTicketGrouping(List.of("LAST_NAME"));
		saved.setTicketGroupOrder("SIZE");

		DisplayPreferences prefs = load(saved);

		assertTrue(prefs.ticketGroupingProperty().get().isEmpty());
		assertEquals(GroupOrder.VALUE, prefs.ticketGroupOrderProperty().get());
	}

	@Test
	void changingTheTicketGroupingIsWrittenAndTheListIsUnmodifiable() {
		DisplayPreferences prefs = load(saved(true));
		assertTrue(prefs.ticketGroupingProperty().get().isEmpty());

		prefs.ticketGroupingProperty().set(new ArrayList<>(List.of(TicketColumn.CATEGORY, TicketColumn.SEAT)));
		assertEquals(1, writes);
		assertEquals(List.of("CATEGORY", "SEAT"), file.getTicketGrouping());
		assertEquals("BELOW", file.getSelectionHintPlacement(), "die übrige Auswahl wird mitgeschrieben");
		assertThrows(UnsupportedOperationException.class, () -> prefs.ticketGroupingProperty().get().add(TicketColumn.TABLE));

		prefs.ticketGroupOrderProperty().set(GroupOrder.SIZE);
		assertEquals("SIZE", file.getTicketGroupOrder());
		assertEquals(List.of("CATEGORY", "SEAT"), file.getTicketGrouping(), "eine andere Änderung schreibt die Gruppierung mit");

		prefs.ticketGroupingProperty().set(null);
		assertTrue(file.getTicketGrouping().isEmpty());
	}

	@Test
	void unknownAndDuplicateColumnsAreSkippedWhenReading() {
		DisplaySettings saved = saved(true);
		saved.setTicketGrouping(List.of("LAST_NAME", "ZUKUNFT", "TABLE", "LAST_NAME", "ZUKUNFT"));

		DisplayPreferences prefs = load(saved);

		assertEquals(List.of(TicketColumn.LAST_NAME, TicketColumn.TABLE), prefs.ticketGroupingProperty().get());
		prefs.ticketGroupOrderProperty().set(GroupOrder.SIZE);
		assertEquals(List.of("LAST_NAME", "TABLE"), file.getTicketGrouping(), "unbekannte Namen verschwinden beim nächsten Schreiben");
	}

	@Test
	void anUnknownGroupOrderIsValueAndWrittenBackUntilAnotherIsChosen() {
		DisplaySettings saved = saved(true);
		saved.setTicketGroupOrder("ALPHABET");
		DisplayPreferences prefs = load(saved);

		assertEquals(GroupOrder.VALUE, prefs.ticketGroupOrderProperty().get());
		prefs.selectionHintProperty().set(true);
		assertEquals("ALPHABET", file.getTicketGroupOrder(), "eine neuere Version findet ihre Wahl wieder");

		prefs.ticketGroupOrderProperty().set(GroupOrder.SIZE);
		assertEquals("SIZE", file.getTicketGroupOrder());
		prefs.ticketGroupOrderProperty().set(GroupOrder.VALUE);
		assertEquals("VALUE", file.getTicketGroupOrder());
	}

	@Test
	void switchingAvoidEdgesWritesTheWholeSelection() {
		DisplayPreferences prefs = load(saved(true));

		prefs.selectionHintAvoidEdgesProperty().set(true);
		assertEquals(1, writes);
		assertTrue(file.isSelectionHintAvoidEdges());
		assertEquals("BELOW", file.getSelectionHintPlacement(), "die übrige Auswahl wird mitgeschrieben");

		prefs.selectionHintProperty().set(true);
		assertTrue(file.isSelectionHintAvoidEdges(), "eine andere Änderung schreibt den Haken unverändert mit");
	}

	@Test
	void withoutRestoreEverythingIsShownAndPlacedAutomatically() {
		DisplayPreferences prefs = load(saved(false));

		assertEquals(PlanLabels.ALL, prefs.planLabels().snapshot());
		assertTrue(prefs.seatPatternsProperty().get(), "die barrierefreie Darstellung ist der Normalfall");
		assertTrue(prefs.selectionHintProperty().get(), "der Hinweis am Mauszeiger ist der Normalfall");
		assertEquals(SelectionHintPlacement.ABOVE, prefs.selectionHintPlacementProperty().get());
		assertTrue(prefs.selectionHintAvoidEdgesProperty().get(), "Ausweichen am Rand ist der Normalfall");
		assertEquals(SummaryPlacement.AUTO, prefs.summaryPlacementProperty().get());
	}

	@Test
	void changingTheHintPlacementWritesTheWholeSelection() {
		DisplayPreferences prefs = load(saved(true));

		prefs.selectionHintPlacementProperty().set(SelectionHintPlacement.ABOVE);
		assertEquals(1, writes);
		assertEquals("ABOVE", file.getSelectionHintPlacement());
		assertFalse(file.isSelectionHint(), "die übrige Auswahl wird mitgeschrieben");

		prefs.selectionHintProperty().set(true);
		assertEquals("ABOVE", file.getSelectionHintPlacement(), "eine andere Änderung schreibt die Lage unverändert mit");
	}

	@Test
	void anUnknownHintPlacementIsAboveAndWrittenBackUntilAnotherIsChosen() {
		DisplaySettings saved = saved(true);
		saved.setSelectionHintPlacement("LEFT");
		DisplayPreferences prefs = load(saved);

		assertEquals(SelectionHintPlacement.ABOVE, prefs.selectionHintPlacementProperty().get());
		prefs.selectionHintProperty().set(true);
		assertEquals("LEFT", file.getSelectionHintPlacement(), "eine neuere Version findet ihre Wahl wieder");

		prefs.selectionHintPlacementProperty().set(SelectionHintPlacement.BELOW);
		assertEquals("BELOW", file.getSelectionHintPlacement());
	}

	@Test
	void aMissingHintPlacementIsWrittenAsAbove() {
		DisplayPreferences prefs = load(saved(true));

		prefs.selectionHintPlacementProperty().set(null);

		assertEquals("ABOVE", file.getSelectionHintPlacement());
	}

	@Test
	void switchingTheSelectionHintWritesTheWholeSelection() {
		DisplayPreferences prefs = load(saved(true));

		prefs.selectionHintProperty().set(true);
		assertEquals(1, writes);
		assertTrue(file.isSelectionHint());
		assertFalse(file.isSeatPatterns(), "die übrige Auswahl wird mitgeschrieben");

		prefs.planLabels().rowsProperty().set(true);
		assertTrue(file.isSelectionHint(), "eine andere Änderung schreibt den Schalter unverändert mit");
	}

	@Test
	void switchingSeatPatternsWritesTheWholeSelection() {
		DisplayPreferences prefs = load(saved(true));

		prefs.seatPatternsProperty().set(true);
		assertEquals(1, writes);
		assertTrue(file.isSeatPatterns());
		assertFalse(file.isRows(), "die übrige Auswahl wird mitgeschrieben");
		assertEquals("LEFT", file.getSummaryPlacement());

		prefs.planLabels().rowsProperty().set(true);
		assertTrue(file.isSeatPatterns(), "eine andere Änderung schreibt den Schalter unverändert mit");
	}

	@Test
	void anUnknownPlacementFromANewerVersionBecomesAutomatic() {
		DisplaySettings saved = saved(true);
		saved.setSummaryPlacement("CORNER");

		assertEquals(SummaryPlacement.AUTO, load(saved).summaryPlacementProperty().get());
	}

	@Test
	void anUnknownPlacementIsWrittenBackUntilAnotherIsChosen() {
		DisplaySettings saved = saved(true);
		saved.setSummaryPlacement("CORNER");
		DisplayPreferences prefs = load(saved);

		prefs.planLabels().tablesProperty().set(false);
		assertEquals("CORNER", file.getSummaryPlacement(), "eine neuere Version findet ihre Wahl wieder");

		prefs.summaryPlacementProperty().set(SummaryPlacement.LEFT);
		assertEquals("LEFT", file.getSummaryPlacement());
		prefs.summaryPlacementProperty().set(SummaryPlacement.AUTO);
		assertEquals("AUTO", file.getSummaryPlacement());
	}

	@Test
	void theDefaultPlacementOfTheFileIsTheAutomaticOne() {
		// settings kennt die Oberfläche nicht und führt den Namen deshalb als Text
		assertEquals(SummaryPlacement.AUTO.name(), DisplaySettings.DEFAULT_SUMMARY_PLACEMENT);
	}

	@Test
	void aMissingPlacementIsWrittenAsAutomatic() {
		DisplayPreferences prefs = load(saved(true));

		prefs.summaryPlacementProperty().set(null);

		assertEquals(1, writes);
		assertEquals("AUTO", file.getSummaryPlacement());
	}

	@Test
	void loadingWritesNothing() {
		load(saved(true));

		assertEquals(0, writes);
	}

	@Test
	void everyChangeWritesTheWholeSelection() {
		DisplayPreferences prefs = load(saved(true));

		prefs.planLabels().tablesProperty().set(false);
		prefs.summaryPlacementProperty().set(SummaryPlacement.BOTTOM);

		assertEquals(2, writes);
		assertFalse(file.isRows());
		assertFalse(file.isTables());
		assertFalse(file.isSeatNumbers());
		assertTrue(file.isHallObjects());
		assertFalse(file.isSeatPatterns());
		assertEquals("BOTTOM", file.getSummaryPlacement());
		assertTrue(file.isRestoreAtStart(), "der Schalter gehört den Einstellungen, das Menü schreibt ihn nicht");
	}

	@Test
	void changesAreWrittenEvenWithoutRestore() {
		DisplayPreferences prefs = load(saved(false));

		prefs.planLabels().hallObjectsProperty().set(false);

		assertEquals(1, writes);
		assertFalse(file.isHallObjects());
		assertTrue(file.isRows(), "geschrieben wird, was gerade gilt, nicht der alte gespeicherte Stand");
		assertEquals("AUTO", file.getSummaryPlacement(), "gilt gerade, gespeichert war LEFT");
	}

	@Test
	void aFailedWriteKeepsTheSelectionAndThrowsNothing() {
		DisplayPreferences prefs = new DisplayPreferences(saved(true), change -> {
			throw new IllegalStateException("Datenträger voll");
		});
		// JavaFX fängt Ausnahmen aus Listenern ab und gibt sie hierhin; in der App zeigte der GlobalErrorHandler sie an
		List<Throwable> uncaught = new ArrayList<>();
		Thread thread = Thread.currentThread();
		Thread.UncaughtExceptionHandler before = thread.getUncaughtExceptionHandler();
		thread.setUncaughtExceptionHandler((t, e) -> uncaught.add(e));
		try {
			prefs.planLabels().rowsProperty().set(true);
			prefs.summaryPlacementProperty().set(SummaryPlacement.TOP);
		} finally {
			thread.setUncaughtExceptionHandler(before);
		}

		assertTrue(uncaught.isEmpty(), "das Scheitern wird nur protokolliert: " + uncaught);
		assertTrue(prefs.planLabels().rowsProperty().get());
		assertEquals(SummaryPlacement.TOP, prefs.summaryPlacementProperty().get());
	}
}
