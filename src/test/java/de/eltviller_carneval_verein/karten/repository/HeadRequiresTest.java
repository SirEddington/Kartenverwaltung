package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.UnitMapper.UnitPayload;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.VersionId;

/** Das Merkmal {@code cashRegisters} am Event-Kopf ({@link HeadRequires}) auf JSON, ohne Versionsspeicher. */
class HeadRequiresTest {

	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();

	private static final UnitKey HEAD = UnitKey.event("e1");
	private static final UnitKey FRIDAY = UnitKey.presentation("e1", "p1");
	private static final UnitKey CASH = UnitKey.cashRegister("e1", "_kasse");
	private static final UnitKey OTHER_CASH = UnitKey.cashRegister("e1", "_andere");

	private static final VersionId HEAD_V1 = VersionId.create(1_800_000_000_000L, "abcdefgh", 1);
	private static final VersionId FRIDAY_V1 = VersionId.create(1_800_000_000_000L, "abcdefgh", 2);
	private static final VersionId CASH_V1 = VersionId.create(1_800_000_000_000L, "abcdefgh", 3);

	private static ObjectNode json(String text) {
		try {
			return (ObjectNode) MAPPER.readTree(text);
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
	}

	private static ObjectNode headPayload() {
		return json("{\"id\":\"e1\",\"name\":\"Prunksitzung\",\"archived\":false}");
	}

	private static UnitSnapshot live(UnitKey unit, VersionId version, ObjectNode payload) {
		return new UnitSnapshot(unit, version, Status.OK, false, payload, false);
	}

	private static Map<UnitKey, UnitSnapshot> states(UnitSnapshot... snapshots) {
		Map<UnitKey, UnitSnapshot> states = new HashMap<>();
		for (UnitSnapshot snapshot : snapshots) {
			states.put(snapshot.unit(), snapshot);
		}
		return states;
	}

	private static List<UnitKey> units(List<Change> changes) {
		return changes.stream().map(Change::unit).toList();
	}

	@Test
	void carriesOnlyTheFeature() {
		assertFalse(HeadRequires.carries(json("{}")));
		assertFalse(HeadRequires.carries(json("{\"requires\":null}")));
		assertFalse(HeadRequires.carries(json("{\"requires\":[]}")));
		assertFalse(HeadRequires.carries(json("{\"requires\":\"cashRegisters\"}")));
		assertFalse(HeadRequires.carries(null));
		assertTrue(HeadRequires.carries(json("{\"requires\":[\"cashRegisters\"]}")));
		assertTrue(HeadRequires.carries(json("{\"requires\":[\"x-unbekannt\",\"cashRegisters\"]}")));
	}

	@Test
	void withSetsOrRemovesTheFeatureOnACopy() {
		ObjectNode head = headPayload();

		ObjectNode required = HeadRequires.with(head, true);
		ObjectNode free = HeadRequires.with(json("{\"id\":\"e1\",\"requires\":[\"cashRegisters\"]}"), false);

		assertEquals("[\"cashRegisters\"]", required.get("requires").toString());
		assertEquals(headPayload(), head, "die Eingabe bleibt unverändert");
		assertEquals(json("{\"id\":\"e1\"}"), free, "ohne Merkmal fehlt das Feld");
	}

	/** Kennt der Versionsspeicher eine lebende Kasse, schreibt das Speichern den Kopf mit dem Merkmal mit, vor allem anderen. */
	@Test
	void liveCashInStoreWritesHeadFirstOnItsKnownVersion() {
		Map<UnitKey, UnitSnapshot> baseline = states(live(HEAD, HEAD_V1, headPayload()),
				live(FRIDAY, FRIDAY_V1, json("{\"id\":\"p1\"}")));
		Change friday = new Change(FRIDAY, List.of(FRIDAY_V1), json("{\"id\":\"p1\",\"name\":\"Freitag\"}"), false);
		List<UnitPayload> units = List.of(new UnitPayload(HEAD, headPayload()),
				new UnitPayload(FRIDAY, friday.payload()));

		List<Change> adjusted = HeadRequires.adjust("e1", List.of(friday), baseline, units,
				states(live(CASH, CASH_V1, json("{\"id\":\"_kasse\"}"))));

		assertEquals(List.of(HEAD, FRIDAY), units(adjusted));
		assertEquals(List.of(HEAD_V1), adjusted.get(0).parents());
		assertEquals(HeadRequires.with(headPayload(), true), adjusted.get(0).payload());
	}

	/** Ein gesperrter oder wartender Kopf wird nicht mitgeschrieben; der übrige Speichervorgang bleibt, wie er ist. */
	@Test
	void lockedOrWaitingHeadIsNotWrittenAlong() {
		for (Status status : List.of(Status.LOCKED_DAMAGED, Status.LOCKED_FORMAT, Status.WAITING)) {
			Map<UnitKey, UnitSnapshot> baseline = states(
					new UnitSnapshot(HEAD, HEAD_V1, status, false, headPayload(), false));
			Change cash = new Change(CASH, List.of(), json("{\"id\":\"_kasse\"}"), false);
			List<UnitPayload> units = List.of(new UnitPayload(HEAD, headPayload()), new UnitPayload(CASH, cash.payload()));

			List<Change> adjusted = HeadRequires.adjust("e1", List.of(cash), baseline, units, Map.of());

			assertEquals(List.of(CASH), units(adjusted), status.name());
		}
	}

	/**
	 * Beim Wiederbeleben stehen die Kassen vor den vorangestellten Löschungen, der Kopf mit dem Merkmal wie bisher danach:
	 * Bis er lebt, ist alles darunter unsichtbar.
	 */
	@Test
	void revivedHeadStandsAfterCashAndCleanup() {
		Map<UnitKey, UnitSnapshot> baseline = states(new UnitSnapshot(HEAD, HEAD_V1, Status.OK, true, null, false),
				live(FRIDAY, FRIDAY_V1, json("{\"id\":\"p1\"}")), live(CASH, CASH_V1, json("{\"id\":\"_kasse\"}")));
		Change cleanup = UnitMapper.deletion(baseline.get(FRIDAY));
		Change head = new Change(HEAD, List.of(HEAD_V1), headPayload(), false);
		Change cash = new Change(CASH, List.of(CASH_V1), json("{\"id\":\"_kasse\",\"actualCashAmount\":500}"), false);
		List<UnitPayload> units = List.of(new UnitPayload(HEAD, headPayload()), new UnitPayload(CASH, cash.payload()));

		List<Change> adjusted = HeadRequires.adjust("e1", List.of(cleanup, head, cash), baseline, units,
				states(baseline.get(CASH)));

		assertEquals(List.of(CASH, FRIDAY, HEAD), units(adjusted));
		assertTrue(HeadRequires.carries(adjusted.get(2).payload()));
	}

	/** Verliert der Kopf das Merkmal, steht er nach den Löschungen der Kassen. */
	@Test
	void headLosingTheFeatureStandsAfterTheCashDeletion() {
		ObjectNode stored = HeadRequires.with(headPayload(), true);
		Map<UnitKey, UnitSnapshot> baseline = states(live(HEAD, HEAD_V1, stored),
				live(CASH, CASH_V1, json("{\"id\":\"_kasse\"}")));
		Change deletion = UnitMapper.deletion(baseline.get(CASH));
		List<UnitPayload> units = List.of(new UnitPayload(HEAD, headPayload()));

		List<Change> adjusted = HeadRequires.adjust("e1", List.of(deletion), baseline, units, states(baseline.get(CASH)));

		assertEquals(List.of(CASH, HEAD), units(adjusted));
		assertFalse(adjusted.get(1).payload().has("requires"));
	}

	/** Bleibt nach der Löschung eine lebende Kasse im Versionsspeicher (etwa eine gesperrte), behält der Kopf das Merkmal. */
	@Test
	void deletionBesideAnotherLiveCashKeepsTheFeature() {
		ObjectNode stored = HeadRequires.with(headPayload(), true);
		Map<UnitKey, UnitSnapshot> baseline = states(live(HEAD, HEAD_V1, stored),
				live(CASH, CASH_V1, json("{\"id\":\"_kasse\"}")));
		Change deletion = UnitMapper.deletion(baseline.get(CASH));
		List<UnitPayload> units = List.of(new UnitPayload(HEAD, headPayload()));
		UnitSnapshot locked = new UnitSnapshot(OTHER_CASH, null, Status.LOCKED_DAMAGED, false, null, false);

		List<Change> adjusted = HeadRequires.adjust("e1", List.of(deletion), baseline, units,
				states(baseline.get(CASH), locked));

		assertEquals(List.of(CASH), units(adjusted));
	}

	/** Ein Kopf, den das Speichern ohnehin schreibt, bekommt das abgeleitete Merkmal. */
	@Test
	void changedHeadGetsTheDerivedFeature() {
		ObjectNode stored = HeadRequires.with(headPayload(), true);
		Map<UnitKey, UnitSnapshot> baseline = states(live(HEAD, HEAD_V1, stored));
		ObjectNode renamed = headPayload().put("name", "Kappensitzung");
		Change head = new Change(HEAD, List.of(HEAD_V1), renamed, false);

		List<Change> adjusted = HeadRequires.adjust("e1", List.of(head), baseline,
				List.of(new UnitPayload(HEAD, renamed)), Map.of());

		assertEquals(List.of(HEAD), units(adjusted));
		assertEquals(HeadRequires.with(renamed, true), adjusted.get(0).payload(), "das gespeicherte Merkmal bleibt");
	}
}
