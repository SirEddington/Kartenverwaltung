package de.eltviller_carneval_verein.karten.update;

import java.util.List;
import java.util.Optional;

/**
 * Was der gemeinsame Ordner über Versionen und Updates sagt (KARV-132), für Fußzeile und „Info und Updates“.
 *
 * @param stations        alle lesbaren Kasseneinträge
 * @param newerStation    die Kasse mit der höchsten Version über der eigenen (gleicher {@code fsname})
 * @param offer           ein Update aus dem Ordner, das angeboten wird (nur nach {@link FolderDecision.Result#FOLDER})
 * @param withdrawn       ein Installer, der neuer ist als die Update-Quelle (vermutlich zurückgezogen)
 * @param reinstallNeeded ein neuerer Installer einer anderen Paketfamilie
 * @param loading         die Version, die diese Kasse gerade in den Ordner lädt
 */
public record FolderState(List<StationEntry> stations, Optional<StationEntry> newerStation, Optional<FolderOffer> offer,
		Optional<AppVersion> withdrawn, Optional<AppVersion> reinstallNeeded, Optional<AppVersion> loading) {

	public FolderState {
		stations = List.copyOf(stations);
	}

	/** Kein gemeinsamer Ordner oder nichts bekannt. */
	public static FolderState empty() {
		return new FolderState(List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty());
	}
}
