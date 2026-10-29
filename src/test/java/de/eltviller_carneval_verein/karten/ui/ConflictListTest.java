package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Kind;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Party;
import de.eltviller_carneval_verein.karten.store.OpenConflict;
import de.eltviller_carneval_verein.karten.store.OpenConflict.ParentDeletion;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.ui.ConflictList.Action;
import de.eltviller_carneval_verein.karten.ui.ConflictList.Row;

/** Das Modell des Sammel-Dialogs ohne JavaFX: Zeilen, Gruppen, Gegenseite, Aktion und "neu". */
class ConflictListTest {

	private static final Instant AT = Instant.parse("2026-10-28T10:00:00Z");

	private final List<OpenConflict> source = new ArrayList<>();
	private final ConflictList list = new ConflictList(() -> List.copyOf(source), unit -> "Label " + unit.relativePath(),
			(conflict, group) -> false, hallId -> null);
	private int counter;

	private VersionId nextId() {
		return VersionId.of(String.format("%011d-abcdefgh-%04d", 1, ++counter));
	}

	private static UnitKey seat(String seatId) {
		return UnitKey.booking("ev", "pr", seatId);
	}

	private static Party party(String name, String operator) {
		return new Party("machine-b", name, operator, VersionId.of("00000000002-abcdefgh-0001"));
	}

	private OpenConflict conflict(UnitKey unit, String saveId, ConflictNote note) {
		return new OpenConflict(nextId(), unit, AT.plusSeconds(counter), saveId, note, null);
	}

	private static ConflictNote note(Kind kind, Party other) {
		return new ConflictNote(kind, "me", other, List.of(), null, null, null);
	}

	/** Ein Vermerk, wie die Auflösung ihn schreibt: mit dem Speichervorgang der weichenden eigenen Version. */
	private static ConflictNote note(Kind kind, Party other, String lostSave) {
		return new ConflictNote(kind, "me", other, List.of(), null, null, null, lostSave);
	}

	@Test
	void doubleSalesOfOneLostSaveAreGrouped() {
		// jede Zusammenführung hat ihren eigenen Speichervorgang; gemeinsam ist der der weichenden Version
		source.add(conflict(seat("s1"), "merge-1", note(Kind.DOUBLE_SALE, party("Kasse 1", "Anna"), "save-1")));
		source.add(conflict(seat("s7"), "merge-2", note(Kind.FIELDS, party("Kasse 1", "Anna"), "save-1")));
		source.add(conflict(seat("s2"), "merge-3", note(Kind.DOUBLE_SALE, party("Kasse 1", "Anna"), "save-1")));
		source.add(conflict(seat("s3"), "merge-4", note(Kind.DOUBLE_SALE, party("Kasse 1", "Anna"), "save-1")));

		List<Row> rows = list.rows();

		assertEquals(4, rows.size());
		assertEquals(List.of("s1", "s2", "s3", "s7"),
				rows.stream().map(row -> row.conflict().unit().bookingDir()).toList(), "Zeilen einer Gruppe stehen zusammen");
		assertEquals(List.of("save-1", "save-1", "save-1"), rows.subList(0, 3).stream().map(Row::group).toList());
		assertNull(rows.get(3).group(), "nur Doppelverkäufe bilden Gruppen");
		assertEquals(Action.ASSIGN_PLACE, rows.get(0).action());
		assertEquals("Doppelverkauf", rows.get(0).kindText());
		assertEquals("Label " + seat("s1").relativePath(), rows.get(0).entry());
		assertEquals(Instant.ofEpochMilli(2), rows.get(0).at(), "der Zeitpunkt der Gegenseite (ihre Version)");
	}

	@Test
	void noteWithoutLostSaveIsGroupedBySaveOfTheConflict() {
		source.add(conflict(seat("s1"), "alt", note(Kind.DOUBLE_SALE, party("Kasse 1", null))));
		source.add(conflict(seat("s2"), "neu", note(Kind.DOUBLE_SALE, party("Kasse 1", null), "save-9")));
		source.add(conflict(seat("s3"), "alt", note(Kind.DOUBLE_SALE, party("Kasse 1", null))));

		List<Row> rows = list.rows();

		assertEquals(List.of("s1", "s3", "s2"), rows.stream().map(row -> row.conflict().unit().bookingDir()).toList());
		assertEquals(List.of("alt", "alt", "save-9"), rows.stream().map(Row::group).toList());
	}

	@Test
	void rowWithoutKnownCounterpartVersionUsesTheTimeOfTheConflict() {
		OpenConflict withoutOther = conflict(seat("s1"), "a", note(Kind.DOUBLE_SALE, null));
		source.add(withoutOther);
		UnitKey show = UnitKey.presentation("ev", "pr");
		source.add(new OpenConflict(nextId(), show, AT.plusSeconds(99), "del", null,
				new ParentDeletion(show, "Kasse 3", null, List.of(seat("s1")))));

		List<Row> rows = list.rows();

		assertEquals(withoutOther.at(), rows.get(0).at());
		assertEquals(AT.plusSeconds(99), rows.get(1).at());
	}

	@Test
	void fieldRowsShowWhichGroupsAreAlreadyAdopted() {
		ConflictNote note = new ConflictNote(Kind.FIELDS, "me", party("Kasse 1", null),
				List.of(new FieldConflict("customer", JsonNodeFactory.instance.objectNode().put("lastName", "Meier"),
						JsonNodeFactory.instance.objectNode().put("lastName", "Maier")),
						new FieldConflict("comment", JsonNodeFactory.instance.objectNode().put("comment", "x"),
								JsonNodeFactory.instance.objectNode().put("comment", "y"))),
				null, null, nextId());
		source.add(conflict(seat("s1"), "a", note));
		ConflictList withState = new ConflictList(() -> List.copyOf(source), unit -> "Label",
				(conflict, group) -> group.equals("comment"), hallId -> null);

		List<ConflictList.FieldRow> fields = withState.rows().get(0).fields();

		assertEquals(List.of(false, true), fields.stream().map(ConflictList.FieldRow::adopted).toList());
		assertEquals(List.of(false, false), list.rows().get(0).fields().stream().map(ConflictList.FieldRow::adopted).toList(),
				"ohne Auskunft über das Modell ist nichts übernommen");
	}

	@Test
	void counterpartNamesMachineAndOperator() {
		source.add(conflict(seat("s1"), "a", note(Kind.DOUBLE_SALE, party("Kasse 1", "Anna"))));
		source.add(conflict(seat("s2"), "b", note(Kind.DOUBLE_SALE, party("Kasse 2", null))));
		source.add(conflict(seat("s3"), "c", note(Kind.DOUBLE_SALE, party(null, null))));
		source.add(conflict(seat("s4"), "d", note(Kind.DOUBLE_SALE, null)));

		List<String> counterparts = list.rows().stream().map(Row::counterpart).toList();

		assertEquals(List.of("Rechner Kasse 1 (Anna)", "Rechner Kasse 2", "ein anderer Rechner", "ein anderer Rechner"),
				counterparts);
	}

	@Test
	void parentDeletionRowHasNoAction() {
		UnitKey show = UnitKey.presentation("ev", "pr");
		source.add(new OpenConflict(nextId(), show, AT, "del", null,
				new ParentDeletion(show, "Kasse 3", "Bert", List.of(seat("s1")))));
		UnitKey event = UnitKey.event("ev");
		source.add(new OpenConflict(nextId(), event, AT.plusSeconds(1), "del2", null,
				new ParentDeletion(event, null, null, List.of(seat("s1")))));

		List<Row> rows = list.rows();

		assertEquals(Action.NONE, rows.get(0).action());
		assertEquals("Vorstellung gelöscht", rows.get(0).kindText());
		assertEquals("Rechner Kasse 3 (Bert)", rows.get(0).counterpart());
		assertEquals("Label " + show.relativePath(), rows.get(0).entry());
		assertEquals(Action.NONE, rows.get(1).action());
		assertEquals("Event gelöscht", rows.get(1).kindText());
		assertEquals("ein anderer Rechner", rows.get(1).counterpart());
		assertTrue(rows.get(0).fields().isEmpty());
	}

	@Test
	void hasUnseenIsTrueOnlyForNewConflicts() {
		assertFalse(list.hasUnseen(), "ohne Konflikte nichts Neues");

		source.add(conflict(seat("s1"), "a", note(Kind.DOUBLE_SALE, party("Kasse 1", null))));
		assertTrue(list.hasUnseen(), "noch nie gezeigt");
		list.rows();
		assertTrue(list.hasUnseen(), "Zeilen liefern allein genügt nicht, erst markSeen");
		list.markSeen();
		assertFalse(list.hasUnseen());

		source.add(conflict(seat("s2"), "b", note(Kind.DOUBLE_SALE, party("Kasse 1", null))));
		assertTrue(list.hasUnseen(), "ein neuer Konflikt");
		list.markSeen();
		assertTrue(list.hasUnseen(), "markSeen markiert nur, was rows() geliefert hat");
		list.rows();
		list.markSeen();
		assertFalse(list.hasUnseen());

		source.remove(0);
		assertFalse(list.hasUnseen(), "ein bestätigter Konflikt ist nichts Neues");
	}

	@Test
	void actionAndFieldsFollowTheKindOfTheNote() {
		ObjectNode mine = JsonNodeFactory.instance.objectNode().put("lastName", "Meier").put("firstName", "Anna");
		ObjectNode theirs = JsonNodeFactory.instance.objectNode().put("lastName", "Maier").put("firstName", "Anna");
		ConflictNote fields = new ConflictNote(Kind.FIELDS, "me", party("Kasse 1", null),
				List.of(new FieldConflict("customer", mine, theirs)), null, null, nextId());
		source.add(conflict(seat("s1"), "a", fields));
		source.add(conflict(UnitKey.hall("h1"), "b", new ConflictNote(Kind.FIELDS, "me", party("Kasse 1", null),
				List.of(new FieldConflict("hall", JsonNodeFactory.instance.objectNode().put("name", "Halle B"),
						JsonNodeFactory.instance.objectNode().put("name", "Halle A"))), null, null, nextId())));
		source.add(conflict(seat("s2"), "c", note(Kind.DELETE_WINS, party("Kasse 1", null))));
		source.add(conflict(UnitKey.presentation("ev", "pr"), "d", note(Kind.DELETE_WINS, party("Kasse 1", null))));
		source.add(conflict(seat("s3"), "e", note(Kind.UNMERGED, party("Kasse 1", null))));

		List<Row> rows = list.rows();

		assertEquals(List.of(Action.TAKE_MY_VALUE, Action.TAKE_MY_HALL, Action.RESTORE_BOOKING, Action.NONE, Action.NONE),
				rows.stream().map(Row::action).toList());
		assertEquals(List.of("Gleichzeitig geändert", "Gleichzeitig geändert", "Gelöscht", "Gelöscht",
				"Nicht zusammengeführt"), rows.stream().map(Row::kindText).toList());
		assertEquals(1, rows.get(0).fields().size());
		assertEquals("customer", rows.get(0).fields().get(0).group());
		assertEquals("Meier, Anna", rows.get(0).fields().get(0).mine());
		assertEquals("Maier, Anna", rows.get(0).fields().get(0).valid());
		assertEquals("Halle B", rows.get(1).fields().get(0).mine());
	}

	@Test
	void valuesShowNamesInsteadOfIdsAndEnumTexts() {
		Hall festhalle = new Hall("h1");
		festhalle.setName("Festhalle");
		SeatingPlan prunk = festhalle.addSeatingPlan();
		prunk.setName("Prunksitzung");
		ConflictList named = new ConflictList(() -> List.copyOf(source), unit -> "Label", (conflict, group) -> false,
				hallId -> hallId.equals("h1") ? festhalle : null);
		ObjectNode myHall = JsonNodeFactory.instance.objectNode().put("hallId", "h1").put("seatingPlanId", prunk.getId());
		ObjectNode theirHall = JsonNodeFactory.instance.objectNode().put("hallId", "weg").put("seatingPlanId", "auch-weg");
		ObjectNode myPlace = JsonNodeFactory.instance.objectNode().put("seatId", "f00-ba5").put("rowLabel", "A")
				.put("tableNumber", 3).put("seatNumber", 2);
		ObjectNode theirPlace = JsonNodeFactory.instance.objectNode().put("seatId", "f00-ba7").put("rowLabel", "A")
				.put("tableNumber", 3).put("seatNumber", 4);
		ObjectNode myPayment = JsonNodeFactory.instance.objectNode().put("paymentStatus", "TRANSFER");
		ObjectNode theirPayment = JsonNodeFactory.instance.objectNode().put("paymentStatus", "NEU_IN_SPAETERER_APP");
		source.add(conflict(UnitKey.presentation("ev", "pr"), "a", new ConflictNote(Kind.FIELDS, "me", null,
				List.of(new FieldConflict("hall", myHall, theirHall)), null, null, nextId())));
		source.add(conflict(seat("s1"), "b", new ConflictNote(Kind.FIELDS, "me", null,
				List.of(new FieldConflict("place", myPlace, theirPlace), new FieldConflict("payment", myPayment, theirPayment)),
				null, null, nextId())));

		List<Row> rows = named.rows();

		assertEquals("Festhalle, Prunksitzung", rows.get(0).fields().get(0).mine());
		assertEquals("–, –", rows.get(0).fields().get(0).valid(), "unbekannte Halle und Bestuhlung wie in den Tabellen");
		assertEquals("A, 3, 2", rows.get(1).fields().get(0).mine(), "die Sitz-ID steht nicht da");
		assertEquals("A, 3, 4", rows.get(1).fields().get(0).valid());
		assertEquals("Überweisung", rows.get(1).fields().get(1).mine());
		assertEquals("NEU_IN_SPAETERER_APP", rows.get(1).fields().get(1).valid(), "ein unbekannter Wert bleibt, wie er ist");
	}

	@Test
	void amountsInCentShowAsEuro() {
		source.add(conflict(seat("s1"), "a", new ConflictNote(Kind.FIELDS, "me", null,
				List.of(new FieldConflict("price", JsonNodeFactory.instance.objectNode().put("price", 3500),
						JsonNodeFactory.instance.objectNode().put("price", 4050))),
				null, null, nextId())));
		source.add(conflict(UnitKey.presentation("ev", "pr"), "b", new ConflictNote(Kind.FIELDS, "me", null,
				List.of(new FieldConflict("cash",
						JsonNodeFactory.instance.objectNode().put("actualCashAmount", 123450).put("cashDifferenceComment", "Wechselgeld"),
						JsonNodeFactory.instance.objectNode().putNull("actualCashAmount").put("cashDifferenceComment", ""))),
				null, null, nextId())));

		List<Row> rows = list.rows();

		assertEquals("35,00 €", rows.get(0).fields().get(0).mine());
		assertEquals("40,50 €", rows.get(0).fields().get(0).valid());
		assertEquals("1234,50 €, Wechselgeld", rows.get(1).fields().get(0).mine());
		assertEquals("(leer)", rows.get(1).fields().get(0).valid());
	}

	@Test
	void deletionTextAgreesWithTheCount() {
		assertEquals("Darunter stand 1 Eintrag von dir, den der andere Rechner nicht kannte. Der Stand lässt sich aus dem "
				+ "Verlauf wiederherstellen.", ConflictList.deletionText(1));
		assertEquals("Darunter standen 3 Einträge von dir, die der andere Rechner nicht kannte. Der Stand lässt sich aus dem "
				+ "Verlauf wiederherstellen.", ConflictList.deletionText(3));
	}

	@Test
	void indicatorTextCountsTheOpenConflicts() {
		assertEquals("1 Konflikt offen", ConflictList.indicatorText(1));
		assertEquals("2 Konflikte offen", ConflictList.indicatorText(2));
		assertEquals("12 Konflikte offen", ConflictList.indicatorText(12));
	}

	@Test
	void placeTextNamesTheFormerPlaceOfTheNote() {
		ObjectNode place = JsonNodeFactory.instance.objectNode().put("rowLabel", "A").put("tableNumber", 3).put("seatNumber", 2);
		ConflictNote withPlace = new ConflictNote(Kind.DOUBLE_SALE, "me", null, List.of(), null, place, null);
		ObjectNode noRow = JsonNodeFactory.instance.objectNode().put("rowLabel", "").put("tableNumber", 7).put("seatNumber", 1);

		assertEquals("Tisch A3, Sitz 2", ConflictList.placeText(withPlace));
		assertEquals("Tisch 7, Sitz 1",
				ConflictList.placeText(new ConflictNote(Kind.DOUBLE_SALE, "me", null, List.of(), null, noRow, null)));
		assertNull(ConflictList.placeText(note(Kind.DOUBLE_SALE, null)), "ohne Platz im Vermerk");
		assertNull(ConflictList.placeText(new ConflictNote(Kind.DOUBLE_SALE, "me", null, List.of(), null,
				JsonNodeFactory.instance.objectNode().put("rowLabel", "A"), null)), "ohne Tischnummer ist es kein Platz");
	}
}
