package de.eltviller_carneval_verein.karten.store;

import java.time.Instant;

/**
 * Der Inhalt einer Anspruchsdatei: Sie hält fest, welche Version den Platz hinter einem Vorgänger belegt hat.
 *
 * @param version die beanspruchende Version
 * @param sha256  SHA-256 der Versionsdatei in Hex-Schreibweise
 * @param machine ID des Rechners, der den Anspruch geschrieben hat
 * @param at      Zeitpunkt des Anspruchs
 */
public record Claim(VersionId version, String sha256, String machine, Instant at) {
}
