package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.TrailCollector.Folder;

class TrailCollectorTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String OWN = "eigen";
	private static final String OTHER = "fremd";
	private static final UnitKey M = UnitKey.unassignedBooking("e1", "p1", "m");

	private int counter;
	/** Je Einheit die Kette und alle Versionen ihres Ordners; der aktuelle Stand ist das Ende der Kette. */
	private final Map<UnitKey, List<Version>> chains = new HashMap<>();
	private final Map<UnitKey, List<Version>> folders = new HashMap<>();
	private final Map<UnitKey, VersionId> currents = new HashMap<>();
	private final List<UnitKey> read = new ArrayList<>();

	private static UnitKey seat(String seat) {
		return UnitKey.booking("e1", "p1", seat);
	}

	private static ObjectNode booking(String id) {
		return MAPPER.createObjectNode().put("id", id);
	}

	/** Eine Version auf dem Vorgänger (oder ohne); ohne Inhalt eine Löschung. */
	private Version version(String machine, Version parent, ObjectNode payload, VersionMarks marks) {
		counter++;
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine.equals(OWN) ? "aaaaaaaa" : "bbbbbbbb",
				counter), parent == null ? List.of() : List.of(parent.id()), machine, machine, machine, Instant.EPOCH,
				"save-" + counter, payload == null, payload == null ? MAPPER.createObjectNode() : payload, null, Map.of(),
				marks);
	}

	private Version claim(UnitKey unit, Version version) {
		chains.computeIfAbsent(unit, key -> new ArrayList<>()).add(version);
		return beside(unit, version);
	}

	private Version beside(UnitKey unit, Version version) {
		folders.computeIfAbsent(unit, key -> new ArrayList<>()).add(version);
		return version;
	}

	private Map<UnitKey, TrailUnit> collect(UnitKey unit, Version base, List<Version> stretch) throws IOException {
		return TrailCollector.collect(unit, base, stretch, key -> {
			read.add(key);
			List<Version> chain = chains.getOrDefault(key, List.of());
			VersionId current = currents.getOrDefault(key, chain.isEmpty() ? null : chain.get(chain.size() - 1).id());
			return new Folder(chain, folders.getOrDefault(key, List.of()), current);
		});
	}

	private static VersionMarks to(UnitKey target) {
		return VersionMarks.NONE.withMovedTo(target);
	}

	private static VersionMarks from(UnitKey source) {
		return VersionMarks.NONE.withMovedFrom(source);
	}

	@Test
	void followsMovedToForwardAcrossTwoUnits() throws IOException {
		Version base = claim(M, version(OTHER, null, booking("m"), VersionMarks.NONE));
		Version theirs = claim(M, version(OTHER, base, null, to(seat("s4"))));
		Version mine = beside(M, version(OWN, base, null, to(seat("s1"))));
		Version atS4 = claim(seat("s4"), version(OTHER, null, booking("m"), from(M)));
		claim(seat("s4"), version(OTHER, atS4, null, to(seat("s5"))));
		claim(seat("s5"), version(OTHER, null, booking("m"), from(seat("s4"))));

		Map<UnitKey, TrailUnit> trail = collect(M, base, List.of(mine));

		assertEquals(Set.of(M, seat("s1"), seat("s4"), seat("s5")), trail.keySet());
		assertEquals(2, trail.get(seat("s4")).chain().size());
	}

	/**
	 * KARV-98: Die Einheit ohne Platz jeder betrachteten Buchung kommt gleich nach der Einheit selbst, auch wenn keine
	 * Umzugsangabe dorthin zeigt (eine Auflösung hat die Buchung dort geparkt).
	 */
	@Test
	void readsTheUnassignedUnitOfEachBookingRightAfterTheUnit() throws IOException {
		Version base = claim(seat("s1"), version(OTHER, null, booking("m"), VersionMarks.NONE));
		claim(seat("s1"), version(OTHER, base, null, to(seat("s4"))));
		claim(seat("s4"), version(OTHER, null, booking("m"), from(seat("s1"))));
		claim(M, version(OTHER, null, booking("m"), VersionMarks.RESOLUTION.withMovedFrom(seat("s1"))));
		Version mine = beside(seat("s1"), version(OWN, base, null, VersionMarks.NONE));

		Map<UnitKey, TrailUnit> trail = collect(seat("s1"), base, List.of(mine));

		assertEquals(List.of(seat("s1"), M, seat("s4")), List.copyOf(trail.keySet()));
	}

	/** Eine lange Umzugskette verdrängt die Einheit ohne Platz nicht: Sie kommt vor dem Rest der Spur. */
	@Test
	void unassignedUnitIsNotDisplacedByTheLimit() throws IOException {
		Version base = claim(seat("s0"), version(OTHER, null, booking("m"), VersionMarks.NONE));
		claim(seat("s0"), version(OTHER, base, null, to(seat("s1"))));
		for (int i = 1; i <= 20; i++) {
			Version arrival = claim(seat("s" + i), version(OTHER, null, booking("m"), from(seat("s" + (i - 1)))));
			claim(seat("s" + i), version(OTHER, arrival, null, to(seat("s" + (i + 1)))));
		}
		claim(M, version(OTHER, null, booking("m"), VersionMarks.RESOLUTION.withMovedFrom(seat("s0"))));
		Version mine = beside(seat("s0"), version(OWN, base, null, VersionMarks.NONE));

		Map<UnitKey, TrailUnit> trail = collect(seat("s0"), base, List.of(mine));

		assertTrue(trail.containsKey(M), trail.keySet().toString());
	}

	/** Gibt es die Einheit ohne Platz nicht, steht sie nicht in der Spur, und nichts scheitert. */
	@Test
	void missingUnassignedFolderIsHarmless() throws IOException {
		Version base = claim(seat("s1"), version(OTHER, null, booking("m"), VersionMarks.NONE));
		Version mine = beside(seat("s1"), version(OWN, base, null, VersionMarks.NONE));

		Map<UnitKey, TrailUnit> trail = collect(seat("s1"), base, List.of(mine));

		assertEquals(Set.of(seat("s1")), trail.keySet());
		assertTrue(read.contains(M), "gelesen wurde sie");
	}

	@Test
	void includesTargetsOfBothSides() throws IOException {
		Version base = claim(M, version(OTHER, null, booking("m"), VersionMarks.NONE));
		claim(M, version(OTHER, base, null, to(seat("s4"))));
		beside(M, version(OWN, base, null, to(seat("s1"))));
		claim(seat("s4"), version(OTHER, null, booking("m"), from(M)));
		Version t = claim(seat("s1"), version(OTHER, null, booking("t"), VersionMarks.NONE));
		Version mine = beside(seat("s1"), version(OWN, null, booking("m"), from(M)));

		Map<UnitKey, TrailUnit> trail = collect(seat("s1"), null, List.of(mine));

		assertEquals(Set.of(seat("s1"), M, seat("s4")), trail.keySet());
	}

	@Test
	void ignoresMovesOfOtherBookings() throws IOException {
		Version base = claim(M, version(OTHER, null, booking("m"), VersionMarks.NONE));
		Version theirs = claim(M, version(OTHER, base, null, to(seat("s4"))));
		Version mine = beside(M, version(OWN, base, booking("m").put("name", "neu"), VersionMarks.NONE));
		Version x = claim(seat("s4"), version(OTHER, null, booking("x"), from(seat("s9"))));
		claim(seat("s4"), version(OTHER, x, booking("m"), from(M)));

		Map<UnitKey, TrailUnit> trail = collect(M, base, List.of(mine));

		assertEquals(Set.of(M, seat("s4")), trail.keySet());
		assertFalse(read.contains(seat("s9")));
	}

	@Test
	void stopsAtCycles() throws IOException {
		Version base = claim(seat("s1"), version(OTHER, null, booking("m"), VersionMarks.NONE));
		Version away = claim(seat("s1"), version(OTHER, base, null, to(seat("s2"))));
		claim(seat("s1"), version(OTHER, away, booking("m"), from(seat("s2"))));
		Version mine = beside(seat("s1"), version(OWN, base, booking("m").put("name", "neu"), VersionMarks.NONE));
		Version atS2 = claim(seat("s2"), version(OTHER, null, booking("m"), from(seat("s1"))));
		claim(seat("s2"), version(OTHER, atS2, null, to(seat("s1"))));

		Map<UnitKey, TrailUnit> trail = collect(seat("s1"), base, List.of(mine));

		assertEquals(Set.of(seat("s1"), seat("s2")), trail.keySet());
		assertEquals(List.of(seat("s1"), M, seat("s2")), read, "jede Einheit einmal gelesen (KARV-98: auch _m)");
	}

	@Test
	void stopsAtTheLimitWithAWarning() throws IOException {
		Version base = claim(seat("s0"), version(OTHER, null, booking("m"), VersionMarks.NONE));
		Version theirs = claim(seat("s0"), version(OTHER, base, null, to(seat("s1"))));
		Version mine = beside(seat("s0"), version(OWN, base, booking("m").put("name", "neu"), VersionMarks.NONE));
		for (int i = 1; i < 20; i++) {
			Version arrived = claim(seat("s" + i), version(OTHER, null, booking("m"), from(seat("s" + (i - 1)))));
			claim(seat("s" + i), version(OTHER, arrived, null, to(seat("s" + (i + 1)))));
		}
		List<LogRecord> warnings = new ArrayList<>();
		Logger logger = Logger.getLogger(TrailCollector.class.getName());
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
					warnings.add(record);
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		logger.addHandler(handler);
		Map<UnitKey, TrailUnit> trail;
		try {
			trail = collect(seat("s0"), base, List.of(mine));
		} finally {
			logger.removeHandler(handler);
		}

		assertEquals(TrailCollector.LIMIT, trail.size());
		assertEquals(1, warnings.size());
	}

	@Test
	void onlySamePresentation() throws IOException {
		UnitKey elsewhere = UnitKey.booking("e1", "p2", "s1");
		Version base = claim(M, version(OTHER, null, booking("m"), VersionMarks.NONE));
		Version theirs = claim(M, version(OTHER, base, null, to(elsewhere)));
		Version mine = beside(M, version(OWN, base, booking("m").put("name", "neu"), VersionMarks.NONE));

		Map<UnitKey, TrailUnit> trail = collect(M, base, List.of(mine));

		assertEquals(Set.of(M), trail.keySet());
		assertFalse(read.contains(elsewhere));
	}

	/** Ohne Buchung zum Verfolgen (zwei Neuverkäufe auf einem freien Sitz) liest die Spur keinen Ordner. */
	@Test
	void noBookingToFollowReadsNothing() throws IOException {
		Version mine = beside(seat("s1"), version(OWN, null, booking("y"), VersionMarks.NONE));

		assertEquals(Map.of(), collect(seat("s1"), null, List.of(mine)));
		assertEquals(List.of(), read);
	}

	@Test
	void outsideSkipsResolutionVersions() throws IOException {
		UnitKey s1 = seat("s1");
		Version base = claim(s1, version(OTHER, null, booking("m"), VersionMarks.NONE));
		Version t = claim(s1, version(OTHER, base, booking("t"), VersionMarks.NONE));
		Version lost = beside(s1, version(OWN, base, booking("m").put("name", "neu"), VersionMarks.NONE));
		Version other = beside(s1, version(OWN, base, booking("m").put("name", "anders"), VersionMarks.NONE));
		Version merge = beside(s1, new Version(1, VersionId.create(1_800_000_000_999L, "aaaaaaaa", 999),
				List.of(t.id(), lost.id()), OWN, OWN, OWN, Instant.EPOCH, "merge", false, booking("t"), null, Map.of(),
				VersionMarks.RESOLUTION));
		currents.put(s1, merge.id());

		TrailUnit unit = collect(s1, base, List.of(other)).get(s1);

		assertEquals(List.of(base, t), unit.chain());
		assertEquals(Set.of(lost, other), Set.copyOf(unit.outside()), "die Zusammenführung der Auflösung fehlt");
		assertEquals(merge.id(), unit.current());
	}

	@Test
	void ownPendingVersionsStand() throws IOException {
		UnitKey s1 = seat("s1");
		Version head = claim(s1, version(OTHER, null, booking("x"), VersionMarks.NONE));
		Version lost = beside(s1, version(OWN, null, booking("y"), VersionMarks.NONE));
		Version first = beside(s1, version(OWN, head, booking("m"), from(M)));
		Version second = beside(s1, version(OWN, first, booking("m").put("name", "neu"), VersionMarks.NONE));
		currents.put(s1, second.id());

		TrailUnit unit = collect(s1, head, List.of(lost)).get(s1);

		assertTrue(unit.stands(head.id()));
		assertTrue(unit.stands(first.id()));
		assertTrue(unit.stands(second.id()));
		assertFalse(unit.stands(lost.id()));
		assertEquals(List.of(head, first, second), unit.standing());
	}
}
