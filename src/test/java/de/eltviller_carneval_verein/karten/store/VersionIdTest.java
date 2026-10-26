package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class VersionIdTest {

	@Test
	void createHasFixedWidthAndSortsByTime() {
		assertEquals("00000000001-k4m9q2x8-0001", VersionId.create(1, "k4m9q2x8abc", 1).value());

		VersionId earlier = VersionId.create(999, "k4m9q2x8abc", 1);
		VersionId later = VersionId.create(1000, "k4m9q2x8abc", 1);
		assertTrue(earlier.compareTo(later) < 0);
		assertTrue(later.compareTo(earlier) > 0);
		assertEquals(0, earlier.compareTo(VersionId.of(earlier.value())));
	}

	@Test
	void createLowercasesMachineAndPadsCounterInBase36() {
		assertEquals("00000000001-k4m9q2x8-0010", VersionId.create(1, "K4M9Q2X8ABC", 36).value());
		assertEquals("00000000001-k4m9q2x8-zzzz", VersionId.create(1, "k4m9q2x8", 36 * 36 * 36 * 36 - 1).value());
	}

	@Test
	void epochMillisRoundTrips() {
		long now = 1_790_000_000_123L;
		assertEquals(now, VersionId.create(now, "k4m9q2x8", 7).epochMillis());
		assertEquals(0, VersionId.create(0, "k4m9q2x8", 0).epochMillis());
	}

	@Test
	void createRejectsBadArguments() {
		assertThrows(IllegalArgumentException.class, () -> VersionId.create(1, "kurz", 1));
		assertThrows(IllegalArgumentException.class, () -> VersionId.create(1, null, 1));
		assertThrows(IllegalArgumentException.class, () -> VersionId.create(1, "k4m9q2x8", -1));
		assertThrows(IllegalArgumentException.class, () -> VersionId.create(1, "k4m9q2x8", 36 * 36 * 36 * 36));
		assertThrows(IllegalArgumentException.class, () -> VersionId.create(-1, "k4m9q2x8", 1));
		assertThrows(IllegalArgumentException.class, () -> VersionId.create(1, "k4m9q2x-", 1));
	}

	@Test
	void rejectsUppercaseAndWrongShape() {
		assertFalse(VersionId.isValid("0MH2X7K9A1C-K4M9Q2X8-0001"));
		assertFalse(VersionId.isValid("abc"));
		assertFalse(VersionId.isValid(null));
		assertFalse(VersionId.isValid(""));
		assertFalse(VersionId.isValid("00000000001-k4m9q2x8-0001\n"));
		assertFalse(VersionId.isValid("00000000001-k4m9q2x8-0001.json"));
		assertTrue(VersionId.isValid("00000000001-k4m9q2x8-0001"));
		assertThrows(IllegalArgumentException.class, () -> VersionId.of("abc"));
		assertThrows(IllegalArgumentException.class, () -> VersionId.of(null));
		assertThrows(IllegalArgumentException.class, () -> new VersionId("0MH2X7K9A1C-K4M9Q2X8-0001"));
		assertEquals("00000000001-k4m9q2x8-0001", VersionId.of("00000000001-k4m9q2x8-0001").value());
	}

	@Test
	void generatorIsUniqueWithinSameMillisecond() {
		Clock fixed = Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000L), ZoneOffset.UTC);
		VersionIdGenerator generator = new VersionIdGenerator("k4m9q2x8abc", fixed);
		Set<VersionId> ids = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			ids.add(generator.next());
		}
		assertEquals(1000, ids.size());
		for (VersionId id : ids) {
			assertEquals(1_790_000_000_000L, id.epochMillis());
			assertTrue(id.value().contains("-k4m9q2x8-"), id.value());
		}
	}

	@Test
	void generatorFollowsClock() {
		VersionIdGenerator first = new VersionIdGenerator("k4m9q2x8", Clock.fixed(Instant.ofEpochMilli(5), ZoneOffset.UTC));
		VersionIdGenerator second = new VersionIdGenerator("k4m9q2x8", Clock.fixed(Instant.ofEpochMilli(6), ZoneOffset.UTC));
		assertTrue(first.next().compareTo(second.next()) < 0);
	}
}
