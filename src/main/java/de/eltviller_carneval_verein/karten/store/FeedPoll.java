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
 * @param contested      Plätze, an denen ein fremder Eintrag auf einen lokal schon vorhandenen Anspruch trifft, der
 *                       davon abweicht: Der lokale Anspruch ist der eigene und nennt eine andere Version als der Eintrag,
 *                       oder der gemeinsame Anspruch nennt eine andere Version als der lokale (späte Niederlage nach
 *                       Ablauf der Beobachtung). Der Aufrufer vergleicht und verdrängt.
 * @param damaged        Einheiten, deren Versionsdatei beim Kopieren nicht zum Prüfwert des gemeinsamen Anspruchs passte
 *                       (die gemeinsame Kopie ist beschädigt)
 */
public record FeedPoll(Set<UnitKey> changed, List<PendingSighting> pending, boolean fullSyncNeeded,
		Set<Contested> contested, Set<UnitKey> damaged) {

	/**
	 * Ein umstrittener Platz.
	 *
	 * @param claimName {@code root} oder die ID des Vorgängers
	 */
	public record Contested(UnitKey unit, String claimName) {
	}

	/** Übernimmt unveränderliche Kopien. */
	public FeedPoll {
		changed = Set.copyOf(changed);
		pending = List.copyOf(pending);
		contested = Set.copyOf(contested);
		damaged = Set.copyOf(damaged);
	}
}
