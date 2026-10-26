package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

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
	 * @throws IOException               bei einer Störung; die Nummer bleibt dann unverbraucht
	 */
	public synchronized long append(List<FeedEntry> entries) throws IOException {
		if (next == 0) {
			next = highestExisting() + 1;
		}
		long seq = next;
		Feed feed = new Feed(StoreCodec.FORMAT, machineId, seq, Instant.now(clock), List.copyOf(entries));
		if (!shared.createRelative(folder() + "/" + seq + ".json", StoreCodec.write(feed))) {
			throw new DuplicateMachineException(machineId, seq);
		}
		next = seq + 1;
		return seq;
	}

	private String folder() {
		return "feeds/" + machineId;
	}

	/** Die höchste Nummer unter den Dateien {@code <Nr>.json} des Ordners; 0, wenn es keine gibt. Andere Namen zählen nicht. */
	private long highestExisting() throws IOException {
		long highest = 0;
		for (String name : shared.listRelative(folder())) {
			if (!name.endsWith(".json")) {
				continue;
			}
			String stem = name.substring(0, name.length() - ".json".length());
			try {
				long number = Long.parseLong(stem);
				// nur die kanonische Schreibweise: "007" oder "+7" zählen nicht
				if (number > 0 && stem.equals(Long.toString(number))) {
					highest = Math.max(highest, number);
				}
			} catch (NumberFormatException e) {
				// kein Listenname
			}
		}
		return highest;
	}
}
