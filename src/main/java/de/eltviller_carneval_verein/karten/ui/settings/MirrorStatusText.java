package de.eltviller_carneval_verein.karten.ui.settings;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import de.eltviller_carneval_verein.karten.mirror.MirrorState;

/** Der Text, mit dem die Einstellungsansicht den Zustand der Spiegelung anzeigt. */
final class MirrorStatusText {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private MirrorStatusText() {
	}

	/**
	 * @param state    der Zustand der Spiegelung
	 * @param lastSync Zeitpunkt des letzten erfolgreichen Abgleichs (darf {@code null} sein); nur bei
	 *                 {@link MirrorState#AKTUELL} gezeigt
	 * @param zone     Zeitzone, in der die Uhrzeit angezeigt wird
	 * @return der Anzeigetext
	 */
	static String of(MirrorState state, Instant lastSync, ZoneId zone) {
		return switch (state) {
		case AUS -> "Aus";
		case ABGLEICH -> "Abgleich läuft …";
		case AKTUELL -> lastSync == null ? "Aktuell" : "Aktuell (zuletzt " + TIME.format(lastSync.atZone(zone)) + ")";
		case AUSSTEHEND -> "Änderungen ausstehend";
		case NICHT_ERREICHBAR -> "Ordner nicht erreichbar";
		case FEHLER -> "Fehler";
		};
	}
}
