package de.eltviller_carneval_verein.karten.store;

/**
 * Eine im Einheitenordner gefundene Versionsdatei: die rohen Bytes (für Prüfsumme und Weitergabe) und, soweit lesbar,
 * der gelesene Inhalt.
 *
 * @param id            ID der Version (der Dateiname ohne Endung)
 * @param bytes         der Inhalt der Datei, unverändert
 * @param version       der gelesene Inhalt; {@code null}, wenn die Datei unlesbar ist, ein höheres Format hat oder ihre
 *                      ID nicht zum Dateinamen passt
 * @param unknownFormat das unbekannte Format, wenn die Datei von einem neueren Programm stammt; sonst {@code null}
 */
public record VersionFile(VersionId id, byte[] bytes, Version version, Integer unknownFormat) {
}
