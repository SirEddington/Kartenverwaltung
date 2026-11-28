package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Kind;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

/** Die Konfliktregel der Kasse auf JSON, ohne Versionsspeicher. */
class CashRegisterPolicyTest {

	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();
	private static final String OWN = "ownmach1";
	private static final String OTHER = "othermac";
	private static final String THIRD = "thirdmac";

	private static final UnitKey REGISTER = UnitKey.cashRegister("event-1", "_event");
	private static final ConflictPolicy POLICY = new CashRegisterPolicy();

	private static final String BASE = "{\"id\":\"_event\",\"name\":null,\"allPresentations\":false,"
			+ "\"presentationIds\":[],\"actualCashAmount\":0,\"cashDifferenceComment\":null}";

	private static int counter;

	private static ObjectNode json(String text) {
		try {
			return (ObjectNode) MAPPER.readTree(text);
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
	}

	/** Die Basis-Kasse mit den angegebenen Feldern überschrieben. */
	private static String register(String... fieldsAndValues) {
		ObjectNode node = json(BASE);
		for (int i = 0; i < fieldsAndValues.length; i += 2) {
			node.set(fieldsAndValues[i], json("{\"v\":" + fieldsAndValues[i + 1] + "}").get("v"));
		}
		return node.toString();
	}

	private static Version version(String machine, String payload) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(), machine,
				"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, "save-" + counter, false, json(payload));
	}

	private static Version deletion(String machine) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(), machine,
				"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, "save-" + counter, true, json("{}"));
	}

	private static Resolution resolve(Version base, Version mine, Version theirs) {
		return POLICY.resolve(new ConflictInput(REGISTER, base, mine, theirs, List.of(theirs), OWN, Map.of()));
	}

	@Test
	void mergesDifferentGroups() {
		Version base = version(THIRD, BASE);
		Version mine = version(OWN, register("name", "\"Bar\""));
		Version theirs = version(OTHER, register("actualCashAmount", "5000"));

		Resolution resolution = resolve(base, mine, theirs);

		assertFalse(resolution.deleted());
		assertEquals(json(register("name", "\"Bar\"", "actualCashAmount", "5000")), resolution.payload());
		assertNull(resolution.note());
	}

	@Test
	void sameGroupOnBothSidesIsAFieldConflict() {
		Version base = version(THIRD, BASE);
		Version mine = version(OWN, register("actualCashAmount", "4000"));
		Version theirs = version(OTHER, register("cashDifferenceComment", "\"Wechselgeld\""));

		Resolution resolution = resolve(base, mine, theirs);

		assertEquals(theirs.payload(), resolution.payload());
		assertEquals(Kind.FIELDS, resolution.note().kind());
		assertEquals("cash", resolution.note().fields().get(0).group());
	}

	@Test
	void deletionLosesAgainstTheirCount() {
		Version base = version(THIRD, BASE);
		Version mine = deletion(OWN);
		Version theirs = version(OTHER, register("actualCashAmount", "5000"));

		Resolution resolution = resolve(base, mine, theirs);

		assertFalse(resolution.deleted(), "die Zählung bleibt");
		assertEquals(theirs.payload(), resolution.payload());
		assertEquals(Kind.UNMERGED, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
		assertEquals(mine.id(), resolution.note().dropped());
	}

	@Test
	void myCountWinsAgainstTheirDeletion() {
		Version base = version(THIRD, BASE);
		Version mine = version(OWN, register("cashDifferenceComment", "\"Wechselgeld\""));
		Version theirs = deletion(OTHER);

		Resolution resolution = resolve(base, mine, theirs);

		assertFalse(resolution.deleted(), "die Zählung bleibt");
		assertEquals(mine.payload(), resolution.payload());
		assertNull(resolution.note());
	}

	@Test
	void countWithoutBaseBeatsDeletion() {
		Version mine = deletion(OWN);
		Version theirs = version(OTHER, register("actualCashAmount", "5000"));

		Resolution resolution = resolve(null, mine, theirs);

		assertFalse(resolution.deleted());
		assertEquals(theirs.payload(), resolution.payload());
		assertEquals(Kind.UNMERGED, resolution.note().kind());
	}

	@Test
	void unchangedCountDoesNotBeatDeletion() {
		Version base = version(THIRD, register("actualCashAmount", "5000"));
		Version mine = version(OWN, register("actualCashAmount", "5000", "name", "\"Bar\""));
		Version theirs = deletion(OTHER);

		Resolution resolution = resolve(base, mine, theirs);

		assertTrue(resolution.deleted(), "ein Betrag, der schon in der Basis stand, ist keine neue Zählung");
		assertEquals(Kind.DELETE_WINS, resolution.note().kind());
	}

	@Test
	void deletionWinsAgainstRename() {
		Version base = version(THIRD, BASE);
		Version mine = deletion(OWN);
		Version theirs = version(OTHER, register("name", "\"Bar\""));

		Resolution resolution = resolve(base, mine, theirs);

		assertTrue(resolution.deleted());
		assertEquals(Kind.DELETE_WINS, resolution.note().kind());
		assertEquals(OTHER, resolution.note().affected());
	}
}
