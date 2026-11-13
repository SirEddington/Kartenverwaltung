package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.store.UnitState.Status;

/** Das Merkmal „inhaltlich zu neu“ reist mit dem Stand (KARV-135). */
class UnitSnapshotTest {

	private static final UnitKey UNIT = UnitKey.booking("e1", "p1", "A-1");
	private static final Instant AT = Instant.parse("2026-11-07T10:00:00Z");

	private static Version version(int n, boolean newerContent) {
		VersionId id = VersionId.create(n * 1000L, "bbbbbbbb", 0);
		return new Version(1, id, List.of(), "bbbbbbbb", "Kasse", "Anna", AT, "save-" + n, false,
				new ObjectMapper().createObjectNode().put("name", "x-test-" + n)).withNewerContent(newerContent);
	}

	@Test
	void ofStateCarriesContentLocked() {
		Version newer = version(1, true);
		UnitState locked = new UnitState(UNIT, Status.LOCKED_FORMAT, List.of(newer), List.of(), List.of(), newer.id(),
				Set.of(newer.id()));
		Version plain = version(2, false);
		UnitState readable = new UnitState(UNIT, Status.OK, List.of(plain), List.of(), List.of(), null,
				Set.of(plain.id()));
		UnitState empty = new UnitState(UNIT, Status.OK, List.of(), List.of(), List.of(), null, Set.of());

		UnitSnapshot snapshot = UnitSnapshot.of(locked);

		assertTrue(snapshot.contentLocked());
		assertEquals(newer.id(), snapshot.version());
		assertEquals(Status.LOCKED_FORMAT, snapshot.status());
		assertFalse(UnitSnapshot.of(readable).contentLocked());
		assertFalse(UnitSnapshot.of(empty).contentLocked());
	}

	@Test
	void ofVersionCarriesNewerContent() {
		assertTrue(UnitSnapshot.of(UNIT, version(1, true)).contentLocked());
		assertFalse(UnitSnapshot.of(UNIT, version(2, false)).contentLocked());
	}
}
