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
	private final ConflictList list = new ConflictList(() -> List.copyOf(source), unit -> "Label " + unit.relativePath());
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

	@Test
	void doubleSalesOfOneSaveAreGrouped() {
		source.add(conflict(seat("s1"), "save-1", note(Kind.DOUBLE_SALE, party("Kasse 1", "Anna"))));
		source.add(conflict(seat("s7"), "other-save", note(Kind.FIELDS, party("Kasse 1", "Anna"))));
		source.add(conflict(seat("s2"), "save-1", note(Kind.DOUBLE_SALE, party("Kasse 1", "Anna"))));
		source.add(conflict(seat("s3"), "save-1", note(Kind.DOUBLE_SALE, party("Kasse 1", "Anna"))));

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
				(conflict, group) -> group.equals("comment"));

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
}
