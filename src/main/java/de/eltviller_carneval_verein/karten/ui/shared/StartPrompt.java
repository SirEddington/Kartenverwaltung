package de.eltviller_carneval_verein.karten.ui.shared;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Wann die Rückfrage „Version X liegt im gemeinsamen Ordner“ beim Programmstart kommt (KARV-132), ohne JavaFX: einmal je
 * Lauf, am ersten Zeitpunkt innerhalb von 2 Minuten nach dem ersten Erscheinen des Hauptmenüs, an dem ein Update aus dem
 * Ordner anzubieten ist, die erste Update-Prüfung beendet ist, kein blockierendes Fenster offen ist und kein Update- oder
 * Verbindungsablauf läuft. Danach entfällt sie. Nur auf dem FX-Thread benutzen.
 */
public final class StartPrompt {

	/** So lange nach dem ersten Hauptmenü darf die Rückfrage kommen. */
	public static final Duration WINDOW = Duration.ofMinutes(2);

	private final Clock clock;
	private Instant menuShownAt;
	private boolean asked;

	public StartPrompt(Clock clock) {
		this.clock = clock;
	}

	/** Das Hauptmenü ist sichtbar; nur das erste Mal zählt. */
	public void menuShown() {
		if (menuShownAt == null) {
			menuShownAt = clock.instant();
		}
	}

	/** Ob die Rückfrage jetzt kommen soll. */
	public boolean due(boolean offerPresent, boolean firstCheckDone, boolean noBlockingWindow, boolean flowIdle) {
		return menuShownAt != null && !expired() && offerPresent && firstCheckDone && noBlockingWindow && flowIdle;
	}

	/** Ob die Rückfrage für diesen Lauf erledigt ist (gestellt oder das Fenster ist vorbei). */
	public boolean expired() {
		return asked || menuShownAt != null && clock.instant().isAfter(menuShownAt.plus(WINDOW));
	}

	/** Die Rückfrage wurde gestellt. */
	public void asked() {
		asked = true;
	}
}
