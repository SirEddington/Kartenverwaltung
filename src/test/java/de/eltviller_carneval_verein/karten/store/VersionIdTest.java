package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
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
	void isFromComparesTheMachineAbbreviation() {
		VersionId id = VersionId.create(1_790_000_000_000L, "K4M9Q2X8ABCDEFGHIJKLMNOPQR", 3);

		assertTrue(id.isFrom("k4m9q2x8abcdefghijklmnopqr"));
		assertTrue(id.isFrom("K4M9Q2X8XXXX"), "nur die ersten 8 Zeichen zählen");
		assertFalse(id.isFrom("k4m9q2x9abcdefghijklmnopqr"));
		assertFalse(id.isFrom("k4m9q2x"), "zu kurz für ein Kürzel");
		assertFalse(id.isFrom(null));
	}

	@Test
	void generatorFollowsClock() {
		VersionIdGenerator first = new VersionIdGenerator("k4m9q2x8", Clock.fixed(Instant.ofEpochMilli(5), ZoneOffset.UTC));
		VersionIdGenerator second = new VersionIdGenerator("k4m9q2x8", Clock.fixed(Instant.ofEpochMilli(6), ZoneOffset.UTC));
		assertTrue(first.next().compareTo(second.next()) < 0);
	}

	@Test
	void frozenGeneratorIssuesNoIdAfterTheLimit() {
		TestClock clock = new TestClock();
		VersionIdGenerator generator = new VersionIdGenerator("k4m9q2x8", clock);
		VersionId issued = generator.next();
		// die Uhr geht zurück: Die Grenze ist trotzdem die späteste vergebene ID
		clock.advance(Duration.ofSeconds(-3));

		Instant limit = generator.freeze();

		assertEquals(issued.epochMillis(), limit.toEpochMilli());
		clock.advance(Duration.ofSeconds(10));
		VersionId capped = generator.next();
		assertEquals(limit.toEpochMilli(), capped.epochMillis());
		assertTrue(issued.compareTo(capped) < 0, "der Zähler trennt die IDs derselben Millisekunde");
		generator.thaw();
		assertEquals(clock.millis(), generator.next().epochMillis());
	}

	@Test
	void freezeWithoutEarlierIdsUsesTheClock() {
		TestClock clock = new TestClock();
		assertEquals(clock.instant(), new VersionIdGenerator("k4m9q2x8", clock).freeze());
	}

	@Test
	void retiredGeneratorIssuesNoId() {
		VersionIdGenerator generator = new VersionIdGenerator("k4m9q2x8", new TestClock());
		generator.next();

		generator.retire();

		assertThrows(IllegalStateException.class, generator::next);
	}
}
