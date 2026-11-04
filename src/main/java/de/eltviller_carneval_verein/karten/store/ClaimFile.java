package de.eltviller_carneval_verein.karten.store;

/**
 * Eine im Ordner {@code claims/} einer Einheit gefundene Anspruchsdatei.
 *
 * @param name  Dateiname ohne Endung: {@code root} oder die ID des Vorgängers
 * @param bytes der Inhalt der Datei, unverändert
 * @param claim der gelesene Inhalt; {@code null}, wenn die Datei unlesbar ist oder ein höheres Format hat
 */
public record ClaimFile(String name, byte[] bytes, Claim claim) {
}
