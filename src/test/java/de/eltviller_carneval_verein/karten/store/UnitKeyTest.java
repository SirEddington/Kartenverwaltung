package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class UnitKeyTest {

	@Test
	void relativePathsMatchLayout() {
		assertEquals("events/e1", UnitKey.event("e1").relativePath());
		assertEquals("events/e1/presentations/p1", UnitKey.presentation("e1", "p1").relativePath());
		assertEquals("events/e1/presentations/p1/bookings/s1", UnitKey.booking("e1", "p1", "s1").relativePath());
		assertEquals("events/e1/presentations/p1/bookings/_b1",
				UnitKey.unassignedBooking("e1", "p1", "b1").relativePath());
		assertEquals("halls/h1", UnitKey.hall("h1").relativePath());
	}

	@Test
	void typesMatchFactories() {
		assertEquals(UnitType.EVENT, UnitKey.event("e1").type());
		assertEquals(UnitType.PRESENTATION, UnitKey.presentation("e1", "p1").type());
		assertEquals(UnitType.BOOKING, UnitKey.booking("e1", "p1", "s1").type());
		assertEquals(UnitType.BOOKING, UnitKey.unassignedBooking("e1", "p1", "b1").type());
		assertEquals(UnitType.HALL, UnitKey.hall("h1").type());
		assertEquals("_b1", UnitKey.unassignedBooking("e1", "p1", "b1").bookingDir());
	}

	@Test
	void hierarchyHelpersFollowTheFolderLayout() {
		UnitKey event = UnitKey.event("e1");
		UnitKey presentation = UnitKey.presentation("e1", "p1");
		UnitKey seat = UnitKey.booking("e1", "p1", "s1");
		UnitKey unassigned = UnitKey.unassignedBooking("e1", "p1", "b1");
		UnitKey hall = UnitKey.hall("h1");

		assertNull(event.parent());
		assertEquals(event, presentation.parent());
		assertEquals(presentation, seat.parent());
		assertNull(hall.parent());
		assertEquals(List.of(presentation, event), unassigned.ancestors());
		assertEquals(List.of(event), presentation.ancestors());
		assertEquals(List.of(), hall.ancestors());

		assertTrue(unassigned.isUnassigned());
		assertEquals("b1", unassigned.unassignedBookingId());
		assertFalse(seat.isUnassigned());
		assertNull(seat.unassignedBookingId());
		assertFalse(event.isUnassigned());

		assertTrue(UnitType.EVENT.hasChildren());
		assertTrue(UnitType.PRESENTATION.hasChildren());
		assertFalse(UnitType.BOOKING.hasChildren());
		assertFalse(UnitType.HALL.hasChildren());
	}

	@Test
	void parseIsInverseOfRelativePath() {
		List<UnitKey> keys = List.of(UnitKey.event("e1"), UnitKey.presentation("e1", "p1"),
				UnitKey.booking("e1", "p1", "s1"), UnitKey.unassignedBooking("e1", "p1", "b1"), UnitKey.hall("h1"));
		for (UnitKey key : keys) {
			assertEquals(Optional.of(key), UnitKey.parse(key.relativePath()), key.relativePath());
		}
	}

	@Test
	void parseRejectsForeignPaths() {
		for (String path : new String[] { "events/e1/claims", "feeds/m1", "events", "halls", "", "events/",
				"events/e1/", "events/e1/presentations", "events/e1/presentations/p1/bookings",
				"events/e1/presentations/p1/bookings/s1/x", "events/e1/presentations/p1/bookings/_", "halls/h1/x",
				"events/e 1", "events/e1/presentations/p1/bookings/s_1" }) {
			assertTrue(UnitKey.parse(path).isEmpty(), path);
		}
	}

	@Test
	void rejectsUnsafeIds() {
		for (String id : new String[] { "", "a/b", "a\\b", "a:b", "..", "_x", "ä" }) {
			assertThrows(IllegalArgumentException.class, () -> UnitKey.event(id), id);
			assertThrows(IllegalArgumentException.class, () -> UnitKey.presentation("e1", id), id);
			assertThrows(IllegalArgumentException.class, () -> UnitKey.booking("e1", "p1", id), id);
			assertThrows(IllegalArgumentException.class, () -> UnitKey.hall(id), id);
		}
		assertThrows(IllegalArgumentException.class, () -> UnitKey.event(null));
		assertThrows(IllegalArgumentException.class, () -> UnitKey.event("a".repeat(65)));
		assertEquals("events/" + "a".repeat(64), UnitKey.event("a".repeat(64)).relativePath());
	}

	@Test
	void unassignedBookingIdMustNotStartWithUnderscore() {
		assertThrows(IllegalArgumentException.class, () -> UnitKey.unassignedBooking("e1", "p1", "_b1"));
		assertThrows(IllegalArgumentException.class, () -> UnitKey.unassignedBooking("e1", "p1", ""));
	}

	@Test
	void recordRejectsInconsistentComponents() {
		assertThrows(IllegalArgumentException.class, () -> new UnitKey(UnitType.EVENT, "e1", "p1", null, null));
		assertThrows(IllegalArgumentException.class, () -> new UnitKey(UnitType.HALL, null, null, null, null));
		assertThrows(IllegalArgumentException.class, () -> new UnitKey(null, "e1", null, null, null));
	}
}
