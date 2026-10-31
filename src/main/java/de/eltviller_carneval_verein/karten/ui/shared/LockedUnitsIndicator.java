package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.scene.control.Hyperlink;

/**
 * Der Link in der Fußzeile auf gesperrte Einträge (Spec "Gesperrte Einheiten"): "1 gesperrter Eintrag" bzw. "n gesperrte
 * Einträge", neben dem Hinweis auf Konflikte; unsichtbar und ohne Platz, solange es keine gibt. Ein Klick öffnet den Dialog
 * {@link LockedUnitsDialog}. Der Link hat weder Listener noch einen Takt: Aufgefrischt wird er von {@link #refresh()}, und
 * das ruft die App, wenn sich die gesperrten Einträge ändern, nur für den Link der aktuellen Ansicht. Eine ersetzte
 * Ansicht behält so nichts am Laufen. Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class LockedUnitsIndicator extends Hyperlink {

	private static final Logger LOG = Logger.getLogger(LockedUnitsIndicator.class.getName());

	private final IntSupplier count;

	/**
	 * @param count liefert die Zahl der gesperrten Einträge ({@link LockedUnits#count()})
	 * @param open  öffnet den Dialog
	 */
	public LockedUnitsIndicator(IntSupplier count, Runnable open) {
		this.count = count;
		getStyleClass().add("locked-indicator");
		setOnAction(event -> {
			// ein Link, der immer wieder gilt, ist nie "besucht"
			setVisited(false);
			open.run();
		});
		refresh();
	}

	/** Liest die Zahl neu und zeigt oder versteckt den Link. */
	public void refresh() {
		int n = 0;
		try {
			n = count.getAsInt();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Die gesperrten Einträge ließen sich nicht zählen", e);
		}
		if (n > 0) {
			setText(SharedFolderTexts.lockedLink(n));
		}
		setVisible(n > 0);
		setManaged(n > 0);
	}
}
