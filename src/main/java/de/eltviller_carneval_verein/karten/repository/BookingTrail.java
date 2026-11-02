package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.TrailUnit;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

/**
 * Was eine Seite mit einer Buchung getan hat, gelesen aus der Umzugsspur (KARV-93): Ausgehend von der Quelle und der
 * Basis dort zählen nur Nutzer-Versionen (ohne {@code resolution}), die sich lesen lassen. Eine Seite sind die Versionen
 * dieses Rechners ({@link ConflictInput#ownMachineId()}) bzw. die aller anderen.
 *
 * @param kind    was die Seite getan hat
 * @param removal die Version, die die Buchung an der Quelle wegnahm; {@code null} bei {@link Kind#UNTOUCHED} und
 *                {@link Kind#KEPT}
 * @param last    {@link Kind#KEPT}: die jüngste Version der Seite an der Quelle; {@link Kind#CANCELLED}: die Version, die
 *                die Buchung ohne Umzug wegnahm; {@link Kind#ARRIVED}: die jüngste geltende Version mit der Buchung am Ende
 *                der Spur; {@link Kind#FAILED}: die jüngste Version der Seite mit der Buchung am gescheiterten Ziel, oder
 *                {@code null}, wenn das Ziel nicht auf der Spur liegt
 * @param end     die Einheit, auf die sich {@code last} bezieht; {@code null} bei {@link Kind#UNTOUCHED}
 */
record BookingTrail(Kind kind, Version removal, Version last, UnitKey end) {

	/** Was eine Seite mit der Buchung getan hat. */
	enum Kind {
		/** Keine Version an der Quelle seit der Basis. */
		UNTOUCHED,
		/** Versionen an der Quelle, keine nimmt die Buchung weg. */
		KEPT,
		/** Die Buchung fiel ohne Umzug weg, an der Quelle oder später auf der Spur. */
		CANCELLED,
		/** Die Buchung zog um und lebt am Ende der Spur. */
		ARRIVED,
		/** Die Buchung zog um, aber am Ziel gilt eine andere Buchung. */
		FAILED
	}

	/** Höchstens so viele Schritte folgt die Spur (Kreise enden spätestens hier). */
	private static final int MAX_STEPS = 32;

	private static final BookingTrail UNTOUCHED = new BookingTrail(Kind.UNTOUCHED, null, null, null);

	/**
	 * Die Absicht einer Seite.
	 *
	 * @param input     die Eingabe mit der Spur
	 * @param bookingId die Buchung
	 * @param source    die Quelle
	 * @param baseId    die Basis an der Quelle; {@code null}: alle Versionen der Seite dort zählen
	 * @param own       ob es die Seite dieses Rechners ist
	 */
	static BookingTrail of(ConflictInput input, String bookingId, UnitKey source, VersionId baseId, boolean own) {
		TrailUnit unit = input.trail().get(source);
		if (unit == null) {
			return UNTOUCHED;
		}
		List<Version> side = sideVersions(input, unit, baseId, own);
		if (side.isEmpty()) {
			return UNTOUCHED;
		}
		for (Version version : side) {
			if (!carries(version, bookingId)) {
				return follow(input, bookingId, source, version, version);
			}
		}
		return new BookingTrail(Kind.KEPT, null, side.get(side.size() - 1), source);
	}

	/**
	 * Die Basis an der Quelle eines Umzugs, der mit {@code arrival} am Ziel {@link ConflictInput#unit()} ankam: der erste
	 * Vorgänger der Version an der Quelle aus demselben Speichervorgang mit {@code movedTo} = Ziel; {@code null}, wenn es
	 * keine gibt.
	 */
	static VersionId sourceBase(ConflictInput input, UnitKey source, Version arrival) {
		TrailUnit unit = input.trail().get(source);
		if (unit == null) {
			return null;
		}
		for (Version version : all(unit)) {
			if (input.unit().equals(version.marks().movedTo()) && arrival.saveId() != null
					&& arrival.saveId().equals(version.saveId())) {
				return version.firstParent();
			}
		}
		return null;
	}

	/**
	 * Ob an der Einheit die jüngste geltende Nutzer-Version seit der Basis von diesem Rechner stammt; {@code null}, wenn
	 * dort keine gilt.
	 */
	static Boolean ownWinsAt(ConflictInput input, UnitKey unitKey, VersionId baseId) {
		TrailUnit unit = input.trail().get(unitKey);
		if (unit == null) {
			return null;
		}
		Set<VersionId> after = baseId == null ? null : descendants(unit, baseId);
		Boolean own = null;
		for (Version version : unit.standing()) {
			if (!version.marks().resolution() && (after == null || after.contains(version.id()))) {
				own = input.ownMachineId().equals(version.machine());
			}
		}
		return own;
	}

	/**
	 * Folgt einem Wegfall: ohne {@code movedTo} ein Storno, sonst zum Ziel. Dort ist der Umzug angekommen, wenn eine
	 * geltende Nutzer-Version mit {@code movedFrom} = Herkunft die Buchung trägt (gleich von welcher Seite); dann geht es
	 * mit den späteren geltenden Versionen dort weiter.
	 */
	private static BookingTrail follow(ConflictInput input, String bookingId, UnitKey from, Version leaving,
			Version removal) {
		UnitKey at = from;
		Version gone = leaving;
		for (int step = 0; step < MAX_STEPS; step++) {
			UnitKey target = gone.marks().movedTo();
			if (target == null) {
				return new BookingTrail(Kind.CANCELLED, removal, gone, at);
			}
			TrailUnit unit = input.trail().get(target);
			if (unit == null) {
				return new BookingTrail(Kind.FAILED, removal, null, target);
			}
			List<Version> standing = userVersions(unit.standing(), target);
			int arrival = -1;
			for (int i = 0; i < standing.size(); i++) {
				Version version = standing.get(i);
				if (at.equals(version.marks().movedFrom()) && carries(version, bookingId)) {
					arrival = i;
				}
			}
			if (arrival < 0) {
				return new BookingTrail(Kind.FAILED, removal, latestCarrying(input, unit, bookingId, removal.machine()),
						target);
			}
			Version next = null;
			Version last = standing.get(arrival);
			for (Version version : standing.subList(arrival + 1, standing.size())) {
				if (!carries(version, bookingId)) {
					next = version;
					break;
				}
				last = version;
			}
			if (next == null) {
				return new BookingTrail(Kind.ARRIVED, removal, last, target);
			}
			at = target;
			gone = next;
		}
		return new BookingTrail(Kind.ARRIVED, removal, null, at);
	}

	/** Die jüngste lesbare Nutzer-Version des Rechners mit der Buchung in der Einheit (Kette oder daneben). */
	private static Version latestCarrying(ConflictInput input, TrailUnit unit, String bookingId, String machine) {
		Version found = null;
		for (Version version : userVersions(all(unit), unit.unit())) {
			if (machine.equals(version.machine()) && carries(version, bookingId)
					&& (found == null || version.id().compareTo(found.id()) > 0)) {
				found = version;
			}
		}
		return found;
	}

	/** Die Nutzer-Versionen einer Seite an der Einheit seit der Basis, ältestes zuerst. */
	private static List<Version> sideVersions(ConflictInput input, TrailUnit unit, VersionId baseId, boolean own) {
		Set<VersionId> after = baseId == null ? null : descendants(unit, baseId);
		List<Version> side = new ArrayList<>();
		for (Version version : userVersions(all(unit), unit.unit())) {
			boolean mine = input.ownMachineId().equals(version.machine());
			if (mine == own && (after == null || after.contains(version.id()))) {
				side.add(version);
			}
		}
		side.sort(Comparator.comparing(Version::id));
		return side;
	}

	/** Kette und Versionen daneben. */
	private static List<Version> all(TrailUnit unit) {
		List<Version> all = new ArrayList<>(unit.chain());
		unit.outside().forEach(other -> all.add(other.version()));
		return all;
	}

	/** Ohne Versionen einer Auflösung und ohne unlesbare (eine neuere App, ein Inhalt, der nicht zur Einheit passt). */
	private static List<Version> userVersions(List<Version> versions, UnitKey unit) {
		return versions.stream().filter(version -> !version.marks().resolution() && readable(version, unit)).toList();
	}

	private static boolean readable(Version version, UnitKey unit) {
		return version.deleted()
				|| UnitMapper.isReadable(new UnitSnapshot(unit, version.id(), Status.OK, false, version.payload()));
	}

	/** Die Versionen der Einheit, die die Basis über ihre Vorgänger enthalten, ohne sie selbst. */
	private static Set<VersionId> descendants(TrailUnit unit, VersionId baseId) {
		Map<VersionId, Version> byId = new HashMap<>();
		all(unit).forEach(version -> byId.put(version.id(), version));
		Set<VersionId> reached = new HashSet<>(Set.of(baseId));
		boolean grown = true;
		while (grown) {
			grown = false;
			for (Version version : byId.values()) {
				if (!reached.contains(version.id()) && version.parents().stream().anyMatch(reached::contains)) {
					reached.add(version.id());
					grown = true;
				}
			}
		}
		reached.remove(baseId);
		return reached;
	}

	private static boolean carries(Version version, String bookingId) {
		return !version.deleted() && bookingId.equals(ConflictInput.bookingIdOf(version.payload()));
	}
}
