package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OwnershipTest {

	private static final String CURRENT = "currentmachineid0000000000";
	private static final String OLD = "oldmachineid00000000000000";
	private static final String FOREIGN = "foreignmachineid0000000000";
	private static final Instant UNTIL = Instant.parse("2026-10-26T13:45:07Z");

	private final Ownership ownership = new Ownership(CURRENT, List.of(new Ownership.Previous(OLD, UNTIL)));

	private static VersionId idOf(String machineId, long epochMillis) {
		return VersionId.create(epochMillis, machineId, 0);
	}

	private static Version versionOf(String machineId, VersionId id) {
		return new Version(1, id, List.of(), machineId, "Name", "Bediener", Instant.ofEpochMilli(id.epochMillis()), "save",
				false, null);
	}

	@Test
	void currentIdIsAlwaysOwn() {
		VersionId early = idOf(CURRENT, 1_000L);
		VersionId late = idOf(CURRENT, UNTIL.toEpochMilli() + 1_000_000L);

		assertTrue(ownership.isOwn(CURRENT, early));
		assertTrue(ownership.isOwn(CURRENT, late));
		assertTrue(ownership.isOwn(versionOf(CURRENT, late)));
		assertTrue(ownership.isOwnId(late));
		assertTrue(ownership.isOwnTempTag(CURRENT));
	}

	@Test
	void previousIdIsOwnUpToUntil() {
		VersionId exactly = idOf(OLD, UNTIL.toEpochMilli());
		VersionId oneMillisecondLater = idOf(OLD, UNTIL.toEpochMilli() + 1);

		assertTrue(ownership.isOwn(OLD, exactly));
		assertTrue(ownership.isOwn(versionOf(OLD, exactly)));
		assertFalse(ownership.isOwn(OLD, oneMillisecondLater));
		assertFalse(ownership.isOwn(versionOf(OLD, oneMillisecondLater)));
		assertTrue(ownership.isOwnTempTag(OLD));
	}

	@Test
	void foreignIdIsNeverOwn() {
		VersionId id = idOf(FOREIGN, 1_000L);

		assertFalse(ownership.isOwn(FOREIGN, id));
		assertFalse(ownership.isOwn(versionOf(FOREIGN, id)));
		assertFalse(ownership.isOwnId(id));
		assertFalse(ownership.isOwnTempTag(FOREIGN));
		assertFalse(ownership.isOwnTempTag(null));
	}

	@Test
	void isOwnIdUsesTheShortFormWithTheSameLimit() {
		assertTrue(ownership.isOwnId(idOf(OLD, UNTIL.toEpochMilli())));
		assertFalse(ownership.isOwnId(idOf(OLD, UNTIL.toEpochMilli() + 1)));
		assertTrue(ownership.isOwnId(idOf(CURRENT, UNTIL.toEpochMilli() + 1)));
		assertFalse(ownership.isOwnId(idOf(FOREIGN, 1_000L)));
	}

	@Test
	void idsListsCurrentAndPreviousIds() {
		assertEquals(Set.of(CURRENT, OLD), ownership.ids());
	}

	@Test
	void idsAreComputedOnceAndUnmodifiable() {
		assertSame(ownership.ids(), ownership.ids(), "einmal berechnet, nicht je Aufruf");
		assertThrows(UnsupportedOperationException.class, () -> ownership.ids().add(FOREIGN));
		assertEquals(ownership, new Ownership(CURRENT, List.of(new Ownership.Previous(OLD, UNTIL))));
	}

	@Test
	void nullPartsAreRejected() {
		assertThrows(NullPointerException.class, () -> new Ownership(null, List.of()));
		assertThrows(NullPointerException.class, () -> new Ownership.Previous(null, UNTIL));
		assertThrows(NullPointerException.class, () -> new Ownership.Previous(OLD, null));
	}

	@Test
	void listIsCopiedDefensively() {
		List<Ownership.Previous> source = new ArrayList<>(List.of(new Ownership.Previous(OLD, UNTIL)));
		Ownership copy = new Ownership(CURRENT, source);

		source.clear();

		assertEquals(1, copy.previous().size());
		assertThrows(UnsupportedOperationException.class, () -> copy.previous().clear());
	}
}
