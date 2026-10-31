package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.scene.control.Hyperlink;
import javafx.scene.layout.Region;

/**
 * Der Hinweis in der Fußzeile zum Abgleich mit dem gemeinsamen Ordner (Spec "Fußzeile: SyncIndicator"): ein Link mit
 * <em>einem</em> Text nach Vorrang ({@link SyncHint}), unsichtbar und ohne Platz, solange nichts zu zeigen ist. Ein Klick
 * stellt bei Vorrang 1 die Frage "Mitnehmen" / "Beiseitelegen" noch einmal (und öffnet das Info-Fenster, wenn keine
 * Entscheidung wartet), sonst öffnet er das Info-Fenster.
 * <p>
 * Was der Hinweis wissen muss (seit wann {@code AUSSTEHEND}, ob ein Lauf zu Ende ging), steht in {@link SyncStatus}, das
 * es nur einmal gibt: Jede Ansicht hat eine eigene Fußzeile und damit einen eigenen Hinweis. Der Hinweis hat weder
 * Listener noch einen eigenen Takt: Aufgefrischt wird er von {@link #refresh()}, und das ruft die App bei jeder Rückmeldung
 * des Abgleichs und im 1-s-Takt der App auf, nur für den Hinweis der aktuellen Ansicht ({@link SyncStatus#track}). Eine
 * ersetzte Ansicht behält so nichts am Laufen. Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class SyncIndicator extends Hyperlink {

	private static final Logger LOG = Logger.getLogger(SyncIndicator.class.getName());

	private final SyncStatus status;

	/**
	 * @param status      der Stand des Abgleichs (einer je App)
	 * @param askDecision stellt die Frage beim Verbinden noch einmal (Vorrang 1)
	 * @param openInfo    öffnet das Info-Fenster
	 */
	public SyncIndicator(SyncStatus status, Runnable askDecision, Runnable openInfo) {
		this.status = status;
		getStyleClass().add("sync-indicator");
		// in voller Breite: kürzen soll die Fußzeile die Meldung daneben (StatusLabel)
		setMinWidth(Region.USE_PREF_SIZE);
		setOnAction(event -> {
			// ein Link, der immer wieder gilt, ist nie "besucht"
			setVisited(false);
			Optional<SyncHint> hint = current();
			if (hint.isEmpty()) {
				return;
			}
			if (hint.get().rank() == 1) {
				askDecision.run();
			} else {
				openInfo.run();
			}
		});
		// eine neue Ansicht zeigt gleich den Stand von jetzt
		sceneProperty().addListener((observable, oldScene, newScene) -> {
			if (newScene != null) {
				refresh();
			}
		});
		refresh();
	}

	/** Liest den Hinweis neu und zeigt oder versteckt den Link. */
	public void refresh() {
		Optional<SyncHint> hint = current();
		hint.ifPresent(h -> setText(h.text()));
		boolean alert = hint.isPresent() && hint.get().rank() <= 3;
		if (alert != getStyleClass().contains("sync-alert")) {
			if (alert) {
				getStyleClass().add("sync-alert");
			} else {
				getStyleClass().remove("sync-alert");
			}
		}
		setVisible(hint.isPresent());
		setManaged(hint.isPresent());
	}

	private Optional<SyncHint> current() {
		try {
			return status.hint();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Der Hinweis zum Abgleich ließ sich nicht ableiten", e);
			return Optional.empty();
		}
	}
}
