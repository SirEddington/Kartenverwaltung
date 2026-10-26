package de.eltviller_carneval_verein.karten.store;

/**
 * Ein Eintrag einer Listendatei: eine Version, die ein Rechner geschrieben hat.
 *
 * @param unit    die Einheit, als relativer Pfad nach {@link UnitKey}
 * @param version die geschriebene Version
 * @param claim   Name der Anspruchsdatei ohne Endung ({@code "root"} oder die ID des ersten Vorgängers)
 */
public record FeedEntry(String unit, VersionId version, String claim) {
}
