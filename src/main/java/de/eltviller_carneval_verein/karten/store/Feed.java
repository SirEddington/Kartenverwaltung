package de.eltviller_carneval_verein.karten.store;

import java.time.Instant;
import java.util.List;

/**
 * Der Inhalt einer Listendatei: die Versionen, die ein Rechner geschrieben hat, damit die anderen Rechner sie ohne
 * Durchsuchen des ganzen Ordners finden.
 *
 * @param format  Dateiformat, derzeit {@link StoreCodec#FORMAT}
 * @param machine ID des Rechners, dem die Liste gehört
 * @param seq     laufende Nummer der Liste dieses Rechners
 * @param at      Zeitpunkt des Schreibens
 * @param entries die Einträge
 */
public record Feed(int format, String machine, long seq, Instant at, List<FeedEntry> entries) {
}
