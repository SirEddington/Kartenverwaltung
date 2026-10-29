package de.eltviller_carneval_verein.karten.ui;

import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.scene.control.Hyperlink;

/**
 * Der Hinweis in der Fußzeile auf offene Konflikte: ein Link "1 Konflikt offen" bzw. "N Konflikte offen", sichtbar nur,
 * solange Konflikte offen sind; ein Klick öffnet den Sammel-Dialog. Der Text kommt von {@link ConflictList#indicatorText}.
 * Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class ConflictIndicator extends Hyperlink {

	private static final Logger LOG = Logger.getLogger(ConflictIndicator.class.getName());

	private final IntSupplier openCount;

	/**
	 * @param openCount liefert die Zahl der offenen Konflikte (in der App {@code SyncService.openConflicts().size()})
	 * @param open      öffnet den Sammel-Dialog
	 */
	public ConflictIndicator(IntSupplier openCount, Runnable open) {
		this.openCount = openCount;
		getStyleClass().add("conflict-indicator");
		setOnAction(event -> {
			// ein Link, der immer wieder gilt, ist nie "besucht"
			setVisited(false);
			open.run();
		});
		refresh();
	}

	/** Liest die Zahl der offenen Konflikte neu und zeigt oder versteckt den Link. */
	public void refresh() {
		int count = 0;
		try {
			count = openCount.getAsInt();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Die offenen Konflikte ließen sich nicht zählen", e);
		}
		setText(ConflictList.indicatorText(count));
		setVisible(count > 0);
		setManaged(count > 0);
	}
}
