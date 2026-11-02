package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
 * @param mine    die jüngste eigene Version der Seite mit der Buchung am Ende, auch wenn sie dort nicht gilt (die weichende
 *                Fassung zum Wiederherstellen); sonst wie {@code last}
 */
record BookingTrail(Kind kind, Version removal, Version last, UnitKey end, Version mine) {

	/** Eine Absicht, deren eigene Fassung {@code last} ist. */
	BookingTrail(Kind kind, Version removal, Version last, UnitKey end) {
		this(kind, removal, last, end, last);
	}

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
		if (own) {
			return of(input, bookingId, source, side);
		}
		// mehrere andere Rechner: je Rechner eine Absicht; ein Storno gewinnt gegen alles, sonst gilt die der Kette
		Set<VersionId> inChain = new HashSet<>();
		unit.chain().forEach(link -> inChain.add(link.id()));
		Map<String, List<Version>> byMachine = new TreeMap<>();
		side.forEach(version -> byMachine.computeIfAbsent(version.machine(), key -> new ArrayList<>()).add(version));
		BookingTrail chosen = UNTOUCHED;
		boolean chosenInChain = false;
		for (List<Version> versions : byMachine.values()) {
			BookingTrail trail = of(input, bookingId, source, versions);
			boolean standsInChain = versions.stream().anyMatch(version -> inChain.contains(version.id()));
			if (trail.kind() == Kind.CANCELLED) {
				return trail;
			}
			if (chosen.kind() == Kind.UNTOUCHED || (standsInChain && !chosenInChain)) {
				chosen = trail;
				chosenInChain = standsInChain;
			}
		}
		return chosen;
	}

	/** Die Absicht aus den Versionen einer Seite an der Quelle, ältestes zuerst. */
	private static BookingTrail of(ConflictInput input, String bookingId, UnitKey source, List<Version> side) {
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
	 * Die Basis an der Quelle eines Umzugs, der mit {@code arrival} am Ziel {@link ConflictInput#unit()} ankam: Ausgehend
	 * von der Version an der Quelle aus demselben Speichervorgang mit {@code movedTo} = Ziel der nächste Vorfahre (über erste
	 * Vorgänger), auf dem auch eine Nutzer-Version eines anderen Rechners aufbaut; hat dort kein anderer Rechner etwas
	 * geschrieben, ihr erster Vorgänger. Hat der Umziehende vorher an der Quelle geändert, ist das nicht die Basis.
	 * {@code null}, wenn es keine solche Version gibt.
	 */
	static VersionId sourceBase(ConflictInput input, UnitKey source, Version arrival) {
		TrailUnit unit = input.trail().get(source);
		if (unit == null || arrival.saveId() == null) {
			return null;
		}
		Version removal = null;
		for (Version version : all(unit)) {
			if (input.unit().equals(version.marks().movedTo()) && arrival.saveId().equals(version.saveId())) {
				removal = version;
			}
		}
		if (removal == null || removal.firstParent() == null) {
			return null;
		}
		Map<VersionId, Version> byId = new HashMap<>();
		all(unit).forEach(version -> byId.put(version.id(), version));
		Set<VersionId> shared = new HashSet<>();
		for (Version other : userVersions(all(unit), source)) {
			if (!other.machine().equals(removal.machine())) {
				shared.addAll(firstParents(other.firstParent(), byId));
			}
		}
		for (VersionId ancestor : firstParents(removal.firstParent(), byId)) {
			if (shared.contains(ancestor)) {
				return ancestor;
			}
		}
		return removal.firstParent();
	}

	/** Die Version und ihre Vorfahren über erste Vorgänger, soweit sie im Ordner liegen, jüngste zuerst. */
	private static List<VersionId> firstParents(VersionId start, Map<VersionId, Version> byId) {
		List<VersionId> line = new ArrayList<>();
		VersionId at = start;
		while (at != null && !line.contains(at)) {
			line.add(at);
			Version version = byId.get(at);
			at = version == null ? null : version.firstParent();
		}
		return line;
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
				Version mine = latestCarrying(input, unit, bookingId, removal.machine());
				Kind kind = displacedOnly(unit, at, bookingId) ? Kind.ARRIVED : Kind.FAILED;
				return new BookingTrail(kind, removal, mine, target);
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
				Version own = latestCarrying(input, unit, bookingId, removal.machine());
				return new BookingTrail(Kind.ARRIVED, removal, last, target, own == null ? last : own);
			}
			at = target;
			gone = next;
		}
		return new BookingTrail(Kind.ARRIVED, removal, null, at);
	}

	/**
	 * Ob ein Umzug am Ziel nur gegen die Buchung verlor, die er dort selbst ersetzte (Tausch, Ersetzen): Eine Version mit
	 * {@code movedFrom} = Herkunft trägt die Buchung, ihr erster Vorgänger trägt eine andere, und was seitdem am Ziel gilt,
	 * trägt nur diese andere (geändert, nicht neu verkauft). Diese verlässt das Ziel nach jeder Zeile der Tabelle
	 * ({@link BookingFate}: Wer sie ersetzt hat, hat sie storniert oder verschoben), also kommt die Buchung dort an, gleich
	 * wer das Ziel gewann.
	 */
	private static boolean displacedOnly(TrailUnit unit, UnitKey from, String bookingId) {
		Map<VersionId, Version> byId = new HashMap<>();
		all(unit).forEach(version -> byId.put(version.id(), version));
		for (Version version : userVersions(all(unit), unit.unit())) {
			if (!from.equals(version.marks().movedFrom()) || !carries(version, bookingId)
					|| version.firstParent() == null) {
				continue;
			}
			Version parent = byId.get(version.firstParent());
			String replaced = parent == null || parent.deleted() ? null : ConflictInput.bookingIdOf(parent.payload());
			if (replaced == null || replaced.equals(bookingId) || !unit.stands(parent.id())) {
				continue;
			}
			Set<VersionId> after = descendants(unit, parent.id());
			boolean onlyReplaced = true;
			for (Version standing : userVersions(unit.standing(), unit.unit())) {
				if (after.contains(standing.id()) && !carries(standing, replaced)) {
					onlyReplaced = false;
				}
			}
			if (onlyReplaced) {
				return true;
			}
		}
		return false;
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
