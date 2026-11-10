package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.settings.DisplaySettings;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Die Auswahl im Menü "Anzeige", gemeinsam für Saalansicht, Kundenfenster und Hallen-Editor (KARV-76): Reihen im
 * Editor ausblenden blendet sie auch in der Saalansicht aus und umgekehrt.
 * <p>
 * Die App hat eine Instanz ({@link #instance()}). Sie übernimmt beim ersten Zugriff die Auswahl aus {@code settings.json},
 * wenn dort "Anzeige beim Start wiederherstellen" an ist ({@link DisplaySettings#isRestoreAtStart()}); sonst ist alles
 * sichtbar und die Kaufzusammenfassung steht automatisch. Jede Änderung im Menü wird sofort geschrieben, auch bei
 * ausgeschaltetem Schalter, per Teil-Speichern ({@link SettingsStore#saveOnly}), damit andere offene Änderungen der
 * Einstellungen unberührt bleiben. Scheitert das Schreiben, gilt die Auswahl trotzdem für die laufende Sitzung.
 * <p>
 * Nur im JavaFX-Thread verwenden.
 */
public final class DisplayPreferences {

	private static final Logger LOG = Logger.getLogger(DisplayPreferences.class.getName());

	private static DisplayPreferences instance;

	private final PlanLabelOptions planLabels = new PlanLabelOptions();
	private final ObjectProperty<SummaryPlacement> summaryPlacement = new SimpleObjectProperty<>(SummaryPlacement.AUTO);
	private final Consumer<Consumer<DisplaySettings>> writer;

	/**
	 * @param saved  der gespeicherte Stand
	 * @param writer schreibt eine Änderung des gespeicherten Stands (in der App per {@link SettingsStore#saveOnly})
	 */
	DisplayPreferences(DisplaySettings saved, Consumer<Consumer<DisplaySettings>> writer) {
		this.writer = writer;
		if (saved.isRestoreAtStart()) {
			planLabels.rowsProperty().set(saved.isRows());
			planLabels.tablesProperty().set(saved.isTables());
			planLabels.seatNumbersProperty().set(saved.isSeatNumbers());
			planLabels.hallObjectsProperty().set(saved.isHallObjects());
			summaryPlacement.set(placementOf(saved.getSummaryPlacement()));
		}
		// Erst nach dem Übernehmen, damit der Start nichts schreibt
		planLabels.addListener((obs, oldValue, newValue) -> write());
		summaryPlacement.addListener((obs, oldValue, newValue) -> write());
	}

	/** Die Instanz der App, beim ersten Aufruf aus den gespeicherten Einstellungen aufgebaut. */
	public static DisplayPreferences instance() {
		if (instance == null) {
			SettingsStore store = SettingsStore.getInstance();
			instance = new DisplayPreferences(store.current().getDisplay(),
					change -> store.saveOnly(settings -> change.accept(settings.getDisplay())));
		}
		return instance;
	}

	/** Eine Auswahl, die nichts liest und nichts schreibt (für Tests und Ansichten ohne gespeicherte Anzeige). */
	public static DisplayPreferences unsaved() {
		return new DisplayPreferences(new DisplaySettings(), change -> {
		});
	}

	/** Die Beschriftungen des Plans (Reihen, Tische, Sitznummern, Hallenobjekte). */
	public PlanLabelOptions planLabels() {
		return planLabels;
	}

	/** Wo das Kundenfenster die Kaufzusammenfassung zeigt. */
	public ObjectProperty<SummaryPlacement> summaryPlacementProperty() {
		return summaryPlacement;
	}

	private void write() {
		PlanLabels labels = planLabels.snapshot();
		String placement = summaryPlacement.get().name();
		try {
			writer.accept(display -> {
				display.setRows(labels.rows());
				display.setTables(labels.tables());
				display.setSeatNumbers(labels.seatNumbers());
				display.setHallObjects(labels.hallObjects());
				display.setSummaryPlacement(placement);
			});
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Die Auswahl im Menü \"Anzeige\" ließ sich nicht speichern", e);
		}
	}

	/** Die gespeicherte Platzierung; eine unbekannte (etwa aus einer neueren Version) gilt als automatisch. */
	private static SummaryPlacement placementOf(String name) {
		for (SummaryPlacement value : SummaryPlacement.values()) {
			if (value.name().equals(name)) {
				return value;
			}
		}
		return SummaryPlacement.AUTO;
	}
}
