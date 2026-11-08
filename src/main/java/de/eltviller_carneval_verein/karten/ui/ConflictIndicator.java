package de.eltviller_carneval_verein.karten.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.scene.control.Hyperlink;
import javafx.scene.layout.Region;

/**
 * Der Hinweis in der Fußzeile auf offene Konflikte: ein Link "1 Konflikt offen" bzw. "N Konflikte offen" (Text von
 * {@link ConflictList#indicatorText}); warten eigene Änderungen mit ihrer Auflösung auf eine gesperrte Einheit (KARV-135),
 * dazu "1 Änderung wartet" bzw. "N Änderungen warten", mit " · " angehängt. Sonst sähe der Nutzer den eben verkauften Sitz
 * gesperrt und erführe nicht, warum. Sichtbar nur, solange eins von beiden ansteht; ein Klick öffnet den Sammel-Dialog.
 * Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class ConflictIndicator extends Hyperlink {

	private static final Logger LOG = Logger.getLogger(ConflictIndicator.class.getName());

	private final IntSupplier openCount;
	private final IntSupplier waitingCount;

	/**
	 * @param openCount    liefert die Zahl der offenen Konflikte (in der App die nach der letzten Rückmeldung des
	 *                     Versionsspeichers, damit eine neue Fußzeile nicht neu ableitet)
	 * @param waitingCount liefert die Zahl der eigenen Änderungen, deren Auflösung auf eine gesperrte Einheit wartet (in der
	 *                     App {@code SyncService.waitingResolutions()})
	 * @param open         öffnet den Sammel-Dialog
	 */
	public ConflictIndicator(IntSupplier openCount, IntSupplier waitingCount, Runnable open) {
		this.openCount = openCount;
		this.waitingCount = waitingCount;
		getStyleClass().add("conflict-indicator");
		// in voller Breite: kürzen soll die Fußzeile die Meldung daneben (StatusLabel)
		setMinWidth(Region.USE_PREF_SIZE);
		setOnAction(event -> {
			// ein Link, der immer wieder gilt, ist nie "besucht"
			setVisited(false);
			open.run();
		});
		refresh();
	}

	/** Liest beide Zahlen neu und zeigt oder versteckt den Link. Scheitert eine, zählt sie als 0. */
	public void refresh() {
		int open = 0;
		try {
			open = openCount.getAsInt();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Die offenen Konflikte ließen sich nicht zählen", e);
		}
		int waiting = 0;
		try {
			waiting = waitingCount.getAsInt();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Die wartenden Änderungen ließen sich nicht zählen", e);
		}
		setText(text(open, waiting));
		setVisible(open > 0 || waiting > 0);
		setManaged(open > 0 || waiting > 0);
	}

	/**
	 * Der Text des Links: die offenen Konflikte, dann die wartenden Änderungen, mit {@code " · "} verbunden; was 0 ist,
	 * fehlt.
	 */
	static String text(int open, int waiting) {
		List<String> parts = new ArrayList<>(2);
		if (open > 0) {
			parts.add(ConflictList.indicatorText(open));
		}
		if (waiting > 0) {
			parts.add(waiting == 1 ? "1 Änderung wartet" : waiting + " Änderungen warten");
		}
		return String.join(" · ", parts);
	}
}
