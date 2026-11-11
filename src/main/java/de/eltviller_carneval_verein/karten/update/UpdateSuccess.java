package de.eltviller_carneval_verein.karten.update;

import java.time.Instant;

/**
 * Das Ergebnis der letzten erfolgreichen Update-Prüfung (KARV-132): was die Quelle meldete und wann, egal ob es neuer
 * war als die installierte Version. Bleibt über spätere gescheiterte Prüfungen hinweg bekannt.
 *
 * @param info     was {@code metadata.properties} der Quelle sagte
 * @param geprueft wann die Prüfung zu Ende ging
 */
public record UpdateSuccess(UpdateInfo info, Instant geprueft) {
}
