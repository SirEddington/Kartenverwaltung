package de.eltviller_carneval_verein.karten.store;

/**
 * Eine im Einheitenordner gefundene Versionsdatei: die rohen Bytes (für Prüfsumme und Weitergabe) und, soweit lesbar,
 * der gelesene Inhalt.
 *
 * @param id            ID der Version (der Dateiname ohne Endung)
 * @param bytes         der Inhalt der Datei, unverändert
 * @param version       der gelesene Inhalt; {@code null}, wenn die Datei unlesbar ist, ein höheres Format hat oder ihre
 *                      ID nicht zum Dateinamen passt. Bei {@link Reason#CONTENT} gelesen, aber als inhaltlich zu neu
 *                      markiert ({@link Version#newerContent()}).
 * @param unknownFormat das unbekannte Format, wenn die Datei von einem neueren Programm stammt; sonst {@code null}
 * @param reason        warum die Datei ganz, gesperrt oder unbrauchbar ist
 */
public record VersionFile(VersionId id, byte[] bytes, Version version, Integer unknownFormat, Reason reason) {

	/** Der Zustand einer Versionsdatei beim Lesen. */
	public enum Reason {

		/** Gelesen, die ID passt, der Inhalt ist verarbeitbar. */
		OK,

		/** Unlesbar (beschädigt) oder die ID passt nicht zum Dateinamen. */
		DAMAGED,

		/** Die Datei hat ein höheres Dateiformat ({@link VersionFile#unknownFormat()}). */
		FORMAT,

		/** Gelesen, aber der Inhalt stammt von einem neueren Programm ({@link Version#newerContent()}). */
		CONTENT
	}
}
