package de.eltviller_carneval_verein.karten.store;

/**
 * Eine Version, die ein anderer Rechner gerade verkauft: Ihre Änderungsliste steht schon im gemeinsamen Ordner, die
 * Anspruchsdatei noch nicht. Die Version ist bis dahin nicht gültig.
 *
 * @param unit        die Einheit
 * @param version     die Version, die gerade beansprucht wird
 * @param machineName Anzeigename des Rechners aus der Versionsdatei; {@code null}, wenn er dort fehlt oder die Datei
 *                    nicht lesbar ist
 */
public record PendingSighting(UnitKey unit, VersionId version, String machineName) {
}
