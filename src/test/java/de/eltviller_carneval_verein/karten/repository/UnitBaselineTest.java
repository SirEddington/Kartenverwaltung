package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.VersionId;

/** Der gemerkte Rundlauf des Vergleichsstands (KARV-133). */
class UnitBaselineTest {

	private static final String HALL_ID = "66666666-6666-6666-6666-666666666666";
	private static final UnitKey UNIT = UnitKey.hall(HALL_ID);

	private static UnitSnapshot snapshot(int counter, String name, boolean withSeatHeight) {
		Hall hall = new Hall(HALL_ID);
		hall.setName(name);
		hall.addSeatingPlan().addTable(hall.getSeatingPlans().get(0).addRow()).addSeat();
		ObjectNode payload = UnitMapper.decompose(hall).payload();
		if (withSeatHeight) {
			ObjectNode plan = (ObjectNode) payload.get("seatingPlans").get(0);
			((ObjectNode) plan.get("tables").get(0).get("seats").get(0)).put("height", 30.0);
		}
		return new UnitSnapshot(UNIT, VersionId.create(1_800_000_000_000L + counter, "abcdefgh", counter), Status.OK, false,
				payload);
	}

	@Test
	void comparableIsTheRoundTripOfTheKnownSnapshot() {
		UnitBaseline baseline = new UnitBaseline(null);
		UnitSnapshot known = snapshot(1, "Stadthalle", true);
		baseline.reset(List.of(known), Set.of(UNIT));

		ObjectNode comparable = baseline.comparable(UNIT);

		assertNotNull(comparable);
		assertEquals(UnitMapper.comparable(known), comparable);
		assertEquals(false, comparable.equals(known.payload()), "die verworfene Sitzhöhe fehlt im Rundlauf");
	}

	@Test
	void comparableIsKeptUntilTheSnapshotIsReplaced() {
		UnitBaseline baseline = new UnitBaseline(null);
		baseline.reset(List.of(snapshot(1, "Stadthalle", true)), Set.of(UNIT));

		ObjectNode first = baseline.comparable(UNIT);
		assertSame(first, baseline.comparable(UNIT), "der Rundlauf wird nur einmal gebildet");

		baseline.adopt(HALL_ID, snapshot(2, "Bürgerhaus", true), true);

		ObjectNode second = baseline.comparable(UNIT);
		assertEquals("Bürgerhaus", second.get("name").asText(), "ein neuer Snapshot bekommt einen neuen Rundlauf");
	}

	@Test
	void comparableOfUnknownOrForgottenUnitIsNull() {
		UnitBaseline baseline = new UnitBaseline(null);
		assertNull(baseline.comparable(UNIT));

		baseline.reset(List.of(snapshot(1, "Stadthalle", false)), Set.of());
		assertNotNull(baseline.comparable(UNIT));

		baseline.forget(UNIT);
		assertNull(baseline.comparable(UNIT));
	}
}
