package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Hyperlink;
import javafx.util.Duration;

/**
 * Der Hinweis in der Fußzeile zum Abgleich mit dem gemeinsamen Ordner (Spec "Fußzeile: SyncIndicator"): ein Link mit
 * <em>einem</em> Text nach Vorrang ({@link SyncHint}), unsichtbar und ohne Platz, solange nichts zu zeigen ist. Ein Klick
 * stellt bei Vorrang 1 die Frage "Mitnehmen" / "Beiseitelegen" noch einmal, sonst öffnet er das Info-Fenster.
 * <p>
 * Was der Hinweis wissen muss (seit wann {@code AUSSTEHEND}, ob ein Lauf zu Ende ging), steht in {@link SyncStatus}, das
 * es nur einmal gibt: Jede Ansicht hat eine eigene Fußzeile und damit einen eigenen Hinweis. Aufgefrischt wird er von
 * {@link #refresh()} (die App ruft es bei jeder Rückmeldung des Abgleichs) und, solange er in einem Fenster steht, im
 * 1-s-Takt: für die 30 s von {@code AUSSTEHEND} und den Fortschritt in Prozent. Ohne Fenster läuft der Takt nicht, so bleibt
 * von einer alten Ansicht nichts zurück. Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class SyncIndicator extends Hyperlink {

	private static final Logger LOG = Logger.getLogger(SyncIndicator.class.getName());

	private final SyncStatus status;
	private final Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh()));

	/**
	 * @param status       der Stand des Abgleichs (einer je App)
	 * @param askDecision  stellt die Frage beim Verbinden noch einmal (Vorrang 1)
	 * @param openInfo     öffnet das Info-Fenster (ab Vorrang 2)
	 */
	public SyncIndicator(SyncStatus status, Runnable askDecision, Runnable openInfo) {
		this.status = status;
		getStyleClass().add("sync-indicator");
		ticker.setCycleCount(Animation.INDEFINITE);
		setOnAction(event -> {
			// ein Link, der immer wieder gilt, ist nie "besucht"
			setVisited(false);
			Optional<SyncHint> hint = current();
			if (hint.isEmpty() || !hint.get().clickable()) {
				return;
			}
			if (hint.get().rank() == 1) {
				askDecision.run();
			} else {
				openInfo.run();
			}
		});
		sceneProperty().addListener((observable, oldScene, newScene) -> {
			if (newScene == null) {
				ticker.stop();
			} else {
				refresh();
				ticker.play();
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
