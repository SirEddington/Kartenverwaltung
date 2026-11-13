package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.update.FolderOffer;

/**
 * Ein Schritt des 1-s-Takts für die Rückfrage beim Start (KARV-132), ohne JavaFX. Die Rückfrage selbst läuft über
 * {@code later} (in der App {@code Platform::runLater}): Der Takt ist eine {@code Timeline}, und in ihrem
 * Animationsschritt verbietet JavaFX modale Dialoge ({@code showAndWait}, Fund aus dem Handtest).
 */
public final class StartPromptTicker {

	/** Was für die Rückfrage gerade gilt. */
	public interface Conditions {
		Optional<FolderOffer> offer();

		boolean firstCheckDone();

		boolean noBlockingWindow();

		boolean flowIdle();
	}

	private final StartPrompt prompt;
	private final Conditions conditions;
	private final Consumer<FolderOffer> ask;
	private final Executor later;

	public StartPromptTicker(StartPrompt prompt, Conditions conditions, Consumer<FolderOffer> ask, Executor later) {
		this.prompt = prompt;
		this.conditions = conditions;
		this.ask = ask;
		this.later = later;
	}

	/**
	 * Prüft einmal und gibt die Rückfrage gegebenenfalls an {@code later} weiter.
	 *
	 * @return {@code true}, wenn der Takt aufhören kann (gefragt oder Zeitfenster vorbei)
	 */
	public boolean tick() {
		if (prompt.expired()) {
			return true;
		}
		Optional<FolderOffer> offer = conditions.offer();
		if (prompt.due(offer.isPresent(), conditions.firstCheckDone(), conditions.noBlockingWindow(),
				conditions.flowIdle())) {
			prompt.asked();
			FolderOffer o = offer.get();
			later.execute(() -> ask.accept(o));
			return true;
		}
		return false;
	}
}
