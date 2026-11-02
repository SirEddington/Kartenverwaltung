package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Sammelt die Ordner der Einheiten auf der Umzugsspur einer verlorenen Buchungs-Strecke (KARV-93). Betrachtet werden die
 * Buchung der Basis und jede Buchung, die die Strecke mit {@code movedFrom} hereingeholt hat. Ausgehend von der Einheit
 * selbst folgt die Spur in jedem gelesenen Ordner den Angaben {@code movedTo} und {@code movedFrom} jeder Version, die
 * eine betrachtete Buchung trägt oder sie ihrem ersten Vorgänger wegnimmt (ohne Versionen einer Auflösung). Nur Einheiten
 * derselben Vorstellung, jede einmal, höchstens {@link #LIMIT}. Kennt keine Inhalte außer der Buchungs-ID
 * ({@link ConflictInput#bookingIdOf}).
 */
public final class TrailCollector {

	/** Höchstens so viele Einheiten liest eine Auflösung für die Spur. */
	public static final int LIMIT = 16;

	private static final Logger LOGGER = Logger.getLogger(TrailCollector.class.getName());

	/** Der Lesezugriff auf die Ordner. */
	public interface Folders {

		/**
		 * Der Ordner der Einheit.
		 *
		 * @throws IOException wenn er sich nicht lesen lässt
		 */
		Folder folder(UnitKey unit) throws IOException;
	}

	/**
	 * Ein Ordner, wie der Speicher ihn kennt.
	 *
	 * @param chain    die gültige Kette, ältestes Glied zuerst
	 * @param versions alle lesbaren Versionen des Ordners (auch die der Kette)
	 * @param current  der aktuelle Stand für diesen Rechner, oder {@code null}
	 */
	public record Folder(List<Version> chain, Collection<Version> versions, VersionId current) {
	}

	private TrailCollector() {
	}

	/**
	 * Die Einheiten auf der Spur, jede mit Kette und Versionen daneben; die Einheit selbst immer.
	 *
	 * @param unit           die Einheit der verlorenen Strecke
	 * @param base           die Basis der Strecke, oder {@code null}
	 * @param stretch        die Versionen der Strecke, ältestes zuerst
	 * @param chainSinceBase die Glieder der Kette seit der Basis (sie stehen im Ordner der Einheit)
	 * @param folders        der Lesezugriff
	 * @throws IOException wenn sich ein Ordner nicht lesen lässt
	 */
	public static Map<UnitKey, TrailUnit> collect(UnitKey unit, Version base, List<Version> stretch,
			List<Version> chainSinceBase, Folders folders) throws IOException {
		Set<String> bookings = new HashSet<>();
		addBooking(bookings, base);
		Deque<UnitKey> open = new ArrayDeque<>();
		open.add(unit);
		for (Version version : stretch) {
			if (version.marks().movedFrom() != null) {
				addBooking(bookings, version);
				open.add(version.marks().movedFrom());
			}
		}
		Map<UnitKey, TrailUnit> trail = new LinkedHashMap<>();
		Set<UnitKey> seen = new HashSet<>();
		while (!open.isEmpty()) {
			UnitKey next = open.removeFirst();
			if (!seen.add(next) || !samePresentation(unit, next)) {
				continue;
			}
			if (trail.size() == LIMIT) {
				LOGGER.warning("Die Umzugsspur von " + unit.relativePath() + " ist länger als " + LIMIT
						+ " Einheiten; die Auflösung entscheidet mit den gelesenen.");
				break;
			}
			Folder folder = folders.folder(next);
			trail.put(next, unitOf(next, folder));
			Map<VersionId, Version> byId = new HashMap<>();
			folder.versions().forEach(version -> byId.put(version.id(), version));
			for (Version version : folder.versions()) {
				if (!version.marks().resolution() && concerns(version, byId, bookings)) {
					addUnit(open, version.marks().movedTo());
					addUnit(open, version.marks().movedFrom());
				}
			}
		}
		return trail;
	}

	private static void addBooking(Set<String> bookings, Version version) {
		String id = version == null || version.deleted() ? null : ConflictInput.bookingIdOf(version.payload());
		if (id != null) {
			bookings.add(id);
		}
	}

	private static void addUnit(Deque<UnitKey> open, UnitKey unit) {
		if (unit != null) {
			open.add(unit);
		}
	}

	private static boolean samePresentation(UnitKey unit, UnitKey other) {
		return other.type() == UnitType.BOOKING && unit.eventId().equals(other.eventId())
				&& unit.presentationId().equals(other.presentationId());
	}

	/** Ob die Version eine betrachtete Buchung trägt oder sie ihrem ersten Vorgänger (im selben Ordner) wegnimmt. */
	private static boolean concerns(Version version, Map<VersionId, Version> byId, Set<String> bookings) {
		if (!version.deleted() && bookings.contains(ConflictInput.bookingIdOf(version.payload()))) {
			return true;
		}
		Version parent = version.firstParent() == null ? null : byId.get(version.firstParent());
		return parent != null && !parent.deleted() && bookings.contains(ConflictInput.bookingIdOf(parent.payload()));
	}

	/** Kette, Versionen daneben (ohne die einer Auflösung) mit ihrem Stand, und der aktuelle Stand. */
	private static TrailUnit unitOf(UnitKey unit, Folder folder) {
		Set<VersionId> inChain = new HashSet<>();
		folder.chain().forEach(link -> inChain.add(link.id()));
		Map<VersionId, Version> byId = new HashMap<>();
		folder.versions().forEach(version -> byId.put(version.id(), version));
		Set<VersionId> absorbed = ancestors(folder.current(), byId);
		List<TrailUnit.Outside> outside = new ArrayList<>();
		for (Version version : folder.versions()) {
			if (!inChain.contains(version.id()) && !version.marks().resolution()) {
				outside.add(new TrailUnit.Outside(version, absorbed.contains(version.id())
						? ConflictInput.Standing.ABSORBED
						: ConflictInput.Standing.BESIDE));
			}
		}
		outside.sort((a, b) -> a.version().id().compareTo(b.version().id()));
		return new TrailUnit(unit, folder.chain(), outside, folder.current());
	}

	/** Die Vorgänger der Version im Ordner über alle Vorgänger, ohne sie selbst. */
	private static Set<VersionId> ancestors(VersionId start, Map<VersionId, Version> byId) {
		Set<VersionId> found = new HashSet<>();
		Deque<VersionId> open = new ArrayDeque<>();
		Version first = start == null ? null : byId.get(start);
		if (first != null) {
			open.addAll(first.parents());
		}
		while (!open.isEmpty()) {
			VersionId id = open.removeFirst();
			Version version = byId.get(id);
			if (found.add(id) && version != null) {
				open.addAll(version.parents());
			}
		}
		return found;
	}
}
