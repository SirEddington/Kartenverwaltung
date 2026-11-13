package de.eltviller_carneval_verein.karten.update;

import java.time.Instant;

/**
 * Der Versionseintrag einer Kasse in {@code updates/kassen/<Rechner-ID>.json} (KARV-132).
 *
 * @param fsname    {@code app.fsname} der App; leer, wenn nicht gesetzt
 * @param plattform nur zur Anzeige
 * @param gestartet wann die Kasse den Eintrag zuletzt schrieb
 */
public record StationEntry(String machineId, String name, String fsname, AppVersion version, String plattform,
		Instant gestartet) {
}
