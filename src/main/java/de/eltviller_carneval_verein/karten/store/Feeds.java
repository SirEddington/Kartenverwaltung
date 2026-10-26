package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;

/**
 * Was Schreiber und Leser der Änderungslisten gemeinsam festlegen: wo eine Liste liegt und was als ihre Nummer zählt.
 */
final class Feeds {

	private static final String FOLDER = "feeds";
	private static final String JSON = ".json";

	private Feeds() {
	}

	/** Der Ordner der Listen eines Rechners, relativ zum Datenordner: {@code feeds/<ID>}. */
	static String feedFolder(String machineId) {
		return FOLDER + "/" + machineId;
	}

	/** Der Pfad einer Listendatei, relativ zum Datenordner: {@code feeds/<ID>/<Nr>.json}. */
	static String feedPath(String machineId, long seq) {
		return feedFolder(machineId) + "/" + seq + JSON;
	}

	/**
	 * Die höchste Nummer unter den Dateien {@code <Nr>.json} im Ordner des Rechners; 0, wenn es keine gibt. Andere Namen
	 * zählen nicht, ebenso wenig Schreibweisen wie {@code 007} oder {@code +7}.
	 */
	static long highestFeedNumber(VersionStore shared, String machineId) throws IOException {
		long highest = 0;
		for (String name : shared.listRelative(feedFolder(machineId))) {
			if (!name.endsWith(JSON)) {
				continue;
			}
			String stem = name.substring(0, name.length() - JSON.length());
			try {
				long number = Long.parseLong(stem);
				// nur die kanonische Schreibweise
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
