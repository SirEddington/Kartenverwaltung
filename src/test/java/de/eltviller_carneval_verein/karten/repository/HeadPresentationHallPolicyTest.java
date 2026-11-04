package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Kind;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

/** Die Strategien für Event-Kopf, Vorstellung und Halle auf JSON, ohne Versionsspeicher. */
class HeadPresentationHallPolicyTest {

	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();
	private static final String OWN = "ownmach1";
	private static final String OTHER = "othermac";
	private static final String THIRD = "thirdmac";

	private static final UnitKey PRESENTATION = UnitKey.presentation("event-1", "pres-1");
	private static final UnitKey HEAD = UnitKey.event("event-1");
	private static final UnitKey HALL = UnitKey.hall("hall-1");

	private static final ConflictPolicy PRESENTATION_POLICY = new PresentationPolicy();
	private static final ConflictPolicy HEAD_POLICY = new EventHeadPolicy();
	private static final ConflictPolicy HALL_POLICY = new HallPolicy();

	private static int counter;

	private static ObjectNode json(String text) {
		try {
			return (ObjectNode) MAPPER.readTree(text);
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
	}

	private static Version version(String machine, String payload) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(), machine,
				"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, "save-" + counter, false, json(payload));
	}

	private static Version deletion(String machine) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(), machine,
				"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, "save-" + counter, true, json("{}"));
	}

	/** Eine Vorstellung, die sich nicht lesen lässt: Das Datum ist kein Datum. */
	private static Version unreadablePresentation(String machine) {
		return version(machine, presentation("date", "\"kaputt\"").toString());
	}

	private static ConflictInput input(UnitKey unit, Version base, Version mine, Version theirs, Version... chain) {
		return new ConflictInput(unit, base, mine, theirs, List.of(chain), OWN, Map.of());
	}

	private static ConflictNote.Party party(Version version) {
		return new ConflictNote.Party(version.machine(), version.machineName(), version.operator(), version.id());
	}

	private static final String PRESENTATION_BASE = "{\"id\":\"pres-1\",\"name\":\"Freitag\",\"date\":\"2026-11-06\","
			+ "\"time\":\"19:11:00\",\"hallId\":\"hall-1\",\"seatingPlanId\":\"plan-1\",\"price\":2000,"
			+ "\"actualCashAmount\":0,\"cashDifferenceComment\":null}";

	/** Die Basis-Vorstellung mit den angegebenen Feldern überschrieben. */
	private static ObjectNode presentation(String... fieldsAndValues) {
		ObjectNode node = json(PRESENTATION_BASE);
		for (int i = 0; i < fieldsAndValues.length; i += 2) {
			node.set(fieldsAndValues[i], json("{\"v\":" + fieldsAndValues[i + 1] + "}").get("v"));
		}
		return node;
	}

	// Vorstellung -->

	@Test
	void presentationHallChangeAgainstScheduleChangeMergesBoth() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version mine = version(OWN, presentation("hallId", "\"hall-2\"", "seatingPlanId", "\"plan-2\"").toString());
		Version theirs = version(OTHER, presentation("date", "\"2026-11-07\"", "time", "\"20:11:00\"").toString());

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals(presentation("hallId", "\"hall-2\"", "seatingPlanId", "\"plan-2\"", "date", "\"2026-11-07\"", "time",
				"\"20:11:00\""), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void cashAmountAndCommentAreOneGroup() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version mine = version(OWN, presentation("actualCashAmount", "5000").toString());
		Version theirs = version(OTHER, presentation("cashDifferenceComment", "\"Wechselgeld\"").toString());

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirs, theirs));

		// die Gruppe "cash" ist strittig: "Deren" gilt vollständig, der Betrag von "Meine" geht nicht in die Mischung
		assertEquals(theirs.payload(), resolution.payload());
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(mine.id(), note.dropped());
		assertEquals(party(theirs), note.other());
		assertEquals(1, note.fields().size());
		assertEquals("cash", note.fields().get(0).group());
		assertEquals(json("{\"actualCashAmount\":5000,\"cashDifferenceComment\":null}"), note.fields().get(0).mine());
		assertEquals(json("{\"actualCashAmount\":0,\"cashDifferenceComment\":\"Wechselgeld\"}"),
				note.fields().get(0).theirs());
	}

	@Test
	void sameChangeOnBothSidesIsNoConflict() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version mine = version(OWN, presentation("hallId", "\"hall-2\"").toString());
		Version theirs = version(OTHER, presentation("hallId", "\"hall-2\"").toString());

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertNull(resolution.note());
	}

	@Test
	void unchangedMineTakesTheirs() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version mine = version(OWN, PRESENTATION_BASE);
		Version theirs = version(OTHER, presentation("name", "\"Samstag\"").toString());

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertFalse(resolution.deleted());
		assertNull(resolution.note());
	}

	@Test
	void presentationsWithoutBaseCompareAgainstEmpty() {
		Version mine = version(OWN, presentation("name", "\"Mein Name\"").toString());
		Version theirs = version(OTHER, presentation("name", "\"Dein Name\"").toString());

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, null, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertEquals(Kind.FIELDS, resolution.note().kind());
		assertEquals("name", resolution.note().fields().get(0).group());
	}

	@Test
	void deletionWinsAgainstPresentationEdit() {
		Version base = version(THIRD, PRESENTATION_BASE);

		// Deren hat gelöscht, Meine geändert
		Version mine = version(OWN, presentation("name", "\"Samstag\"").toString());
		Version theirDeletion = deletion(OTHER);
		Resolution theirs = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirDeletion, theirDeletion));

		assertTrue(theirs.deleted());
		assertEquals(Kind.DELETE_WINS, theirs.note().kind());
		assertEquals(OWN, theirs.note().affected());
		assertEquals(mine.id(), theirs.note().dropped());
		assertEquals(party(theirDeletion), theirs.note().other());

		// Meine hat gelöscht, Deren geändert
		Version myDeletion = deletion(OWN);
		Version theirEdit = version(OTHER, presentation("name", "\"Samstag\"").toString());
		Resolution mineWins = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, myDeletion, theirEdit, theirEdit));

		assertTrue(mineWins.deleted());
		assertEquals(Kind.DELETE_WINS, mineWins.note().kind());
		assertEquals(OTHER, mineWins.note().affected());
		assertEquals(theirEdit.id(), mineWins.note().dropped());
		assertEquals(party(myDeletion), mineWins.note().other());
	}

	@Test
	void deletionAgainstUnchangedTheirsHasNoNote() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version myDeletion = deletion(OWN);
		Version theirs = version(OTHER, PRESENTATION_BASE);

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, myDeletion, theirs, theirs));

		assertTrue(resolution.deleted());
		assertNull(resolution.note());
	}

	@Test
	void bothDeletedIsNoConflict() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version theirs = deletion(OTHER);

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, deletion(OWN), theirs, theirs));

		assertTrue(resolution.deleted());
		assertNull(resolution.note());
	}

	@Test
	void otherIsTheVersionThatIntroducedTheWinningValue() {
		Version base = version(THIRD, PRESENTATION_BASE);
		// Ein dritter Rechner setzt den Betrag; Deren (der Gewinner) ist dessen Zusammenführung mit einer Namensänderung
		Version third = version(THIRD, presentation("actualCashAmount", "700").toString());
		Version theirs = version(OTHER, presentation("actualCashAmount", "700", "name", "\"Samstag\"").toString());
		Version mine = version(OWN, presentation("actualCashAmount", "500").toString());

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirs, third, theirs));

		assertEquals("cash", resolution.note().fields().get(0).group());
		assertEquals(party(third), resolution.note().other());
		assertEquals(OWN, resolution.note().affected());
	}

	@Test
	void otherOfADeletionIsTheDeletionInTheChain() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version edit = version(THIRD, presentation("name", "\"Samstag\"").toString());
		Version thirdDeletion = deletion(THIRD);
		Version theirs = deletion(OTHER);
		Version mine = version(OWN, presentation("price", "2500").toString());

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirs, edit, thirdDeletion, theirs));

		assertEquals(Kind.DELETE_WINS, resolution.note().kind());
		assertEquals(party(thirdDeletion), resolution.note().other());
	}

	@Test
	void unreadableTheirsIsUnmergedAndTheirsStays() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version mine = version(OWN, presentation("name", "\"Samstag\"").toString());
		Version theirs = unreadablePresentation(OTHER);

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirs, theirs));

		// kein Feldvergleich mit einer unlesbaren Seite: Der gespeicherte Inhalt von "Deren" bleibt, wie er ist
		assertFalse(resolution.deleted());
		assertEquals(theirs.payload(), resolution.payload());
		ConflictNote note = resolution.note();
		assertEquals(Kind.UNMERGED, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirs), note.other());
		assertEquals(mine.id(), note.dropped());
	}

	@Test
	void unreadableMineIsUnmergedAndTheirsStays() {
		Version base = version(THIRD, PRESENTATION_BASE);
		// Meine ist unlesbar; Deren hat eine andere Gruppe geändert, die ein Feldvergleich übernehmen würde
		Version mine = unreadablePresentation(OWN);
		Version theirs = version(OTHER, presentation("name", "\"Samstag\"").toString());

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertEquals(Kind.UNMERGED, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
		assertEquals(party(theirs), resolution.note().other());
		assertEquals(mine.id(), resolution.note().dropped());
	}

	@Test
	void contentOfAnotherUnitIsUnreadable() {
		// die ID im Inhalt passt nicht zum Ordner der Einheit
		Version base = version(THIRD, "{\"id\":\"event-1\",\"name\":\"Session\"}");
		Version wrongId = version(OTHER, "{\"id\":\"event-9\",\"name\":\"Session\"}");
		Version mine = version(OWN, "{\"id\":\"event-1\",\"name\":\"Neu\"}");

		Resolution theirsWrong = HEAD_POLICY.resolve(input(HEAD, base, mine, wrongId, wrongId));
		assertEquals(wrongId.payload(), theirsWrong.payload());
		assertEquals(Kind.UNMERGED, theirsWrong.note().kind());

		Version theirs = version(OTHER, "{\"id\":\"event-1\",\"name\":\"Session\",\"archived\":true}");
		Version mineWrong = version(OWN, "{\"id\":\"event-9\",\"name\":\"Session\"}");
		Resolution mineIsWrong = HEAD_POLICY.resolve(input(HEAD, base, mineWrong, theirs, theirs));
		assertEquals(theirs.payload(), mineIsWrong.payload());
		assertEquals(Kind.UNMERGED, mineIsWrong.note().kind());

		Version hallBase = version(THIRD, "{\"id\":\"hall-1\",\"name\":\"Alt\"}");
		Version hallWrong = version(OTHER, "{\"id\":\"hall-9\",\"name\":\"Fremd\"}");
		Version hallMine = version(OWN, "{\"id\":\"hall-1\",\"name\":\"Neu\"}");
		Resolution hall = HALL_POLICY.resolve(input(HALL, hallBase, hallMine, hallWrong, hallWrong));
		assertEquals(hallWrong.payload(), hall.payload());
		assertEquals(Kind.UNMERGED, hall.note().kind());
		assertEquals(OWN, hall.note().affected());
	}

	@Test
	void equalContentIsDecidedBeforeReadability() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version mine = unreadablePresentation(OWN);
		Version theirs = unreadablePresentation(OTHER);

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertNull(resolution.note());
	}

	@Test
	void deleteWinsOnlyAppliesToTheSameIdentityWhenAskedTo() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version myDeletion = deletion(OWN);
		Version theirs = version(OTHER, presentation("name", "\"Samstag\"").toString());
		ConflictInput input = input(PRESENTATION, base, myDeletion, theirs, theirs);

		// Deren ist eine andere Sache als die Basis (bei Buchungen: neue Buchung auf demselben Sitz): nichts wird gelöscht
		assertNull(PolicyRules.deleteWins(input, version -> false));
		assertTrue(PolicyRules.deleteWins(input, version -> true).deleted());

		// Deren gelöscht gilt unabhängig davon
		Version theirDeletion = deletion(OTHER);
		Version mine = version(OWN, presentation("name", "\"Samstag\"").toString());
		assertTrue(PolicyRules.deleteWins(input(PRESENTATION, base, mine, theirDeletion, theirDeletion), version -> false)
				.deleted());
	}

	@Test
	void payloadsOfTheInputAreNotChanged() {
		Version base = version(THIRD, PRESENTATION_BASE);
		Version mine = version(OWN, presentation("hallId", "\"hall-2\"").toString());
		Version theirs = version(OTHER, presentation("date", "\"2026-11-07\"").toString());
		ObjectNode mineBefore = mine.payload().deepCopy();
		ObjectNode theirsBefore = theirs.payload().deepCopy();

		Resolution resolution = PRESENTATION_POLICY.resolve(input(PRESENTATION, base, mine, theirs, theirs));
		resolution.payload().put("name", "verändert");

		assertEquals(mineBefore, mine.payload());
		assertEquals(theirsBefore, theirs.payload());
	}

	// <-- Vorstellung

	// Event-Kopf -->

	@Test
	void headNameChangeAgainstArchiveMergesBoth() {
		Version base = version(THIRD, "{\"id\":\"event-1\",\"name\":\"Session\",\"description\":null,\"archived\":false}");
		Version mine = version(OWN, "{\"id\":\"event-1\",\"name\":\"Session 2027\",\"description\":null,\"archived\":false}");
		Version theirs = version(OTHER, "{\"id\":\"event-1\",\"name\":\"Session\",\"description\":null,\"archived\":true}");

		Resolution resolution = HEAD_POLICY.resolve(input(HEAD, base, mine, theirs, theirs));

		assertEquals(json("{\"id\":\"event-1\",\"name\":\"Session 2027\",\"description\":null,\"archived\":true}"),
				resolution.payload());
		assertNull(resolution.note());
	}

	@Test
	void headSameFieldChangedDifferentlyKeepsTheirsAndNotesMine() {
		Version base = version(THIRD, "{\"id\":\"event-1\",\"name\":\"Session\",\"archived\":false}");
		Version mine = version(OWN, "{\"id\":\"event-1\",\"name\":\"Mein Name\",\"archived\":false}");
		Version theirs = version(OTHER, "{\"id\":\"event-1\",\"name\":\"Dein Name\",\"archived\":false}");

		Resolution resolution = HEAD_POLICY.resolve(input(HEAD, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertEquals(Kind.FIELDS, resolution.note().kind());
		assertEquals("name", resolution.note().fields().get(0).group());
		assertEquals(json("{\"name\":\"Mein Name\"}"), resolution.note().fields().get(0).mine());
		assertEquals(json("{\"name\":\"Dein Name\"}"), resolution.note().fields().get(0).theirs());
	}

	@Test
	void headDeletionWinsInBothDirections() {
		Version base = version(THIRD, "{\"id\":\"event-1\",\"name\":\"Session\",\"archived\":false}");
		Version edit = version(OWN, "{\"id\":\"event-1\",\"name\":\"Neu\",\"archived\":false}");
		Version theirDeletion = deletion(OTHER);

		Resolution lost = HEAD_POLICY.resolve(input(HEAD, base, edit, theirDeletion, theirDeletion));
		assertTrue(lost.deleted());
		assertEquals(OWN, lost.note().affected());

		Version myDeletion = deletion(OWN);
		Version theirEdit = version(OTHER, "{\"id\":\"event-1\",\"name\":\"Neu\",\"archived\":false}");
		Resolution won = HEAD_POLICY.resolve(input(HEAD, base, myDeletion, theirEdit, theirEdit));
		assertTrue(won.deleted());
		assertEquals(OTHER, won.note().affected());
	}

	// <-- Event-Kopf

	// Halle -->

	@Test
	void hallAgainstHallKeepsTheirsAndNotesMine() {
		Version base = version(THIRD, "{\"id\":\"hall-1\",\"name\":\"Alt\",\"hallWidth\":10.0}");
		Version mine = version(OWN, "{\"id\":\"hall-1\",\"name\":\"Meine Halle\",\"hallWidth\":10.0}");
		Version theirs = version(OTHER, "{\"id\":\"hall-1\",\"name\":\"Deren Halle\",\"hallWidth\":12.0}");

		Resolution resolution = HALL_POLICY.resolve(input(HALL, base, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals(theirs.payload(), resolution.payload());
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(mine.id(), note.dropped());
		assertEquals(party(theirs), note.other());
		assertEquals(1, note.fields().size());
		assertEquals("hall", note.fields().get(0).group());
		assertEquals(json("{\"name\":\"Meine Halle\"}"), note.fields().get(0).mine());
		assertEquals(json("{\"name\":\"Deren Halle\"}"), note.fields().get(0).theirs());
	}

	@Test
	void hallWithEqualNamesButDifferentContentStillNotesTheConflict() {
		Version base = version(THIRD, "{\"id\":\"hall-1\",\"name\":\"Halle\",\"hallWidth\":10.0}");
		Version mine = version(OWN, "{\"id\":\"hall-1\",\"name\":\"Halle\",\"hallWidth\":11.0}");
		Version theirs = version(OTHER, "{\"id\":\"hall-1\",\"name\":\"Halle\",\"hallWidth\":12.0}");

		Resolution resolution = HALL_POLICY.resolve(input(HALL, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertNotNull(resolution.note());
		assertEquals(Kind.FIELDS, resolution.note().kind());
	}

	@Test
	void hallEqualToTheirsHasNoNote() {
		Version base = version(THIRD, "{\"id\":\"hall-1\",\"name\":\"Alt\"}");
		Version mine = version(OWN, "{\"id\":\"hall-1\",\"name\":\"Neu\"}");
		Version theirs = version(OTHER, "{\"id\":\"hall-1\",\"name\":\"Neu\"}");

		Resolution resolution = HALL_POLICY.resolve(input(HALL, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertNull(resolution.note());
	}

	@Test
	void hallDeletionWinsInBothDirections() {
		Version base = version(THIRD, "{\"id\":\"hall-1\",\"name\":\"Alt\"}");
		Version edit = version(OWN, "{\"id\":\"hall-1\",\"name\":\"Neu\"}");
		Version theirDeletion = deletion(OTHER);

		Resolution lost = HALL_POLICY.resolve(input(HALL, base, edit, theirDeletion, theirDeletion));
		assertTrue(lost.deleted());
		assertEquals(Kind.DELETE_WINS, lost.note().kind());
		assertEquals(OWN, lost.note().affected());

		Version myDeletion = deletion(OWN);
		Version theirEdit = version(OTHER, "{\"id\":\"hall-1\",\"name\":\"Neu\"}");
		Resolution won = HALL_POLICY.resolve(input(HALL, base, myDeletion, theirEdit, theirEdit));
		assertTrue(won.deleted());
		assertEquals(Kind.DELETE_WINS, won.note().kind());
		assertEquals(OTHER, won.note().affected());
		assertEquals(theirEdit.id(), won.note().dropped());
	}

	@Test
	void hallOtherIsTheVersionThatIntroducedTheWinningContent() {
		Version base = version(THIRD, "{\"id\":\"hall-1\",\"name\":\"Alt\"}");
		Version third = version(THIRD, "{\"id\":\"hall-1\",\"name\":\"Dritte Halle\"}");
		// Deren ist die Zusammenführung des dritten Rechners mit demselben Inhalt
		Version theirs = version(OTHER, "{\"id\":\"hall-1\",\"name\":\"Dritte Halle\"}");
		Version mine = version(OWN, "{\"id\":\"hall-1\",\"name\":\"Meine Halle\"}");

		Resolution resolution = HALL_POLICY.resolve(input(HALL, base, mine, theirs, third, theirs));

		assertEquals(party(third), resolution.note().other());
	}

	// <-- Halle
}
