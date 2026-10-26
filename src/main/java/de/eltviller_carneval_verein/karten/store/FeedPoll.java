package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Set;

/**
 * Das Ergebnis eines Durchlaufs von {@link FeedReader#poll()}.
 *
 * @param changed        Einheiten, die einen neuen Anspruch in den lokalen Bestand bekommen haben (ihr gültiger Stand
 *                       kann sich geändert haben)
 * @param pending        Versionen, deren Anspruchsdatei noch fehlt und die jetzt beobachtet werden
 * @param fullSyncNeeded ob ein Vollabgleich nötig ist: eine Liste war unbrauchbar, ein Eintrag ungültig oder eine
 *                       genannte Versionsdatei kam auch nach der Wartezeit nicht
 */
public record FeedPoll(Set<UnitKey> changed, List<PendingSighting> pending, boolean fullSyncNeeded) {

	/** Übernimmt unveränderliche Kopien. */
	public FeedPoll {
		changed = Set.copyOf(changed);
		pending = List.copyOf(pending);
	}
}
