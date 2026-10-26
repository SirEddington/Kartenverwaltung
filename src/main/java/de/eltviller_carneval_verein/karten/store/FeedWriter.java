package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Schreibt die Änderungslisten eines Rechners in den gemeinsamen Ordner: {@code feeds/<Rechner-ID>/<Nr>.json},
 * fortlaufend ab 1, je Stapel eine Datei, angelegt mit "nur wenn nicht vorhanden".
 * <p>
 * Die nächste Nummer ist beim ersten Schreiben die höchste vorhandene plus eins und wird danach hochgezählt. Ist sie
 * trotzdem belegt, schreibt ein anderer Rechner mit derselben Kennung in diesen Ordner.
 */
public final class FeedWriter {

	private final VersionStore shared;
	private final String machineId;
	private final Clock clock;
	private long next;
	/** Nummer und Einträge des letzten Versuchs, der mit einer Störung endete; 0, wenn es keinen gibt. */
	private long uncertainSeq;
	private List<FeedEntry> uncertainEntries;

	/**
	 * @param shared    der gemeinsame Bestand
	 * @param machineId die Kennung dieses Rechners
	 * @param clock     die Uhr für den Zeitpunkt der Liste
	 */
	public FeedWriter(VersionStore shared, String machineId, Clock clock) {
		this.shared = shared;
		this.machineId = machineId;
		this.clock = clock;
	}

	/**
	 * Schreibt eine neue Listendatei mit den Einträgen.
	 *
	 * @return die Nummer der geschriebenen Liste
	 * @throws DuplicateMachineException wenn die Nummer schon belegt ist
	 * @throws IOException               bei einer Störung; die Nummer bleibt dann unverbraucht. Wurde die Datei trotz der
	 *                                   Störung schon angelegt (verlorene Antwort), erkennt der nächste Aufruf mit
	 *                                   denselben Einträgen sie als die eigene und zählt weiter.
	 */
	public synchronized long append(List<FeedEntry> entries) throws IOException {
		if (next == 0) {
			next = Feeds.highestFeedNumber(shared, machineId) + 1;
		}
		long seq = next;
		List<FeedEntry> copy = List.copyOf(entries);
		Feed feed = new Feed(StoreCodec.FORMAT, machineId, seq, Instant.now(clock), copy);
		String rel = Feeds.feedPath(machineId, seq);
		boolean created;
		try {
			created = shared.createRelative(rel, StoreCodec.write(feed));
		} catch (IOException e) {
			// Ob die Datei angelegt wurde, ist unklar: Nummer und Einträge für den nächsten Versuch merken
			uncertainSeq = seq;
			uncertainEntries = copy;
			throw e;
		}
		if (!created) {
			boolean ownEarlierAttempt = uncertainSeq == seq && copy.equals(uncertainEntries);
			// scheitert das Lesen, bleibt der unklare Versuch für den nächsten Aufruf gemerkt
			if (!ownEarlierAttempt || !isOwnFile(rel, seq, copy)) {
				uncertainSeq = 0;
				uncertainEntries = null;
				throw new DuplicateMachineException(machineId, seq);
			}
		}
		uncertainSeq = 0;
		uncertainEntries = null;
		next = seq + 1;
		return seq;
	}

	/**
	 * Ob die vorhandene Datei von diesem Schreiber stammt: gleicher Rechner, gleiche Nummer, gleiche Einträge (der
	 * Zeitpunkt zählt nicht). Eine unlesbare Datei ist nicht die eigene.
	 *
	 * @throws IOException wenn das Lesen selbst scheitert
	 */
	private boolean isOwnFile(String rel, long seq, List<FeedEntry> entries) throws IOException {
		Optional<byte[]> bytes = shared.readRelative(rel);
		if (bytes.isEmpty()) {
			return false;
		}
		try {
			Feed existing = StoreCodec.readFeed(bytes.get());
			return existing.machine().equals(machineId) && existing.seq() == seq && existing.entries().equals(entries);
		} catch (IOException e) {
			return false;
		}
	}

}
