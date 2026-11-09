package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.UnitKey;

/** Die Regel „inhaltlich zu neu“ von Version 1.1 ({@link ContentRules}). */
class ContentRulesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "A-1");
	private static final UnitKey PRESENTATION = UnitKey.presentation("e1", "p1");
	private static final UnitKey HEAD = UnitKey.event("e1");
	private static final UnitKey HALL = UnitKey.hall("h1");
	private static final Map<String, UnitKey> ALL_KINDS = Map.of("Buchung", BOOKING, "Vorstellung", PRESENTATION,
			"Kopf", HEAD, "Halle", HALL);

	private static ObjectNode payload(String json) throws Exception {
		return (ObjectNode) MAPPER.readTree(json);
	}

	private static boolean newer(UnitKey unit, String json) throws Exception {
		return ContentRules.GATE.isNewer(unit, payload(json));
	}

	@Test
	void unknownPaymentStatusIsNewer() throws Exception {
		assertTrue(newer(BOOKING, "{\"paymentStatus\":\"x-test-voucher\"}"));
		// Namen gelten genau, nicht nach Groß-/Kleinschreibung
		assertTrue(newer(BOOKING, "{\"paymentStatus\":\"cash\"}"));
		for (String known : List.of("NONE", "CASH", "CARD", "TRANSFER")) {
			assertFalse(newer(BOOKING, "{\"paymentStatus\":\"" + known + "\"}"), known);
		}
	}

	@Test
	void numericPaymentStatusIsNewer() throws Exception {
		assertTrue(newer(BOOKING, "{\"paymentStatus\":1}"));
		assertTrue(newer(BOOKING, "{\"paymentStatus\":0}"));
		assertTrue(newer(BOOKING, "{\"paymentStatus\":[\"CASH\"]}"));
	}

	@Test
	void nullOrMissingPaymentStatusIsNotNewer() throws Exception {
		assertFalse(newer(BOOKING, "{\"paymentStatus\":null}"));
		assertFalse(newer(BOOKING, "{}"));
		assertFalse(newer(BOOKING, "{\"firstName\":\"Anna\"}"));
	}

	@Test
	void paymentStatusOnlyCountsForBookings() throws Exception {
		for (Map.Entry<String, UnitKey> kind : ALL_KINDS.entrySet()) {
			if (kind.getValue() != BOOKING) {
				assertFalse(newer(kind.getValue(), "{\"paymentStatus\":\"x-test-voucher\"}"), kind.getKey());
			}
		}
		assertFalse(newer(UnitKey.unassignedBooking("e1", "p1", "b1"), "{\"paymentStatus\":\"CARD\"}"));
		assertTrue(newer(UnitKey.unassignedBooking("e1", "p1", "b1"), "{\"paymentStatus\":\"x-test-voucher\"}"));
	}

	@Test
	void requiresNullOrEmptyIsNotNewer() throws Exception {
		for (Map.Entry<String, UnitKey> kind : ALL_KINDS.entrySet()) {
			assertFalse(newer(kind.getValue(), "{\"requires\":null}"), kind.getKey());
			assertFalse(newer(kind.getValue(), "{\"requires\":[]}"), kind.getKey());
			assertFalse(newer(kind.getValue(), "{}"), kind.getKey());
		}
	}

	@Test
	void requiresWithUnknownFeatureIsNewer() throws Exception {
		for (Map.Entry<String, UnitKey> kind : ALL_KINDS.entrySet()) {
			assertTrue(newer(kind.getValue(), "{\"requires\":[\"x-test-merkmal\"]}"), kind.getKey());
			assertTrue(newer(kind.getValue(), "{\"requires\":[\"x-test-merkmal\",\"x-test-zwei\"]}"), kind.getKey());
		}
	}

	@Test
	void requiresOfWrongTypeIsNewer() throws Exception {
		for (Map.Entry<String, UnitKey> kind : ALL_KINDS.entrySet()) {
			assertTrue(newer(kind.getValue(), "{\"requires\":\"x\"}"), kind.getKey());
			assertTrue(newer(kind.getValue(), "{\"requires\":{\"a\":1}}"), kind.getKey());
			assertTrue(newer(kind.getValue(), "{\"requires\":[1]}"), kind.getKey());
			assertTrue(newer(kind.getValue(), "{\"requires\":1}"), kind.getKey());
			assertTrue(newer(kind.getValue(), "{\"requires\":true}"), kind.getKey());
		}
	}

	@Test
	void requiresAndPaymentStatusCombine() throws Exception {
		assertTrue(newer(BOOKING, "{\"requires\":[\"x-test-merkmal\"],\"paymentStatus\":\"CASH\"}"));
		assertTrue(newer(BOOKING, "{\"requires\":[],\"paymentStatus\":\"x-test-voucher\"}"));
		assertFalse(newer(BOOKING, "{\"requires\":[],\"paymentStatus\":\"CASH\"}"));
	}

	@Test
	void rulesIdIsComputedFromItsInputs() {
		assertEquals("gate-1;payment=CARD,CASH,NONE,TRANSFER;requires=", ContentRules.GATE.rulesId());
		assertEquals(Set.of("Booking.paymentStatus"), ContentRules.STRICT_FIELDS);
	}
}
