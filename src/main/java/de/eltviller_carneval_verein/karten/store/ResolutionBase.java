package de.eltviller_carneval_verein.karten.store;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Die Basis einer verlorenen Strecke, die mit einer Auflösung beginnt (KARV-98): Gabeln zwei Auflösungen, ist die Basis
 * nach dem ersten Vorgänger eine Nutzer-Version, über der nur noch Auflösungen stehen; die Absichten darunter sähe die
 * Spur nicht. Abgesenkt wird auf den nächsten gemeinsamen Vorfahren der Nutzer-Grenzen beider Seiten. Rechnet nur auf
 * den Dateien der Einheit.
 * <ul>
 * <li>Die <b>Nutzer-Grenze</b> einer Version: von ihr über alle Vorgänger durch Versionen einer Auflösung hindurch bis
 * zu den ersten Nutzer-Versionen; eine Nutzer-Version ist ihre eigene Grenze, eine Auflösung ohne Vorgänger hat eine
 * leere.</li>
 * <li>Gemeinsame Vorfahren in der strengen Lesart: Versionen, die jede Grenz-Version über ihre Vorgänger enthält, ohne
 * sie selbst. Unter ihnen gilt die nächste, die in keiner anderen als Vorfahre steckt; bleiben mehrere (Kreuz-
 * Zusammenführung), wird nicht abgesenkt.</li>
 * </ul>
 */
final class ResolutionBase {

	/** Was aus der Basis wird. */
	enum Kind {
		/** Abgesenkt auf {@link Result#base()}. */
		LOWERED,
		/** Die heutige Basis bleibt: leere Grenze, unlesbare Version, kein oder kein eindeutiger gemeinsamer Vorfahre. */
		KEEP,
		/** Eine Version der Grenzen oder ihrer Vorfahren fehlt lokal; sie kann noch eintreffen. */
		MISSING
	}

	/**
	 * Das Ergebnis.
	 *
	 * @param kind was aus der Basis wird
	 * @param base die abgesenkte Basis; nur bei {@link Kind#LOWERED}
	 */
	record Result(Kind kind, VersionId base) {
	}

	private static final Result KEEP = new Result(Kind.KEEP, null);
	private static final Result MISSING = new Result(Kind.MISSING, null);

	private ResolutionBase() {
	}

	/**
	 * Die Basis für eine Gabel zwischen {@code mine} (Spitze der verlorenen Strecke) und {@code theirs} (Kopf der Kette).
	 * Eine unlesbare Version geht vor einer fehlenden: Auf sie zu warten, brächte nichts.
	 */
	static Result lower(UnitFiles files, Version mine, Version theirs) {
		Lookup lookup = new Lookup(files);
		Set<VersionId> frontier = new LinkedHashSet<>();
		Set<VersionId> mineFrontier = lookup.frontier(mine);
		Set<VersionId> theirsFrontier = lookup.frontier(theirs);
		frontier.addAll(mineFrontier);
		frontier.addAll(theirsFrontier);
		Set<VersionId> common = null;
		for (VersionId id : frontier) {
			Set<VersionId> ancestors = lookup.ancestors(id);
			if (common == null) {
				common = ancestors;
			} else {
				common.retainAll(ancestors);
			}
		}
		if (lookup.unreadable) {
			return KEEP;
		}
		if (lookup.missing) {
			return MISSING;
		}
		if (mineFrontier.isEmpty() || theirsFrontier.isEmpty() || common == null || common.isEmpty()) {
			return KEEP;
		}
		Set<VersionId> nearest = new HashSet<>(common);
		for (VersionId id : common) {
			nearest.removeAll(lookup.ancestors(id));
		}
		return nearest.size() == 1 ? new Result(Kind.LOWERED, nearest.iterator().next()) : KEEP;
	}

	/** Liest Versionen aus den Dateien und merkt, ob eine fehlte oder unlesbar war. */
	private static final class Lookup {
		private final UnitFiles files;
		boolean missing;
		boolean unreadable;

		Lookup(UnitFiles files) {
			this.files = files;
		}

		private Version get(VersionId id) {
			VersionFile file = files.versions().get(id);
			if (file == null) {
				missing = true;
				return null;
			}
			if (file.version() == null) {
				unreadable = true;
			}
			return file.version();
		}

		/** Die Nutzer-Grenze der Version (siehe Klasse). */
		Set<VersionId> frontier(Version version) {
			Set<VersionId> frontier = new LinkedHashSet<>();
			if (!version.marks().resolution()) {
				frontier.add(version.id());
				return frontier;
			}
			Deque<VersionId> open = new ArrayDeque<>(version.parents());
			Set<VersionId> seen = new HashSet<>();
			while (!open.isEmpty()) {
				VersionId id = open.removeFirst();
				if (!seen.add(id)) {
					continue;
				}
				Version parent = get(id);
				if (parent == null) {
					continue;
				}
				if (parent.marks().resolution()) {
					open.addAll(parent.parents());
				} else {
					frontier.add(id);
				}
			}
			return frontier;
		}

		/** Alle Vorfahren der Version über ihre Vorgänger, ohne sie selbst. */
		Set<VersionId> ancestors(VersionId start) {
			Set<VersionId> reached = new HashSet<>();
			Version version = get(start);
			Deque<VersionId> open = new ArrayDeque<>(version == null ? List.of() : version.parents());
			while (!open.isEmpty()) {
				VersionId id = open.removeFirst();
				if (!reached.add(id)) {
					continue;
				}
				Version parent = get(id);
				if (parent != null) {
					open.addAll(parent.parents());
				}
			}
			return reached;
		}
	}
}
