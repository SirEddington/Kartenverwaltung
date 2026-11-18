package de.eltviller_carneval_verein.karten.ui;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.TolerantEnum;
import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.settings.DisplaySettings;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.ui.hall.PlanLabelOptions;
import de.eltviller_carneval_verein.karten.ui.hall.PlanLabels;
import de.eltviller_carneval_verein.karten.ui.sales.SelectionHintPlacement;
import de.eltviller_carneval_verein.karten.ui.sales.SummaryPlacement;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Die Auswahl im Menü "Anzeige", gemeinsam für Saalansicht, Kundenfenster und Hallen-Editor (KARV-76): Reihen im
 * Editor ausblenden blendet sie auch in der Saalansicht aus und umgekehrt.
 * <p>
 * Die App hat eine Instanz, die {@code MainApp} beim Start einrichtet ({@link #install}). Ist "Anzeige beim Start
 * wiederherstellen" an ({@link DisplaySettings#isRestoreAtStart()}), übernimmt sie die gespeicherte Auswahl, sonst ist
 * alles sichtbar, der Hinweis am Mauszeiger steht über dem Zeiger und die Kaufzusammenfassung automatisch. Jede Änderung im Menü wird sofort per Teil-Speichern
 * ({@link SettingsStore#saveOnly}) geschrieben, auch bei ausgeschaltetem Schalter; der Schalter entscheidet nur über
 * den nächsten Start. Scheitert das Schreiben, gilt die Auswahl trotzdem für die laufende Sitzung.
 * <p>
 * Nur im JavaFX-Thread verwenden.
 */
public final class DisplayPreferences {

	private static final Logger LOG = Logger.getLogger(DisplayPreferences.class.getName());

	private static DisplayPreferences instance;

	private final PlanLabelOptions planLabels = new PlanLabelOptions();
	private final BooleanProperty seatPatterns = new SimpleBooleanProperty(true);
	private final BooleanProperty selectionHint = new SimpleBooleanProperty(true);
	private final ObjectProperty<SelectionHintPlacement> selectionHintPlacement = new SimpleObjectProperty<>(SelectionHintPlacement.ABOVE);
	private final BooleanProperty selectionHintAvoidEdges = new SimpleBooleanProperty(true);
	// Wie bei der Platzierung unten: Eine unbekannte Lage gilt als ABOVE und bleibt in der Datei, bis eine andere gewählt wird
	private final TolerantEnum<SelectionHintPlacement> fileHintPlacement = new TolerantEnum<>(SelectionHintPlacement.class,
			SelectionHintPlacement.ABOVE, SelectionHintPlacement.ABOVE);
	private final ObjectProperty<SummaryPlacement> summaryPlacement = new SimpleObjectProperty<>(SummaryPlacement.AUTO);
	// Die Platzierung, wie sie in der Datei steht: Eine unbekannte (aus einer neueren Version) gilt als automatisch und
	// wird unverändert zurückgeschrieben, bis jemand eine andere als "Automatisch" wählt
	private final TolerantEnum<SummaryPlacement> filePlacement = new TolerantEnum<>(SummaryPlacement.class, SummaryPlacement.AUTO,
			SummaryPlacement.AUTO);
	private final Consumer<Consumer<AppSettings>> saveOnly;

	/**
	 * @param saved    der gespeicherte Stand
	 * @param saveOnly schreibt eine Änderung der Einstellungen (in der App {@link SettingsStore#saveOnly})
	 */
	DisplayPreferences(DisplaySettings saved, Consumer<Consumer<AppSettings>> saveOnly) {
		this.saveOnly = saveOnly;
		filePlacement.fromJson(saved.getSummaryPlacement());
		fileHintPlacement.fromJson(saved.getSelectionHintPlacement());
		if (saved.isRestoreAtStart()) {
			planLabels.rowsProperty().set(saved.isRows());
			planLabels.tablesProperty().set(saved.isTables());
			planLabels.seatNumbersProperty().set(saved.isSeatNumbers());
			planLabels.hallObjectsProperty().set(saved.isHallObjects());
			seatPatterns.set(saved.isSeatPatterns());
			selectionHint.set(saved.isSelectionHint());
			selectionHintPlacement.set(fileHintPlacement.get());
			selectionHintAvoidEdges.set(saved.isSelectionHintAvoidEdges());
			summaryPlacement.set(filePlacement.get());
		}
		// Erst nach dem Übernehmen, damit der Start nichts schreibt
		planLabels.addListener((obs, oldValue, newValue) -> write());
		seatPatterns.addListener((obs, oldValue, newValue) -> write());
		selectionHint.addListener((obs, oldValue, newValue) -> write());
		selectionHintPlacement.addListener((obs, oldValue, newValue) -> write());
		selectionHintAvoidEdges.addListener((obs, oldValue, newValue) -> write());
		summaryPlacement.addListener((obs, oldValue, newValue) -> write());
	}

	/** Richtet die Instanz der App aus den geladenen Einstellungen ein (beim Start, bevor eine Ansicht sie braucht). */
	public static void install(SettingsStore store) {
		instance = new DisplayPreferences(store.current().getDisplay(), store::saveOnly);
	}

	/** Die Instanz der App. */
	public static DisplayPreferences instance() {
		if (instance == null) {
			throw new IllegalStateException("DisplayPreferences.install wurde beim Start nicht aufgerufen");
		}
		return instance;
	}

	/** Eine Auswahl, die nichts liest und nichts schreibt (für Tests). */
	public static DisplayPreferences unsaved() {
		return new DisplayPreferences(new DisplaySettings(), change -> {
		});
	}

	/** Die Beschriftungen des Plans (Reihen, Tische, Sitznummern, Hallenobjekte). */
	public PlanLabelOptions planLabels() {
		return planLabels;
	}

	/**
	 * Ob Saalansicht und Kundenfenster den Sitzstatus barrierefrei zeigen (KARV-26): mit Ring, Schraffur und Kreuz statt
	 * nur über die Farbe, die Saalansicht dazu mit Legende. Standard an.
	 */
	public BooleanProperty seatPatternsProperty() {
		return seatPatterns;
	}

	/** Ob die Verkaufsansicht Anzahl und Preis der gewählten Karten am Mauszeiger zeigt (KARV-194). Standard an. */
	public BooleanProperty selectionHintProperty() {
		return selectionHint;
	}

	/** Ob der Hinweis am Mauszeiger über oder unter dem Zeiger steht (KARV-197). Standard über dem Zeiger. */
	public ObjectProperty<SelectionHintPlacement> selectionHintPlacementProperty() {
		return selectionHintPlacement;
	}

	/**
	 * Ob der Hinweis am Mauszeiger am Rand auf die andere Seite ausweicht; sonst wird er nur an den Rand geschoben
	 * (KARV-197). Standard an.
	 */
	public BooleanProperty selectionHintAvoidEdgesProperty() {
		return selectionHintAvoidEdges;
	}

	/** Wo das Kundenfenster die Kaufzusammenfassung zeigt. */
	public ObjectProperty<SummaryPlacement> summaryPlacementProperty() {
		return summaryPlacement;
	}

	private void write() {
		try {
			PlanLabels labels = planLabels.snapshot();
			boolean patterns = seatPatterns.get();
			boolean hint = selectionHint.get();
			fileHintPlacement.set(Objects.requireNonNullElse(selectionHintPlacement.get(), SelectionHintPlacement.ABOVE));
			String hintPlacement = fileHintPlacement.toJson();
			boolean avoidEdges = selectionHintAvoidEdges.get();
			SummaryPlacement current = summaryPlacement.get();
			filePlacement.set(current == null ? SummaryPlacement.AUTO : current);
			String placement = filePlacement.toJson();
			saveOnly.accept(settings -> {
				DisplaySettings display = settings.getDisplay();
				display.setRows(labels.rows());
				display.setTables(labels.tables());
				display.setSeatNumbers(labels.seatNumbers());
				display.setHallObjects(labels.hallObjects());
				display.setSeatPatterns(patterns);
				display.setSelectionHint(hint);
				display.setSelectionHintPlacement(hintPlacement);
				display.setSelectionHintAvoidEdges(avoidEdges);
				display.setSummaryPlacement(placement);
			});
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Die Auswahl im Menü \"Anzeige\" ließ sich nicht speichern", e);
		}
	}
}
