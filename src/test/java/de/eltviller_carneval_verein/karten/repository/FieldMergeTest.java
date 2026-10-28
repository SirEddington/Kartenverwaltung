package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

class FieldMergeTest {

	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();

	private static ObjectNode json(String text) {
		try {
			return (ObjectNode) MAPPER.readTree(text);
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
	}

	private static FieldMerge.Result merge(String base, String mine, String theirs) {
		return FieldMerge.merge(base == null ? null : json(base), json(mine), json(theirs), FieldGroups.booking());
	}

	@Test
	void onlyMineChangedTakesMine() {
		FieldMerge.Result result = merge("{\"lastName\":\"A\",\"comment\":\"x\"}", "{\"lastName\":\"B\",\"comment\":\"x\"}",
				"{\"lastName\":\"A\",\"comment\":\"x\"}");

		assertEquals(json("{\"lastName\":\"B\",\"comment\":\"x\"}"), result.merged());
		assertTrue(result.conflicts().isEmpty());
	}

	@Test
	void onlyTheirsChangedTakesTheirs() {
		FieldMerge.Result result = merge("{\"lastName\":\"A\",\"comment\":\"x\"}", "{\"lastName\":\"A\",\"comment\":\"x\"}",
				"{\"lastName\":\"C\",\"comment\":\"x\"}");

		assertEquals(json("{\"lastName\":\"C\",\"comment\":\"x\"}"), result.merged());
		assertTrue(result.conflicts().isEmpty());
	}

	@Test
	void differentGroupsChangedOnBothSidesTakeBoth() {
		FieldMerge.Result result = merge("{\"lastName\":\"A\",\"comment\":\"x\"}", "{\"lastName\":\"B\",\"comment\":\"x\"}",
				"{\"lastName\":\"A\",\"comment\":\"y\"}");

		assertEquals(json("{\"lastName\":\"B\",\"comment\":\"y\"}"), result.merged());
		assertTrue(result.conflicts().isEmpty());
	}

	@Test
	void bothChangedEquallyIsNoConflict() {
		FieldMerge.Result result = merge("{\"lastName\":\"A\"}", "{\"lastName\":\"B\"}", "{\"lastName\":\"B\"}");

		assertEquals(json("{\"lastName\":\"B\"}"), result.merged());
		assertTrue(result.conflicts().isEmpty());
	}

	@Test
	void bothChangedDifferentlyTakesTheirsAndReportsGroup() {
		FieldMerge.Result result = merge("{\"lastName\":\"A\",\"comment\":\"x\"}", "{\"lastName\":\"B\",\"comment\":\"y\"}",
				"{\"lastName\":\"C\",\"comment\":\"x\"}");

		assertEquals(json("{\"lastName\":\"C\",\"comment\":\"y\"}"), result.merged());
		assertEquals(1, result.conflicts().size());
		FieldConflict conflict = result.conflicts().get(0);
		assertEquals("customer", conflict.group());
		assertEquals(json("{\"lastName\":\"B\"}"), conflict.mine());
		assertEquals(json("{\"lastName\":\"C\"}"), conflict.theirs());
	}

	@Test
	void groupIsNotMixed() {
		// A ändert den Nachnamen, B den Vornamen: Die Gruppe "customer" ist strittig, das Ergebnis ist theirs vollständig
		FieldMerge.Result result = merge("{\"lastName\":\"Alt\",\"firstName\":\"Anna\",\"email\":\"a@x\"}",
				"{\"lastName\":\"Neu\",\"firstName\":\"Anna\",\"email\":\"a@x\"}",
				"{\"lastName\":\"Alt\",\"firstName\":\"Berta\",\"email\":\"a@x\"}");

		assertEquals(json("{\"lastName\":\"Alt\",\"firstName\":\"Berta\",\"email\":\"a@x\"}"), result.merged());
		assertEquals(1, result.conflicts().size());
		FieldConflict conflict = result.conflicts().get(0);
		assertEquals("customer", conflict.group());
		assertEquals(json("{\"lastName\":\"Neu\",\"firstName\":\"Anna\",\"email\":\"a@x\"}"), conflict.mine());
		assertEquals(json("{\"lastName\":\"Alt\",\"firstName\":\"Berta\",\"email\":\"a@x\"}"), conflict.theirs());
	}

	@Test
	void unknownFieldIsItsOwnGroup() {
		// "nickname" kennt keine Gruppe; "lastName" ändert nur die eigene Seite: beide Änderungen bleiben
		FieldMerge.Result both = merge("{\"lastName\":\"A\",\"nickname\":\"n\"}", "{\"lastName\":\"B\",\"nickname\":\"n\"}",
				"{\"lastName\":\"A\",\"nickname\":\"m\"}");
		assertEquals(json("{\"lastName\":\"B\",\"nickname\":\"m\"}"), both.merged());
		assertTrue(both.conflicts().isEmpty());

		FieldMerge.Result disputed = merge("{\"nickname\":\"n\"}", "{\"nickname\":\"m\"}", "{\"nickname\":\"o\"}");
		assertEquals(json("{\"nickname\":\"o\"}"), disputed.merged());
		assertEquals(1, disputed.conflicts().size());
		assertEquals("nickname", disputed.conflicts().get(0).group());
		assertEquals(json("{\"nickname\":\"m\"}"), disputed.conflicts().get(0).mine());
		assertEquals(json("{\"nickname\":\"o\"}"), disputed.conflicts().get(0).theirs());
	}

	@Test
	void unknownFieldOnlyOnOneSideIsKept() {
		FieldMerge.Result result = merge("{\"lastName\":\"A\"}", "{\"lastName\":\"A\",\"nickname\":\"m\"}",
				"{\"lastName\":\"A\"}");

		assertEquals(json("{\"lastName\":\"A\",\"nickname\":\"m\"}"), result.merged());
		assertTrue(result.conflicts().isEmpty());
	}

	@Test
	void nullBaseCountsAsEmpty() {
		FieldMerge.Result onlyMine = merge(null, "{\"comment\":\"a\"}", "{}");
		assertEquals(json("{\"comment\":\"a\"}"), onlyMine.merged());
		assertTrue(onlyMine.conflicts().isEmpty());

		FieldMerge.Result both = merge(null, "{\"comment\":\"a\"}", "{\"comment\":\"b\"}");
		assertEquals(json("{\"comment\":\"b\"}"), both.merged());
		assertEquals(1, both.conflicts().size());
		assertEquals("comment", both.conflicts().get(0).group());
	}

	@Test
	void missingFieldAndNullAreTheSame() {
		FieldMerge.Result result = merge("{\"comment\":null}", "{}", "{\"comment\":\"b\"}");

		// Meine hat gegenüber der Basis nichts geändert
		assertEquals(json("{\"comment\":\"b\"}"), result.merged());
		assertTrue(result.conflicts().isEmpty());
	}

	@Test
	void fieldRemovedByTheChosenSideIsRemoved() {
		FieldMerge.Result result = merge("{\"comment\":\"x\",\"lastName\":\"A\"}", "{\"lastName\":\"A\"}",
				"{\"comment\":\"x\",\"lastName\":\"A\"}");

		assertEquals(json("{\"lastName\":\"A\"}"), result.merged());
	}

	@Test
	void inputsStayUnchanged() {
		ObjectNode base = json("{\"lastName\":\"A\"}");
		ObjectNode mine = json("{\"lastName\":\"B\"}");
		ObjectNode theirs = json("{\"lastName\":\"C\"}");

		FieldMerge.Result result = FieldMerge.merge(base, mine, theirs, FieldGroups.booking());
		result.merged().put("lastName", "X");

		assertEquals(json("{\"lastName\":\"A\"}"), base);
		assertEquals(json("{\"lastName\":\"B\"}"), mine);
		assertEquals(json("{\"lastName\":\"C\"}"), theirs);
	}

	private static Version version(int counter, String payload) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, "abcdefgh", counter), List.of(), "abcdefgh",
				"Kasse", "Anna", Instant.EPOCH, "save", false, json(payload));
	}

	@Test
	void introducedByIsTheOldestMatchingVersion() {
		Version first = version(1, "{\"v\":1}");
		Version second = version(2, "{\"v\":2}");
		Version third = version(3, "{\"v\":2}");

		assertSame(second, FieldMerge.introducedBy(List.of(first, second, third), v -> v.payload().path("v").asInt() == 2));
	}

	@Test
	void introducedByWithoutMatchIsNull() {
		assertNull(FieldMerge.introducedBy(List.of(version(1, "{}")), v -> false));
		assertNull(FieldMerge.introducedBy(List.of(), v -> true));
	}
}
